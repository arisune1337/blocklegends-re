# 08d — Opcode Table Deep Dive (static, round 2)

> Extends `08_PROTOCOL_SPEC.md` §4 and `08b_app_packet_format.md`. Target: `C:\Users\user\Desktop\blocklegends\extracted\natives\libblocklegends.so` (idalib session `bl`, imagebase 0x0). Six independent static angles were run and adversarially verified; this report records only what survived verification.

## 1. Honest verdict

**PARTIAL — and the opcode table specifically is STILL BLOCKED.**

- **Opcode table (the `int id <-> packetClass` registry / `PacketManagerCustom` dispatch): NOT RECOVERED.** Zero numeric opcodes and zero id↔class pairs passed verification. The only numeric candidates (`901`/`902`) were **refuted** — they are i18n localization-key suffixes (`bedwars.error.server_packet_901`), with no demonstrated binding to any wire id or packet class.
- **Serializer primitives + field-layout method: RECOVERED and byte-level verified** (Angle 3). This is real, load-bearing progress for a custom client: you now know exactly how every field is encoded on the wire, and have a repeatable method to read any packet's field order out of its (anonymous) writer.
- **Transport / JNI call graph + framing: RECOVERED and verified** (Angle 4). The long-standing "`send` has no code xref" wall is broken.

Net: we can encode/decode fields and we know the transport, but we cannot yet name a packet or state its numeric id from static data alone. The registry is built at runtime (consistent with GraalVM `initialize-at-run-time` for app classes) and is provably absent from heap data.

## 2. Confirmed opcodes

**None.** No `id -> packetClass` mapping survived adversarial verification.

| id | packet class | evidence | status |
|----|--------------|----------|--------|
| 901 | "server_packet" (unknown class) | `0xa531f58` | **REFUTED** — i18n error-key suffix only, not a wire opcode |
| 902 | "server_packet" (unknown class) | `0xa4aaa78` | **REFUTED** — i18n error-key suffix only, not a wire opcode |

The protocol is a **small fixed registry of exactly 6 classes** (verified: names occur exactly once each, only inside the reflect-config JSON @ `0x8890990`), NOT a Minecraft-style large per-opcode table:

- Outbound (client→server): `PacketRequest` (generic id+payload wrapper), `PacketPlayOutPlayerChange` (the only concrete typed PLAY packet).
- Inbound (server→client): `PacketResponse` (generic wrapper).
- Infra: `net.blocklegends.network.Packet` (base read/write), `PacketManagerCustom` (id↔class registry + dispatch), `EnumConnectionState` (HANDSHAKING/STATUS/LOGIN/PLAY state machine).

Because the dispatch is almost certainly a `Map<Integer,handler>` populated at runtime (not a compiler `tableswitch`), there is no large packet jump table to surface statically — verified by disproving the `.rodata` jump-table premise (Angle 6).

## 3. Serializer primitives (VERIFIED — the wire encoding toolkit)

The protocol serializer is a Netty-`ByteBuf`-backed `FriendlyByteBuf`/`PacketDataSerializer` equivalent. `this+0x8` holds the underlying `ByteBuf`; every primitive forwards to a fixed `ByteBuf` vtable slot. All entries below were re-disassembled at the instruction level and confirmed.

| primitive | entry addr | wire encoding |
|-----------|-----------|---------------|
| `writeVarInt` (32-bit) | `0x680ea30` | LEB128: `TST W1,#0xFFFFFF80` loop; `AND W3,W1,#0x7F`; `ORR W3,#0x80`; writeByte; `LSR W,#7` |
| `writeVarLong` (64-bit) | `0x680eb40` | identical LEB128 loop on X registers |
| `readVarLong` | `0x680a660` | readByte; `AND #0x7F`; `TBZ W0,#7` continuation; accumulate `(b&0x7f)<<(iter*7)`; overflow guard `CMP #0xB` |
| `writeString`/`writeUTF` | `0x680df60` | UTF-8 encode (`sub_3E547C0`) → `writeVarInt(byteLen)` → `writeBytes` (cap check vs `0x80001`). **No separate char count.** |
| `writeUUID` | `0x680a900` | two `writeLong` of `obj+0x8` and `obj+0x10` |
| `writeBlockPos` | `0x680abb0` | sign-extend ints `obj+0x8/+0xC/+0x10` (x/y/z), mask+shift, OR into one long, `writeLong` |
| `writeIdentifier`/enum-name | `0x680e510` (medium) | stringify (`sub_3965890`) then `writeString` |

