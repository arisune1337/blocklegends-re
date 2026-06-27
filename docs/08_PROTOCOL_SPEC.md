# 08 — Block Legends Protocol Spec (client-implementer's reference)

**Goal:** everything needed to connect a custom client to **`81.8.66.123:26002`** (`m2.blocklegends.net`)
and speak Block Legends' protocol. Synthesized from static RE of `libblocklegends.so` (`gnatives.GnsNative`
JNI bridge) and `libGameNetworkingSockets.so`. Detail in `08a` (recv + state machine + config), `08b`
(app packet format), `08c` (transport + crypto), `05`/`07b` (networking). Each item tagged **KNOWN**
(byte/code-proven) or **UNKNOWN** (only resolvable by dynamic capture).

> **Status line:** Transport layer = **fully recovered**. Application opcode/field layout = **not statically
> recoverable** (GraalVM native-image AOT — see §4); needs one capture session. Runtime capture is currently
> **blocked**: the engine is arm64 GraalVM native-image and does **not** run under x86→arm translation
> (berberis *and* houdini both fail — see §8). Capture requires a real arm64 device or Genymotion Cloud arm64.

---

## 1. Transport — stock Valve GameNetworkingSockets (KNOWN)

- The game statically links **stock open-source GameNetworkingSockets** (OpenSSL crypto backend), used exactly
  as Valve ships it — confirmed by the flat `SteamAPI_ISteamNetworkingSockets_*` API + the OSS-only config ids
  47/48/49. No custom transport.
- **You cannot speak this with a raw UDP socket.** Below the app payload GNS adds its own **SNP** layer
  (per-packet 16-bit sequence numbers, reliable/unreliable typed segments, selective acks, per-lane reliable
  byte-streams, Nagle coalescing, MTU fragmentation+reassembly) and then **AES-256-GCM-seals the whole frame**.
  Reproducing that bug-compatibly = rewriting GNS.
