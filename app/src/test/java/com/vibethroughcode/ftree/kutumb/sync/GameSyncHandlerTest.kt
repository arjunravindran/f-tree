package com.vibethroughcode.ftree.kutumb.sync

import com.vibethroughcode.ftree.kutumb.InMemoryKutumbRepository
import com.vibethroughcode.ftree.kutumb.LocalIdentity
import com.vibethroughcode.ftree.kutumb.game.Fact
import com.vibethroughcode.ftree.kutumb.game.FactAnswer
import com.vibethroughcode.ftree.kutumb.game.GameSync
import com.vibethroughcode.ftree.kutumb.game.ResolutionMessage
import com.vibethroughcode.ftree.kutumb.trust.PairingMethod
import com.vibethroughcode.ftree.kutumb.trust.TrustedContact
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class GameSyncHandlerTest {
    private val repo = InMemoryKutumbRepository()
    private val handler = GameSyncHandler(repo)
    private val fact = Fact("asha/q1", "asha", "habits", "q1")
    private val guess = FactAnswer("a1", "asha/q1", "ravi", "tea", isSelfReported = false, submittedAt = 10)
    private val rumor = UnsignedEvent(NostrBip340Scheme.generateKeyPair().publicKey, 1, SyncEnvelope.RUMOR_KIND, emptyList(), "")

    private fun contact(id: String) = TrustedContact(
        id, NostrBip340Scheme.generateKeyPair().publicKey, pairedAt = 0, pairingMethod = PairingMethod.DIRECT,
    )

    private fun asha() = contact("asha")
    private fun ravi() = contact("ravi")

    private fun asLocal(id: String) = runBlocking { repo.setIdentity(LocalIdentity(id, NostrBip340Scheme.generateKeyPair())) }

    @Test fun `a trusted guess about me becomes a fact with an answer, and a replay changes nothing`() = runBlocking {
        asLocal("asha")
        val env = GameSync.answerEnvelope(fact, guess)
        handler.onEnvelope(env, ravi(), rumor)
        handler.onEnvelope(env, ravi(), rumor)
        assertEquals(listOf(fact), repo.observeFacts().first())
        assertEquals(listOf(guess), repo.observeAnswers().first())
    }

    @Test fun `an answer claiming to be from someone else is dropped`() = runBlocking {
        asLocal("asha")
        handler.onEnvelope(GameSync.answerEnvelope(fact, guess), contact("mallory"), rumor)
        assertTrue(repo.observeAnswers().first().isEmpty())
        assertTrue(repo.observeFacts().first().isEmpty())
    }

    @Test fun `a resolution from the owner awards the guesser, even before the answer arrived`() = runBlocking {
        asLocal("ravi")
        handler.onEnvelope(GameSync.resolutionEnvelope(ResolutionMessage(fact, guess, 2, 77)), asha(), rumor)
        assertEquals(listOf(guess), repo.observeAnswers().first())
        assertEquals(1, repo.observeResolutions().first().size)
        val ledger = repo.observeLedger().first().single()
        assertEquals("ravi", ledger.personId)
        assertEquals(2, ledger.points)
        // A replay must not award twice.
        handler.onEnvelope(GameSync.resolutionEnvelope(ResolutionMessage(fact, guess, 2, 77)), asha(), rumor)
        assertEquals(1, repo.observeLedger().first().size)
    }

    @Test fun `a resolution from anyone but the owner is dropped`() = runBlocking {
        asLocal("ravi")
        handler.onEnvelope(GameSync.resolutionEnvelope(ResolutionMessage(fact, guess, 2, 77)), contact("mallory"), rumor)
        assertTrue(repo.observeResolutions().first().isEmpty())
        assertTrue(repo.observeLedger().first().isEmpty())
    }

    @Test fun `malformed payloads and unhandled types are accepted and ignored`() = runBlocking {
        asLocal("asha")
        handler.onEnvelope(SyncEnvelope(SyncType.ANSWER, mapOf("x" to 1L)), ravi(), rumor)
        handler.onEnvelope(SyncEnvelope(SyncType.TREE_EDIT, emptyMap()), ravi(), rumor)
        assertTrue(repo.observeAnswers().first().isEmpty())
    }

    @Test fun `with no identity on this phone nothing is stored`() = runBlocking {
        handler.onEnvelope(GameSync.answerEnvelope(fact, guess), ravi(), rumor)
        assertTrue(repo.observeAnswers().first().isEmpty())
    }
}
