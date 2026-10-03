package com.vibethroughcode.ftree.kutumb.sync

import com.vibethroughcode.ftree.kutumb.sync.SyncPreferences.Companion.normalizeRelay
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class SyncPreferencesTest {
    @Test fun `only wss relays with a real host are accepted`() {
        assertEquals("wss://relay.example.com", normalizeRelay(" wss://Relay.Example.com/ "))
        assertEquals("wss://relay.example.com:8443/nostr", normalizeRelay("wss://relay.example.com:8443/nostr/"))
        assertNull(normalizeRelay("ws://relay.example.com"))
        assertNull(normalizeRelay("https://relay.example.com"))
        assertNull(normalizeRelay("relay.example.com"))
        assertNull(normalizeRelay("wss://user:pw@relay.example.com"))
        assertNull(normalizeRelay("wss://relay.example.com/#x"))
        assertNull(normalizeRelay("wss://intranet"))
        assertNull(normalizeRelay("wss://a b.example.com"))
        assertNull(normalizeRelay(""))
    }
}
