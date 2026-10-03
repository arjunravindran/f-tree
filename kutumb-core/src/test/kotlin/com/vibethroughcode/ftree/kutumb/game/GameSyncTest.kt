package com.vibethroughcode.ftree.kutumb.game

import com.vibethroughcode.ftree.kutumb.sync.SyncEnvelope
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class GameSyncTest {
    private val fact = Fact("asha/q1", "asha", "habits", "q1")
    private val guess = FactAnswer("a1", "asha/q1", "ravi", "tea", isSelfReported = false, submittedAt = 10)
    private val self = FactAnswer("a2", "asha/q1", "asha", "coffee", isSelfReported = true, submittedAt = 20)

    private fun roundTrip(e: SyncEnvelope) = SyncEnvelope.parse(e.toJson())!!.payload

    @Test fun `answer survives the wire`() {
        val m = GameSync.decodeAnswer(roundTrip(GameSync.answerEnvelope(fact, guess)))
        assertEquals(AnswerMessage(fact, guess), m)
    }

    @Test fun `resolution survives the wire`() {
        val msg = ResolutionMessage(fact, guess, 3, 99)
        assertEquals(msg, GameSync.decodeResolution(roundTrip(GameSync.resolutionEnvelope(msg))))
    }

    @Test fun `malformed or oversized messages decode to null`() {
        assertNull(GameSync.decodeAnswer(emptyMap()))
        assertNull(GameSync.decodeAnswer(mapOf("fact" to "x", "answer" to "y")))
        val big = guess.copy(answerText = "x".repeat(GameSync.MAX_TEXT + 1))
        assertNull(GameSync.decodeAnswer(roundTrip(GameSync.answerEnvelope(fact, big))))
        val p = roundTrip(GameSync.resolutionEnvelope(ResolutionMessage(fact, guess, 1, 1))).toMutableMap()
        p["points"] = 0L
        assertNull(GameSync.decodeResolution(p))
        p["points"] = (GameSync.MAX_POINTS + 1).toLong()
        assertNull(GameSync.decodeResolution(p))
        p["points"] = "5"
        assertNull(GameSync.decodeResolution(p))
    }

    @Test fun `an answer is accepted only from the person who gave it`() {
        val ok = GameSyncRules.acceptAnswer("ravi", "asha", AnswerMessage(fact, guess), null, emptyList())
        assertEquals(GameDecision.SaveAnswer(fact, createFact = true, answer = guess), ok)
        assertTrue(GameSyncRules.acceptAnswer("mallory", "asha", AnswerMessage(fact, guess), null, emptyList()) is GameDecision.Ignore)
    }

    @Test fun `a redelivered answer is ignored`() {
        val d = GameSyncRules.acceptAnswer("ravi", "asha", AnswerMessage(fact, guess), fact, listOf(guess))
        assertTrue(d is GameDecision.Ignore)
    }

    @Test fun `an unknown fact about a stranger is not created`() {
        val other = Fact("zed/q1", "zed", "habits", "q1")
        val a = guess.copy(id = "a9", factId = other.id)
        assertTrue(GameSyncRules.acceptAnswer("ravi", "asha", AnswerMessage(other, a), null, emptyList()) is GameDecision.Ignore)
    }

    @Test fun `a self-report flag that lies is rejected`() {
        val lie = guess.copy(isSelfReported = true)
        assertTrue(GameSyncRules.acceptAnswer("ravi", "asha", AnswerMessage(fact, lie), fact, emptyList()) is GameDecision.Ignore)
    }

    @Test fun `only the owner resolves, and never a fact about the local person`() {
        val msg = ResolutionMessage(fact, guess, 1, 50)
        assertTrue(GameSyncRules.acceptResolution("ravi", "meena", msg, fact, listOf(guess), null) is GameDecision.Ignore)
        assertTrue(GameSyncRules.acceptResolution("asha", "asha", msg, fact, listOf(guess), null) is GameDecision.Ignore)
        val ok = GameSyncRules.acceptResolution("asha", "ravi", msg, fact, listOf(guess), null)
        assertTrue(ok is GameDecision.SaveResolution)
        ok as GameDecision.SaveResolution
        assertEquals("ravi", ok.ledger.personId)
        assertNull(ok.answerToSave)
        assertEquals(false, ok.createFact)
    }

    @Test fun `a resolution that arrives before its answer carries the answer along`() {
        val ok = GameSyncRules.acceptResolution("asha", "meena", ResolutionMessage(fact, guess, 2, 50), null, emptyList(), null)
        ok as GameDecision.SaveResolution
        assertEquals(guess, ok.answerToSave)
        assertEquals(true, ok.createFact)
    }

    @Test fun `a second resolution is ignored`() {
        val done = FactResolution(fact.id, guess.id, "asha", 1)
        val d = GameSyncRules.acceptResolution("asha", "ravi", ResolutionMessage(fact, self, 1, 60), fact, listOf(guess, self), done)
        assertTrue(d is GameDecision.Ignore)
    }
}
