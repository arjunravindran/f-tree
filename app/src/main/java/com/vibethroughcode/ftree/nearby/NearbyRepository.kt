package com.vibethroughcode.ftree.nearby

import com.vibethroughcode.ftree.nearby.wire.Beacon
import com.vibethroughcode.ftree.nearby.wire.Dh
import com.vibethroughcode.ftree.nearby.wire.Handshake
import com.vibethroughcode.ftree.nearby.wire.NearbyPlatform
import com.vibethroughcode.ftree.nearby.wire.NearbyProblem
import com.vibethroughcode.ftree.nearby.wire.NearbyProtocol
import com.vibethroughcode.ftree.nearby.wire.Offer
import com.vibethroughcode.ftree.nearby.wire.QrLink
import com.vibethroughcode.ftree.transfer.ImportProblem
import java.io.File
import java.io.OutputStream
import java.math.BigInteger
import java.util.concurrent.atomic.AtomicBoolean
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

/**
 * Where a transfer has got to, as one value, so the screen can never show two things at once.
 *
 * Modelled on [com.vibethroughcode.ftree.update.UpdateState], which solved the same problem for the
 * updater: a sealed state rather than a handful of booleans that can disagree.
 */
sealed interface NearbyState {
    /** The setting is off. Nothing is bound and nothing is listening. */
    data object Disabled : NearbyState

    /** Visible and looking, with nothing in progress. */
    data class Browsing(val peers: List<NearbyPeer>) : NearbyState

    /** [name] is the peer's announced name, or the typed address when there is no announcement. */
    data class Connecting(val name: String) : NearbyState

    /** Both devices should be showing [sas]; somebody has to say whether they match. */
    data class ConfirmingCode(val sas: String, val sending: Boolean) : NearbyState

    /**
     * Something has arrived and is waiting to be accepted. The counts are the sender's claim.
     *
     * [code] is the six digits this side showed, repeated at the moment of accepting — carried in
     * the state rather than remembered by the screen, because the sender can confirm faster than a
     * screen observes the state in between. Null when the sender scanned this device's code, in
     * which case no digits were ever compared.
     */
    data class Reviewing(val offer: Offer, val fromName: String, val code: String?) : NearbyState

    data class Sending(val done: Long, val total: Long) : NearbyState
    data class Receiving(val done: Long, val total: Long) : NearbyState

    /** The bytes are whole and written; the caller now runs the existing import. */
    data class Arrived(val file: File, val suggestedFileName: String) : NearbyState

    /** The last byte left and the receiver could read it; it is now in the other device's review. */
    data object Sent : NearbyState

    data class Failed(
        val problem: NearbyProblem,
        val importProblem: ImportProblem? = null,
    ) : NearbyState
}

/**
 * Nearby sharing, as the rest of the app sees it.
 *
 * Shaped on [com.vibethroughcode.ftree.update.UpdateRepository], including the property that
 * matters most: **every path that opens a socket runs through
 * [NearbyPreferences.enabled] first.** With the setting off this class will not bind a port, join a
 * group or send a datagram even if something asks it to — which is what makes "no network unless
 * you turn it on" a property of the code rather than of the screen that draws the toggle.
 *
 * It owns no sockets itself. [NearbyTransport] does, and an instrumented test substitutes a fake
 * for it so the whole receive flow can be driven on an emulator, which cannot do multicast at all.
 */