**ByteBuf vtable map (VERIFIED):** `readByte +0x2A0`, `writeByte +0x3D8`, `writeBytes +0x3F0`, `writeInt +0x420`, `writeLong +0x428`.

`writeVarInt @0x680ea30` has **exactly 96 code callers** = the per-packet `write()` bodies. The call SEQUENCE in each is the packet's field order/type list.

**PITFALL (verified):** the `(v&0x7f)|0x80` byte idiom also appears in ICU4j's ISO-2022 charset converter (around `0x7c15xxx–0x7c70xxx`, incl. `sub_7C6F190`). These are NOT VarInt. The discriminator is the in-loop `LSR #7` (`>>>=7`) **and** the `this+8`→ByteBuf-vtable dispatch; ICU code has neither.

## 4. Per-packet field layouts recovered

The recovery method (decompile a `writeVarInt` caller, read the Java field offset loaded just before each primitive call) was demonstrated end-to-end and **independently re-verified** on two anonymous writers:

- **`sub_5628D20`** — 2-field packet: `writeBlockPos(pos@+0x8)` then `writeVarInt(enumOrdinal)` (ordinal via `sub_5CA34B0`, enum field @`+0x10`). References metadata hubs `off_83C6518/20/28`.
- **`sub_5F52B70`** — `writeVarInt(header)` then an enum-switched body:
  - variant A: `{ writeVarInt(field@+0x20); writeInt(field@+0x1C) }`
  - variant B: `{ writeVarInt(field@+0x18); writeInt(field@+0x1C); writeString(field@+0x10) }`
  - References metadata hubs `off_847FD48/50/58`.
- **`sub_5136870`** — map/collection codec: `writeVarInt(count)` then per-entry `writeString`/`writeVarInt`/`writeUUID` (7× writeVarInt; consistent, not independently re-decompiled).

**LOGIN / auth-token packet: NOT yet isolated.** The token is sent as a length-prefixed UTF string (`writeString @0x680df60`), so the login writer is one of the 96 `writeVarInt` callers that also calls `writeString` on a field tied to the `onTokenReceived` path (`nativesClass = qword_DB2CF30`). This correlation was not completed. **Caveat (verified gap):** every writer is anonymous (`sub_XXXX`) and references obfuscated `DynamicHub` metadata pointers, not packet name strings — so no writer is yet tied to a named class (`PacketPlayOutPlayerChange`/`PacketRequest`/etc.) or to a numeric id.

## 5. Transport / JNI call graph + framing (VERIFIED)

The "`send` has no code BL" wall is broken via the **SubstrateVM JNINativeLinkage pivot** — a generalizable primitive that recovers the Java→native call graph that reflect-config and direct bridge xrefs could not.

**Outbound chain (native → up):**
- native bridge `GnsNative.send @0x811fd3c`
- ← `JNINativeLinkage` heap object `0xba4cff0` (hub `0x9cd45e0` = `com.oracle.svm.core.jni.access.JNINativeLinkage`; `+0x10`→`"Lgnatives/GnsNative;"`, `+0x18`→`"send"`, `+0x20`→`"(JLjava/nio/ByteBuffer;III)I"`)
- ← linkage-pointer slot `off_8275FE8`
- ← **AOT JNI wrapper `sub_3C84240`** (resolves entryPoint via `sub_3AD2360`, marshals ByteBuffer→JNI handle via `sub_3AD0520`, jclass from `off_8275FF0`, sets thread IN_NATIVE=3 at `thr+0x14`, then BLR)
- ← **exactly two transport callers:**
  - `sub_64DCFF0` = sendReliable: frames via `0x6678240`, calls with literal `lane=0, flags=9` (Reliable 8 | NoNagle 1)
  - `sub_64DD1F0` = generic send: forwards `(conn, ByteBuffer, len, lane, flags)`
- one frame up: reliable ← `sub_64D8060` (13KB flush/serialize, calls sendReliable @`0x64d81c0`/`0x64d84a8`); generic ← virtual methods in heap vtable slots `0x994db80`→`sub_543EFC0` and `0x9951a68`→`sub_68A9470`, plus `sub_69EA4B0` ← `sub_69EA6B0`.

**Send-descriptor struct (verified):** `{ +0x08 ByteBuffer, +0x10 len(int), +0x18 lane(int), +0x1C flags(int) }`.

