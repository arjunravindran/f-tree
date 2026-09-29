package com.vibethroughcode.ftree.nearby

import android.content.Context
import com.vibethroughcode.ftree.nearby.wire.DeviceId
import com.vibethroughcode.ftree.nearby.wire.NearbyNames
import java.security.SecureRandom
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * The two things a transfer needs to know about the device it is running on.
 *
 * An interface rather than the class, so the protocol side depends on two values instead of on a
 * `Context`. That is what lets a whole transfer run in a JVM unit test — which matters more here
 * than it looks, because CI has no emulator, so this interface is the only reason the two halves of
 * the Android implementation ever meet before somebody runs the app on two phones.
 */
interface NearbySelf {
    val deviceId: DeviceId
    val displayName: String
}

/**
 * What this device calls itself on a network.
 *
 * The id here is deliberately **not** [com.vibethroughcode.ftree.transfer.TreeIdentity.treeId], and
 * that separation is the whole reason this class exists rather than reusing the one next door.
 *
 * `treeId` is stamped into every `.ftree` this device has ever exported. Broadcasting it twice a
 * second would turn a file-provenance identifier into a **device tracker**: anybody who had ever
 * received a file from this phone could then recognise it on every network it joined afterwards,
 * for as long as the tree existed, with no way to reset it short of losing the identity that makes
 * re-imports work. Two ids, two purposes, and this one can be regenerated freely.
 *
 * Kept in its own preferences file for the same reason: "forget this device" should be a delete,
 * not a careful edit of something else's settings.
 */
open class NearbyIdentity(context: Context) : NearbySelf {

    private val preferences = context.applicationContext
        .getSharedPreferences("nearby-identity", Context.MODE_PRIVATE)

    override val deviceId: DeviceId
        get() {
            preferences.getString(KEY_DEVICE_ID, null)
                ?.let { stored -> DeviceId.parse(stored)?.let { return it } }
            val minted = DeviceId(ByteArray(DEVICE_ID_BYTES).also { SecureRandom().nextBytes(it) })
            preferences.edit().putString(KEY_DEVICE_ID, minted.hex()).apply()
            return minted
        }

    /**
     * The name other devices see, which is a generated one until somebody chooses otherwise.
     *
     * Never `Build.MODEL`, and never anything derived from the account on the phone. Consumer
     * device names are overwhelmingly *"Ankit's Galaxy"*, and a default that used one would
     * broadcast a real person's name, in clear, to every stranger on a café network, twice a
     * second, for as long as the screen was open — and would never mention that it did.
     * *"Quiet Heron"* tells one device in a room from another just as well.
     */
    override val displayName: String
        get() = chosenName ?: NearbyNames.friendlyName(deviceId.bytes)

    // Seeded on first use rather than in the constructor, because reading it mints a device id and
    // merely constructing this class should not.
    private val _displayNames: MutableStateFlow<String> by lazy { MutableStateFlow(displayName) }

    /**
     * The same name as [displayName], as a flow, so that a rename reaches a screen that is already
     * open instead of waiting for it to be closed and opened again (#192).
     */
    val displayNames: StateFlow<String> get() = _displayNames.asStateFlow()

    /** What the reader typed, or `null` if they have not. Sanitised before it is stored. */
    open var chosenName: String?
        get() = preferences.getString(KEY_NAME, null)
        set(value) {
            val cleaned = value?.let { NearbyNames.sanitise(it) }
            preferences.edit().apply {
                if (cleaned == null) remove(KEY_NAME) else putString(KEY_NAME, cleaned)
            }.apply()
            _displayNames.value = displayName
        }

    private companion object {
        const val KEY_DEVICE_ID = "device-id"
        const val KEY_NAME = "display-name"
        const val DEVICE_ID_BYTES = 16
    }
}
