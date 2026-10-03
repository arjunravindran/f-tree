package com.vibethroughcode.ftree.ui.person

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.vibethroughcode.ftree.data.DeletionMode
import com.vibethroughcode.ftree.data.FamilyRepository
import com.vibethroughcode.ftree.data.Person
import com.vibethroughcode.ftree.data.RelationshipType
import com.vibethroughcode.ftree.data.RelativeKind
import com.vibethroughcode.ftree.data.SpouseKind
import com.vibethroughcode.ftree.graph.Circle
import com.vibethroughcode.ftree.graph.Connection
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

data class PersonDetailUiState(
    val person: Person? = null,
    val relationshipCount: Int = 0,
    val loaded: Boolean = false,
    val relatives: Map<RelativeKind, List<Person>> = emptyMap(),
    /** The state of each marriage, by the spouse's id (#291), so a heading can say "Former wife". */
    val spouseKinds: Map<String, SpouseKind> = emptyMap(),
) {
    fun of(kind: RelativeKind): List<Person> = relatives[kind].orEmpty()
}

class PersonDetailViewModel(
    private val repository: FamilyRepository,
    val personId: String,
) : ViewModel() {

    private val relatives = combine(
        repository.observeParents(personId),
        repository.observeSpouses(personId),
        repository.observeChildren(personId),
        repository.observeSiblings(personId),
    ) { parents, spouses, children, siblings ->
        mapOf(
            RelativeKind.PARENT to parents,
            RelativeKind.SPOUSE to spouses,
            RelativeKind.CHILD to children,
            RelativeKind.SIBLING to siblings,
        )
    }

    /** Their lines to others in a Circle, labelled. Empty in a family tree, which has no such edges. */
    val connections: StateFlow<List<Connection>> = combine(
        repository.observeAllPeople(), repository.observeEdgesOf(personId),
    ) { people, edges -> Circle.connectionsOf(personId, people, edges) }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    fun setConnectionLabel(edgeId: String, label: String) {
        viewModelScope.launch { repository.setConnectionLabel(edgeId, label) }
    }

    val uiState: StateFlow<PersonDetailUiState> = combine(
        repository.observePerson(personId),
        repository.observeEdgesOf(personId),
        relatives,
    ) { person, edges, related ->
        PersonDetailUiState(
            person = person,
            relationshipCount = edges.size,
            loaded = true,
            relatives = related,
            spouseKinds = edges
                .filter { it.type == RelationshipType.SPOUSE }
                .mapNotNull { edge ->
                    val other = edge.other(personId) ?: return@mapNotNull null
                    SpouseKind.fromName(edge.subtype)?.let { other to it }
                }
                .toMap(),
        )
    }.stateIn(
        scope = viewModelScope,
        started = SharingStarted.WhileSubscribed(5_000),
        initialValue = PersonDetailUiState(),
    )

    /**
     * Removes one connection without touching either person. Both stay in the tree; only the
     * link between them goes.
     */
    fun removeRelationshipWith(otherPersonId: String) {
        viewModelScope.launch {
            repository.edgesBetween(personId, otherPersonId).forEach {
                repository.removeRelationship(it.id)
            }
        }
    }

    /**
     * Deletion is a two-option decision rather than a yes/no, because losing one name should not
     * have to tear a hole in the family. The caller is told when the work is done so it can leave
     * the screen only after the row is actually gone.
     */
    fun delete(mode: DeletionMode, onDone: () -> Unit) {
        viewModelScope.launch {
            repository.deletePerson(personId, mode)
            onDone()
        }
    }
}
