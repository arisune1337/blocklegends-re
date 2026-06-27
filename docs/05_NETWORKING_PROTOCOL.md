# Block Legends v218.0.0 — Multiplayer Networking Stack

Target: `C:/Users/user/Desktop/blocklegends/extracted/natives/libGameNetworkingSockets.so`
IDA session: `17a34222`  |  Module: `libGameNetworkingSockets.so`
MD5 `8f4c45b06f71ace36f240899e9f0e2e0`  SHA256 `aeb15c5476b44007ef46b1083ad7331617db01976f2e402cc46dbb2547495111`
Arch: AArch64 (ELF shared object, base 0x0, image size 0x961a00 ≈ 9.6 MB)
Functions: 24,815 (12,921 named) | Strings: 27,140 | 23 segments

---

## 0. TL;DR

- The multiplayer transport is **STOCK / UNMODIFIED Valve open-source GameNetworkingSockets (GNS)**, the `GameNetworkingSockets-master` branch, statically built (with its own bundled OpenSSL 3.x + abseil). The build was done on the same developer machine as the rest of the game: every assertion path embeds the absolute source path `**/Users/yunus/git/GameNetworkingSockets-master/src/...**` (compare the game build origin `/Users/yunus/AndroidStudioProjects/craftriseandroidstudio/`). No game-specific patches were found in the library: the API surface, protobuf message set, certstore, SNP, and crypto are all bit-for-bit the public GNS code.
- Crypto is GNS-standard: **Curve25519 (x25519) ECDH** key agreement, **Ed25519** cert/identity signatures (with ECDSA also linked), **AES-256-GCM** record encryption (ARMv8 hardware path), **ChaCha20-Poly1305** available, **HKDF** key derivation — all from a **statically-linked OpenSSL 3.x** provider stack.
- The application layer is **not** raw Minecraft-over-TCP. The game keeps a **CraftRise/Minecraft-style packet abstraction** (`net.blocklegends.network.Packet`, `cr.client.packets.PacketPlayOut*`, `cr.obfuscates.EnumConnectionState`, `cr.client.manager.PacketManagerCustom`) and serializes those packets into a **custom binary blob that is tunneled as GNS reliable/unreliable messages**. GNS itself provides the encryption, reliability, fragmentation and ordering; the Minecraft-derived layer provides only the message taxonomy and connection-state machine.
- Java↔native bridge is the JNI class **`gnatives.GnsNative`**, which dynamically loads `libGameNetworkingSockets.{so,dll,dylib}` and exposes a thin wrapper (`connect`, `createServer`, `serverAccept`, `send`/`sendBatch`, `receive`/`receiveBatch`, poll groups, etc.). It is invoked from the AOT-compiled game image `libblocklegends.so`, not from the readable DEX.

---

## 1. GNS overview & API surface

### 1.1 Identity / build provenance
Every internal `AssertMsg`/`SpewMsg` call carries the full build path, e.g. (decompiled, function `sub_385F70`):
```
/Users/yunus/git/GameNetworkingSockets-master/src/steamnetworkingsockets/steamnetworkingsockets_certstore.cpp
/Users/yunus/git/GameNetworkingSockets-master/src/steamnetworkingsockets/clientlib/steamnetworkingsockets_connections.cpp
```
This is the canonical Valve repo layout (`steamnetworkingsockets_*`, `clientlib/`, `certstore`, `snp`). The library is the open-source "GameNetworkingSockets" (not the closed Steamworks SDK build) — it exports `GameNetworkingSockets_Init` / `GameNetworkingSockets_Kill` (strings @ 0x49588 / 0x495cb), which only exist in the OSS standalone build.

### 1.2 Exported interface factories (versions ⇒ ~GNS 1.4.x master)
| Export | Addr | Meaning |
|---|---|---|
| `SteamNetworkingSockets_LibV12` | 0x397fc4 | ISteamNetworkingSockets v12 factory (flat C entry) |
| `SteamNetworkingUtils_LibV4` | 0x398190 | ISteamNetworkingUtils v4 |
| `SteamNetworkingMessages_LibV2` | 0x3981e4 | ISteamNetworkingMessages v2 |
| `SteamAPI_SteamNetworkingSockets_v009` | 0x3a2590 | flat accessor, interface "v009" |
| `SteamAPI_SteamNetworkingUtils_v003` | (str 0x49ed5) | utils flat accessor |
| `GameNetworkingSockets_Init` / `_Kill` | (str) | standalone lib init/teardown |
| `SteamNetworkingSockets_CRGetConnRecvWakeFd` | 0x397fd0 | epoll/poll wake fd (custom flat export used by the JNI wrapper for non-blocking receive) |
| `SteamNetworkingSockets_CRGetPollGroupRecvWakeFd` | 0x3980bc | poll-group wake fd |
| `SteamNetworkingSockets_Poll` / `_SetManualPollMode` | 0x3b6014 / 0x3b5eb8 | manual service-thread pump (the game drives the loop itself) |

