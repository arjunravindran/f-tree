package com.vibethroughcode.ftree.ui.network

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.vibethroughcode.ftree.data.FamilyRepository
import com.vibethroughcode.ftree.data.Person
import com.vibethroughcode.ftree.kutumb.KutumbRepository
import com.vibethroughcode.ftree.kutumb.LocalIdentity
import com.vibethroughcode.ftree.kutumb.trust.SignatureScheme
import com.vibethroughcode.ftree.ui.common.matchingPeople
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

data class ContactsUiState(
    val loaded: Boolean = false,
    /** The person this device speaks for; null until the reader says who they are in the tree. */
    val me: Person? = null,
    val rows: List<ContactRow> = emptyList(),
    /** Everyone in the tree, for choosing [me]. */
    val everyone: List<Person> = emptyList(),
)

class ContactsViewModel(
    family: FamilyRepository,
    private val kutumb: KutumbRepository,
    private val scheme: SignatureScheme,
) : ViewModel() {

    val state: StateFlow<ContactsUiState> =
        combine(kutumb.observeIdentity(), kutumb.observeContacts(), family.observeWholeGraph()) { identity, contacts, snapshot ->
            ContactsUiState(
                loaded = true,
                me = identity?.let { snapshot.people[it.personId] },
                rows = identity?.let { ContactsModel.rows(it.personId, contacts, snapshot) }.orEmpty(),
                everyone = snapshot.people.values.toList(),
            )
        }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), ContactsUiState())

    private val _query = MutableStateFlow("")
    val query: StateFlow<String> = _query

    fun onQueryChange(value: String) { _query.value = value }

    fun candidates(everyone: List<Person>, query: String): List<Person> = matchingPeople(everyone, query)

    /** "This is me." Mints the long-term key here, on the device, and it never leaves. */
    fun chooseMe(personId: String) {
        viewModelScope.launch {
            val keyPair = withContext(Dispatchers.Default) { scheme.generateKeyPair() }
            kutumb.setIdentity(LocalIdentity(personId, keyPair))
            _query.value = ""
        }
    }
}
