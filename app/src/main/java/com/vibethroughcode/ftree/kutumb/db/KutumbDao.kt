package com.vibethroughcode.ftree.kutumb.db

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Update
import kotlinx.coroutines.flow.Flow

@Dao
interface KutumbDao {
    @Query("SELECT * FROM local_identity WHERE id = 1")
    fun observeIdentity(): Flow<LocalIdentityEntity?>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun putIdentity(identity: LocalIdentityEntity)

    @Query("SELECT * FROM trusted_contacts")
    fun observeContacts(): Flow<List<TrustedContactEntity>>

    @Query("SELECT COUNT(*) FROM trusted_contacts WHERE personId = :personId")
    suspend fun contactCount(personId: String): Int

    /**
     * Plain insert and update, which both throw if the key is already another contact's. `REPLACE`
     * would "resolve" that clash by silently deleting the other person's row, and `@Upsert` by
     * silently doing nothing; refusing loudly is the only safe answer for a trust store.
     */
    @Insert(onConflict = OnConflictStrategy.ABORT)
    suspend fun insertContact(contact: TrustedContactEntity)

    @Update
    suspend fun updateContact(contact: TrustedContactEntity)

    @Query("SELECT * FROM identity_rotations ORDER BY timestamp")
    fun observeRotations(): Flow<List<IdentityRotationEntity>>

    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun putRotation(rotation: IdentityRotationEntity)

    @Query("SELECT * FROM person_locations")
    fun observeLocations(): Flow<List<PersonLocationEntity>>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun putLocation(location: PersonLocationEntity)

    @Query("SELECT * FROM facts")
    fun observeFacts(): Flow<List<FactEntity>>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun putFact(fact: FactEntity)

    @Query("SELECT * FROM fact_answers ORDER BY submittedAt, id")
    fun observeAnswers(): Flow<List<FactAnswerEntity>>

    /** Answers are kept, never overwritten: a repeat of the same id is the same answer again. */
    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun putAnswer(answer: FactAnswerEntity)

    @Query("SELECT * FROM fact_resolutions")
    fun observeResolutions(): Flow<List<FactResolutionEntity>>

    /** Final: a second resolution of the same fact is ignored, not substituted. */
    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun putResolution(resolution: FactResolutionEntity): Long

    @Query("SELECT * FROM points_ledger ORDER BY awardedAt, id")
    fun observeLedger(): Flow<List<LedgerEntity>>

    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun putLedgerEntry(entry: LedgerEntity): Long

    @Query("SELECT * FROM reward_redemptions")
    fun observeRedemptions(): Flow<List<RedemptionEntity>>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun putRedemption(redemption: RedemptionEntity)

    @Query("SELECT * FROM sync_outbox")
    suspend fun outbox(): List<OutboxEntity>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun putOutbox(entry: OutboxEntity)

    @Query("DELETE FROM sync_outbox WHERE eventId = :eventId")
    suspend fun deleteOutbox(eventId: String)

    @Query("DELETE FROM local_identity")
    suspend fun clearLocalIdentity()

    @Query("DELETE FROM trusted_contacts")
    suspend fun clearTrustedContacts()

    @Query("DELETE FROM identity_rotations")
    suspend fun clearIdentityRotations()

    @Query("DELETE FROM person_locations")
    suspend fun clearPersonLocations()

    @Query("DELETE FROM facts")
    suspend fun clearFacts()

    @Query("DELETE FROM fact_answers")
    suspend fun clearFactAnswers()

    @Query("DELETE FROM fact_resolutions")
    suspend fun clearFactResolutions()

    @Query("DELETE FROM points_ledger")
    suspend fun clearPointsLedger()

    @Query("DELETE FROM sync_outbox")
    suspend fun clearSyncOutbox()

    @Query("DELETE FROM reward_redemptions")
    suspend fun clearRewardRedemptions()
}
