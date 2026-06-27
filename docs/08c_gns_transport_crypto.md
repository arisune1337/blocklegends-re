# 08c — GameNetworkingSockets: Transport + Crypto Internals

Target: `libGameNetworkingSockets.so` (IDA session `96649281`, ARM64 / GraalVM Android build).
Sibling bridge: `libblocklegends.so` (`gnatives.GnsNative`, session `d3d0fa7b`) — see report 08/07b.
Server: `81.8.66.123:26002` (`m2.blocklegends.net`), stock Valve GameNetworkingSockets over UDP.

This report answers: *is the UDP payload always encrypted, what crypto is used, what framing GNS
adds below the app payload, what the send-flags/lanes mean, and whether a custom client can avoid
linking the real GNS library.* **Short answer to the last point: no.**

---

## 0. This is stock Valve GNS built against OpenSSL

The library exports the full flat Steamworks networking C API, e.g.
`SteamAPI_ISteamNetworkingSockets_ConnectByIPAddress` is reached from the bridge, plus
`...AcceptConnection`, `...SendMessageToConnection`, `...ConfigureConnectionLanes`,
`...GetCertificateRequest`, `...SetCertificate`, `...GetConnectionRealTimeStatus`, and
`SteamAPI_ISteamNetworkingUtils_SetConnectionConfigValueInt32/Float/String`
(strings @ 0x498c1, 0x49a2b, 0x49c21, 0x49e40, 0x49e77, 0x4a071…). The crypto backend is the
**OpenSSL build of GNS** (`crypto_openssl.cpp` / `crypto25519_openssl.cpp`), not libsodium/bcrypt —
proven by the statically-linked OpenSSL symbol set below. There is no custom protocol layered on top;
the game uses the library exactly as Valve ships it.

---

## 1. Encryption is MANDATORY on the wire — AllowWithoutAuth does NOT turn it off

### 1a. What the crypto-handshake gate actually decides
In GNS the connection cannot reach `k_ESteamNetworkingConnectionState_Connected` until
`BFinishCryptoHandshake` succeeds. That routine has two independent jobs:

1. **Authentication** (identity/cert): verify the *peer's* cert chain and that the signed
   key-exchange material is bound to a trusted identity. This is the part
   `k_ESteamNetworkingConfig_IP_AllowWithoutAuth` and the "Unauthenticated"/"Unencrypted" certs relax.
2. **Encryption setup** (key agreement): run X25519 ECDH, derive the symmetric session keys, and
   install the AEAD cipher. **This step always runs regardless of the auth setting.**

`IP_AllowWithoutAuth=1` only tells GNS to *skip rejecting the connection when the peer cert is
absent / self-signed / not signed by the cert authority* — i.e. it downgrades **authentication**,
not **confidentiality**. The ECDH + AEAD path is unconditional. There is no GNS configuration that
sends cleartext application data over an established `ConnectByIPAddress` connection. (Even the
`k_ESteamNetworkingConfig_Unencrypted` developer knob in upstream GNS is compiled out of release
builds and is not exposed here; the only datagrams that are ever cleartext are the pre-handshake
`ChallengeRequest/ChallengeReply/ConnectRequest` control packets, which carry no app payload.)

**Consequence for on-path capture:** every byte of every application message (the bytes the bridge
`send()` copies out of the Java `ByteBuffer`) is sealed with AES-256-GCM before it leaves the socket.
A passive UDP sniffer on `81.8.66.123:26002` sees only ciphertext + GNS/SNP headers. AllowWithoutAuth
does not change that.

### 1b. Crypto primitives — confirmed by symbols present in the binary

Key exchange = **Curve25519 / X25519 ECDH**:
- `ossl_x25519` @ str 0x54e6b, `ossl_x25519_public_from_private` @ 0x54e77, `ossl_ecx25519_pkey_method`
  @ 0x577c6, `ossl_ecx25519_asn1_meth` @ 0x57828.
- Driven via `EVP_PKEY_new_raw_private_key` / `EVP_PKEY_new_raw_public_key` /
  `EVP_PKEY_get_raw_public_key` (strs @ 0x4aed9/0x4aef6/0x4aea0), then
  `EVP_PKEY_derive_init` → `EVP_PKEY_derive_set_peer` → `EVP_PKEY_derive`
  (strs @ 0x4af23/0x4af38/0x4af51). This is exactly GNS `CECKeyExchangePublicKey_Curve25519` ECDH.
- The raw 32-byte shared secret is run through **SHA-256** (`EVP_sha256` @ 0x4afda) HKDF to expand the
  send/recv AES keys + IV salts.

Signing = **Ed25519** (cert signatures + the signed key-exchange blob):
- `ossl_ed25519_sign` @ 0x54e09, `ossl_ed25519_verify` @ 0x54e36, `ossl_ed25519_pubkey_verify`
  @ 0x54e1b, `ossl_ed25519_public_from_private` @ 0x54e4a, `ossl_ed25519_pkey_method` @ 0x577f8.
