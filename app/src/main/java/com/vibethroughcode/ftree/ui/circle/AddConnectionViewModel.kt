package com.vibethroughcode.ftree.ui.circle

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.vibethroughcode.ftree.data.FamilyRepository
import com.vibethroughcode.ftree.data.Person
import com.vibethroughcode.ftree.data.RelationshipRejectedException
import com.vibethroughcode.ftree.data.RelationshipType
import com.vibethroughcode.ftree.graph.Circle
import com.vibethroughcode.ftree.graph.RelationshipRejection
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.debounce
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

data class AddConnectionUiState(
    val anchor: Person? = null,
    /** People who could still be connected: not the anchor, not already connected to them. */
    val candidates: List<Person> = emptyList(),
    val query: String = "",
    val newName: String = "",
    val label: String = "",
    /** Labels already used in this circle, most used first. */
    val usedLabels: List<String> = emptyList(),
    val rejection: RelationshipRejection? = null,
)

@OptIn(ExperimentalCoroutinesApi::class)
class AddConnectionViewModel(
    private val repository: FamilyRepository,
    private val anchorId: String,
) : ViewModel() {
    private val query = MutableStateFlow("")
    private val newName = MutableStateFlow("")
    private val label = MutableStateFlow("")
    private val rejection = MutableStateFlow<RelationshipRejection?>(null)

    private val matches = query
        .debounce { if (it.isBlank()) 0L else 180L }
        .flatMapLatest { text -> if (text.isBlank()) repository.observeAllPeople() else repository.searchPeople(text) }

    private data class Form(val query: String, val name: String, val label: String, val rejection: RelationshipRejection?)

    private val form = combine(query, newName, label, rejection, ::Form)

    val uiState: StateFlow<AddConnectionUiState> = combine(
        repository.observePerson(anchorId), matches, repository.observeAllEdges(), form,
    ) { anchor, people, edges, f ->
        val already = edges.filter { it.type == RelationshipType.CONNECTED }.mapNotNull { it.other(anchorId) }.toSet()
        AddConnectionUiState(
            anchor = anchor,
            candidates = people.filter { it.id != anchorId && it.id !in already },
            query = f.query,
            newName = f.name,
            label = f.label,
            usedLabels = Circle.labelsInUse(edges),
            rejection = f.rejection,
        )
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), AddConnectionUiState())

    fun onQueryChange(value: String) { query.value = value }
    fun onNewNameChange(value: String) { newName.value = value }
    fun onLabelChange(value: String) { label.value = value.take(Circle.MAX_LABEL) }
    fun dismissRejection() { rejection.value = null }

    fun linkExisting(personId: String, onDone: () -> Unit) = attempt(onDone) {
        repository.addConnection(anchorId, personId, label.value)
    }

    fun createAndLink(onDone: () -> Unit) = attempt(onDone) {
        repository.addNewConnection(anchorId, Person(name = newName.value.trim().ifBlank { null }), label.value).map { }
    }

    private fun attempt(onDone: () -> Unit, block: suspend () -> Result<Unit>) {
        viewModelScope.launch {
            block().onSuccess { onDone() }.onFailure { failure ->
                rejection.value = (failure as? RelationshipRejectedException)?.reason ?: RelationshipRejection.DUPLICATE
            }
        }
    }
}
