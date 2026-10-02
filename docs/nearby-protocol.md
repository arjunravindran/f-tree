# Nearby sharing: the protocol

Nearby sharing sends a `.ftree` from one device to another over the local network. This page is the
wire specification: the bytes on the network, the handshake that protects them, and the rules both
implementations follow.

It exists because the feature is written **twice** — once in Kotlin for the Android app, once in
JavaScript for the desktop app — and those two have nothing in common except what is written down.
`docs/desktop.md` says the cost of that arrangement plainly: *"this is a second implementation of
the app's behaviour, in a second language, kept in step by hand."* For a file format, hand is
enough. For a cryptographic handshake it is not, so the agreement is pinned by a shared table of
test vectors that both test suites read. See [Test vectors](#test-vectors).

**What this is not.** There is no server, no account, no relay and nothing stored in between. The
two devices talk to each other directly and only while somebody is looking at the screen that says
so. It is not sync: nothing is reconciled, nothing is overwritten, and the receiving device runs
the arriving file through the same review as a file picked out of a folder. See
[`ftree-format.md`](ftree-format.md) for what that review does.

**Contents**

- [Shape of a transfer](#shape-of-a-transfer)
- [Discovery](#discovery)
- [Visibility](#visibility)
- [Frames](#frames)
- [The handshake](#the-handshake)
- [The six digits](#the-six-digits)
- [The QR code](#the-qr-code)
- [Encryption](#encryption)
- [The conversation](#the-conversation)
- [Pairing](#pairing)
- [When it goes wrong](#when-it-goes-wrong)
- [Versions, and what a later release may change](#versions-and-what-a-later-release-may-change)
- [Test vectors](#test-vectors)

---

## Shape of a transfer

Two roles, and in version 1 they do not swap:

- The **receiver** advertises itself, listens on a TCP port, and is the side that consents.
- The **sender** browses, connects, and is the side that has a tree to give.

Consent belonging to the receiver is why the roles are arranged this way round. "Who is allowed to
see me" is then a property of the device being seen, which is where anybody would look for it.

A transfer is: the receiver announces itself → the sender finds it and connects → both agree a key
and show the same six digits → the sender describes what it is about to send → the receiver agrees
→ the bytes move → the receiver checks them and hands the file to the ordinary import review.

The last step is the point. **Nearby sharing produces a file, and then stops.** Everything that
decides what happens to somebody's family — the duplicate matching, the field conflicts, the
add-never-replace rule — is the code that was already there.

---

## Discovery

### The beacon

A small UDP datagram, sent to a multicast group **and** to the broadcast address.

| | | |
|---|---|---|
| Multicast group | `239.255.70.84` | RFC 2365 *IPv4 Local Scope*: administratively scoped, unregistered, defined as not forwarded beyond the site. `70.84` is `F`, `T`. |
| Port | `50737` (UDP) | In the dynamic range, so it cannot collide with a registered service, and clear of every port LAN discovery already crowds: 1900 SSDP, 5353 mDNS, 5355 LLMNR, 3702 WS-Discovery, 21027 Syncthing. |
| TTL | `1` | The feature promises "the same Wi-Fi". A TTL of one makes that true of the packets and not merely of the wording. |
| TCP port | ephemeral | Bound with `0` and published in the beacon. Nothing to reserve, no clash between two copies on one machine, and the firewall asks at the moment visibility is switched on rather than at launch. |

Sending to both addresses is not belt and braces. Consumer access points routinely drop multicast
groups they do not recognise, or convert multicast to unicast and lose it; broadcast usually
survives. One socket bound to `0.0.0.0:50737` with the group joined receives both, peers are keyed
on `deviceId`, and the duplicate costs nothing.

IPv6 is not in version 1. The format carries no address at all (below), so adding it later is a
change to the socket and not to the packet.

### The bytes

All integers big-endian. One datagram, never fragmented.

```
off  size  field
  0     4  magic            0x46 0x54 0x52 0x45   "FTRE"
  4     1  maxVersion       highest wire version spoken          (1)
  5     1  minVersion       lowest wire version spoken           (1)
  6     1  messageType      1 ANNOUNCE, 2 GOODBYE, 3 QUERY
  7     1  platform         0 unknown, 1 android, 2 linux, 3 windows, 4 macos
  8     2  flags            capability bits
 10     2  tcpPort          the port the advertiser is accepting on
 12    16  deviceId         raw UUID bytes, network order
 28     8  keyFingerprint   first 8 bytes of the beacon key fingerprint
 36     1  nameLength       N, UTF-8 byte length of displayName, 0..64
 37     N  displayName      UTF-8, NFC, sanitised
                            total 37 + N, at most 101 bytes
```

**The beacon carries no IP address.** The address is taken from the datagram's source. A forged
beacon therefore cannot point a sender at a third machine, and the familiar bug of advertising a
stale interface address cannot happen because there is no address to go stale.

Reading one:

- Shorter than 37, or shorter than `37 + nameLength` — ignore, silently. A malformed datagram on a
  shared network is noise, not a fault, and discovery has nowhere to report it.
- Wrong magic — ignore.
- **Longer** than `37 + N` — accept, ignore the excess. This is what lets a later version append
  fields without breaking this one.
- `ANNOUNCE` with `N = 0` — ignore. An empty name is legal only for `QUERY`.
- `QUERY` carries sixteen zero bytes for `deviceId`, eight for `keyFingerprint`, `tcpPort = 0` and
  `N = 0`. It says only that somebody is looking.
- Our own `deviceId` — ignore. Filter on the id and never on the source address: two copies on one
  machine must still see each other, and multicast loopback is not portable enough to depend on.

### Timing

| | |
|---|---|
| Announce | every 2000 ms |
| Start burst | `t = 0`, `250 ms`, `750 ms`, then the interval. A browser that has just opened sees the peer at once, and one lost datagram does not cost two seconds. |
| Answering a QUERY | one extra announce after 0–150 ms of jitter, at most one per 500 ms. The jitter is so that twenty devices in a room do not all answer in the same millisecond. |
| Forgetting a peer | 7000 ms since its last announce — three missed beacons and some slack. Swept once a second. |
| Stopping | three GOODBYE datagrams 40 ms apart, then leave the group, close the socket, release the multicast lock, close the listener. |

GOODBYE is a courtesy and expiry is the mechanism. Somebody can forge one; it costs the person
looking at the screen one more glance at the list, and nothing else.

### The beacon key

When advertising starts the receiver generates one ephemeral Diffie-Hellman keypair and publishes a
fingerprint of it:

```
keyFingerprint = SHA-256("f-tree/nearby/1/beacon-key" || deviceId || Ypub)[0..7]
```

The sender checks this against what the other end presents during the handshake, which ties *the
device I tapped in the list* to *the device I am now talking to*. Eight bytes is enough: forging a
match means 2^64 modular exponentiations.

The key is held in memory, never written to disk, and regenerated whenever visibility goes off and
on again. It is reused across several transfers within one advertising session, which is a bounded
trade and worth naming: if that private key were extracted at the end of a session, recordings made
during that session could be read. The per-connection keys still differ, because the sender's key is
fresh for every connection and both ends contribute a fresh nonce.

### Capability bits

```
bit  0  ACCEPTS_TREE       willing to receive a .ftree   (a receiver always sets this)
bit  1  MERGE_SYNC         reserved, always 0 in version 1
bit  2  RESUME             reserved, always 0
bit  3  COMPRESSED_FRAMES  reserved, always 0
bit  8  PAIRED_BY_QR       HELLO only: this connection presents a pairing token
        others             reserved, written 0, ignored on read
```

---

## Visibility

Three things a device can be, and the default is the first.

| | |
|---|---|
| **Off** | No UDP socket, no multicast lock, no listener, nothing on any wire. Invisible, and unable to receive. This is where the app is unless somebody has said otherwise. |
| **Anyone nearby** | While the nearby screen is open, any device on the network may see this one. |
| **Devices I have used** | While the nearby screen is open, only devices already confirmed once may see this one. |

There is deliberately **no "always"**. A device that announces itself while nobody is looking at it
is a device somebody has forgotten they configured, and a family tree app has nothing to gain from
being findable in a pocket. Visibility is a property of the screen being open, which also means
there is no background service, no notification to justify and no wake lock to hold.

### What a stranger can learn

Before anyone has agreed to anything, a device on the same network sees exactly what is in the
beacon: a display name, a platform, a version range, capability bits, a key fingerprint, and the
address the datagram came from.

It does **not** see how many people are in the tree, anybody's name, the `sourceTreeId`, the app
version, the device model, or whether there is a tree at all.

After the handshake the encrypted offer adds three counts, a length, a digest and a suggested file
name — never a person's name. That ceiling is deliberate. The counts are worth showing in the
prompt that asks whether to accept, and they are dull enough that showing them costs nothing.

### `deviceId` is not `sourceTreeId`

They must be different values, and this is not a detail.

`sourceTreeId` is stamped into every `.ftree` this installation has ever written; it is how a
re-import recognises people it has seen before. Broadcasting it twice a second would turn a
file-provenance identifier into a **device tracker** — anybody who had ever been sent a file by you
could recognise you on every network you later walked into. Nearby sharing mints its own UUID under
its own key.

### Names

The default display name is **not** the device name. Consumer device names are overwhelmingly
"Ankit's Galaxy", and defaulting to one broadcasts a real person's name to every stranger on a café
network, twice a second, for as long as the screen is open. The default is generated from the
`deviceId` against a fixed word table — *Quiet Heron*, *Amber Swift*. Anybody who wants their own
name can set one.

A name arriving from the network is attacker-controlled text about to be drawn in a list, so both
implementations must: decode UTF-8 with replacement, normalise to NFC, strip C0 and C1 controls,
strip the bidirectional overrides `U+202A`–`U+202E` and isolates `U+2066`–`U+2069`, collapse runs of
whitespace, trim, truncate to 64 UTF-8 bytes on a codepoint boundary, and fall back to the generated
name if nothing survives. Without the bidirectional strip, one device can draw itself as another.

---

## Frames

Everything after discovery — handshake and payload alike — is one frame shape.

```
off  size  field
  0     4  length    number of bytes that follow  =  1 + payload length
  4     1  type
  5     L  payload
```

```
MAX_PLAINTEXT   = 65536      64 KiB
MAX_FRAME_BODY  = 65553      1 type + 65536 ciphertext + 16 tag
```

A `length` below 1 or above `MAX_FRAME_BODY` is refused **before anything is allocated**. A length
prefix without a ceiling is how a stranger on the café Wi-Fi hands you an `OutOfMemoryError`, and
this is the most important single check in the protocol.

Two traps, one per language, both of which have test vectors:

- **Kotlin.** Read the length into a `Long` through `readInt().toLong() and 0xFFFFFFFFL`. Read as an
  `Int`, `0xFFFFFFFF` is `-1` and walks straight past a `> MAX` test.
- **JavaScript.** Every 64-bit field here — `sequence`, `totalBytes`, `bytesSent`, and the reduction
  that makes the six digits — is a `BigInt`. A `Number` loses precision above 2^53.

### Types

```
0x01 HELLO       plaintext   sender   -> receiver
0x02 HELLO_ACK   plaintext   receiver -> sender
0x03 KEY         plaintext   sender   -> receiver
0x04 KEY_ACK     plaintext   receiver -> sender
0x10 OFFER       encrypted   sender   -> receiver
0x11 ACCEPT      encrypted   receiver -> sender
0x12 DECLINE     encrypted   receiver -> sender
0x20 DATA        encrypted   sender   -> receiver
0x21 END         encrypted   sender   -> receiver
0x22 RESULT      encrypted   receiver -> sender
0x30..0x3F       reserved for a later conversation
0x40 IDENTITY    encrypted   either way, pairing only: see [Pairing](#pairing)
0x7F ABORT       either
```

`0x40` sits outside the reserved range, which stays set aside for merge sync.

**An unrecognised type ends the connection; it is never skipped.** Skipping an unknown frame in an
encrypted stream means consuming a sequence number whose meaning you do not know, which is exactly
how a downgrade gets smuggled past a version check.

---

## The handshake

Four plaintext frames, then everything is encrypted.

The order matters as much as the contents. The receiver **promises** its key and nonce in
`HELLO_ACK`, before it has seen anything of the sender's; the sender sends its own in `KEY`; the
receiver then **reveals** them in `KEY_ACK`, and the sender checks the reveal against the promise
before it derives anything. Without that promise the six digits could be chosen by a machine in the
middle — see [Why the receiver promises first](#why-the-receiver-promises-first).

```
HELLO (0x01)                       HELLO_ACK (0x02)
off size field                     off size field
  0   4  magic "FTRE"                0   4  magic "FTRE"
  4   1  maxVersion                  4   1  chosenVersion
  5   1  minVersion                  5   1  reserved (0)
  6   1  role = 1 sender             6   1  role = 2 receiver
  7   1  platform                    7   1  platform
  8   2  flags offered               8   2  flags negotiated
 10  16  deviceId                   10  16  deviceId
 26   1  treeFormatMin              26   1  treeFormatMin
 27   1  treeFormatMax              27   1  treeFormatMax
 28   1  nameLength N (1..64)       28   1  nameLength N (1..64)
 29   N  displayName                29   N  displayName
                                  29+N  32  keyCommitment
```

`keyCommitment` is the receiver's promise of what `KEY_ACK` will carry:

```
keyCommitment = SHA-256("f-tree/nearby/1/key-commitment" || to256(Yreceiver) || nonceReceiver)
```

It comes after the name so that the greeting both messages share keeps one layout. A `HELLO_ACK`
that ends before it is `MALFORMED_FRAME`; bytes after it are ignored, as they are after a `HELLO`.

### Two version numbers, and they are not the same number

`maxVersion` and `minVersion` are the **wire** protocol — this document. `treeFormatMin` and
`treeFormatMax` are the **`.ftree` document version**, the `version` field described in
[`ftree-format.md`](ftree-format.md).

They move independently, which is the entire reason there are two of them. A release may learn a
new way to talk without changing the file, or a new file without changing the conversation.
Conflating them is the mistake this arrangement exists to prevent.

Negotiation:

```
chosen = min(sender.maxVersion, receiver.maxVersion)
floor  = max(sender.minVersion, receiver.minVersion)

chosen < floor  ->  PROTOCOL_TOO_NEW  if sender.minVersion > receiver.maxVersion
                    PROTOCOL_TOO_OLD  if sender.maxVersion < receiver.minVersion

negotiatedFlags = sender.flags AND receiver.flags
```

The receiver **states** the outcome; the sender **verifies** it — that `chosen` lies inside its own
range, and that the flags are the AND it expected. A receiver must not be able to name a version the
sender never offered.

Then, before a single row is read from the database:

- If this device's `.ftree` version is higher than `helloAck.treeFormatMax`, the sender stops with
  `TREE_FORMAT_TOO_NEW` — the sender's side of the refusal the importer already makes when it meets
  a file from a newer release. Catching it here rather than after a four-megabyte upload is the
  whole reason the field is in the first frame.
- If `helloAck.deviceId` is not the id that was discovered, stop with `WRONG_DEVICE`.

### Agreeing a key

```
KEY (0x03) and KEY_ACK (0x04), same layout:
off size field
  0  256  dhPublic   big-endian, left-padded with zeros to exactly 256 bytes
256   32  nonce      32 bytes from a cryptographic random source
```

Diffie-Hellman over the RFC 3526 2048-bit MODP group (group 14), `g = 2`. Both are compiled in;
neither is ever sent.

**The private exponent is 256 bits, not 2048.** Discrete log in this group is worth about 110 bits,
so a longer exponent buys nothing and costs roughly eight times the work — on a mid-range phone,
the difference between fifteen milliseconds and a hesitation somebody can feel.

Before anything else, the sender checks `KEY_ACK` against the promise it was given:

```
reject unless SHA-256(label || to256(Y) || nonce) == helloAck.keyCommitment    KEY_NOT_AS_PROMISED
```

The comparison is constant-time, and it happens before a single digit exists to be shown.

Both ends must validate the other's public value **before** exponentiating:

```
reject unless 2 <= Y <= p - 2          BAD_PUBLIC_KEY
```

`p` is a safe prime, so the only small subgroups are `{1}` and `{1, p-1}`, and that one test
excludes both. Afterwards, reject a shared value of `0`, `1` or `p - 1`.

### The padding rule

This is the likeliest interoperability bug in the whole document, which is why it has its own
helper and its own vectors on both sides.

- **Kotlin.** `BigInteger.toByteArray()` prepends a zero sign byte when bit 2047 is set, giving 257
  bytes, and returns fewer than 256 for a small value.
- **JavaScript.** `DiffieHellman#getPublicKey()` and `#computeSecret()` both **strip leading
  zeros**. A shared secret beginning `0x00` — which happens once in 256 — comes back 255 bytes long.

Both languages need one `to256()` that strips a sign byte and left-pads to exactly 256. Skipped,
this is a feature that works two hundred and fifty-five times and then silently derives a different
key.

---

## The six digits

The transcript is defined the least ambiguous way available: **the first four frames, verbatim, as
they crossed the wire, including their length prefixes.** Both ends keep a running SHA-256 and feed
it every handshake byte they read or write. There is no field order to agree on and no
canonicalisation to get wrong.

```
T      = SHA-256("f-tree/nearby/1/transcript"
                 || frame(HELLO) || frame(HELLO_ACK)
                 || frame(KEY)   || frame(KEY_ACK))

token  = the 16-byte pairing token from a scanned QR, or 16 zero bytes

salt   = SHA-256(T || token)
PRK    = HMAC-SHA256(key = salt, message = to256(Z))

K_s2r  = HKDF-Expand(PRK, "f-tree/nearby/1/s2r", 32)
K_r2s  = HKDF-Expand(PRK, "f-tree/nearby/1/r2s", 32)
sasRaw = HKDF-Expand(PRK, "f-tree/nearby/1/sas", 8)

sas    = u64be(sasRaw) mod 1000000, written with leading zeros
```

HKDF-Expand is RFC 5869 unchanged. All three outputs are 32 bytes or fewer, so each is one block.
Labels are ASCII with no terminator.

Eight bytes feed the reduction rather than four because reducing a 32-bit value modulo a million
carries a bias of roughly one in half a million, and four more bytes remove it for nothing.

The number is a function of both the transcript and the shared secret. A machine in the middle is
running two separate conversations with two different secrets, so the two screens show two different
numbers — **provided it cannot choose its own inputs after seeing the other side's.** That is the
whole security argument, and it is why the wording when they disagree has to be alarming rather than
helpful: a mismatch means somebody is in the middle, not that the user should try again.

### Why the receiver promises first

Six digits are twenty bits. They are only a defence if nobody gets to *try* for a match.

Without the promise in `HELLO_ACK`, the receiver speaks last. A machine in the middle — one that has
forged the beacon, or simply sits on the path of a typed address — would first finish its
conversation with the real receiver, so the code on the receiver's screen is known to it. It then
plays the receiver towards the sender: the sender's `KEY` arrives, the shared secret with the sender
is fixed, and the only thing left that feeds the code is the nonce the machine is about to send. It
tries nonces. Each attempt is one SHA-256 and one HMAC, no exponentiation, and a million of them —
enough to hit any six digits — take well under a second. Both screens then show the same number,
and the one check the people holding them were asked to make passes.

The promise takes the choice away:

- **Playing the receiver**, the machine must commit to its key and nonce in `HELLO_ACK`, before the
  sender's `KEY` — and so before the sender's fresh nonce — exists. The code is then decided by a
  value it could not see when it had to choose.
- **Playing the sender**, it must send `KEY` before the real receiver reveals, and the receiver's
  nonce is fixed behind a promise it cannot open. The same again.

Either way a forced match is one chance in a million per attempt, not a certainty, and each attempt
puts a code on somebody's screen. The receiver's key being fixed for the whole advertising session
does not help the attacker: the promise covers the *nonce*, which is fresh for every connection.
The beacon fingerprint could not have done this job — it is published by a beacon anybody can
forge, and it covers the key but not the nonce.

This is the same arrangement as Bluetooth's numeric comparison and ZRTP, where one side commits
before the exchange that decides the code.

---

## The QR code

```
ftree://nearby/v1?a=<ipv4>&p=<port>&d=<deviceId hex>&f=<fingerprint>&t=<token>&n=<name>&v=<version>
```

| key | required | |
|---|---|---|
| `a` | yes | IPv4 dotted quad the receiver is listening on |
| `p` | yes | TCP port |
| `d` | yes | `deviceId`, 32 lowercase hex, no dashes |
| `f` | yes | the 8-byte key fingerprint, base64url, unpadded |
| `t` | no | the 16-byte pairing token, base64url, unpadded |
| `n` | no | display name, percent-encoded UTF-8, at most 64 bytes |
| `v` | no | highest wire version, default 1 |

A custom scheme rather than an `https:` link, because an https URL holding a LAN address is a URL a
browser will cheerfully fetch, and `ftree://` is not.

**`a` must be private or link-local** — `10/8`, `172.16/12`, `192.168/16`, `169.254/16`. Anything
else is refused outright, and the same test applies to a typed address. A code pointing at a public
address is either a mistake or an attempt to make a phone post somebody's family to the internet,
and there is no third reading.

### The token is never transmitted

The 16-byte `t` is mixed into the salt and never put on the wire. A device that did not see the
screen derives a different key, and its first encrypted frame fails to authenticate — `BAD_PAIRING`.

This is what makes the QR a real shared secret rather than a convenience. A fingerprint alone proves
the receiver's identity to the sender, but nothing proves the sender ever saw the screen; the token
proves it, in both directions. That is why scanning a code is allowed to **skip the six digits**,
and typing an address is not.

A token is single-use: the first connection presenting it consumes it, and it is discarded when the
code leaves the screen or after five minutes.

---

## Encryption

AES-256-GCM, two keys, one per direction, each with its own counter.

The nonce is twelve bytes and **is never transmitted**:

```
off size field
  0   4  direction   1 sender to receiver, 2 receiver to sender
  4   8  sequence    from 0, +1 per encrypted frame in that direction
```

The counter counts encrypted frames only; the plaintext handshake consumes nothing. So the sender's
`OFFER` is sequence 0 and the receiver's `ACCEPT` is also sequence 0, under a different key and a
different direction tag.

**Random nonces are not used, deliberately.** A 96-bit random nonce carries a birthday risk, and a
poorly seeded generator on a phone can repeat one outright — and GCM nonce reuse does not merely
expose a message, it exposes the authentication key and forges every frame after it. A counter
cannot repeat while it does not wrap, which at 2^64 frames of 64 KiB it will not; the key cannot
carry over from a previous session because both fresh nonces feed the salt. It is also free replay
and reordering detection, and being deterministic, it is testable.

```
payload  =  ciphertext || 16-byte tag
AAD      =  length(4) || type(1) || nonce(12)
```

Binding the type stops a frame being replayed as a different one. Binding the nonce makes the
sequence explicit even though it is not on the wire.

A tag that does not verify ends the connection immediately. There is no retry, because there is
nothing a retry could fix.

---

## The conversation

```
OFFER (0x10)                              ACCEPT (0x11)   empty
off size field
  0   4  peopleCount                      DECLINE (0x12)
  4   4  relationshipCount                off size field
  8   4  photoCount                         0   1  reason
 12   8  totalBytes
 20  32  sha256 of the whole .ftree       END (0x21)
 52   1  treeFormatVersion                off size field
 53   1  nameLength N (0..96)               0   8  bytesSent
 54   N  suggestedFileName                  8  32  sha256

DATA (0x20)  1..65536 bytes of the        RESULT (0x22)
             archive, in order            off size field
                                            0   1  outcome  0 ok, 1 refused
ABORT (0x7F)                                1   1  import problem, 0 when ok
off size field
  0   1  reason
```

### The sender writes a file before it offers

`TreeExporter` streams a ZIP and cannot know its size in advance, so `totalBytes` and the digest are
only knowable afterwards. The archive is written to a `.part` file first — the same pattern the
updater already uses for a download — and that buys four things: an honest progress bar, a receiver
that can refuse on size before a byte moves, a digest bound into the decision to accept rather than
discovered at the end, and no database transaction held open across a network for minutes. One
temporary file is a cheap price.

### States

```
sender
  CONNECTING          connected     -> send HELLO                       AWAITING_HELLO_ACK
  AWAITING_HELLO_ACK  HELLO_ACK     -> negotiate, check id and format,
                                       send KEY                         AWAITING_KEY_ACK
  AWAITING_KEY_ACK    KEY_ACK       -> check the promise, validate,
                                       check fingerprint, derive keys   CONFIRMING_CODE
                                       (scanned a QR: skip)             AWAITING_ACCEPT
  CONFIRMING_CODE     yes           -> send OFFER                       AWAITING_ACCEPT
                      no            -> ABORT CODES_DID_NOT_MATCH        FAILED
  AWAITING_ACCEPT     ACCEPT        -> read the file                    SENDING
                      DECLINE                                           FAILED
  SENDING             a chunk       -> send DATA                        SENDING
                      end of file   -> send END                         AWAITING_RESULT
  AWAITING_RESULT     RESULT                                            DONE

receiver
  LISTENING           busy          -> ABORT BUSY, close
                      connection                                        AWAITING_HELLO
  AWAITING_HELLO      HELLO         -> negotiate, send HELLO_ACK
                                       with the promise                 AWAITING_KEY
  AWAITING_KEY        KEY           -> validate, send KEY_ACK,
                                       derive keys, show the digits     AWAITING_OFFER
  AWAITING_OFFER      OFFER         -> check size and free space        AWAITING_USER
  AWAITING_USER       accept        -> send ACCEPT                      RECEIVING
                      decline       -> send DECLINE                     FAILED
  RECEIVING           DATA          -> write, tally, digest             RECEIVING
                      END           -> check length and digest          VERIFYING
  VERIFYING           mismatch      -> ABORT CONTENT_MISMATCH           FAILED
                      ok            -> rename, prepare, send RESULT     DONE
```

In every state a frame not listed is `UNEXPECTED_MESSAGE`, and a peer that closes before `DONE` is
`CONNECTION_LOST`.

The receiver sends `RESULT` after the import has been *prepared* but before anybody has reviewed it.
Preparing takes no human input — it is a copy, a parse and a duplicate match — so the socket is held
a second at most, and the sender learns whether what it sent could be read. The sending screen then
says the tree is being looked at, rather than implying the story has ended.

An `ABORT` uses whatever encryption is in force when it is sent: plaintext before both key frames
have crossed, encrypted after. **A plaintext abort arriving after the keys exist is ignored**, so a
single forged segment cannot tear down an established transfer.

### Limits

```
CONNECT_TIMEOUT        10 s
HANDSHAKE_FRAME        15 s   each
USER_DECISION         120 s   waiting on a person
TRANSFER_IDLE          30 s   no frame during DATA
RESULT_WAIT            60 s   after END
MAX_OFFER_BYTES       512 MiB
HANDSHAKE_COOLDOWN      3 s   per remote address, between attempts
```

One transfer at a time. A second connection is accepted only far enough to say `BUSY` and close,
because a refusal somebody can read beats a hang they cannot. That cooldown, and the rule that no
exponentiation happens until `HELLO` has validated, are what stop a stranger spending your battery
on modular arithmetic.

---

## Pairing

A second conversation on the same connection, used by Kutumb to let two people in the same room
exchange public keys. It is the file transfer with the last half swapped out: HELLO, the key
exchange and the six digits (or the scanned token) are unchanged, and where a sender would send an
`OFFER` it sends one identity card. No file moves and nothing is saved by the protocol; the app asks
the reader who the person is before anything is written.

Roles are as for a transfer: the **sender** is the phone that connects (scanned or typed), the
**receiver** is the phone showing the code. Which conversation it is, is decided locally — a pairing
is started from the pairing screen, and neither side announces it.

### The identity card

`IDENTITY (0x40)`, encrypted like `OFFER`, with the same sequence rules.

```
off size field
  0   32  publicKey   x-only BIP-340 public key
 32   64  signature   BIP-340 signature by that key
                      total 96 bytes exactly
```

The signature covers this UTF-8 text, fields joined by a single `\n` and no trailing newline:

```
"ftree-kutumb/pair/1"
<the six digits of this session, as shown>
<role: "1" if the card is the sender's, "2" if the receiver's>
<publicKey, 64 lowercase hex characters>
```

What each field is for:

- The **six digits** make a card recorded from an earlier pairing fail in a new one.
- The **role** stops one side's card being played back at its author as the other side's.
- The **key** inside the signed text, with the signature check against that same key, is proof of
  possession: nobody can present a key they do not hold, and so nobody can get a bystander's public
  key registered under their own name.

The signature adds no secrecy. The channel is already authenticated by the two people comparing
digits (or by the token in the QR), and the card is encrypted under keys that comparison vouches for.

A card is refused — `BAD_PAIRING` — if it is not exactly 96 bytes, if the signature does not verify
for this session and the *other* side's role, or if its key is the receiver's own (pairing with
oneself or with a clone of one's own key is never what anybody meant).

### Order

```
sender                                      receiver
  ...digits compared (sender confirms;
     skipped when the QR token was used)
  IDENTITY (sender's card)        ->        check the card
                                            show "pair with <name>?" and wait
                                  <-        IDENTITY (receiver's card)   only after yes
  check the card; both sides finish
```

**The receiver confirms before it replies.** It verifies the sender's card on arrival, but sends
nothing back until its person has said yes. Answering first would hand its key to anybody who could
open a connection and sit through the digits. If the person says no, the receiver sends **no card**,
only a `DECLINE` frame, and closes; the sender, waiting in `AWAITING_PEER_IDENTITY`, reports
`DECLINED`. The receiver's person is given `USER_DECISION` (120 s), as for a transfer.

Each side reports the peer's key to the application only when the exchange is complete on its side:
the sender once the receiver's card verifies, the receiver as it sends its own.

States added to the session machines in [The conversation](#the-conversation):

```
sender    AWAITING_PEER_IDENTITY   IDENTITY  -> check, report key       DONE
                                   DECLINE                              FAILED (DECLINED)
receiver  AWAITING_OFFER           IDENTITY  -> check, ask the person   AWAITING_USER
          AWAITING_USER            accept    -> send own IDENTITY       DONE
```

### Unexpected frames

A pairing is told apart from a transfer only by which frame arrives, and neither is allowed to be
mistaken for the other.

- A receiver that is **not pairing** does not know `IDENTITY` in `AWAITING_OFFER`; like any frame not
  listed for a state it is `UNEXPECTED_MESSAGE` and the connection ends. A receiver that **is
  pairing** likewise refuses an `OFFER`.
- A **pairing sender** in `AWAITING_PEER_IDENTITY` accepts only `IDENTITY` or `DECLINE`; a
  **transfer sender** waiting for `ACCEPT` that is sent `IDENTITY` ends the same way.
- Any other frame in any state is as before: `UNEXPECTED_MESSAGE`. Types are still never skipped, so
  an older build that has never heard of `0x40` ends the connection and nothing more.

A pairing that ends in `CODES_DID_NOT_MATCH`, `KEY_NOT_AS_PROMISED` or `BAD_PAIRING` is a
verification failure, not a glitch, and is not offered a retry.

**Implementations.** Only the Android app speaks `IDENTITY` today. The desktop app (`desktop/nearby/`)
and the browser viewer (`site/`) do not implement the frame (no reference to type `0x40` in either),
so neither can take part in a pairing, and by the unknown-type rule a desktop peer ends a
connection that sends one. The shared
[test vectors](#test-vectors) do not yet cover it.

---

## When it goes wrong

Reasons carry **explicit numbers, never enum ordinals**. An ordinal is a wire format nobody
declared, and reordering a list should not break a protocol.

```
0x01 PROTOCOL_TOO_NEW      0x0B CODES_DID_NOT_MATCH
0x02 PROTOCOL_TOO_OLD      0x0C DECLINED
0x03 NOT_A_NEARBY_PEER     0x0D TIMED_OUT
0x04 MALFORMED_FRAME       0x0E CANCELLED
0x05 FRAME_TOO_LARGE       0x0F CONNECTION_LOST
0x06 UNEXPECTED_MESSAGE    0x10 TOO_LARGE
0x07 BAD_PUBLIC_KEY        0x11 NO_SPACE
0x08 WRONG_DEVICE          0x12 TRANSFER_INCOMPLETE
0x09 BAD_PAIRING           0x13 CONTENT_MISMATCH
0x0A DECRYPT_FAILED        0x14 TREE_FORMAT_TOO_NEW
                           0x15 IMPORT_REFUSED
                           0x16 NETWORK
                           0x17 PERMISSION
                           0x18 BUSY
                           0x19 KEY_NOT_AS_PROMISED
                           0xFF UNKNOWN
```

A number this release does not recognise reads as `UNKNOWN` and is shown as *the other device
stopped* — never as a raw code.

`RESULT` can also carry an import problem, so the existing refusals keep the sentences they already
have:

```
0x01 NOT_AN_ARCHIVE   0x02 NOT_A_TREE_FILE   0x03 FROM_A_NEWER_VERSION
0x04 EMPTY            0x05 UNREADABLE
```

An `ABORT` carries a number and nothing else. Free text would be attacker-controlled words in
somebody's log or dialog, and a number is all a sentence needs.

---

## Versions, and what a later release may change

A peer announcing a `minVersion` this release cannot speak is **shown, greyed, with a note that it
needs a newer f-tree** — not hidden. It is the same choice the importer makes when it refuses a file
from a later version out loud rather than pretending it is not there: a peer that vanishes is a bug
report, a peer that explains itself is an upgrade.

**Downgrading is structurally impossible.** Capability bits are covered by the transcript hash, so
stripping one in flight changes one side's transcript, which changes the salt, the keys and the six
digits, and the first encrypted frame fails to authenticate.

A later version may, without changing the wire version: append fields after the beacon's name, give
meaning to a reserved capability bit, or define a message type in an unused range.

It must bump the wire version to: move any offset in a frame that feeds the transcript, change the
group, change a label, change how the nonce is built, or change how the digits are reduced.

**A later merge-sync mode** is already accounted for. Both ends set bit 1; if the negotiated flags
carry it and the person chose to sync rather than send, the conversation after `ACCEPT` uses the
reserved range instead of `DATA`/`END`/`RESULT`. A version-1 implementation refuses that range
automatically, because an unknown type is fatal by rule.

---

## Test vectors

`docs/nearby/vectors.txt` holds the agreement between the two implementations, in the same shape as
`kinship-golden.txt`: comments on `#`, one case per line. It is read by the Kotlin unit tests and by
`node --test`, and **the diff on that file is the deliverable of any change to this document.**

```
 1  beacon encoding      short and long names, multibyte, every flag, port 1 and 65535,
                         GOODBYE, QUERY
 2  beacon rejection     truncated, wrong magic, a length past the end, an empty ANNOUNCE,
                         trailing bytes (which must be accepted)
 3  frames               round trips, a byte-at-a-time feed, a header split across two reads,
                         the smallest and largest legal bodies, and one past the ceiling
 4  messages             one per type, including an offer with no file name
 5  to256                the padding trap: a value whose top byte is zero, one with bit 2047
                         set, a one-byte value, and one already 256 bytes long
 6  DH arithmetic        fixed exponents to expected public values and shared secret
 7  DH rejection         Y of 0, 1, p-1, p, p+1 refused; 2 and p-2 accepted
 8  HKDF                 the RFC 5869 SHA-256 cases, then this document's three labels
 9  a whole handshake    fixed keys and nonces to an expected transcript, both session keys,
                         and the six digits, with and without a pairing token.
                         If both languages agree here, they interoperate.
10  nonces               sequence 0, 1, 2^32-1, 2^32, 2^53 and 2^64-1. The 2^53 case is there
                         to fail if JavaScript ever stops using BigInt.
11  GCM                  fixed inputs to ciphertext and tag, an empty message, a full one,
                         and a one-bit change to the AAD that must fail to open
12  QR links             round trips, a percent-encoded name, a public address refused,
                         a link-local one accepted, an unknown key ignored, a wrong scheme
13  the state machine    scripted event logs with the expected state and actions after each
                         step: the happy path, a decline, a mismatch, a bad digest, a peer
                         that disappears mid-transfer, an unexpected frame in every state
14  reason codes         every name against every number, both tables
15  QR modules           a golden matrix for one fixed link
16  commitment           the receiver's promise for two fixed keys and one fixed nonce, and a
                         HELLO_ACK carrying one
```
