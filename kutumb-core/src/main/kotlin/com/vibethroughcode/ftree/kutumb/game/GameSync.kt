package com.vibethroughcode.ftree.kutumb.game

import com.vibethroughcode.ftree.kutumb.sync.SyncEnvelope
import com.vibethroughcode.ftree.kutumb.sync.SyncType

/** An answer as it travels: the fact is embedded so it can arrive before the fact exists on the receiving phone. */
data class AnswerMessage(val fact: Fact, val answer: FactAnswer)

/** A resolution as it travels, with the winning answer embedded so it cannot arrive before the answer it picks. */
data class ResolutionMessage(val fact: Fact, val winningAnswer: FactAnswer, val points: Int, val resolvedAt: Long)

/**
 * Wire form of the game messages. Decoding is strict: anything missing, mistyped or blank gives
 * null, so a malformed message is dropped rather than half-applied. Ids and text are bounded so a
 * trusted-but-buggy peer cannot make this phone store megabytes.
 */
object GameSync {
    const val MAX_TEXT = 2_000
    const val MAX_ID = 200
    const val MAX_POINTS = 100

    fun answerEnvelope(fact: Fact, answer: FactAnswer) =
        SyncEnvelope(SyncType.ANSWER, linkedMapOf("fact" to factMap(fact), "answer" to answerMap(answer)))

    fun resolutionEnvelope(message: ResolutionMessage) = SyncEnvelope(
        SyncType.RESOLUTION,
        linkedMapOf(
            "fact" to factMap(message.fact),
            "answer" to answerMap(message.winningAnswer),
            "points" to message.points.toLong(),
            "resolvedAt" to message.resolvedAt,
        ),
    )

    fun decodeAnswer(payload: Map<String, Any?>): AnswerMessage? = runCatching {
        AnswerMessage(fact(payload["fact"]) ?: return null, answer(payload["answer"]) ?: return null)
    }.getOrNull()

    fun decodeResolution(payload: Map<String, Any?>): ResolutionMessage? = runCatching {
        val points = (payload["points"] as? Long)?.takeIf { it in 1..MAX_POINTS } ?: return null
        val at = (payload["resolvedAt"] as? Long)?.takeIf { it >= 0 } ?: return null
        ResolutionMessage(fact(payload["fact"]) ?: return null, answer(payload["answer"]) ?: return null, points.toInt(), at)
    }.getOrNull()

    private fun factMap(f: Fact) = linkedMapOf<String, Any?>(
        "id" to f.id, "personId" to f.personId, "category" to f.category, "prompt" to f.prompt,
    )

    private fun answerMap(a: FactAnswer) = linkedMapOf<String, Any?>(
        "id" to a.id, "factId" to a.factId, "answererId" to a.answererId, "text" to a.answerText,
        "self" to a.isSelfReported, "at" to a.submittedAt,
    )

    private fun str(value: Any?, max: Int): String? = (value as? String)?.takeIf { it.isNotBlank() && it.length <= max }

    private fun fact(raw: Any?): Fact? {
        val m = raw as? Map<*, *> ?: return null
        return Fact(
            str(m["id"], MAX_ID) ?: return null,
            str(m["personId"], MAX_ID) ?: return null,
            str(m["category"], MAX_ID) ?: return null,
            str(m["prompt"], MAX_ID) ?: return null,
        )
    }

    private fun answer(raw: Any?): FactAnswer? {
        val m = raw as? Map<*, *> ?: return null
        return FactAnswer(
            id = str(m["id"], MAX_ID) ?: return null,
            factId = str(m["factId"], MAX_ID) ?: return null,
            answererId = str(m["answererId"], MAX_ID) ?: return null,
            answerText = str(m["text"], MAX_TEXT) ?: return null,
            isSelfReported = m["self"] as? Boolean ?: return null,
            submittedAt = (m["at"] as? Long)?.takeIf { it >= 0 } ?: return null,
        )
    }
}

