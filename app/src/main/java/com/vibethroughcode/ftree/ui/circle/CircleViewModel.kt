package com.vibethroughcode.ftree.ui.circle

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.vibethroughcode.ftree.data.FamilyRepository
import com.vibethroughcode.ftree.data.Person
import com.vibethroughcode.ftree.data.Relationship
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn

data class CircleUiState(
    val loaded: Boolean = false,
    val people: List<Person> = emptyList(),
    val edges: List<Relationship> = emptyList(),
)

/** Everyone in a Circle and every line between them. A circle is small, so the screen works from the whole of it. */
class CircleViewModel(repository: FamilyRepository) : ViewModel() {
    val state: StateFlow<CircleUiState> = combine(repository.observeAllPeople(), repository.observeAllEdges()) { people, edges ->
        CircleUiState(loaded = true, people = people, edges = edges)
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), CircleUiState())
}
