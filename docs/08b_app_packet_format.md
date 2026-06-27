# 08b — Application Packet Format (the bytes inside the GNS send() ByteBuffer)

Database: `d3d0fa7b` = `libblocklegends.so` (GraalVM native-image AOT engine + JNI bridges).
Goal: recover, as far as statically possible, the app-level framing the engine writes into the
`GnsNative.send` ByteBuffer (the body of each GameNetworkingSockets message).

Bottom line up front: **the transport boundary is fully recovered; the exact app byte layout is NOT
statically recoverable** because GraalVM AOT did not symbolize the Java engine. What survives
statically is the *class model* of the protocol (a CraftRise / Minecraft-style packet system) plus
the precise capture points to finish it dynamically. Concrete evidence and the finish plan below.

---

## 1. Why the Java call sites cannot be decompiled (the AOT wall)

`xrefs_to` on the four bridge entry points returns **only data refs**, no code callers:

| Bridge (JNI export)              | addr        | xrefs (both `type:data`)        |
|----------------------------------|-------------|---------------------------------|
| `GnsNative.send`                 | 0x811fd3c   | 0x7e70 , 0x8222160              |
| `GnsNative.sendBatch`            | 0x8120a70   | 0x12fe8 , 0x8222178             |
| `GnsNative.connect`              | 0x811a88c   | 0x15d00 , 0x8222088             |
| `GnsNative.receive`              | 0x811f534   | 0x1aad0 , 0x8222130             |

The `0x8222088..0x8222178` cluster is the **`JNINativeMethod[]` registration table** (name/sig/fnptr
triples passed to `RegisterNatives`); the low addresses (0x7e70…) are the relocation/GOT slots.
There is **no direct `BL` from engine code** to these stubs: GraalVM AOT calls a `native` method
through a generated JNI wrapper that loads the resolved function pointer indirectly, so the encoder
that fills the ByteBuffer never appears as an xref to `send`.

Confirming the symbolization gap:
- `list_funcs *acket*` → only C-library symbols (`opus_packet_*`). **Zero** `Packet*` Java functions.
- `list_funcs *ConnectionState*` and `*andshak*` → **empty**.
- No `writeVarInt` / `readVarInt` / `registerPacket` / "unknown packet" strings exist in the
  `net.blocklegends.*` namespace.

So the engine's encoder/decoder bodies are AOT machine code with no names, no per-packet strings,
and only indirect entry — i.e. not practically decompilable into a field-by-field packet spec.

## 2. What the native side proves about framing (recovered earlier, re-confirmed)

From `07b_native_networking.md` / prior slice: `send` @0x811fd3c does
`GetDirectBufferAddress` → `AllocateMessage(len)` → **raw `memcpy` of the ByteBuffer bytes** →
set `m_conn/m_idxLane/m_nFlags` → `SendMessages`. **No app framing is added natively.**

Therefore: **one GNS message body == exactly the bytes the Java engine put in the ByteBuffer.**
GameNetworkingSockets already provides message boundaries + reliability (SNP) + Curve25519/AES-256-GCM,
so the app layer does **not** need (and, per the Minecraft-family model, almost certainly omits) the
TCP-style outer `VarInt(length)` frame prefix. The ByteBuffer is the packet.

## 3. The protocol class model that DOES survive statically

Recovered from the embedded GraalVM `reflect-config.json` (strings_all.txt ~L45506–45638) and the
class-metadata blob. This is a **CraftRise-derived, Minecraft-style packet system**:

| Class (FQN)                                                  | Role                                              |
|--------------------------------------------------------------|---------------------------------------------------|
| `net.blocklegends.network.Packet`                            | Base packet (read/write to a buffer)              |
| `net.blocklegends.cr.client.manager.PacketManagerCustom`     | Packet **registry**: id ↔ class mapping/dispatch  |
| `net.blocklegends.cr.client.packets.PacketRequest`           | Request packet (STATUS/handshake-style)           |
| `net.blocklegends.cr.client.packets.PacketResponse`          | Response packet                                   |
| `net.blocklegends.cr.client.packets.PacketPlayOutPlayerChange`| A concrete PLAY-state outbound packet            |
| `net.blocklegends.cr.obfuscates.EnumConnectionState`         | Connection **state machine** (handshaking→…→play) |
| `net.blocklegends.client.CraftRise`, `cr.launcher.main.CRLauncher` | Client/launcher entry                        |

`EnumConnectionState` is confirmed by the live string `NOT_HANDSHAKING` (strings L251053) and the
class name `…obfuscates.EnumConnectionState`. This is the classic Minecraft state set
(HANDSHAKING / STATUS / LOGIN / PLAY) — the other constant names collide with UI strings so only
`NOT_HANDSHAKING` is uniquely greppable, but the enum's existence + role is certain.

**Inferred wire shape (model-level, NOT byte-verified):** each `send` ByteBuffer =
`<packet-id (byte or VarInt)>` + `<fields serialized by Packet.write(...)>`, with the legal id-set
selected by the current `EnumConnectionState`. `PacketManagerCustom` holds the id↔class table that
fixes the numeric opcodes. The exact **id width** (byte vs VarInt) and **field order** are exactly
the parts that live only in the unsymbolized AOT code.

