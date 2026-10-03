package com.vibethroughcode.ftree.kutumb.sync

import android.content.Context
import android.content.SharedPreferences
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.net.URI

/**
 * Whether this phone may talk to Nostr relays at all, and which ones.
 *
 * Both default to nothing: sync is off, and there is no built-in relay list, so a fresh install
 * (or an update) contacts no server on the internet until a person turns it on and names relays.
 * [SyncService] checks the switch itself, so the guarantee does not depend on the screen.
 */
class SyncPreferences(private val prefs: SharedPreferences) {

    constructor(context: Context) : this(
        context.applicationContext.getSharedPreferences("kutumb-sync-preferences", Context.MODE_PRIVATE),
    )

    private val _enabled = MutableStateFlow(prefs.getBoolean(KEY_ENABLED, false))
    val enabled: StateFlow<Boolean> = _enabled.asStateFlow()

    fun setEnabled(value: Boolean) {
        prefs.edit().putBoolean(KEY_ENABLED, value).apply()
        _enabled.value = value
    }

    private val _relays = MutableStateFlow(
        prefs.getString(KEY_RELAYS, null)?.lines()?.mapNotNull(::normalizeRelay)?.distinct()?.take(MAX_RELAYS).orEmpty(),
    )
    val relays: StateFlow<List<String>> = _relays.asStateFlow()

    /** Saves the usable relays out of [text] (one per line or comma separated); returns the ones it refused. */
    fun setRelaysFromText(text: String): List<String> {
        val parts = text.split('\n', ',', ' ').map { it.trim() }.filter { it.isNotEmpty() }
        val good = parts.mapNotNull(::normalizeRelay).distinct().take(MAX_RELAYS)
        val refused = parts.filter { normalizeRelay(it) == null }
        prefs.edit().putString(KEY_RELAYS, good.joinToString("\n")).apply()
        _relays.value = good
        return refused
    }

    companion object {
        const val MAX_RELAYS = 5
        private const val KEY_ENABLED = "enabled"
        private const val KEY_RELAYS = "relays"

        /**
         * A relay address worth connecting to, or null. Only `wss://` with a host: plaintext would
         * hand the relay's operator and the network the connection details for no benefit. A path
         * is kept; credentials, fragments and spaces are refused.
         */
        fun normalizeRelay(raw: String): String? {
            val text = raw.trim()
            if (text.length > 200 || text.any { it.isWhitespace() }) return null
            val uri = try { URI(text) } catch (_: Exception) { return null }
            if (!uri.scheme.equals("wss", ignoreCase = true)) return null
            if (uri.userInfo != null || uri.fragment != null) return null
            val host = uri.host?.lowercase() ?: return null
            if (!host.contains('.') && host != "localhost") return null
            val port = if (uri.port >= 0) ":${uri.port}" else ""
            val path = uri.rawPath.orEmpty().trimEnd('/')
            val query = uri.rawQuery?.let { "?$it" }.orEmpty()
            return "wss://$host$port$path$query"
        }
    }
}
