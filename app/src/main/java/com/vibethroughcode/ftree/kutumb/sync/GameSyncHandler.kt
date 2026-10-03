package com.vibethroughcode.ftree.kutumb.sync

import com.vibethroughcode.ftree.kutumb.KutumbRepository
import com.vibethroughcode.ftree.kutumb.game.GameDecision
import com.vibethroughcode.ftree.kutumb.game.GameSync
import com.vibethroughcode.ftree.kutumb.game.GameSyncRules
import com.vibethroughcode.ftree.kutumb.trust.TrustedContact
import kotlinx.coroutines.flow.first

/**
 * Applies facts, answers and resolutions from trusted contacts. The rules (who may say what) are
 * `GameSyncRules` in kutumb-core; this only reads what is stored, asks them, and writes the result.
 *
 * Idempotent: a redelivered answer or resolution is recognised by id and ignored, so the engine's
 * in-memory seen-set resetting on restart does no harm. Rotations and tree edits are not handled
 * here and are accepted and dropped. A malformed or refused message is dropped silently; none of
 * them is worth failing the delivery for, because redelivery would fail the same way.
 */
class GameSyncHandler(private val repository: KutumbRepository) : SyncHandler {
    override suspend fun onEnvelope(envelope: SyncEnvelope, sender: TrustedContact, rumor: UnsignedEvent) {
        val local = repository.observeIdentity().first()?.personId ?: return
        when (envelope.type) {
            SyncType.ANSWER -> {
                val message = GameSync.decodeAnswer(envelope.payload) ?: return
                val decision = GameSyncRules.acceptAnswer(
                    sender = sender.personId,
                    localPerson = local,
                    message = message,
                    knownFact = repository.observeFacts().first().firstOrNull { it.id == message.fact.id },
                    existingAnswers = repository.observeAnswers().first(),
                )
                if (decision is GameDecision.SaveAnswer) {
                    if (decision.createFact) repository.saveFact(decision.fact)
                    repository.saveAnswer(decision.answer)
                }
            }
            SyncType.RESOLUTION -> {
                val message = GameSync.decodeResolution(envelope.payload) ?: return
                val decision = GameSyncRules.acceptResolution(
                    sender = sender.personId,
                    localPerson = local,
                    message = message,
                    knownFact = repository.observeFacts().first().firstOrNull { it.id == message.fact.id },
                    existingAnswers = repository.observeAnswers().first(),
                    existingResolution = repository.observeResolutions().first().firstOrNull { it.factId == message.fact.id },
                )
                if (decision is GameDecision.SaveResolution) {
                    if (decision.createFact) repository.saveFact(decision.fact)
                    decision.answerToSave?.let { repository.saveAnswer(it) }
                    repository.saveResolution(decision.resolution, decision.ledger)
                }
            }
            else -> Unit
        }
    }
}
