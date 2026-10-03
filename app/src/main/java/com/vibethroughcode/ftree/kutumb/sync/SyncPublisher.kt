package com.vibethroughcode.ftree.kutumb.sync

import com.vibethroughcode.ftree.kutumb.KutumbRepository
import kotlinx.coroutines.flow.first

/**
 * How a screen's local change reaches the family: queue [envelope] for these people, if sync is
 * running. A failure to queue never undoes the local change; the caller has already saved it.
 */
interface SyncPublisher {
    suspend fun publish(envelope: SyncEnvelope, personIds: Collection<String>)
}

/** Does nothing. The default for tests and for screens built without sync. */
object NoSyncPublisher : SyncPublisher {
    override suspend fun publish(envelope: SyncEnvelope, personIds: Collection<String>) = Unit
}

/**
 * Sends to the trusted contacts among [personIds], through the service only when [enabled] says
 * sync is on, so asking never builds the service or opens a socket while it is off.
 */
class ServiceSyncPublisher(
    private val repository: KutumbRepository,
    private val enabled: () -> Boolean,
    private val service: () -> SyncService,
) : SyncPublisher {
    override suspend fun publish(envelope: SyncEnvelope, personIds: Collection<String>) {
        if (!enabled()) return
        val wanted = personIds.toSet()
        val keys = repository.observeContacts().first().filter { it.personId in wanted }.map { it.pubKeyCurrent }
        if (keys.isEmpty()) return
        runCatching { service().publish(envelope, keys) }
    }
}