/** What to do with a verified inbound game message. Pure: the app applies the decision. */
sealed interface GameDecision {
    data class SaveAnswer(val fact: Fact, val createFact: Boolean, val answer: FactAnswer) : GameDecision
    data class SaveResolution(
        val fact: Fact,
        val createFact: Boolean,
        val answerToSave: FactAnswer?,
        val resolution: FactResolution,
        val ledger: LedgerEntry,
    ) : GameDecision

    data class Ignore(val reason: String) : GameDecision
}

/**
 * Who may say what. [sender] is the person whose current key signed the message, established by
 * the sync engine; nothing inside the payload can change that.
 *
 *  - An answer is only accepted as the sender's own (a peer cannot answer in someone else's name).
 *  - A resolution is only accepted from the fact's owner, and never for a fact about the local
 *    person: only the local person resolves those, on this phone.
 *  - Redeliveries (same answer id, fact already resolved) are ignored, so applying twice is safe.
 */
object GameSyncRules {
    fun acceptAnswer(
        sender: String,
        localPerson: String,
        message: AnswerMessage,
        knownFact: Fact?,
        existingAnswers: List<FactAnswer>,
    ): GameDecision {
        val incoming = message.fact
        val answer = message.answer
        if (answer.answererId != sender) return GameDecision.Ignore("answer is not from the sender")
        if (answer.factId != incoming.id) return GameDecision.Ignore("answer is for another fact")
        if (knownFact != null && knownFact.personId != incoming.personId) return GameDecision.Ignore("fact owner disagrees")
        if (knownFact == null && incoming.personId != localPerson && incoming.personId != sender) {
            // A fact this phone has never seen is created only if it is about someone in this conversation.
            return GameDecision.Ignore("fact is about someone outside this exchange")
        }
        val fact = knownFact ?: incoming
        return when (val r = FactResolver.submit(fact, existingAnswers.filter { it.factId == fact.id }, answer)) {
            is SubmitResult.Added -> GameDecision.SaveAnswer(fact, knownFact == null, answer)
            SubmitResult.Duplicate -> GameDecision.Ignore("already have this answer")
            is SubmitResult.Rejected -> GameDecision.Ignore(r.reason)
        }
    }

    fun acceptResolution(
        sender: String,
        localPerson: String,
        message: ResolutionMessage,
        knownFact: Fact?,
        existingAnswers: List<FactAnswer>,
        existingResolution: FactResolution?,
    ): GameDecision {
        val incoming = message.fact
        if (incoming.personId != sender) return GameDecision.Ignore("only the fact's owner resolves it")
        if (incoming.personId == localPerson) return GameDecision.Ignore("facts about this person are resolved on this phone")
        if (message.winningAnswer.factId != incoming.id) return GameDecision.Ignore("answer is for another fact")
        if (knownFact != null && knownFact.personId != incoming.personId) return GameDecision.Ignore("fact owner disagrees")
        if (existingResolution != null) return GameDecision.Ignore("already resolved")
        val fact = knownFact ?: incoming
        val have = existingAnswers.filter { it.factId == fact.id }
        val winner = message.winningAnswer
        val haveWinner = have.any { it.id == winner.id }
        if (!haveWinner && winner.isSelfReported != (winner.answererId == fact.personId)) {
            // The owner vouches for the answer's content; its own consistency is still checked.
            return GameDecision.Ignore("inconsistent answer")
        }
        val withWinner = if (haveWinner) have else have + winner
        return when (val r = FactResolver.resolve(fact, withWinner, null, winner.id, sender, message.resolvedAt, message.points)) {
            is ResolveResult.Resolved -> GameDecision.SaveResolution(
                fact = fact,
                createFact = knownFact == null,
                answerToSave = winner.takeUnless { haveWinner },
                resolution = r.resolution,
                ledger = r.ledgerEntry,
            )
            is ResolveResult.Rejected -> GameDecision.Ignore(r.reason)
        }
    }
}