- **Mandatory action:** link the real `libGameNetworkingSockets.so` (or Valve's GNS source) and call
  `ConnectByIPAddress(&addr, 12, pOptions)`; then `SendMessageToConnection` / `ReceiveMessagesOnConnection`.
- DNS is resolved **client-side** (Java passed `"81.8.66.123:26002"`; native parses via
  `SteamNetworkingIPAddr::ParseString`). Resolve `m2.blocklegends.net` yourself and connect by IP:port.

### The exact 12 `SteamNetworkingConfigValue_t` to set (built by `sub_811ADE4` @0x811ade4) — KNOWN
| k_ESteamNetworkingConfig_* | id | value |
|---|---|---|
| **IP_AllowWithoutAuth** | 23 | **1** — accept server with no Steam identity/cert |
| **Unencrypted** | 34 | **0** — encryption stays ON (cannot be disabled anyway) |
| SendBufferSize | 9 | 1048576 (1 MiB) |
| RecvBufferSize | 47 | 1048576 (1 MiB) |
| RecvBufferMessages | 48 | 1024 |
| RecvMaxMessageSize | 49 | 524288 (512 KiB) |
| SendRateMin | 10 | 524288 (512 KB/s) |
| SendRateMax | 11 | 2097152 (2 MB/s) |
| NagleTime | 12 | 0 (Nagle disabled) |
| MTU_PacketSize | 32 | 1180 |
| TimeoutInitial | 24 | 12000 ms |
| TimeoutConnected | 25 | 15000 ms |

## 2. Crypto (KNOWN — and it makes on-path sniffing useless)

- Key exchange **X25519 ECDH** → **SHA-256/HKDF** → per-direction keys+IV salts. Signing **Ed25519**
  (certs + signed key-exchange blob). Bulk **AES-256-GCM** AEAD; nonce = IV-salt XOR per-packet seqnum; 16-byte
  GMAC tag (failure ⇒ packet dropped).
- **`IP_AllowWithoutAuth=1` relaxes only peer *authentication* (cert checking), NOT confidentiality.** Every
  application message is encrypted before it hits the socket. A passive UDP sniffer on `:26002` sees only
  ciphertext. ⇒ capture must be at the **API boundary** (plaintext `ByteBuffer`), never on the network.

## 3. Message model (KNOWN)

- **One GNS message body == exactly the bytes the engine put in the `send` ByteBuffer.** The JNI bridge adds
  **zero** native framing: `send` @0x811fd3c does `GetDirectBufferAddress → AllocateMessage(len) → memcpy raw
  bytes → SendMessages`. `receive`/`receiveBatch`/`pollGroupReceive` do the symmetric `memcpy(buf, msg->m_pData,
  m_cbSize)`. No outer VarInt length prefix (GNS already provides message boundaries).
- **Lanes:** `m_idxLane` selects a per-lane independent reliable/ordered stream (no cross-lane head-of-line
  block). Lane 0 = default.
- **Send flags** (stock GNS): Unreliable=0, NoNagle=1, NoDelay=4, **Reliable=8**, UseCurrentThread=0x10,
  AutoRestartBrokenSession=32. Bridge OR-s in 0x10 conditionally. Login/state packets are Reliable(8); realtime
  gameplay likely Unreliable(0).

## 4. Application packet format (PARTIAL — class model KNOWN, byte layout UNKNOWN)

The engine is a **CraftRise / Minecraft-style packet system**, recovered from the GraalVM `reflect-config.json`
class metadata (KNOWN classes):

| Class | Role |
|---|---|
| `net.blocklegends.network.Packet` | base packet (read/write buffer) |
| `net.blocklegends.cr.client.manager.PacketManagerCustom` | id ↔ class registry/dispatch |
| `net.blocklegends.cr.client.packets.PacketRequest` / `PacketResponse` | request/response |
| `net.blocklegends.cr.client.packets.PacketPlayOutPlayerChange` | a concrete PLAY-state outbound packet |
| `net.blocklegends.cr.obfuscates.EnumConnectionState` | state machine (HANDSHAKING/STATUS/LOGIN/PLAY) |

**Inferred wire shape (model-level, NOT byte-verified):**
`send ByteBuffer = <packet-id (byte or VarInt)> + <fields via Packet.write(...)>`, legal id-set gated by the
current `EnumConnectionState`; `PacketManagerCustom` holds the numeric id↔class table.

**UNKNOWN (only in unsymbolized AOT machine code — needs capture):**
- packet-id **width** (single byte vs VarInt) and the **numeric opcode table**
- per-packet **field order / types**
- the LOGIN packet's exact byte layout around the auth token

Why static stops here: GraalVM `native-image` kept class/method *names* (for reflection/stack traces) but emitted
no per-method symbols, no per-packet strings, and only indirect JNI call edges. `xrefs_to` the 4 bridge stubs
returns only the `JNINativeMethod[]` registration table — the encoder never appears as a code xref. This is a
hard wall, not a missing pass.

## 5. Auth requirement (KNOWN boundary)

- A valid **account session token is required** in the LOGIN packet. It enters the engine via
  `GNatives.onTokenReceived(String token, String cred2)` →
  `Java_..._GNatives_onTokenReceived` → `CallStaticVoidMethod(nativesClass, "onTokenReceived",
  "(Ljava/lang/String;Ljava/lang/String;)V", token, cred2)` (the precise static boundary). Identity source =
  Google Play Games (`gc_isAuthenticated`, `onGcAuthSuccess`, `onGcServerAuthCode`) → blocklegends backend → token.
- **`IP_AllowWithoutAuth` does NOT bypass app auth.** It only relaxes the GNS transport-layer cert check; the
  application still validates the token server-side. No token ⇒ no play.

## 6. Connection state machine (KNOWN)

`k_ESteamNetworkingConnectionState`: None=0, **Connecting=1, FindingRoute=2, Connected=3, ClosedByPeer=4,
ProblemDetectedLocally=5**. The bridge does **not** wire a Java status-changed callback — it polls
`GetConnectionInfo.m_eState (+176)` each cycle:
- `getState` caches the int into the conn wrapper (+56), returns it to Java; **on transition to Connected(3)**
  it runs `FlushMessagesOnConnection` (drains queued reliable backlog).
- `pollCallbacks` (≤ every 15 s) is a census/reaper: counts connected/connecting/findroute/dead and
  `CloseConnection`s anything stuck Connecting > 60 s.
App expectation: open → poll `getState()` until 3 (Connected) → send HANDSHAKING/LOGIN packets (Reliable) →
on success advance `EnumConnectionState` to PLAY.

## 7. What remains + how to finish (the one capture session)

Because the native side adds zero framing, a hook at the bridge yields the **exact** app packet bytes with no
post-processing. Capture point (plaintext, pre-crypto):

- Hook these exports in `libblocklegends.so` (rebase to runtime load addr):
  `send`@0x811fd3c `(env,cls,connPtr,ByteBuffer,len,lane,flags)` · `sendBatch`@0x8120a70 · `receive`@0x811f534 ·
  `connect`@0x811a88c. In each, `GetDirectBufferAddress(env,ByteBuffer)` + `hexdump(addr,len)`.
- Also hook `GNatives.onTokenReceived` (log `str1`/`str2`) to locate the token field in the LOGIN packet.
- Correlate first bytes across many captures → read off id width, per-state opcode set, handshake→login→play
  order, and the token's encoding.
- Script ready: **`analysis/tools/frida_native_capture.js`** (hooks the GNS flat API
  `SendMessageToConnection`/`ReceiveMessagesOnConnection` — same plaintext, raw pointers).
- ⚠️ Frida 17 note: Java bridge isn't bundled by default; native Interceptor on `libblocklegends.so` works, but
  under any *translation* the lib is arm64 and Frida (x86_64) can't attach to it — another reason capture needs a
  real arm64 runtime. On a real arm64 device, hook `gnatives.GnsNative.send` at the **Java layer** to read the
  ByteBuffer with zero arch friction.

## 8. Runtime environment verdict (why capture isn't done yet)

The full pipeline to run the app off-device was built and works **except** the engine itself:
- ✅ APK minSdk 32→28 patch + page-align + co-sign; installs on Android 11/12 x86_64.
- ✅ PairIP license bypass via `adb install -i com.android.vending` (+ GApps).
- ✅ Renders (EGL/GLES native backend initialises).
- ❌ **GraalVM native-image engine will not run under x86→arm64 translation.** berberis 0.2.3 →
  `graal_create_isolate failed rc=23` (isolate heap/address-space model incompatible). houdini (A11) → SIGSEGV
  inside `libhoudini.so` even earlier. This is architectural (SVM signal-based safepoints + isolate memory
  model), not hookable.

**To capture the protocol you need the engine running on real ARM:** a rooted arm64 Android phone (engine runs
natively; emulator-detection passes; the root/Frida anti-tamper is TR/AZ-locale-gated and bypassable) **or**
Genymotion **Cloud arm64** (real ARM hardware). See `[[blocklegends-emulator-setup]]` for the turnkey
install/patch/frida tooling already prepared.

## 9. Feasibility — grafting onto the "yikes" Minecraft client

- **Packet handlers:** reusable in spirit — same Minecraft/CraftRise `Packet`/state-machine lineage — but the
  numeric opcodes + field layouts differ and are UNKNOWN until captured. You'd re-map ids, not reuse them blindly.
- **Transport:** NOT reusable. yikes speaks Minecraft TCP (25565); Block Legends speaks GNS/UDP/26002 with
  X25519+AES-256-GCM+SNP. You must **replace the transport** with the real GameNetworkingSockets library and
  `ConnectByIPAddress` + the 12 config values above.
- **Auth:** need a valid Play-Games→blocklegends session token; `IP_AllowWithoutAuth` does not bypass it.
- **Verdict:** a custom GNS bot is feasible (link real GNS, replay captured opcode table, supply a real token);
  a drop-in repoint of vanilla "yikes" is **not** — transport + auth + opcode table all differ.

---
*Addresses are `libblocklegends.so` (session d3d0fa7b) / `libGameNetworkingSockets.so` (session 96649281) VAs.
Server `81.8.66.123:26002` = m2.blocklegends.net. Transport = stock Valve GNS over UDP.*