- Driven via `EVP_DigestSignInit`/`EVP_DigestSign` (sign, strs @ 0x4af82/0x4af95) and
  `EVP_DigestVerifyInit`/`EVP_DigestVerify` (verify, strs @ 0x4afb4/0x4afc9). These wrap GNS
  `CECSigningPrivateKey_Ed25519` / `CECSigningPublicKey_Ed25519`.

Bulk record encryption = **AES-256-GCM** (AEAD):
- `EVP_aes_256_gcm` @ 0x4b0b0 (the 128/192 variants @ 0x4b08d/0x4b0c0 are linked but GNS selects 256).
- `EVP_EncryptInit_ex`/`EVP_EncryptUpdate`/`EVP_EncryptFinal_ex` (encrypt) and the matching
  `EVP_Decrypt*` (strs @ 0x4b0f6…0x4b154); `EVP_CIPHER_CTX_ctrl` @ 0x4b0e2 sets the 96-bit GCM IV and
  retrieves/checks the 16-byte GCM auth tag. Backed by ARMv8 hardware AES-GCM
  (`armv8_aes_gcm_encrypt`/`_decrypt` @ 0x5b6ab/0x5b6f3, `aes_v8_*` @ 0x4e8b3+, `CRYPTO_gcm128_*`
  @ 0x5b64f+). GMAC tag failure ⇒ packet dropped — this is the integrity/anti-tamper guarantee.

Net: GNS default suite. **X25519(ECDH) + Ed25519(sign) + AES-256-GCM(AEAD) + SHA-256(KDF)**, all via
statically-linked OpenSSL. Per-direction keys; nonce = derived IV salt XOR per-packet sequence number.

---

## 2. SNP framing — what GNS puts on each UDP datagram (BELOW the app payload)

Above raw UDP, GNS runs **SNP (Steam Networking Protocol)**, its own reliability/segmentation layer.
A custom client cannot speak to the server without reproducing this byte-for-byte, which is why
hand-rolling a raw-socket client is impractical. Evidence (GNS-internal strings in this binary):

- `MTU_DataSize is readonly` @ 0x154528 — the per-connection MTU config value.
- `Send Nagle %.1fms. QueueTime is %.1fms, SendRate=%.1fk, BytesQueued=%d, ping=%dms` @ 0x1572b9.
- `Sender sent abs unreliable message number using %llx mod %llx, highest seen %llx` @ 0x1549a1 —
  the 48-bit wrapped message-number sequencing.
- Reliable-stream assertions: `nNumReliableBytes > 0` @ 0x154a7a, `pMsg->SNPSend_IsReliable()`
  @ 0x154ac3, `sendLane.m_cbPendingReliable >= 0` @ 0x154af0,
  `m_listReadyRetryReliableRange[...] == hSeg` @ 0x157266, `(nDecodeReliablePos & nMask) == nOffset`
  @ 0x157312 — the reliable retransmit/reassembly bookkeeping.

What each encrypted UDP datagram carries (stock GNS wire format, below the AES-GCM boundary the
*plaintext* inside each packet is an SNP frame; the GCM tag covers it):

- **Packet header**: connection ID + a per-packet **sequence number** (16-bit on the wire, expanded to
  a 64-bit internal counter; the low bits also feed the AES-GCM nonce). This seqnum drives ack/nack,
  replay rejection, and RTT estimation.
- **Segments**: the packet body is a list of typed segments — *reliable* segments (carry a position in
  the per-lane reliable byte-stream, retransmitted until acked) and *unreliable* segments (carry an
  absolute message number, dropped if lost). One application message is **split across multiple
  segments** when it exceeds the MTU, and reassembled on the far side.
- **Acks / selective-ack ranges**: piggybacked stop-waiting + received-range info so the sender can
  retransmit only the missing reliable ranges.
- **Nagle / coalescing**: small messages are batched (the Nagle timer above) into one datagram.

**MTU**: GNS default `k_cbSteamNetworkingSocketsMaxUDPMsgLen` ≈ **1300 bytes**; the usable
`MTU_DataSize` after GNS/SNP/GCM overhead defaults to ~**1200 bytes** of plaintext payload per packet
(configurable via the `MTU_DataSize` config value, which is read-only once connected). App messages
larger than the segment budget are fragmented across packets and reassembled by SNP — the application
never sees the fragmentation.

**Practical point:** the bridge added *no* app framing natively (confirmed in report 08: each GNS
message body == the raw Java `ByteBuffer` bytes). But GNS itself wraps that body in the SNP
segment/seq/ack structure above and then AES-256-GCM-seals the whole SNP frame. That SNP+crypto layer
is the part you cannot hand-roll.

---

## 3. Send-flags and lanes (the values the bridge passes through)

