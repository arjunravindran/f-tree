package com.vibethroughcode.ftree.kutumb

import com.vibethroughcode.ftree.kutumb.game.Fact
import com.vibethroughcode.ftree.kutumb.game.FactAnswer
import com.vibethroughcode.ftree.kutumb.game.FactResolution
import com.vibethroughcode.ftree.kutumb.game.LedgerEntry
import com.vibethroughcode.ftree.kutumb.game.RewardRedemption
import com.vibethroughcode.ftree.kutumb.geo.PersonLocation
import com.vibethroughcode.ftree.kutumb.trust.KeyPair
import com.vibethroughcode.ftree.kutumb.trust.TrustedContact
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update

/** Who this device is: the person in the tree it speaks for, and the long-term key it speaks with. */
class LocalIdentity(val personId: String, val keyPair: KeyPair)

/**
 * Everything the family-network screens read and write, as plain values from `kutumb-core`.
 *
 * An interface so the screens do not know where the rows live: [InMemoryKutumbRepository] backs
 * unit and UI tests, the Room-backed one backs the app. The rules - who may resolve, what a
 * rotation does - stay in `kutumb-core`; this only stores what those rules decided.
 */
interface KutumbRepository {
    fun observeIdentity(): Flow<LocalIdentity?>
    suspend fun setIdentity(identity: LocalIdentity)

    fun observeContacts(): Flow<List<TrustedContact>>
    suspend fun saveContact(contact: TrustedContact)

    fun observeLocations(): Flow<List<PersonLocation>>

    /** One row per person per label: saving replaces the one that was there. */
    suspend fun saveLocation(location: PersonLocation)

    fun observeFacts(): Flow<List<Fact>>
    suspend fun saveFact(fact: Fact)

    fun observeAnswers(): Flow<List<FactAnswer>>
    suspend fun saveAnswer(answer: FactAnswer)

    fun observeResolutions(): Flow<List<FactResolution>>

    /** The resolution and the points it awarded are stored together or not at all. */
    suspend fun saveResolution(resolution: FactResolution, entry: LedgerEntry)

    fun observeLedger(): Flow<List<LedgerEntry>>

    fun observeRedemptions(): Flow<List<RewardRedemption>>
    suspend fun saveRedemption(redemption: RewardRedemption)

    /** Forgets everything, the identity included. For tests and the debug seeder's `clear`. */
    suspend fun clearAll()
}

class InMemoryKutumbRepository : KutumbRepository {
    private val identity = MutableStateFlow<LocalIdentity?>(null)
    private val contacts = MutableStateFlow<List<TrustedContact>>(emptyList())
    private val locations = MutableStateFlow<List<PersonLocation>>(emptyList())
    private val facts = MutableStateFlow<List<Fact>>(emptyList())
    private val answers = MutableStateFlow<List<FactAnswer>>(emptyList())
    private val resolutions = MutableStateFlow<List<FactResolution>>(emptyList())
    private val ledger = MutableStateFlow<List<LedgerEntry>>(emptyList())
    private val redemptions = MutableStateFlow<List<RewardRedemption>>(emptyList())

    override fun observeIdentity(): Flow<LocalIdentity?> = identity.asStateFlow()
    override suspend fun setIdentity(identity: LocalIdentity) { this.identity.value = identity }

    override fun observeContacts(): Flow<List<TrustedContact>> = contacts.asStateFlow()
    override suspend fun saveContact(contact: TrustedContact) =
        contacts.update { all -> all.filter { it.personId != contact.personId } + contact }

    override fun observeLocations(): Flow<List<PersonLocation>> = locations.asStateFlow()
    override suspend fun saveLocation(location: PersonLocation) =
        locations.update { all -> all.filter { it.personId != location.personId || it.label != location.label } + location }

    override fun observeFacts(): Flow<List<Fact>> = facts.asStateFlow()
    override suspend fun saveFact(fact: Fact) = facts.update { all -> all.filter { it.id != fact.id } + fact }

    override fun observeAnswers(): Flow<List<FactAnswer>> = answers.asStateFlow()
    override suspend fun saveAnswer(answer: FactAnswer) = answers.update { all -> all.filter { it.id != answer.id } + answer }

    override fun observeResolutions(): Flow<List<FactResolution>> = resolutions.asStateFlow()
    override suspend fun saveResolution(resolution: FactResolution, entry: LedgerEntry) {
        resolutions.update { all -> all.filter { it.factId != resolution.factId } + resolution }
        ledger.update { it + entry }
    }

    override fun observeLedger(): Flow<List<LedgerEntry>> = ledger.asStateFlow()

    override suspend fun clearAll() {
        identity.value = null
        contacts.value = emptyList(); locations.value = emptyList(); facts.value = emptyList(); answers.value = emptyList()
        resolutions.value = emptyList(); ledger.value = emptyList(); redemptions.value = emptyList()
    }

    override fun observeRedemptions(): Flow<List<RewardRedemption>> = redemptions.asStateFlow()
    override suspend fun saveRedemption(redemption: RewardRedemption) =
        redemptions.update { all -> all.filter { it.id != redemption.id } + redemption }
}
