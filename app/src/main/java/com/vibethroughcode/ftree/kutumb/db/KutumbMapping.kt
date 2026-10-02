package com.vibethroughcode.ftree.kutumb.db

import com.vibethroughcode.ftree.kutumb.game.Fact
import com.vibethroughcode.ftree.kutumb.game.FactAnswer
import com.vibethroughcode.ftree.kutumb.game.FactResolution
import com.vibethroughcode.ftree.kutumb.game.LedgerEntry
import com.vibethroughcode.ftree.kutumb.game.RedemptionStatus
import com.vibethroughcode.ftree.kutumb.game.RewardRedemption
import com.vibethroughcode.ftree.kutumb.game.RewardType
import com.vibethroughcode.ftree.kutumb.geo.LocationLabel
import com.vibethroughcode.ftree.kutumb.geo.LocationSource
import com.vibethroughcode.ftree.kutumb.geo.PersonLocation
import com.vibethroughcode.ftree.kutumb.sync.OutboxEntry
import com.vibethroughcode.ftree.kutumb.trust.IdentityRotationEvent
import com.vibethroughcode.ftree.kutumb.trust.PairingMethod
import com.vibethroughcode.ftree.kutumb.trust.TrustedContact

/*
 * Row <-> core type. Rows that no longer parse (a value from a newer release, or damage) map to null
 * and are skipped by the callers rather than crashing a screen.
 */

private fun lines(text: String): List<String> = if (text.isEmpty()) emptyList() else text.split('\n')
private fun joined(values: Collection<String>): String = values.joinToString("\n")

private inline fun <T> parsed(block: () -> T): T? = try { block() } catch (_: IllegalArgumentException) { null } catch (_: NoSuchElementException) { null }

internal fun TrustedContact.toRow() = TrustedContactEntity(
    personId, pubKeyCurrent, joined(pubKeyHistory), pairedAt, pairingMethod.name, vouchedByPersonId,
)

internal fun TrustedContactEntity.toCore(): TrustedContact? = parsed {
    TrustedContact(
        personId, pubKeyCurrent, lines(pubKeyHistory), pairedAt,
        PairingMethod.entries.first { it.name == pairingMethod }, vouchedByPersonId,
    )
}

internal fun IdentityRotationEvent.toRow() = IdentityRotationEntity(signature, personId, oldPubKey, newPubKey, vouchedByPubKey, timestamp)

internal fun IdentityRotationEntity.toCore(): IdentityRotationEvent? =
    parsed { IdentityRotationEvent(personId, oldPubKey, newPubKey, vouchedByPubKey, signature, timestamp) }

internal fun PersonLocation.toRow() = PersonLocationEntity(personId, label.name, lat, lon, source.name, updatedAt, sharingEnabled)

internal fun PersonLocationEntity.toCore(): PersonLocation? = parsed {
    PersonLocation(
        personId, LocationLabel.entries.first { it.name == label }, lat, lon,
        LocationSource.entries.first { it.name == source }, updatedAt, sharingEnabled,
    )
}

internal fun Fact.toRow() = FactEntity(id, personId, category, prompt)
internal fun FactEntity.toCore(): Fact? = parsed { Fact(id, personId, category, prompt) }

internal fun FactAnswer.toRow() = FactAnswerEntity(id, factId, answererId, answerText, isSelfReported, submittedAt)
internal fun FactAnswerEntity.toCore(): FactAnswer? = parsed { FactAnswer(id, factId, answererId, answerText, isSelfReported, submittedAt) }

internal fun FactResolution.toRow() = FactResolutionEntity(factId, winningAnswerId, resolvedByPersonId, pointsAwarded)
internal fun FactResolutionEntity.toCore() = FactResolution(factId, winningAnswerId, resolvedByPersonId, pointsAwarded)

internal fun LedgerEntry.toRow() = LedgerEntity(personId = personId, points = points, reasonFactId = reasonFactId, awardedAt = awardedAt)
internal fun LedgerEntity.toCore(): LedgerEntry? = parsed { LedgerEntry(personId, points, reasonFactId, awardedAt) }

internal fun RewardRedemption.toRow() = RedemptionEntity(id, fromPersonId, toPersonId, rewardType.name, pointsThreshold, status.name, redeemedAt)
internal fun RedemptionEntity.toCore(): RewardRedemption? = parsed {
    RewardRedemption(
        id, fromPersonId, toPersonId, RewardType.entries.first { it.name == rewardType }, pointsThreshold,
        RedemptionStatus.entries.first { it.name == status }, redeemedAt,
    )
}

internal fun OutboxEntry.toRow() = OutboxEntity(
    eventId, payloadEncrypted, joined(targetPubKeys), joined(relayUrls), publishedAt, joined(ackedBy), attempts, nextAttemptAt,
)

internal fun OutboxEntity.toCore(): OutboxEntry? = parsed {
    OutboxEntry(eventId, payloadEncrypted, lines(targetPubKeys), lines(relayUrls), publishedAt, lines(ackedBy).toSet(), attempts, nextAttemptAt)
}
