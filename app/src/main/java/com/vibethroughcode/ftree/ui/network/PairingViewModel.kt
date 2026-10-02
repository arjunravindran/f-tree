package com.vibethroughcode.ftree.ui.network

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.vibethroughcode.ftree.data.Person
import com.vibethroughcode.ftree.kutumb.KutumbRepository
import com.vibethroughcode.ftree.kutumb.PairingFlow
import com.vibethroughcode.ftree.kutumb.PairingState
import com.vibethroughcode.ftree.kutumb.trust.PairingMethod
import com.vibethroughcode.ftree.kutumb.trust.TrustedContact
import com.vibethroughcode.ftree.nearby.wire.QrLink
import com.vibethroughcode.ftree.ui.common.matchingPeople
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

data class PairingUiState(
    val flow: PairingState = PairingState.Idle,
    val me: Person? = null,
    /** Who the reader may say the other person is: not themselves, and not somebody already trusted. */
    val candidates: List<Person> = emptyList(),
    val query: String = "",
    /** True once the new contact has been saved and the screen can close. */
    val saved: Boolean = false,
)

class PairingViewModel(
    private val pairing: PairingFlow,
    people: Flow<List<Person>>,
    private val kutumb: KutumbRepository,
    private val clock: () -> Long = System::currentTimeMillis,
) : ViewModel() {

    private val query = MutableStateFlow("")
    private val saved = MutableStateFlow(false)

    val state: StateFlow<PairingUiState> = combine(
        pairing.state, kutumb.observeIdentity(), kutumb.observeContacts(), people, query,
    ) { flow, identity, contacts, everyone, q ->
        val trusted = contacts.map { it.personId }.toSet()
        PairingUiState(
            flow = flow,
            me = everyone.firstOrNull { it.id == identity?.personId },
            candidates = matchingPeople(everyone.filter { it.id != identity?.personId && it.id !in trusted }, q),
            query = q,
        )
    }.combine(saved) { ui, done -> ui.copy(saved = done) }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), PairingUiState())

    init { pairing.start() }

    fun onQueryChange(value: String) { query.value = value }

    fun scanned(link: QrLink) = pairing.joinByLink(link)

    fun confirmCode(matched: Boolean) = pairing.confirmCode(matched)

    fun retry() {
        pairing.cancel()
        pairing.start()
    }

    /**
     * "That was them." Saved as met in person, the only way a contact becomes one - nothing the
     * network says can do it.
     */
    fun choosePerson(personId: String) {
        val verified = pairing.state.value as? PairingState.Verified ?: return
        viewModelScope.launch {
            // One key, one person: the trust store refuses a key two contacts share, so refuse it
            // here rather than save a row that would make the whole store unreadable.
            val taken = kutumb.observeContacts().first()
                .any { verified.peerPublicKey == it.pubKeyCurrent || verified.peerPublicKey in it.pubKeyHistory }
            if (taken) return@launch
            kutumb.saveContact(
                TrustedContact(
                    personId = personId,
                    pubKeyCurrent = verified.peerPublicKey,
                    pairedAt = clock(),
                    pairingMethod = PairingMethod.DIRECT,
                )
            )
            pairing.cancel()
            saved.value = true
        }
    }

    override fun onCleared() {
        pairing.cancel()
    }
}