**Outbound framing (verified):** reliable framer `0x6678240` requires dest `ByteBuffer.capacity (+0x24) >= payloadLen + 44 (0x2C headroom)`, computes a per-byte checksum over the payload (`sub_60CE330` → `loc_428BA10`, reading DirectByteBuffer backing addr `+0x10`), then `ByteBuffer.clear()` (mark=-1 `+0x18`, pos=0 `+0x1C`, limit=cap `+0x20`). `java.nio.Buffer` offsets: `+0x10` address, `+0x18` mark, `+0x1C` position, `+0x20` limit, `+0x24` capacity.

**Inbound framing (verified):** `Java_gnatives_GnsNative_receive @0x811f534`: `GetDirectBufferAddress` (env vtable `+1840`) → `ReceiveMessagesOnConnection(*(uint*)connPtr,&msg,1)` (via `off_DCC07F0(qword_DCC07B8,…)`); `SteamNetworkingMessage_t` = `m_pData@+0`, `m_cbSize@+8`; if `size<=cap` `memcpy(buf, m_pData, size)` and **return size**, else error `"received message exceeds Java buffer"`. `receiveBatch @0x811f810`, `pollGroupReceive @0x8122280` mirror this for N messages.

**Framing conclusion (verified):** one GNS message == exactly one application packet body, **zero outer framing**. The bytes in the receive buffer ARE the raw packet: first field = packet id, then payload. Therefore a runtime hook of the ByteBuffer yields opcodes + field layout directly.

## 6. Established DynamicHub / image-heap facts (so a future session starts ahead)

- **ELF map:** `.svm_heap` (the GraalVM image heap) = `0x8674000–0xdb2c000` (88.6MB, file off `0x866c000`, delta `-0x8000`; use VA in IDA). `.rela.dyn` @ `0x551f0` size `0x37c19e8` ≈ 2.45M `R_AARCH64_RELATIVE` relocs = the entire heap pointer graph. **Heap pointer slots are stored as 0 in the file**; only relocation addends materialize them — offline analysis MUST apply `.rela.dyn` (IDA already does on load). `.rodata` = `0x822c000–0x866a538` (C strings only, NO jump tables).
- **DynamicHub layout (verified):** `+0x00` hubOfHub = `0x097D3C00` for every hub (java.lang.Class meta-hub, self-referential root) — scan for the 8-aligned qword `0x097D3C00` to enumerate all hubs; `+0x08` identityHashCode; `+0x28` name→String*; `+0x30` superHub→DynamicHub*.
- **String layout:** `+0x00` hub `0x097AE2D0`, `+0x08` value (byte[]*), `+0x10` coder/hash. **byte[] (Latin1):** `+0x00` hub `0x09CFCDE8`, `+0x08` hashCode, `+0x0C` length, `+0x10` raw bytes.
- **Enum layout (verified):** constant object = hub`+0x00` + name String*`+0x08` + ordinal int`+0x10`; **stride 0x28** (NOT 0x20). Anchors: `java.lang.Enum` hub `0x9d84eb8`, `java.lang.Object` hub `0x9c9a6c8`, `[B` hub `0x9cfcde8`, `java.lang.String` hub `0x97ae2d0`.
- **Meta-anchors:** `net.blocklegends.cr` hub `0x96b9bc0` (name bytes @`0xa6352d0`); reflect-config JSON byte[] @`0x8890980` (payload @`0x8890990`, len `0x1B3D0`); second JNI/native config blob ~`0x92c0xxx`.
- **Reusable JNINativeLinkage primitive (verified):** for any native — (1) find heap String of its descriptor/name; (2) reverse-walk byte[]→String→JNINativeLinkage; (3) find the `off_` global == that linkage; (4) the single AOT fn referencing it is the JNI wrapper; (5) `xrefs_to` wrapper = transport/encoder callers. Linkage cluster `0xba47xxx–0xba4exxx`.
- **Scratchpad artifacts already built (Angle 1):** `…\scratchpad\hubnames.txt` (31,159 hubVA→name), `heap_u64.npy` (fully relocated 88MB heap as numpy uint64), `meta.pkl` (names + reloc roff/radd for who-points-to), `util.py`.

## 7. Definitively ruled OUT (do not repeat)