The `_CR…WakeFd` exports (the `CR` = CraftRise) are the only non-vanilla symbols — they are small additive helpers (return the connection's receive wake file descriptor so the JVM side can `select()` on it). They do **not** alter the protocol; they wrap `m_pSock->GetWakeFD()`. Everything else is the standard flat API.

### 1.3 Full flat API (the `SteamAPI_ISteamNetworkingSockets_*` family, 0x3a2594–0x3a3020)
Connection lifecycle: `CreateListenSocketIP`, `ConnectByIPAddress`, `CreateListenSocketP2P`, `ConnectP2P`, `ConnectP2PCustomSignaling`, `AcceptConnection`, `CloseConnection`, `CloseListenSocket`, `CreateSocketPair`.
Data path: `SendMessageToConnection`, `SendMessages`, `FlushMessagesOnConnection`, `ReceiveMessagesOnConnection`, `ReceiveMessagesOnPollGroup`, `ConfigureConnectionLanes` (multi-lane reliable streams).
Poll groups: `CreatePollGroup`, `DestroyPollGroup`, `SetConnectionPollGroup`.
Status / introspection: `GetConnectionInfo`, `GetConnectionRealTimeStatus`, `GetDetailedConnectionStatus`, `GetListenSocketAddress`, `GetConnectionUserData`, `GetConnectionName`.
Auth / certs: `GetIdentity`, `InitAuthentication`, `GetAuthenticationStatus`, `GetCertificateRequest`, `SetCertificate`.
P2P signaling: `CreateCustomSignaling`, `ReceivedP2PCustomSignal`, `ReceivedP2PCustomSignal2`.

### 1.4 SNP — Steam Networking Protocol (reliability layer)
GNS runs its own reliability/ordering protocol over datagrams (UDP), independent of TCP. Evidence in this binary:
- Reliability/flow-control config knobs (strings): `NagleTime` (0x189930), `SendBufferSize` (0x18c4f7), `SendRateMin` (0x1a1d67) / `SendRateMax` (0x19701a), `TimeoutInitial` (0x17a7c3), `TimeoutConnected` (0x182126).
- MTU / fragmentation: `MTU_PacketSize` (0x15c399), `MTU_DataSize is readonly` (0x154528), `MTU / header size problem!` (0x161995), `Msg type %d is %d bytes, larger than MTU of %d bytes` (0x166aad).
- Multi-lane reliable streams via `ConfigureConnectionLanes` (the game can isolate e.g. chunk/world traffic from chat/control traffic into separate reliable lanes so a stall in one does not head-of-line-block the others).
SNP provides: per-lane reliable + unreliable segments, selective ACK/NACK, fragment + reassembly, Nagle batching, RTT-based send-rate estimation, keepalives. The Minecraft-style packets ride on top of this as opaque message bytes.

### 1.5 Transport (UDP) and P2P
- UDP sockets via libc imports: `sendto`, `recvfrom`, `sendmsg`, `recvmsg`, `sendmmsg`, `socket`, `connect`, `socketpair`, `pipe` (.dynsym 0x961068…0x9616e0). `sendmmsg` => batched UDP send.
- `CreateSocketPair` / loopback path: `RecvCryptoHandshake failed creating loopback pipe socket pair` (0x18721b), `…localhost socket pair` (0x189fe6) — local in-process connections.
- **ICE / SDR**: P2P uses ICE (host/STUN/TURN candidate gathering) negotiated through signaling. Protobuf `CMsgICERendezvous` (incl. `.Auth`), `CMsgSteamNetworkingICESessionSummary`, and config keys `ice_enabled` (0x288ea4), `ice_enable_var` (0x2893f9), `sdr_routes` (0x288e6e), `from_fakeip` (0x28913d) are present. **Steam Datagram Relay (SDR) and FakeIP are compiled in but gated off without Steam** — `FakeIP allocation requires Steam` (0x159b50), `FakeIP system requires Steam` (0x1613f5). So in this game the relay/FakeIP path is inert; connections are **direct-IP** (`ConnectByIPAddress` to a CraftRise game server) or **ICE P2P with the game's own custom signaling channel**.

---

## 2. Encryption / handshake / cert verification

### 2.1 Crypto primitives (statically-linked OpenSSL 3.x, ARMv8 accelerated)
The whole OpenSSL provider stack is baked into this .so (thousands of `ossl_*`, `EVP_*`, `X509_*`, `CRYPTO_gcm128_*` symbols). The pieces GNS actually uses:

| Purpose | Primitive | Evidence (strings) |
|---|---|---|
| Ephemeral key exchange | **X25519 (Curve25519) ECDH** | `ossl_x25519` (0x54e6b), `ossl_x25519_public_from_private` (0x54e77), `ECDH_compute_key` (0x55d09), `ossl_ecdh_compute_key`, `EVP_PKEY_derive_SKEY` |
| Cert / identity signature | **Ed25519** (ECDSA also linked) | `ossl_ed25519_sign` (0x54e09), `ossl_ed25519_verify` (0x54e36), `ossl_ed25519_pubkey_verify`, `EVP_PKEY_new_raw_public_key`/`get_raw_public_key` |
| Record encryption (AEAD) | **AES-256-GCM** (HW) | `EVP_aes_256_gcm` (0x4b0b0), `armv8_aes_gcm_encrypt/decrypt` (0x5b6ab/0x5b6f3), `aes_gcm_enc_256_kernel`, `gcm_ghash_v8`, `CRYPTO_gcm128_*` |
| Alt AEAD | **ChaCha20-Poly1305** | `EVP_chacha20_poly1305` (0x5bdf8), `Poly1305_Init/Update/Final` |
| Key derivation | **HKDF** | `EVP_PKEY_CTX_set_hkdf_md` (0x5fb81), `set1_hkdf_salt`, `set1_hkdf_key`, `add1_hkdf_info`, `set_hkdf_mode` |

This is exactly GNS's `CCrypto` model: each session does an X25519 ECDHE, signs the ephemeral key + a `CMsgSteamDatagramSessionCryptInfo` with the per-peer cert key (Ed25519), derives directional AES-256-GCM keys, and encrypts every SNP record with AES-256-GCM. `CryptoSignature_t` is 64 bytes (asserted in `sub_385F70`: `cert.m_signature.length() == sizeof(CryptoSignature_t)` ⇒ 64-byte Ed25519 signature).

### 2.2 Handshake protobufs (standard GNS schema)
Compiled-in `.proto` descriptors (rodata @ 0x287c08 `protodesc_cold`):
`CMsgSteamDatagramCertificate`, `CMsgSteamDatagramCertificateSigned`, `CMsgSteamDatagramCertificateRequest`, `CMsgSteamDatagramSessionCryptInfo`, `CMsgSteamDatagramSessionCryptInfoSigned`, `CMsgSteamNetworkingIdentityLegacyBinary`, `CMsgSteamNetworkingP2PRendezvous` (sub-msgs `ConnectRequest`, `ConnectOK`, `ConnectionClosed`, `ReliableMessage`, `ApplicationMessage`), `CMsgICERendezvous(.Auth)`, `CMsgSteamNetworkingICESessionSummary`, `CMsgSteamDatagramConnectionQuality`, `CMsgSteamDatagramLinkInstantaneousStats`, `CMsgSteamDatagramLinkLifetimeStats`, `CMsgSteamDatagramDiagnostic`.

### 2.3 Handshake functions (decompiled)
- **`BRecvCryptoHandshake`** — `sub_3A93C4` @ 0x3A93C4 (refs string 0x19f72d). Parses the peer's `CMsgSteamDatagramCertificateSigned` + `CMsgSteamDatagramSessionCryptInfoSigned`, validates the cert chain, runs ECDH, installs AES-GCM keys.
- **`BFinishCryptoHandshake`** — `sub_3AA6A4` @ 0x3AA6A4 and `sub_3A9B00` @ 0x3A9B00 (connections.cpp:1866). `sub_3AA6A4` is the cert-acceptance gate; decompiled:
  ```c
  if ( CheckCert(... , v6, ...) ) {            // sub_385BE0
      if ( peer_has_signed_cert ) return 0;    // OK, secure
      v8 = AllowWithoutAuth(this);             // vtbl+160 -> config policy
      if ( v8 == 2 ) return 0;                 // explicitly allowed
      if ( v8 == 1 ) { Spew("[%s] Remote host is using an unsigned cert. "
                            "Allowing connection, but it's not secure!"); return 0; }
      SetErr(a3, "Unsigned certs are not allowed", 1024);
  }
  return 4003;                                 // k_ESteamNetConnectionEnd_Remote_BadCert region
  ```
  So an **unsigned/self-signed peer is rejected with 4003 unless the connection config opts into insecure mode** (`AllowWithoutAuth` ⇒ 1 or 2). Related strings: `We don't have cert, and self-signed certs not allowed` (0x168c42), `Unsigned certs are not allowed` (0x197206), `Continuing with self-signed cert.` (0x18714f), `Remote host is using an unsigned cert. Allowing connection, but it's not secure!` (0x18f2e5).

### 2.4 Cert / CA pinning — `CertStore` (decompiled `sub_385F70` @ 0x385F70, certstore.cpp)
This is the GNS trust-chain resolver (`CCertAuthScope` / `ResolveCert`). Key facts proven in pseudocode:
- There is a **single hardcoded trusted root key**: when the resolver reaches a cert whose signer == the baked-in root key object (`v16 == (_DWORD *)a1`), it sets `"Trusted root is hardcoded, cannot add more self-signed certs"` (string 0x15e99a) and stops — i.e. **CA pinning to one root public key** (`steamnetworkingsockets_certstore.cpp:417/428/438…563`).
- Each cert in the chain is verified: signature length must be 64 (Ed25519), `m_signed_data` non-empty, `m_authScope` non-empty; signature is checked against the signer CA key via **`sub_3E4074`** (`Crypto::VerifySignature`, "Failed signature verification (against CA key %llu)" @ 0x17f85d). CA-key lookup is **`sub_3851F8`** ("CA key %llu is not known" @ 0x186fcd).
- Trust states map to GNS `k_ETrust_*`: `m_eTrust != k_ETrust_UnknownWorking`, `m_eTrust <= k_ETrust_NotTrusted`. Auth scope carries app-IDs ("All apps excluded by auth chain!" 0x1946a3), PoPIDs ("All pops excluded…" 0x191aa1, `Cert with no identity must be scoped to PoPID.` 0x16deb5) and expiry (`authScope.m_timeExpiry > 0`).

**Is it Valve cert auth or custom?** It is the **standard Valve GNS certstore mechanism, with a single pinned root**. Because this is the standalone OSS build with no Steam backend (`FakeIP/SDR require Steam` ⇒ off), the root is **not** Valve's live CA; it is whatever CA public key CraftRise compiled in (or the connection runs in the "unsigned/self-signed allowed" insecure mode shown above). The mechanism is unchanged; only the trusted-root key material and the issuance authority differ. Extracting the exact 32-byte pinned Ed25519 root key would require dumping the certstore singleton initializer (the static buffer fed into the `a1` root object) — the code path is `sub_385F70`/`sub_3851F8`, root installed once at startup.

---

## 3. The game's bridge (`gnatives.GnsNative`) and the application protocol

### 3.1 JNI bridge — `gnatives.GnsNative`
The game accesses GNS through one JNI class (symbols in `strings_all.txt`, registered in the native-image JNI config: `gnatives/GnsNative` @ line 33390, and `{ "name": "gnatives.GnsNative" }` @ 47186). The native methods (all `Java_gnatives_GnsNative_*`, lines 603–617, 1165–1173):

`init`, `lastError`, `connect`, `createServer`, `serverAccept`, `closeServer`, `pollCallbacks`, `getState`, `getRemoteAddress`, `receive`, `receiveBatch`, `send`, `sendBatch`, `getConnectionStatus`, `closeConnection`, `waitForMessages`, `pollGroupWaitForMessages`, `getConnectionQuality`, `setConnectionSendRate`, `getServerPollGroup`, `createPollGroup`, `destroyPollGroup`, `setConnectionPollGroup`, `pollGroupReceive`.

These wrap the flat API 1:1 (e.g. `serverAccept`→`AcceptConnection`, `createServer`→`CreateListenSocketIP`, `connect`→`ConnectByIPAddress`, `send`/`sendBatch`→`SendMessages`, `receive*`→`ReceiveMessagesOn{Connection,PollGroup}`, `getConnectionQuality`→`GetConnectionRealTimeStatus`, `setConnectionSendRate`→`SetConfigValue(SendRate*)`).

Data is moved zero-copy via **direct `java.nio.ByteBuffer`s**: the JNI signatures require it — `send requires a direct ByteBuffer` (str 32855), `receive requires a direct ByteBuffer` (33044), `receiveBatch requires a direct ByteBuffer` (32761), `sendBatch requires direct ByteBuffer and metadata arrays` (34499). So the Minecraft-style `Packet` objects are serialized into a direct ByteBuffer on the JVM heap and handed straight to `SendMessages`.

Dynamic loader strings (cross-platform): `Unable to load GameNetworkingSockets:` (32474), `GameNetworkingSockets.dll` (32570), `libGameNetworkingSockets.so` (33658), `libGameNetworkingSockets.dylib` (35081), `GameNetworkingSockets_Init failed:` (33128). The JNI bridge itself lives in the AOT image `libblocklegends.so` (not in this .so and not in the readable DEX — the DEX grep for these classes returns nothing because R8+GraalVM moved them native).

### 3.2 Application-level packet layer (CraftRise / Minecraft-derived) — rides on top of GNS
The DEX side is obfuscated, but the native-image reflection metadata (embedded JSON in `libblocklegends.so`, visible in `strings_all.txt` ~line 45506+) preserves the original class names:

| Class | strings line | Role |
|---|---|---|
| `net.blocklegends.network.Packet` | 45541 | Base packet (serialize/deserialize to/from buffer) |
| `net.blocklegends.cr.client.manager.PacketManagerCustom` | 45548 | Packet registry / dispatcher (read id → construct → handle) |
| `net.blocklegends.cr.client.packets.PacketRequest` | 45513 | Generic client→server request |
| `net.blocklegends.cr.client.packets.PacketResponse` | 45506 | Generic server→client response |
| `net.blocklegends.cr.client.packets.PacketPlayOutPlayerChange` | 45619 | In-game "PlayOut" state packet (player update) |
| `net.blocklegends.cr.obfuscates.EnumConnectionState` | 45633 | Connection state machine (HANDSHAKING/LOGIN/PLAY-style) |

These names are the dead giveaway of the lineage:
- **`PacketPlayOut…`** is the exact Mojang/NMS/Bukkit naming convention (`PacketPlayOutXxx` = clientbound play-state packet).
- **`EnumConnectionState`** is the literal class name of Minecraft's `net.minecraft.network.EnumConnectionState` (the HANDSHAKING→LOGIN→PLAY/STATUS protocol phase enum).
- **`PacketRequest`/`PacketResponse`/`PacketManagerCustom`** are the CraftRise customizations (the "Custom" suffix and request/response pair are CraftRise additions, not vanilla Minecraft).

So the protocol design is: a **Minecraft-style stateful packet model** (numeric packet IDs registered per connection-state in `PacketManagerCustom`, each `Packet` reading/writing its fields from a buffer), **serialized to a custom binary format and carried as GNS messages**. GNS handles framing, encryption, reliability and ordering, so the app layer does **not** need the Minecraft TCP length-prefix/VarInt framing or its own encryption — it just (de)serializes packet bodies into the direct ByteBuffer that `GnsNative.send` ships. (No `PacketPlayOutMapChunk`/`PacketLoginStart`/Netty pipeline handlers for a Mojang wire format were found; the Netty/`ByteBuf` symbols present in the image belong to gRPC-shaded internals/login HTTP, not to the in-game transport, which is GNS.)

---

## 4. Configuration: ports, timeouts, addresses

- **No hardcoded game-server IP:port or STUN/TURN host strings are baked into `libGameNetworkingSockets.so`.** It is a generic transport library; the destination address is passed in at runtime by the game (`GnsNative.connect`/`createServer`) from `libblocklegends.so` config. (GNS has **no default UDP port** of its own — unlike Steamworks' 27015 — the listen/connect address is always caller-supplied via `SteamNetworkingIPAddr`.) The classic Steam port 27015/27016 does **not** appear.
- Connection-config value names present (set via `SteamNetworkingUtils_SetConfigValue`): `TimeoutInitial`, `TimeoutConnected`, `NagleTime`, `SendBufferSize`, `SendRateMin`, `SendRateMax`, `MTU_PacketSize`, plus the global `ice_enabled` / `ice_enable_var` and `sdr_routes` keys. Defaults are the GNS built-ins (TimeoutInitial 10 s, TimeoutConnected 10 s, MTU ~1200–1500, SendRate auto via bandwidth estimation) unless overridden at runtime by the game.
- **STUN/TURN**: no literal `stun:`/`turn:` URLs are embedded here; ICE candidate/relay servers (if any) are supplied at runtime through the game's signaling (`CMsgICERendezvous`). SDR relay clusters and FakeIP are present in code but disabled (`requires Steam`).
- Identity parsing helpers exported: `SteamNetworkingIdentity_ParseString/ToString`, `SteamNetworkingIPAddr_ParseString/ToString/GetFakeIPType` — the game passes peers as `SteamNetworkingIdentity`/IP strings.

---

## 5. Relationship to the classic Minecraft protocol

- **Not a Minecraft TCP wire protocol tunnel.** There is no Mojang-style framing (length-prefixed VarInt packets), no `PacketPlayOutMapChunk`/`PacketLoginStart`/`PacketStatus` handler set, and no Netty channel pipeline carrying a Minecraft codec on the multiplayer path. The in-game transport is **UDP + GNS (SNP) with AES-256-GCM**, which is fundamentally different from vanilla Minecraft's TCP + per-stream AES-CFB8.
- **But the packet *model* is Minecraft/CraftRise-derived.** The app keeps Minecraft's conceptual structure — a per-connection **`EnumConnectionState`** phase machine and **`PacketPlayOut*`** clientbound packets dispatched by **`PacketManagerCustom`** — and serializes those `Packet` objects into a **custom binary blob** that is sent as a GNS reliable/unreliable message. In effect CraftRise took the Minecraft `net.minecraft.network` packet abstraction, replaced the TCP+Netty transport and its handshake/encryption with **Valve GameNetworkingSockets**, and let GNS provide identity certs (Ed25519), key exchange (X25519), AEAD (AES-256-GCM), reliability and lanes.
- Login/auth: GNS's own `InitAuthentication`/`GetCertificateRequest`/`SetCertificate` cert flow replaces Minecraft's Mojang/Yggdrasil session handshake at the transport layer; the higher-level account login (Google/Apple/GameCenter tokens — see `GNatives_onTokenReceived`, `onGcAuthSuccess` in strings) happens out-of-band over HTTPS before the GNS connection, consistent with the CraftRise login protocol noted in project memory.

---

## 6. Key addresses (for follow-up RE)

| Addr | Symbol (IDA) | What |
|---|---|---|
| 0x397fc4 | `SteamNetworkingSockets_LibV12` | main interface factory |
| 0x3A93C4 | `sub_3A93C4` | `BRecvCryptoHandshake` (parse+verify peer cert/cryptinfo, ECDH) |
| 0x3AA6A4 | `sub_3AA6A4` | `BFinishCryptoHandshake` unsigned-cert gate (returns 4003 / allows insecure) |
| 0x3A9B00 | `sub_3A9B00` | `BFinishCryptoHandshake` body (connections.cpp:1866) |
| 0x385F70 | `sub_385F70` | `CertStore` chain resolver / hardcoded-root pin (certstore.cpp) |
| 0x3851F8 | `sub_3851F8` | certstore CA-key lookup |
| 0x3E4074 | `sub_3E4074` | `Crypto::VerifySignature` (Ed25519, 64-byte sig) |
| 0x3b4c48 | `SteamNetworkingSockets_DefaultPreFormatDebugOutputHandler` | spew/log handler |
| 0x287c08 | segment `protodesc_cold` | compiled protobuf descriptors (all CMsg* schemas) |

Bridge (in `libblocklegends.so`, not this .so): JNI class `gnatives.GnsNative` — methods listed in §3.1.
App packets (in `libblocklegends.so`): `net.blocklegends.network.Packet`, `cr.client.manager.PacketManagerCustom`, `cr.client.packets.{PacketRequest,PacketResponse,PacketPlayOutPlayerChange}`, `cr.obfuscates.EnumConnectionState`.
