package com.vibethroughcode.ftree.kutumb.sync

import com.vibethroughcode.ftree.kutumb.InMemoryKutumbRepository
import com.vibethroughcode.ftree.kutumb.LocalIdentity
import com.vibethroughcode.ftree.kutumb.sync.relay.FakeRelay
import com.vibethroughcode.ftree.kutumb.sync.relay.FakeRelayConnector
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class SyncServiceTest {
    private val me = NostrBip340Scheme.generateKeyPair()
    private val bob = NostrBip340Scheme.generateKeyPair()
    private val relay = FakeRelay("wss://one.example")
    private val connector = FakeRelayConnector(listOf(relay))
    private val repo = InMemoryKutumbRepository()
    private val enabled = MutableStateFlow(false)
    private val relays = MutableStateFlow(listOf(relay.url))
    private var now = 1_800_000_000_000L
    private val fact = SyncEnvelope(SyncType.FACT, linkedMapOf("id" to "f1"))

    private fun TestScope.service() = SyncService(
        enabled = enabled,
        relays = relays,
        repository = repo,
        newEngine = { keys ->
            SyncEngine(repo, keys, connector, backgroundScope, clock = { now }, env = secureWrapEnvironment { now / 1000 })
        },
        scope = backgroundScope,
        clock = { now },
    ).also { it.start() }

    @Test
    fun switchedOffByDefaultItNeverOpensAConnection() = runTest {
        repo.setIdentity(LocalIdentity("me", me))
        val s = service()
        runCurrent()
        assertEquals(SyncStatus.OFF, s.status.value)
        assertTrue(connector.connectAttempts.isEmpty())
        assertFalse(s.publish(fact, listOf(bob.publicKey)))
    }

    @Test
    fun onWithoutRelaysOrIdentityItAsksForSetupAndConnectsToNothing() = runTest {
        enabled.value = true
        val s = service()
        runCurrent()
        assertEquals(SyncStatus.NEEDS_SETUP, s.status.value) // no identity yet
        repo.setIdentity(LocalIdentity("me", me))
        relays.value = emptyList()
        runCurrent()
        assertEquals(SyncStatus.NEEDS_SETUP, s.status.value) // no relays
        assertTrue(connector.connectAttempts.isEmpty())
    }

    @Test
    fun onWithRelaysAndAnIdentityItSubscribesAndPublishes() = runTest {
        repo.setIdentity(LocalIdentity("me", me))
        enabled.value = true
        val s = service()
        runCurrent()
        assertEquals(SyncStatus.ON, s.status.value)
        assertEquals(1, relay.subscriptions.size)

        assertTrue(s.publish(fact, listOf(bob.publicKey)))
        runCurrent()
        assertEquals(1, relay.received.size)
        assertTrue(repo.outboxEntries().isEmpty())
    }

    @Test
    fun switchingOffStopsItAndRefusesToQueue() = runTest {
        repo.setIdentity(LocalIdentity("me", me))
        enabled.value = true
        val s = service()
        runCurrent()
        enabled.value = false
        runCurrent()
        assertEquals(SyncStatus.OFF, s.status.value)
        assertFalse(s.publish(fact, listOf(bob.publicKey)))
        assertTrue(repo.outboxEntries().isEmpty())
        assertTrue(relay.received.isEmpty())
    }

    @Test
    fun aRelayThatWasDownIsRetriedOnTheIdleTimer() = runTest {
        repo.setIdentity(LocalIdentity("me", me))
        relay.online = false
        enabled.value = true
        service()
        runCurrent()
        assertTrue(relay.subscriptions.isEmpty())
        relay.online = true
        now += 61_000
        advanceTimeBy(61_000)
        runCurrent()
        assertEquals(1, relay.subscriptions.size)
    }

    @Test
    fun changingTheRelaysRestartsOnTheNewList() = runTest {
        val other = FakeRelay("wss://two.example")
        val both = FakeRelayConnector(listOf(relay, other))
        repo.setIdentity(LocalIdentity("me", me))
        enabled.value = true
        val s = SyncService(enabled, relays, repo, { SyncEngine(repo, it, both, backgroundScope, { now }) }, backgroundScope, { now })
        s.start()
        runCurrent()
        relays.value = listOf(other.url)
        runCurrent()
        assertEquals(1, other.subscriptions.size)
    }
}