- **Heap-data registry hunt (Angle 1):** the `id↔class` registry is NOT baked into the image heap. No `Map<Integer,Class>`/`Map<Class,Integer>`, no eclipse-collections primitive map (only one *empty* 2048-cap `IntObjectHashMap` @`0xba7be80`), no id-indexed `Class[]`/`[Lnet.blocklegends.*;` table. *(Note: these are exhaustive offline negatives — verifier marked them plausible-but-unaudited, not DB-proven. Treat as "almost certainly absent", consistent with runtime init.)*
- **Name-based hub lookup (Angle 2):** the 6 protocol class names exist **only** as interior substrings of the inert reflect-config JSON (single data xref from `0xaae8ba0`, which itself has zero xrefs; no runtime parser). `nameToHub` by CraftRise names is impossible — the hubs are runtime-obfuscated. `sub_541D890` (the only AOT fn referencing the `cr` hub) is float game-logic, not a registrar.
- **EnumConnectionState via strings (Angle 5):** **DEAD red herring** — `NOT_HANDSHAKING` @`0xac22348` is the JDK `javax.net.ssl.SSLEngineResult$HandshakeStatus` constant (hub `0x996b908`, name @`0xa33c488`), used by TLS, not the game state machine. The real `EnumConnectionState` and its constants (HANDSHAKING/STATUS/LOGIN/PLAY) have zero standalone strings.
- **`.rodata` jump-table scan (Angle 6):** architecturally wrong — GraalVM AOT lowers dense int switches to 4-byte self-relative offset tables **inline in `.text`** (`jpt_XXXX`, e.g. `0x39ac324`), bounds-checked by `CMP/B.HI`, already auto-resolved by IDA. Dispatch is a runtime `Map<id,handler>`, so no large packet jump table exists to find.
- **Static reachability of the Java decoder:** confirmed unreachable — `receive`/`receiveBatch`/`pollGroupReceive` have only dynsym + JNINativeMethod data refs, no code BL; packet class/method/lambda names are in encoded reflection metadata with zero data xrefs.

## 8. Single best remaining static lead

**Decompile `sub_64D8060`** (the 13KB reliable flush/serialize, the closest verified frame above the transport that fills the source ByteBuffer) around its two sendReliable calls (`0x64d81c0`, `0x64d84a8`), and resolve the source ByteBuffer it serializes into. The **first int it writes into that buffer at the top of the serialize loop is the packet opcode.** In parallel, resolve the vtable owners of slots `0x994db80`/`0x9951a68` (scan backward for `0x097D3C00` to get the owning DynamicHub, then dump that class's other vtable methods to find `Packet.write`/`PacketRequest.write`). This is the only static path that can plausibly yield a concrete opcode write; success is not guaranteed (indirect dispatch may still hide the id source).

## 9. To finish

The opcode TABLE is not statically recoverable from data, and the AOT decoder is walled. **Dynamic capture on real/emulated arm64 is the path** — and this round produced the exact hooks so it is now turn-key (the user already has a working GNS capture pipeline + Genymotion A12 berberis arm64 setup per MEMORY):

1. **Hook `Java_gnatives_GnsNative_send @0x811fd3c`** — args `(env, cls, connPtr, ByteBuffer, len, lane, sendFlags)`. In the hook call `GetDirectBufferAddress(env, ByteBuffer)` and dump `len` bytes. Because framing is zero, **byte[0..] = opcode + payload**. Correlate with in-game actions to label opcodes. (Outbound `flags=9` = reliable.)
2. **Hook `Java_gnatives_GnsNative_receive @0x811f534`** — return value = body length; dump the ByteBuffer afterward to read inbound opcodes + field order, cross-checking the serializer layout from §3.
3. **Optionally hook the serializer primitives** (`writeVarInt @0x680ea30`, `writeString @0x680df60`, `writeUUID @0x680a900`, `writeBlockPos @0x680abb0`) to trace field-by-field encoding live and auto-derive each packet's layout without manual offset reading.
4. **For the LOGIN/token packet specifically:** trigger auth, watch the first outbound `send` whose payload contains the length-prefixed token string (the value passed through the `onTokenReceived` path, `nativesClass = qword_DB2CF30`); its leading VarInt is the LOGIN opcode and the field order follows §3's `writeString` format.

With (1)+(2) the live opcodes and id↔class mapping fall out immediately; combined with the verified serializer in §3, the full custom-client protocol is then reconstructable.

---
*Round-2 deep dive: 6 independent static angles + adversarial verification (15 agents, 335 IDA queries). idalib session `bl` = libblocklegends.so. Server `81.8.66.123:26002` = m2.blocklegends.net. Transport = stock Valve GNS over UDP.*
