package com.vibethroughcode.ftree.nearby.wire

/**
 * Every number the two implementations have to agree on.
 *
 * One file, deliberately, because the desktop app is a second implementation in another language
 * and the only way to keep the two in step is for a reviewer to be able to put one file beside
 * `desktop/nearby/protocol.js` and read down. A constant that lives anywhere else is a constant
 * that can drift without anybody noticing until two people are looking at two different six-digit
 * codes.
 *
 * The reasoning behind each of these is in `docs/nearby-protocol.md`. What is here is the values.
 */
object NearbyProtocol {

    /** "FTRE". On the beacon and on the first frame, so a wrong port is diagnosed and not parsed. */
    val MAGIC = byteArrayOf(0x46, 0x54, 0x52, 0x45)

    /** This wire protocol. Not the `.ftree` document version, which moves independently. */
    const val VERSION = 1
    const val MIN_VERSION = 1

    // Discovery ---------------------------------------------------------------------------------

    /**
     * RFC 2365 IPv4 Local Scope: administratively scoped, unregistered, and defined as not
     * forwarded beyond the site. The last two octets are 'F' and 'T'.
     */
    const val MULTICAST_GROUP = "239.255.70.84"
    const val BROADCAST_ADDRESS = "255.255.255.255"

    /**
     * In the dynamic range so it cannot collide with a registered service, and clear of every port
     * LAN discovery already crowds: 1900 SSDP, 5353 mDNS, 5355 LLMNR, 3702 WS-Discovery,
     * 21027 Syncthing.
     */
    const val BEACON_PORT = 50737

    /** The feature promises "the same Wi-Fi". One hop makes that true of the packets. */
    const val MULTICAST_TTL = 1

    const val ANNOUNCE_INTERVAL_MS = 2_000L

    /** A browser that has just opened should not wait two seconds, nor lose one datagram to it. */
    val ANNOUNCE_BURST_MS = longArrayOf(0L, 250L, 750L)

    /** So twenty devices in one room do not all answer a QUERY in the same millisecond. */
    const val QUERY_ANSWER_JITTER_MS = 150L
    const val QUERY_ANSWER_MIN_GAP_MS = 500L

    /** Three missed announces and some slack. */
    const val PEER_EXPIRY_MS = 7_000L
    const val PEER_SWEEP_INTERVAL_MS = 1_000L

    const val GOODBYE_COUNT = 3
    const val GOODBYE_GAP_MS = 40L

    // Beacon layout -----------------------------------------------------------------------------

    const val BEACON_HEADER_SIZE = 37
    const val BEACON_MAX_NAME_BYTES = 64
    const val BEACON_MAX_SIZE = BEACON_HEADER_SIZE + BEACON_MAX_NAME_BYTES

    const val BEACON_ANNOUNCE = 1
    const val BEACON_GOODBYE = 2
    const val BEACON_QUERY = 3

    // Frames ------------------------------------------------------------------------------------

    /** Matches the updater's read buffer. No reason for a second size in one app. */
    const val MAX_PLAINTEXT = 65_536

    /** Declared here rather than with the rest of the cipher, because the frame ceiling needs it. */
    const val GCM_TAG_BYTES = 16

    /**
     * 1 type byte + the largest ciphertext + the GCM tag.
     *
     * A length prefix without a ceiling is how a stranger on the network hands you an
     * `OutOfMemoryError`. Nothing is allocated until the claimed length has been tested against
     * this.
     */
    const val MAX_FRAME_BODY = 1 + MAX_PLAINTEXT + GCM_TAG_BYTES

    const val FRAME_HEADER_SIZE = 5

    const val TYPE_HELLO = 0x01
    const val TYPE_HELLO_ACK = 0x02
    const val TYPE_KEY = 0x03
    const val TYPE_KEY_ACK = 0x04
    const val TYPE_OFFER = 0x10
    const val TYPE_ACCEPT = 0x11
    const val TYPE_DECLINE = 0x12
    const val TYPE_DATA = 0x20
    const val TYPE_END = 0x21
    const val TYPE_RESULT = 0x22
    /**
     * A pairing conversation's one message each way: an identity card (see [PairingIdentity]),
     * sent in place of an OFFER. Outside [RESERVED_TYPES], which stays set aside for merge sync.
     * A receiver that is not pairing treats it, like any frame it did not expect, as the end of the
     * conversation, so a pairing sender cannot be mistaken for a file sender or the reverse.
     */
    const val TYPE_IDENTITY = 0x40
    const val TYPE_ABORT = 0x7F

    /** Reserved for a later merge-sync conversation. A version 1 build refuses the whole range. */
    val RESERVED_TYPES = 0x30..0x3F

    const val ROLE_SENDER = 1
    const val ROLE_RECEIVER = 2

    // Capability bits ---------------------------------------------------------------------------

