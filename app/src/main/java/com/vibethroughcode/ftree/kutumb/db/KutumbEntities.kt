package com.vibethroughcode.ftree.kutumb.db

import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey

/*
 * Room rows for the family-network tables. Each mirrors a type in `kutumb-core` (which stays free of
 * Room) and is converted by `KutumbMapping.kt`. Enums are stored by name, lists as one value per
 * line (a key, a URL or a hex string never contains a newline).
 *
 * None of these has a foreign key to `people`, unlike the SPEC's "FK" columns. A trusted key, a
 * ledger of points and an owed coffee must outlive a person being removed from the tree: cascading
 * the delete would silently drop who may speak for the family, and what somebody is owed.
 */

/** The one row naming who this phone speaks for. The private key is sealed; see [KeyVault]. */
@Entity(tableName = "local_identity")
data class LocalIdentityEntity(
    @PrimaryKey val id: Int = 1,
    val personId: String,
    val publicKey: String,
    val sealedPrivateKey: String,
)

@Entity(tableName = "trusted_contacts", indices = [Index(value = ["pubKeyCurrent"], unique = true)])
data class TrustedContactEntity(
    @PrimaryKey val personId: String,
    val pubKeyCurrent: String,
    val pubKeyHistory: String,
    val pairedAt: Long,
    val pairingMethod: String,
    val vouchedByPersonId: String?,
)

/** The signature is the key: it is unique to one attestation, so a redelivered event is the same row. */
@Entity(tableName = "identity_rotations", indices = [Index(value = ["personId"])])
data class IdentityRotationEntity(
    @PrimaryKey val signature: String,
    val personId: String,
    val oldPubKey: String,
    val newPubKey: String,
    val vouchedByPubKey: String,
    val timestamp: Long,
)

@Entity(tableName = "person_locations", primaryKeys = ["personId", "label"])
data class PersonLocationEntity(
    val personId: String,
    val label: String,
    val lat: Double,
    val lon: Double,
    val source: String,
    val updatedAt: Long,
    val sharingEnabled: Boolean,
)

@Entity(tableName = "facts", indices = [Index(value = ["personId"])])
data class FactEntity(
    @PrimaryKey val id: String,
    val personId: String,
    val category: String,
    val prompt: String,
)

@Entity(tableName = "fact_answers", indices = [Index(value = ["factId"])])
data class FactAnswerEntity(
    @PrimaryKey val id: String,
    val factId: String,
    val answererId: String,
    val answerText: String,
    val isSelfReported: Boolean,
    val submittedAt: Long,
)

/** One per fact: a resolution is final. */
@Entity(tableName = "fact_resolutions")
data class FactResolutionEntity(
    @PrimaryKey val factId: String,
    val winningAnswerId: String,
    val resolvedByPersonId: String,
    val pointsAwarded: Int,
)

/** Append-only. One entry per resolved fact (the unique index is what stops a second award). */
@Entity(tableName = "points_ledger", indices = [Index(value = ["reasonFactId"], unique = true), Index(value = ["personId"])])
data class LedgerEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val personId: String,
    val points: Int,
    val reasonFactId: String,
    val awardedAt: Long,
)

@Entity(tableName = "sync_outbox")
data class OutboxEntity(
    @PrimaryKey val eventId: String,
    val payloadEncrypted: String,
    val targetPubKeys: String,
    val relayUrls: String,
    val publishedAt: Long?,
    val ackedBy: String,
    val attempts: Int,
    val nextAttemptAt: Long,
)

@Entity(tableName = "reward_redemptions", indices = [Index(value = ["fromPersonId", "toPersonId"])])
data class RedemptionEntity(
    @PrimaryKey val id: String,
    val fromPersonId: String,
    val toPersonId: String,
    val rewardType: String,
    val pointsThreshold: Int,
    val status: String,
    val redeemedAt: Long?,
)