## 4. The auth-token entry point (login flow boundary) — fully traced

The auth token enters the engine through `GNatives`, not through any on-disk packet builder:

- Native callbacks (exports in `libblocklegends.so`):
  `Java_..._GNatives_onTokenReceived(String,String)`,
  `Java_..._GNatives_onGcAuthSuccess(String,String)`,
  `Java_..._GNatives_onGcServerAuthCode(String)`,
  `Java_..._GLES30_gc_isAuthenticated` (Google Play Games identity).

- `Java_net_blocklegends_natives_GNatives_onTokenReceived` decompiled (boundary confirmed):
  1. `initGameEnv()` → obtains the GraalVM isolate `gameEnv` + `nativesClass` (qword_DB2CF30).
  2. `GetStaticMethodID(nativesClass, "onTokenReceived", "(Ljava/lang/String;Ljava/lang/String;)V")`
     (JNIEnv vtbl+904), cached in `unk_86728F0`.
  3. The two C-string args → `NewStringUTF` (vtbl+1336) → jstrings `str1`,`str2`.
  4. **`CallStaticVoidMethod(nativesClass, mid, str1, str2)`** (vtbl+1128) — pushes the token (str1)
     and the secondary credential (str2, the Gc server-auth/identity string) **into the AOT-Java
     engine**, then `DeleteLocalRef` (vtbl+184) on both.

After step 4 the token lives inside AOT Java. The engine constructs the LOGIN-state packet that
carries it and emits it via `GnsNative.send` — **all inside unsymbolized AOT code.** This call
(`CallStaticVoidMethod onTokenReceived`) is the precise static boundary; the token's on-wire
encoding is past it and only observable dynamically.

## 5. Honest recoverability verdict

| Question                                                     | Static answer |
|--------------------------------------------------------------|---------------|
| Transport carries raw ByteBuffer, no native frame?           | **YES — proven** (memcpy, no length/id added natively) |
| Outer VarInt length prefix present?                          | Almost certainly **NO** (GNS gives message boundaries) — not byte-confirmed |
| Packet system = id-prefixed, state-gated, registry-dispatched?| **YES** (class model: `Packet`/`PacketManagerCustom`/`EnumConnectionState`) |
| Packet-id width (byte vs VarInt) and numeric opcode table?   | **NOT recoverable** — only in AOT code; `PacketManagerCustom` table never materializes as data |
| Per-packet field order / types?                              | **NOT recoverable** statically |
| Where the auth token enters Java?                            | **Recovered** — `GNatives.onTokenReceived` → `CallStaticVoidMethod` |
| Where the token sits on the wire?                            | **NOT recoverable** statically (built in AOT after the boundary) |

Root cause: GraalVM `native-image` compiled the Java engine to anonymous AOT machine code,
preserving class/method **names** (for reflection + stack traces) but **not** emitting per-method
symbols, per-packet diagnostic strings, or direct call edges to the JNI stubs. Static RE can name
the protocol's classes and prove the transport contract, but cannot enumerate the opcode bytes.

## 6. Concrete next step — dynamic capture at the send/receive boundary

Because the native side adds **zero** framing, a hook on the four bridges yields the *exact* app
packet bytes with no post-processing:

1. Frida-attach the running game; hook these absolute exports in `libblocklegends.so`
   (rebase to the runtime load address of the module):
   - `Java_gnatives_GnsNative_send`        @ 0x811fd3c — args `(env, cls, connPtr, ByteBuffer, len, lane, sendFlags)`
   - `Java_gnatives_GnsNative_sendBatch`    @ 0x8120a70
   - `Java_gnatives_GnsNative_receive`      @ 0x811f534 (and `receiveBatch` @ table 0x8222 cluster)
   - `Java_gnatives_GnsNative_connect`      @ 0x811a88c (logs the `"ip:port"` target)
2. In each hook, call `GetDirectBufferAddress(env, ByteBuffer)` (JNIEnv vtbl+1840) and `hexdump(addr,len)`.
   For `send`/`sendBatch` that buffer **is** the outbound app packet; for `receive` it is the inbound one.
3. Correlate the first bytes across many captures to read off: id width (byte vs VarInt), the
   per-state opcode set, and the handshake→login→play ordering. Cross-check the LOGIN packet against
   the token string passed to `onTokenReceived` (hook that too, log `str1/str2`) to locate the token
   field and its surrounding length/encoding.
4. Optional offline confirmation: the same hook on `SteamNetworkingMessage` alloc inside
   `libGameNetworkingSockets.so` (session `96649281`) verifies body == ByteBuffer.

This is the only path to byte-exact framing; the AOT engine cannot be coerced statically.

---

### Key addresses (this DB)
- send 0x811fd3c · sendBatch 0x8120a70 · receive 0x811f534 · connect 0x811a88c
- JNINativeMethod table cluster: 0x8222088 (connect) · 0x8222130 (receive) · 0x8222160 (send) · 0x8222178 (sendBatch)
- onTokenReceived static-method id cache: unk_86728F0 ; nativesClass: qword_DB2CF30 ; isolate gameEnv emutls `_emutls_v.gameEnv`
- Server: 81.8.66.123:26002 (m2.blocklegends.net), transport stock Valve GNS (UDP/SNP, Curve25519+AES-256-GCM)