    const val FLAG_ACCEPTS_TREE = 1 shl 0
    const val FLAG_MERGE_SYNC = 1 shl 1
    const val FLAG_RESUME = 1 shl 2
    const val FLAG_COMPRESSED_FRAMES = 1 shl 3
    const val FLAG_PAIRED_BY_QR = 1 shl 8

    /** What this build will actually set. The reserved bits stay zero until they mean something. */
    const val SUPPORTED_FLAGS = FLAG_ACCEPTS_TREE or FLAG_PAIRED_BY_QR

    // Key agreement -----------------------------------------------------------------------------

    /** RFC 3526 group 14, the 2048-bit MODP prime. Compiled in, never sent. */
    const val DH_PRIME_HEX =
        "FFFFFFFFFFFFFFFFC90FDAA22168C234C4C6628B80DC1CD129024E08" +
            "8A67CC74020BBEA63B139B22514A08798E3404DDEF9519B3CD3A431B" +
            "302B0A6DF25F14374FE1356D6D51C245E485B576625E7EC6F44C42E9" +
            "A637ED6B0BFF5CB6F406B7EDEE386BFB5A899FA5AE9F24117C4B1FE6" +
            "49286651ECE45B3DC2007CB8A163BF0598DA48361C55D39A69163FA8" +
            "FD24CF5F83655D23DCA3AD961C62F356208552BB9ED529077096966D" +
            "670C354E4ABC9804F1746C08CA18217C32905E462E36CE3BE39E772C" +
            "180E86039B2783A2EC07A28FB5C55DF06F4C52C9DE2BCBF695581718" +
            "3995497CEA956AE515D2261898FA051015728E5A8AACAA68FFFFFFFF" +
            "FFFFFFFF"

    const val DH_GENERATOR = 2L

    const val DH_PUBLIC_BYTES = 256

    /**
     * 256 bits, not 2048.
     *
     * Discrete log in this group is worth about 110 bits, so a longer exponent buys nothing and
     * costs roughly eight times the modular exponentiation — on a mid-range phone, the difference
     * between fifteen milliseconds and a pause somebody can feel in the middle of a pairing.
     */
    const val DH_PRIVATE_BITS = 256

    const val HANDSHAKE_NONCE_BYTES = 32
    const val PAIRING_TOKEN_BYTES = 16
    const val KEY_FINGERPRINT_BYTES = 8

    // Domain separation. ASCII, no terminator, never changed without a version bump. -------------

    const val LABEL_BEACON_KEY = "f-tree/nearby/1/beacon-key"
    const val LABEL_TRANSCRIPT = "f-tree/nearby/1/transcript"
    const val LABEL_SENDER_TO_RECEIVER = "f-tree/nearby/1/s2r"
    const val LABEL_RECEIVER_TO_SENDER = "f-tree/nearby/1/r2s"
    const val LABEL_SAS = "f-tree/nearby/1/sas"
    const val LABEL_KEY_COMMITMENT = "f-tree/nearby/1/key-commitment"

    /** A whole SHA-256: this is a binding promise, not a label, so none of it is thrown away. */
    const val KEY_COMMITMENT_BYTES = 32

    const val SESSION_KEY_BYTES = 32
    const val SAS_RAW_BYTES = 8

    /**
     * Eight bytes reduced rather than four.
     *
     * Reducing a 32-bit value modulo a million carries a bias of about one in half a million.
     * Four more bytes take it to one in twenty million million, for nothing.
     */
    const val SAS_MODULUS = 1_000_000L
    const val SAS_DIGITS = 6

    // Encryption --------------------------------------------------------------------------------

    const val AES_KEY_BITS = 256
    const val GCM_TAG_BITS = GCM_TAG_BYTES * 8
    const val NONCE_BYTES = 12

    const val DIRECTION_SENDER_TO_RECEIVER = 1
    const val DIRECTION_RECEIVER_TO_SENDER = 2

    // Timeouts ----------------------------------------------------------------------------------

    const val CONNECT_TIMEOUT_MS = 10_000
    const val HANDSHAKE_FRAME_TIMEOUT_MS = 15_000
    const val USER_DECISION_TIMEOUT_MS = 120_000L
    const val TRANSFER_IDLE_TIMEOUT_MS = 30_000L
    const val RESULT_WAIT_TIMEOUT_MS = 60_000L
    const val HANDSHAKE_COOLDOWN_MS = 3_000L

    /** Above this a receiver refuses before a byte moves. */
    const val MAX_OFFER_BYTES = 512L * 1024L * 1024L

    const val OFFER_MAX_NAME_BYTES = 96

    /** A scanned code is good only while the screen showing it is. */
    const val PAIRING_TOKEN_LIFETIME_MS = 5 * 60_000L

    // The QR link -------------------------------------------------------------------------------

    const val QR_SCHEME = "ftree"
    const val QR_HOST = "nearby"
    const val QR_PATH = "/v1"
}
