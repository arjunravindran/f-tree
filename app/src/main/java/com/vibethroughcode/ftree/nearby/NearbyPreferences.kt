package com.vibethroughcode.ftree.nearby

import android.content.Context
import android.content.SharedPreferences
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * Who this device is willing to be seen by.
 *
 * Two axes rather than three states, and no *"always visible"* option at all. The argument that
 * settles it: **a device that announces itself while nobody is looking at it is a device somebody
 * has forgotten they configured.** Tying visibility to an open screen is also what keeps this
 * feature free of a foreground service, its permanent notification and a wake lock — none of which
 * could be justified for something that carries one file and stops.
 *
 * Off until switched on, following [com.vibethroughcode.ftree.update.UpdatePreferences]. An app
 * that promises to keep everything on the device should not open its first socket on the strength
 * of a default nobody chose.
 */
class NearbyPreferences(private val prefs: SharedPreferences) {

    constructor(context: Context) : this(
        context.applicationContext.getSharedPreferences("nearby-preferences", Context.MODE_PRIVATE),
    )

    private val _enabled = MutableStateFlow(prefs.getBoolean(KEY_ENABLED, false))

    /**
     * The master switch, checked before anything opens a socket.
     *
     * [NearbyRepository] refuses to start with this off even when something asks it to, which is
     * what makes "no network unless you turn it on" a property of the code rather than of the
     * screen that draws the toggle.
     */
    val enabled: StateFlow<Boolean> = _enabled.asStateFlow()

    fun setEnabled(value: Boolean) {
        prefs.edit().putBoolean(KEY_ENABLED, value).apply()
        _enabled.value = value
    }

    private val _trustedOnly = MutableStateFlow(prefs.getBoolean(KEY_TRUSTED_ONLY, false))

    /**
     * Whether to appear to everybody nearby, or only to devices this one has transferred with
     * before.
     *
     * Narrower than [enabled] rather than a state of it, because they answer different questions:
     * whether this device may be on the network at all, and who it will answer. Neither can make it
     * visible on its own.
     */
    val trustedOnly: StateFlow<Boolean> = _trustedOnly.asStateFlow()

    fun setTrustedOnly(value: Boolean) {
        prefs.edit().putBoolean(KEY_TRUSTED_ONLY, value).apply()
        _trustedOnly.value = value
    }

    private val _trusted = MutableStateFlow(readTrusted())

    /**
     * Devices that have completed a transfer here, by hex device id, with the name each had then.
     * Never an address.
     *
     * The name is kept only so the list in Settings says *who* can be forgotten; a bare id would
     * make "forget" a button nobody could use with confidence. It is the name the device announced,
     * already sanitised on arrival, and it is updated whenever the same device completes another
     * transfer under a new one.
     */
    val trusted: StateFlow<Map<String, String>> = _trusted.asStateFlow()

    fun remember(deviceId: String, name: String) {
        write(_trusted.value + (deviceId to name))
    }

    fun forget(deviceId: String) {
        write(_trusted.value - deviceId)
    }

    fun forgetAll() {
        prefs.edit().remove(KEY_TRUSTED).apply()
        _trusted.value = emptyMap()
    }

    private fun write(updated: Map<String, String>) {
        val encoded = updated.map { (id, name) -> id + name }.toSet()
        prefs.edit().putStringSet(KEY_TRUSTED, encoded).apply()
        _trusted.value = updated
    }

    /**
     * Each entry is the 32-character hex id followed directly by the name. The id is fixed width,
     * so there is no separator to escape and no name that can be mistaken for part of the id.
     *
     * A copy, because [android.content.SharedPreferences.getStringSet] hands back an instance the
     * caller must not modify and whose contents are undefined after the next edit.
     */
    private fun readTrusted(): Map<String, String> =
        (prefs.getStringSet(KEY_TRUSTED, emptySet()) ?: emptySet())
            .filter { it.length >= ID_HEX_LENGTH }
            .associate { it.substring(0, ID_HEX_LENGTH) to it.substring(ID_HEX_LENGTH) }

    private companion object {
        const val KEY_ENABLED = "enabled"
        const val KEY_TRUSTED_ONLY = "trusted-only"
        const val KEY_TRUSTED = "trusted-devices"
        const val ID_HEX_LENGTH = 32
    }
}