`send(conn, ByteBuffer, len, lane, sendFlags)` @ `libblocklegends.so:0x811fd3c` forwards `sendFlags`
into `msg.m_nFlags` (OR-ing in `UseCurrentThread=0x10` under the conditional documented in report 08)
and forwards `lane` into `msg.m_idxLane`. The flag constants are stock GNS
(`steamnetworkingtypes.h`):

| Constant | Value | Meaning |
|---|---|---|
| `k_nSteamNetworkingSend_Unreliable` | `0` | Fire-and-forget; may be dropped/reordered. Default. |
| `k_nSteamNetworkingSend_NoNagle` | `1` | Flush immediately; do not wait to coalesce with later sends. |
| `k_nSteamNetworkingSend_NoDelay` | `4` | Drop rather than queue if it can't be sent right now (latency over delivery). Implies NoNagle. |
| `k_nSteamNetworkingSend_Reliable` | `8` | Reliable, in-order stream segment; retransmitted until acked. |
| `k_nSteamNetworkingSend_UseCurrentThread` | `16 (0x10)` | Do the send work on the calling thread (set conditionally by the bridge). |
| `k_nSteamNetworkingSend_AutoRestartBrokenSession` | `32` | Transparently re-establish a dropped session (P2P-oriented). |

Common composites: `Reliable|NoNagle = 9` (reliable, flush now), `Unreliable|NoDelay = 4`.

**Lanes** (`ConfigureConnectionLanes`, str @ 0x49c21): a single GNS connection is multiplexed into
priority/weighted **lanes**. Ordering and the reliable byte-stream are *per-lane* — messages on lane A
are ordered relative to each other but **independent** of lane B, so a stalled/retransmitting reliable
message on one lane does not head-of-line-block another lane. `m_idxLane` on each message selects the
lane; the bridge clamps it to `>= 0`. Lane 0 is the implicit default lane.

---

## 4. Practical conclusion — you MUST use the real GNS library

A custom/replacement client **cannot** talk to `81.8.66.123:26002` with a raw UDP socket. To produce a
single accepted application packet you must reproduce, in order:

1. The cleartext **connect handshake** (ChallengeRequest → ChallengeReply with the server's
   anti-spoof challenge → ConnectRequest carrying your Ed25519-signed X25519 public key + cert blob).
2. **X25519 ECDH** against the server's ephemeral key + **SHA-256/HKDF** session-key derivation
   (separate send/recv keys + IV salts) — must match Valve's exact KDF labeling.
3. Per-packet **SNP framing** (sequence numbers, reliable/unreliable segments, selective acks,
   per-lane reliable streams, MTU fragmentation/reassembly).
4. **AES-256-GCM** seal of every SNP frame with the correct per-packet nonce (IV salt XOR seqnum) and
   16-byte GMAC tag.

Re-implementing all of that bug-compatibly is effectively rewriting GameNetworkingSockets. The
sanctioned path is to **link the real `libGameNetworkingSockets.so`** (or Valve's open-source GNS) and
call `ConnectByIPAddress(&addr, 12, pOptions)` with the same 12 `SteamNetworkingConfigValue_t` options
the bridge builds (see report 08 / `sub_811ADE4` @ `libblocklegends.so:0x811ade4`, incl.
`IP_AllowWithoutAuth`), then `SendMessageToConnection`. The library handles the handshake, crypto, SNP,
and fragmentation. The app-level bytes you feed it are still just the Java `ByteBuffer` payload — that
is the only layer the game itself defines; everything below it is stock GNS.

---

## 6-line summary

1. Encryption is **mandatory**: every application message is AES-256-GCM sealed; `IP_AllowWithoutAuth=1`
   relaxes only peer *authentication* (cert checking), not *confidentiality* — on-path UDP capture is opaque ciphertext.
2. Key exchange = **Curve25519 / X25519 ECDH** (`ossl_x25519`, `EVP_PKEY_derive`); signing = **Ed25519**
   (`ossl_ed25519_sign/verify`, `EVP_DigestSign/Verify`); KDF = SHA-256; AEAD = AES-256-GCM (`EVP_aes_256_gcm`).
3. Below the app payload GNS adds its own **SNP** layer: per-packet sequence numbers, reliable/unreliable
   segments, selective acks, per-lane reliable streams, Nagle coalescing — then GCM-seals the whole frame.
4. **MTU** ≈ 1300-byte UDP datagram / ~1200-byte usable `MTU_DataSize`; larger messages are split into
   segments and reassembled by SNP automatically.
5. Send flags: Unreliable=0, NoNagle=1, NoDelay=4, Reliable=8, UseCurrentThread=0x10,
   AutoRestartBrokenSession=32; **lanes** give independent per-lane ordering (no cross-lane head-of-line block).
6. A custom client **must link/use the real GameNetworkingSockets library** and call `ConnectByIPAddress`
   with matching config — the handshake + X25519/AES-GCM + SNP framing cannot be hand-rolled over a raw UDP socket.