class NearbyRepository(
    private val preferences: NearbyPreferences,
    private val identity: NearbyIdentity,
    private val transport: NearbyTransport,
    private val downloadDirectory: File,
    private val scope: CoroutineScope,
    private val clock: () -> Long = System::currentTimeMillis,
) {

    private val _state = MutableStateFlow<NearbyState>(
        if (preferences.enabled.value) NearbyState.Browsing(emptyList()) else NearbyState.Disabled,
    )
    val state: StateFlow<NearbyState> = _state.asStateFlow()

    private val peerTable = PeerTable()
    private val _peers = MutableStateFlow<List<NearbyPeer>>(emptyList())
    val peers: StateFlow<List<NearbyPeer>> = _peers.asStateFlow()

    /** Where this device can be reached while it is visible, for the other device to type. */
    data class Listening(val address: String?, val port: Int)

    private val _listening = MutableStateFlow<Listening?>(null)
    val listening: StateFlow<Listening?> = _listening.asStateFlow()

    /**
     * One transfer at a time, in either direction, claimed atomically.
     *
     * Two connections arriving together must not both see "nobody is busy". The one that loses is
     * told BUSY — and nothing it does may touch the transfer that won: not its state, not its file,
     * not the reference the accept button reaches it through.
     */
    private val engaged = AtomicBoolean(false)

    /**
     * The code the receive screen is showing, or null when it shows none.
     *
     * The token in it is single use and short-lived: spent by the first connection that says it
     * scanned it, and replaced after [NearbyProtocol.PAIRING_TOKEN_LIFETIME_MS] whatever happens,
     * so a photograph of somebody's screen is worth nothing five minutes later.
     */
    private val _pairing = MutableStateFlow<QrLink?>(null)
    val pairing: StateFlow<QrLink?> = _pairing.asStateFlow()
    private var showingPairing = false
    @Volatile private var pairingToken: ByteArray? = null
    private var pairingMintedAt = 0L

    /** Who is sending to us, from their HELLO; remembered once what they sent proves readable. */
    private var incomingFrom: Pair<String, String>? = null

    private var visible = false
    private var beaconPrivate: BigInteger? = null
    private var beaconPublic: BigInteger? = null
    private var sweeper: Job? = null

    @Volatile private var sending: NearbySendTransfer? = null
    @Volatile private var receiving: NearbyReceiveTransfer? = null
    @Volatile private var partFile: File? = null

    /**
     * Becomes visible, or stops.
     *
     * The keypair is minted here and thrown away on the way out. Reusing it across an advertising
     * session is a bounded trade worth naming: if it were extracted afterwards, recordings made
     * during that session could be read. Per-connection keys still differ, because the sender's is
     * fresh every time and both ends contribute a nonce.
     */
    fun setVisible(wanted: Boolean) {
        if (!preferences.enabled.value) {
            // The guard. Nothing below this line runs with the setting off.
            _state.value = NearbyState.Disabled
            return
        }
        if (wanted == visible) return

        if (!wanted) {
            stop()
            return
        }

        val private = Dh.generatePrivate()
        val public = Dh.publicOf(private)
        beaconPrivate = private
        beaconPublic = public

        val port = transport.startReceiving { channel -> onIncoming(channel) }
        _listening.value = Listening(transport.localAddress(), port)

        transport.startDiscovery { beacon, address ->
            if (beacon.messageType == NearbyProtocol.BEACON_GOODBYE) {
                peerTable.gone(beacon.deviceId)
            } else if (beacon.speakable && isAllowed(beacon)) {
                peerTable.seen(beacon, address, clock())
            }
            _peers.value = peerTable.list()
            publishBrowsing()
        }

        // The fingerprint is derived once -- it is a hash of a key that does not change while this
        // device is visible -- but the name is read on every tick, so a rename reaches the room
        // without the screen being closed and opened again (#192).
        val fingerprint = Handshake.beaconFingerprint(identity.deviceId.bytes, public)
        transport.startAnnouncing {
            Beacon.announce(
                platform = NearbyPlatform.ANDROID,
                flags = NearbyProtocol.SUPPORTED_FLAGS,
                tcpPort = port,
                deviceId = identity.deviceId,
                keyFingerprint = fingerprint,
                displayName = identity.displayName,
            )
        }
        transport.query()

        sweeper = scope.launch(Dispatchers.Default) {
            while (isActive) {
                kotlinx.coroutines.delay(NearbyProtocol.PEER_SWEEP_INTERVAL_MS)
                if (showingPairing) publishPairing()
                if (peerTable.sweep(clock()).isNotEmpty()) {
                    _peers.value = peerTable.list()
                    publishBrowsing()
                }
            }
        }

        visible = true
        publishBrowsing()
    }

    /** Called when the screen closes, and when the master switch goes off. */
    fun stop() {
        sweeper?.cancel()
        sweeper = null
        transport.stopAnnouncing()
        transport.stopDiscovery()
        transport.stopReceiving()
        peerTable.clear()
        _peers.value = emptyList()
        _listening.value = null
        showingPairing = false
        pairingToken = null
        _pairing.value = null
        beaconPrivate = null
        beaconPublic = null
        visible = false
        sending?.cancel()
        receiving?.cancel()
        sending = null
        receiving = null
        incomingFrom = null
        partFile?.takeIf { it.name.endsWith(".part") }?.delete()
        partFile = null
        engaged.set(false)
        _state.value = if (preferences.enabled.value) {
            NearbyState.Browsing(emptyList())
        } else {
            NearbyState.Disabled
        }
    }

    fun onEnabledChanged(enabled: Boolean) {
        if (!enabled) stop()
        _state.value = if (enabled) NearbyState.Browsing(emptyList()) else NearbyState.Disabled
    }

    /** The person at this screen said the two codes matched, or did not. */
    fun confirmCode(matched: Boolean) {
        sending?.confirmCode(matched)
    }

    fun acceptIncoming() {
        receiving?.accept()
    }

    fun declineIncoming() {
        receiving?.decline()
    }

    fun cancel() {
        sending?.cancel()
        receiving?.cancel()
    }

    /**
     * Back to the list after a transfer has ended, well or badly. Only a finished state can be
     * dismissed: a transfer in progress is stopped with [cancel], which says so to the other side.
     */
    fun dismiss() {
        val current = _state.value
        if (current is NearbyState.Sent || current is NearbyState.Failed) {
            _state.value = if (preferences.enabled.value) {
                NearbyState.Browsing(_peers.value)
            } else {
                NearbyState.Disabled
            }
        }
    }

    /**
     * Sends a `.ftree` the caller has already exported to a file, to a device picked from the list.
     *
     * A file rather than a live export, so the size and the digest are known before anything is
     * offered — and, the reason that actually matters, so no database transaction is held open
     * across a network for minutes.
     */
    fun send(peer: NearbyPeer, outgoing: OutgoingFile, pairingToken: ByteArray = Handshake.NO_TOKEN) {
        startSending(
            address = peer.address,
            port = peer.port,
            name = peer.displayName,
            expectedFingerprint = peer.keyFingerprint,
            outgoing = outgoing,
            pairingToken = pairingToken,
            onSent = { preferences.remember(peer.key, peer.displayName) },
        )
    }

    /** Starts or stops showing a code for another device to scan. Only the receive screen does. */
    fun showPairing(show: Boolean) {
        showingPairing = show
        if (show) publishPairing() else _pairing.value = null
    }

    /**
     * Mints a token when there is none or it has expired, and publishes the link that carries it.
     * No address, no code: a QR pointing nowhere reachable would only fail after somebody scanned it.
     */
    private fun publishPairing() {
        val listening = _listening.value
        val address = listening?.address
        val public = beaconPublic
        if (!showingPairing || listening == null || address == null || public == null) {
            _pairing.value = null
            return
        }
        val now = clock()
        val current = pairingToken
        val token = if (current == null || now - pairingMintedAt >= NearbyProtocol.PAIRING_TOKEN_LIFETIME_MS) {
            ByteArray(NearbyProtocol.PAIRING_TOKEN_BYTES).also { java.security.SecureRandom().nextBytes(it) }
                .also {
                    pairingToken = it
                    pairingMintedAt = now
                }
        } else {
            current
        }
        val link = QrLink(
            address = address,
            port = listening.port,
            deviceId = identity.deviceId,
            keyFingerprint = Handshake.beaconFingerprint(identity.deviceId.bytes, public),
            token = token,
            displayName = identity.displayName,
        )
        if (_pairing.value != link) _pairing.value = link
    }

    /**
     * Sends to a device whose code was scanned.
     *
     * The token in the code never goes on the wire; both ends mix it into the key, which is what
     * lets this path skip the six digits — a device that did not see the screen cannot derive the
     * key, and its first sealed frame does not open. A code without a token still connects, and
     * then compares digits like any other.
     */
    fun sendByLink(link: QrLink, outgoing: OutgoingFile) {
        val name = link.displayName ?: link.address
        startSending(
            address = link.address,
            port = link.port,
            name = name,
            expectedFingerprint = link.keyFingerprint,
            outgoing = outgoing,
            pairingToken = link.token ?: Handshake.NO_TOKEN,
            onSent = { preferences.remember(link.deviceId.hex(), name) },
        )
    }

    /**
     * Sends to an address somebody typed, for a network that drops discovery.
     *
     * There is no beacon, so there is no fingerprint to hold the other end to; the six digits are
     * the only check, which is why this path can never skip them. Only a private address is taken,
     * the same rule a scanned code is held to: a public one is either a mistake or an attempt to
     * make this phone post somebody's family to the internet.
     */
    fun sendTo(address: String, port: Int, outgoing: OutgoingFile) {
        if (!QrLink.isPrivateAddress(address) || port !in 1..65535) {
            _state.value = NearbyState.Failed(NearbyProblem.NETWORK)
            return
        }
        startSending(
            address = address,
            port = port,
            name = "$address:$port",
            expectedFingerprint = null,
            outgoing = outgoing,
            pairingToken = Handshake.NO_TOKEN,
            onSent = {},
        )
    }

    private fun startSending(
        address: String,
        port: Int,
        name: String,
        expectedFingerprint: ByteArray?,
        outgoing: OutgoingFile,
        pairingToken: ByteArray,
        onSent: () -> Unit,
    ) {
        if (!preferences.enabled.value) {
            _state.value = NearbyState.Disabled
            return
        }
        if (!engaged.compareAndSet(false, true)) return
        _state.value = NearbyState.Connecting(name)

        scope.launch(Dispatchers.IO) {
            val transfer = NearbySendTransfer(
                identity = identity,
                outgoing = outgoing,
                pairedByQr = !pairingToken.contentEquals(Handshake.NO_TOKEN),
                pairingToken = pairingToken,
                expectedFingerprint = expectedFingerprint,
                listener = object : NearbyTransferListener {
                    override fun onCode(sas: String) {
                        _state.value = NearbyState.ConfirmingCode(sas, sending = true)
                    }

                    override fun onProgress(done: Long, total: Long) {
                        _state.value = NearbyState.Sending(done, total)
                    }

                    override fun onFailed(problem: NearbyProblem, importProblem: ImportProblem?) {
                        _state.value = NearbyState.Failed(problem, importProblem)
                    }
                },
            )
            sending = transfer

            try {
                val channel = try {
                    transport.connect(address, port, NearbyProtocol.CONNECT_TIMEOUT_MS)
                } catch (_: Exception) {
                    _state.value = NearbyState.Failed(NearbyProblem.NETWORK)
                    return@launch
                }

                val problem = transfer.run(channel)
                if (problem == null) {
                    onSent()
                    _state.value = NearbyState.Sent
                }
            } finally {
                sending = null
                engaged.set(false)
            }
        }
    }

    /** Called once the importer has read what arrived, so the sender learns whether it was readable. */
    fun importFinished(problem: ImportProblem?) {
        val transfer = receiving
        val file = partFile
        if (problem == null) incomingFrom?.let { (id, name) -> preferences.remember(id, name) }
        receiving = null
        partFile = null
        incomingFrom = null
        // Off the main thread: RESULT is a socket write, and the caller is a screen. On the main
        // thread Android refuses it, the refusal is swallowed as a failed courtesy, and the sender
        // — whose file arrived perfectly — is told the connection dropped.
        scope.launch(Dispatchers.IO) {
            transfer?.finish(problem)
            file?.delete()
            engaged.set(false)
        }
    }

    private fun onIncoming(channel: NearbyChannel) {
        scope.launch(Dispatchers.IO) {
            if (!engaged.compareAndSet(false, true)) {
                refuseAsBusy(channel)
                return@launch
            }
            // `.part` until it is whole, then renamed — the same pattern `UpdateClient.download`
            // uses, and for the same reason: a half-written file that looks finished is worse than
            // no file at all.
            downloadDirectory.mkdirs()
            val part = File(downloadDirectory, "nearby-${clock()}.ftree.part")
            partFile = part

            var failed = false
            var transfer: NearbyReceiveTransfer? = null
            var shownCode: String? = null
            fun scanned() = (receiving?.sender?.flags ?: 0) and NearbyProtocol.FLAG_PAIRED_BY_QR != 0
            try {
                part.outputStream().use { sink ->
                    val t = NearbyReceiveTransfer(
                        identity = identity,
                        beaconPrivateKey = beaconPrivate ?: return@use,
                        beaconPublicKey = beaconPublic ?: return@use,
                        sink = sink,
                        pairingToken = pairingToken ?: Handshake.NO_TOKEN,
                        // Spent by the first connection that presents it; the screen gets a new one.
                        onTokenUsed = {
                            pairingToken = null
                            publishPairing()
                        },
                        listener = object : NearbyTransferListener {
                            override fun onCode(sas: String) {
                                // A sender that scanned this screen never sees digits, so showing
                                // them here would ask somebody to compare against nothing.
                                if (scanned()) return
                                shownCode = sas
                                _state.value = NearbyState.ConfirmingCode(sas, sending = false)
                            }

                            override fun onOffer(offer: Offer) {
                                // The name the sender gave in its HELLO, which is on the
                                // connection itself — not a guess from an address the peer list
                                // may never have seen, as it would not for a typed address.
                                val name = receiving?.sender?.displayName.orEmpty()
                                _state.value = NearbyState.Reviewing(offer, name, shownCode.takeUnless { scanned() })
                            }

                            override fun onProgress(done: Long, total: Long) {
                                _state.value = NearbyState.Receiving(done, total)
                            }

                            override fun onFailed(problem: NearbyProblem, importProblem: ImportProblem?) {
                                failed = true
                                _state.value = NearbyState.Failed(problem, importProblem)
                            }
                        },
                    )
                    receiving = t
                    transfer = t
                    val problem = t.run(channel)
                    failed = failed || problem != null
                }
            } catch (_: Exception) {
                failed = true
                _state.value = NearbyState.Failed(NearbyProblem.NO_SPACE)
            }

            val done = transfer
            if (done == null || failed) {
                // Nothing partial is left for somebody to find later and try to open.
                part.delete()
                partFile = null
                receiving = null
                engaged.set(false)
                runCatching { channel.close() }
                return@launch
            }

            val whole = File(downloadDirectory, part.name.removeSuffix(".part"))
            if (!part.renameTo(whole)) {
                part.delete()
                _state.value = NearbyState.Failed(NearbyProblem.NO_SPACE)
                receiving = null
                partFile = null
                engaged.set(false)
                return@launch
            }
            partFile = whole
            incomingFrom = done.sender?.let { it.deviceId.hex() to it.displayName }
            _state.value = NearbyState.Arrived(
                file = whole,
                suggestedFileName = done.incomingOffer?.suggestedFileName ?: whole.name,
            )
        }
    }

    /**
     * Says BUSY and closes, touching nothing that belongs to the transfer in progress.
     *
     * A refusal somebody can read beats a hang they cannot, so the second connection is answered
     * rather than dropped — by a throwaway transfer whose sink discards and whose listener is
     * deaf, because its failure is not the story the screen is telling.
     */
    private fun refuseAsBusy(channel: NearbyChannel) {
        val private = beaconPrivate
        val public = beaconPublic
        if (private != null && public != null) {
            val discard = object : OutputStream() {
                override fun write(b: Int) = Unit
            }
            runCatching {
                NearbyReceiveTransfer(
                    identity = identity,
                    beaconPrivateKey = private,
                    beaconPublicKey = public,
                    sink = discard,
                    busy = true,
                    listener = object : NearbyTransferListener {},
                ).run(channel)
            }
        }
        runCatching { channel.close() }
    }

    /**
     * Whether a device is one this phone will answer.
     *
     * With *devices I have used* switched on, a device that has never completed a transfer here is
     * not listed. It is a filter on this side and not a promise about the other: the beacon is
     * still public, and anybody on the network still sees it. What it does is keep a list somebody
     * is about to tap short and familiar.
     */
    private fun isAllowed(beacon: Beacon): Boolean =
        !preferences.trustedOnly.value || beacon.deviceId.hex() in preferences.trusted.value

    private fun publishBrowsing() {
        if (_state.value is NearbyState.Browsing) _state.value = NearbyState.Browsing(_peers.value)
    }
}
