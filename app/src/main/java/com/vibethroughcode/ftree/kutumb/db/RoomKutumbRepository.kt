package com.vibethroughcode.ftree.kutumb.db

import androidx.room.withTransaction
import com.vibethroughcode.ftree.data.FTreeDatabase
import com.vibethroughcode.ftree.kutumb.KutumbRepository
import com.vibethroughcode.ftree.kutumb.LocalIdentity
import com.vibethroughcode.ftree.kutumb.game.Fact
import com.vibethroughcode.ftree.kutumb.game.FactAnswer
import com.vibethroughcode.ftree.kutumb.game.FactResolution
import com.vibethroughcode.ftree.kutumb.game.LedgerEntry
import com.vibethroughcode.ftree.kutumb.game.RewardRedemption
import com.vibethroughcode.ftree.kutumb.geo.PersonLocation
import com.vibethroughcode.ftree.kutumb.sync.OutboxEntry
import com.vibethroughcode.ftree.kutumb.trust.IdentityRotationEvent
import com.vibethroughcode.ftree.kutumb.trust.KeyPair
import com.vibethroughcode.ftree.kutumb.trust.TrustedContact
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

/** The family-network records in the app's own database, so they survive a restart. */
class RoomKutumbRepository(
    private val database: FTreeDatabase,
    private val vault: KeyVault,
) : KutumbRepository {
    private val dao get() = database.kutumbDao()

    override fun observeIdentity(): Flow<LocalIdentity?> = dao.observeIdentity().map { row ->
        row?.let { r -> vault.open(r.sealedPrivateKey)?.let { secret -> LocalIdentity(r.personId, KeyPair(r.publicKey, secret)) } }
    }

    override suspend fun setIdentity(identity: LocalIdentity) = dao.putIdentity(
        LocalIdentityEntity(
            personId = identity.personId,
            publicKey = identity.keyPair.publicKey,
            sealedPrivateKey = vault.seal(identity.keyPair.privateKey),
        )
    )

    override fun observeContacts(): Flow<List<TrustedContact>> = dao.observeContacts().map { rows -> rows.mapNotNull { it.toCore() } }
    override suspend fun saveContact(contact: TrustedContact) {
        database.withTransaction {
            if (dao.contactCount(contact.personId) > 0) dao.updateContact(contact.toRow()) else dao.insertContact(contact.toRow())
        }
    }

    override fun observeRotations(): Flow<List<IdentityRotationEvent>> = dao.observeRotations().map { rows -> rows.mapNotNull { it.toCore() } }
    override suspend fun saveRotation(event: IdentityRotationEvent) = dao.putRotation(event.toRow())

    override suspend fun outboxEntries(): List<OutboxEntry> = dao.outbox().mapNotNull { it.toCore() }
    override suspend fun saveOutboxEntry(entry: OutboxEntry) = dao.putOutbox(entry.toRow())
    override suspend fun deleteOutboxEntry(eventId: String) = dao.deleteOutbox(eventId)

    override fun observeLocations(): Flow<List<PersonLocation>> = dao.observeLocations().map { rows -> rows.mapNotNull { it.toCore() } }
    override suspend fun saveLocation(location: PersonLocation) = dao.putLocation(location.toRow())

    override fun observeFacts(): Flow<List<Fact>> = dao.observeFacts().map { rows -> rows.mapNotNull { it.toCore() } }
    override suspend fun saveFact(fact: Fact) = dao.putFact(fact.toRow())

    override fun observeAnswers(): Flow<List<FactAnswer>> = dao.observeAnswers().map { rows -> rows.mapNotNull { it.toCore() } }
    override suspend fun saveAnswer(answer: FactAnswer) = dao.putAnswer(answer.toRow())

    override fun observeResolutions(): Flow<List<FactResolution>> = dao.observeResolutions().map { rows -> rows.map { it.toCore() } }

    /**
     * Together or not at all, and only once: a second resolution of the same fact is ignored, and
     * its ledger entry is then not written, so points cannot be awarded twice.
     */
    override suspend fun saveResolution(resolution: FactResolution, entry: LedgerEntry) {
        database.withTransaction {
            if (dao.putResolution(resolution.toRow()) != -1L) dao.putLedgerEntry(entry.toRow())
        }
    }

    override fun observeLedger(): Flow<List<LedgerEntry>> = dao.observeLedger().map { rows -> rows.mapNotNull { it.toCore() } }

    override fun observeRedemptions(): Flow<List<RewardRedemption>> = dao.observeRedemptions().map { rows -> rows.mapNotNull { it.toCore() } }
    override suspend fun saveRedemption(redemption: RewardRedemption) = dao.putRedemption(redemption.toRow())

    override suspend fun clearAll() {
        // Only this feature's tables: the family tree itself is not ours to wipe.
        database.withTransaction {
            dao.clearLocalIdentity()
            dao.clearTrustedContacts()
            dao.clearIdentityRotations()
            dao.clearPersonLocations()
            dao.clearFacts()
            dao.clearFactAnswers()
            dao.clearFactResolutions()
            dao.clearPointsLedger()
            dao.clearSyncOutbox()
            dao.clearRewardRedemptions()
        }
    }
}
