# 08e — Opcode Write-Site & Login Packet (static, round 3)

## Verdict (honest, up front)

**PARTIAL — mechanism recovered, no numeric table, login unproven.**

| Deliverable | Status |
|---|---|
| Concrete opcode table (id → packet/class) | **NOT recovered.** Zero static immediates exist. Every packet id is a runtime object field; values are runtime-registry data, statically unavailable. |
| Login packet field layout + opcode | **NOT confirmed.** `sub_66477D0` is a real 5-field packet write() body and the best structural login candidate, but its login identity is purely inferential (the token→writer dataflow is reflection/async-indirected and was never resolved). Its previously-claimed layout was also incomplete. |
| Opcode **SOURCE mechanism** (immediate vs field vs virtual getId) | **RECOVERED and verified.** This is the round's real result: the id is a per-packet **runtime field** fed to the leading `writeVarInt`, assigned at registration via a **virtual `getId()` at descriptor vtable slot +0x2A8**, range-checked to a **signed byte**. |

Net: we cracked *how and where* the opcode is produced and *what shape* the wire takes, but the numeric id↔packet pairs are not in the image. Dynamic capture remains required for concrete ids.

---

## 1. Verified opcodes (id → packet)

**None.** No id→class pair survived verification, because no static opcode immediate exists anywhere in the 96 writer bodies or the registry builder.

| id | packet/class | evidence addr | lead | note |
|---|---|---|---|---|
| *(none)* | — | — | — | All ids are runtime-assigned object fields / `getId()` returns; not static. |

The closest thing to an "opcode entry" is a **mechanism**, not a value:

| mechanism | where | evidence addr | confidence |
|---|---|---|---|
| opcode = per-packet **signed-byte** id from PacketType registry, fetched via virtual `getId()` at descriptor slot **+0x2A8** during registration, range-checked `(id+0x80) < 0x100` | `loc_76397F0` | `0x76397f0` | verified (registration side) |
| id↔packet table built at runtime in `sub_765CF10` by sequential `loc_76397F0(registry qword_AF979E8, newInstance)` over the packet-hub list | `sub_765CF10` | `0x765cf10` | verified (it registers; **order ≠ id**) |

> Correction carried from verification: registration **order does NOT equal the opcode** — the id is whatever `getId()` (+0x2A8) returns per descriptor, not a sequential counter. `sub_765CF10`'s call order only enumerates *which* hubs are packets.

---

## 2. Opcode-write mechanism (the headline finding)

This is the most important result for the dynamic capture, even without numeric ids — it tells the hook exactly what to read.

### 2a. How the id reaches the wire
- Each packet's `write()` body is a **virtual method at DynamicHub slot +0x100** (read = +0x110, factory/new = +0x108). Cross-verified on two real hubs: `0x98B7340→sub_648E610`, `0x98C2010→sub_5628D20` (hub header marker `0x097D3C00`).
- **The leading `writeVarInt` in each body emits the packet id**, and its operand is **always a field load** `LDR Wn,[obj,#off]` — **never** a `MOV`-immediate. Verified across ~18 bodies spanning the whole `.text` range. So:
  - **The wire opcode is a runtime field of the packet object**, written first by the packet's own `write()` via `writeVarInt @0x680ea30`.
  - For top-level packets the leading varint = the **packet-id field**; for composed list/array sub-codecs the leading varint = an **element count** (do not confuse the two — `sub_5628D20` leads with `writeBlockPos`, `sub_76D86A0` leads with `writeString`, so "first field = id" is not universal).

### 2b. Where the id value comes from (registration)
`sub_765CF10` (registry builder) allocates one instance per packet hub and calls `loc_76397F0(builder, registry singleton qword_AF979E8, instance)`. Inside `loc_76397F0` (disasm-exact):
1. type guard `hub+0x12 == 0x84C`;
2. `LDR X30,[X1,#0x2A8]; BLR` → **virtual getId()** on the **PacketType descriptor** (not the packet);
3. `ADD W1,W0,#0x80; CMP W1,#0x100; B.CC` → **signed-byte range check**;
4. out-of-range → `off_981C498`;
5. register via virtual `hub+0x280`.

So the opcode is a **signed byte** (-128..127) produced by a descriptor-side virtual, stored at runtime, then loaded into the packet object's id field and emitted as the leading varint.

### 2c. What does NOT write the opcode (ruled out this round)
- `sub_64D8060` is the **GNS reliable transport flush** — it drains already-serialized `ByteBuffer`s from a queue and ships them via `sendReliable sub_64DCFF0` (framer `loc_6678240` + JNI `sub_3C84240`, flags=9/lane=0). No id is written here.
- Vtable owners `0x994db80→sub_543EFC0` and `0x9951a68→sub_68A9470` (Lead B) are **outbound send forwarders** of two obfuscated channel/handler classes (Spanish "Alas de Cristal (Rojo)" and Devanagari "Block Legends", both extend `java.lang.Object`). vtable[6] forwards the already-serialized buffer to `sub_64DD1F0→sub_3C84240` with **zero** serializer calls; vtable[7] is a writability/dirty-flag setter, **not** getId. This dispatch sits one layer **below** packet encoding. Dead end for opcodes.

---

## 3. Login packet layout (candidate, NOT confirmed)

Token entry boundary is solid: `Java_net_blocklegends_natives_GNatives_onTokenReceived @0x80c4bf0` → `CallStaticVoidMethod(env, nativesClass=qword_DB2CF30, methodID@unk_86728F0, token, str2)` → AOT `GNatives.onTokenReceived(Ljava/lang/String;Ljava/lang/String;)V`. **But** that AOT method is reflection/JNI-registered (reflect-config JSON `@0x92c0df0`; name byte[] `@0xac3d8b8` with no adjacent code pointer) and async/lambda-indirected (`lambda$onTokenReceived$7/$8/$13/$14/$15`). **No static token→writer edge exists.**

Working from the serializer side, intersecting the 96 `writeVarInt` callers with the 50 `writeString` callers gives exactly 5 two-arg-string packet bodies; the strongest login-shaped one is **`sub_66477D0`**:

**`sub_66477D0` verified wire order (5 fields, corrected):**
1. `writeVarInt(int @+0x20)` — runtime id/version field (op `0x66477ec`/`0x66477f8`)
2. `writeString(String @+0x08, default empty `off_84EB0C0`)` — credential A (token candidate)
3. `writeString(String @+0x10, default empty)` — credential B (str2 candidate)
4. `writeVarInt(int @+0x24)` — trailing int (op `0x6647840`)
5. **`writeByte(bool @+0x28)`** via ByteBuf vtable+0x3D8 (op `0x664786c`/`0x6647874`) — **this field was previously mislabeled as a non-serialized GC barrier; it IS on the wire**

> Honest caveats: (a) the **login identity is unproven** — nothing statically ties `sub_66477D0` to `onTokenReceived`; (b) the opcode here is **not an immediate**, it is `LDR W2,[X0,#0x20]` (object field +0x20); (c) the two String fields default to the empty-string constant when null, which is the only reason this is the *best* login candidate (optional-credential signature). A weaker alternative is `sub_76D86A0` (String-first, enum in the middle). Report this as "an unidentified 5-field packet write() body `(varint, string, string, varint, byte)` that is the leading login candidate", not as the confirmed login packet.

---

## 4. Writer field-layout catalog (Lead D), with login/handshake candidates

ByteBuf wrapper vtable (verified): `+0x3D0` writeBoolean, `+0x3D8` writeByte, `+0x3F0` writeBytes, `+0x410` writeDouble, `+0x418` writeFloat, `+0x420` writeInt, `+0x428` writeLong, `+0x430` writeShort/enum. Serializer fns: writeVarInt `0x680ea30`, writeVarLong `0x680eb40`, writeString `0x680df60`, writeUUID `0x680a900`, writeBlockPos `0x680abb0`, writeIdentifier `0x680e510`, writeByteArray `0x680bae0`, enum-id `0x680c0a0`, registry-id `0x5ca34b0`.

| writerFn | fields (wire order) | candidate identity |
|---|---|---|
| **`sub_66477D0`** | varInt(+0x20); **string(+0x08)**; **string(+0x10)**; varInt(+0x24); byte(+0x28); [trailing byteArray per one transcript] | **LOGIN/HANDSHAKE (primary)** — 2 default-empty strings = optional creds |
| **`sub_76D86A0`** | string(+0x08); varInt((+0x18)+0x10 enum); string(+0x10); opt varInt(+0x20) | **login/identifier-keyed (secondary)** |
| `sub_6A8FEF0` | varInt(+0x28); string; short; varInt(+0x28); opt string; opt string(translated via `sub_4BA7110`) | chat/system-message |
| `sub_5F52B70` | varInt(header); branchA varInt+**writeInt**(+0x1C); branchB varInt+writeInt+string(+0x10) | enum-discriminated (cmd-suggest/resource-pack); *finder's "writeShort" was writeInt* |
| `sub_5136870` | 2 leading varInts; switch(action 0..4){0: UUID,string(name),varInt,loop[string,string],varInt,varInt,identifier; 1/2: UUID,varInt; 3/4: UUID} | PlayerInfoUpdate-style (uses writeUUID) |
| `sub_5628D20` | blockPos(+0x08); varInt(registryId via `sub_5CA34B0` from +0x10) | block-update (BlockPos+id) |
| `sub_55C9530` | varInt(header); switch(type 1..5){ double×(+0x18/+0x20/+0x28); **writeVarLong**(+0x30); varInt(+0x38/+0x3C/+0x40) } | entity move/position (**only** writeVarLong caller) |
| `sub_648E610` | varInt entityId(+0x18); varInt enum((+0x08)+0x10); float×3 (Vec3 @+0x10); float(reach/dist 4.0 default) | interact/use-entity; read pair `sub_648EB10` mirrors |
| `sub_56D6250` | writeInt((+0x08)+0x20); writeBoolean; float×7 (+0x18..+0x30); writeInt; varInt(count @(+0x10)+0x10); loop varInt(int[]) | entity spawn/teleport |
| `sub_6160390` | varInt(header); opt identifier((res)+0x10); opt int×3 (+0x18/+0x20) | registry/event packet |
| `sub_6BEEFB0` | varInt(header); byte; writeByteArray | custom-payload / plugin-message |
| `sub_59762C0` | int×2 ((+0x08)+0x08,+0x0C); varInt(count @(+0x10)+0xC); loop[ short/enc; varInt(id via `sub_5CA34B0`) ] | section/palette / biomes |
| `sub_488ED20` | varInt(component-type ordinal +0x08); recursive text/NBT builder | Text/Component codec (NOT a packet) |
| `loc_67378C0` (fn:null) | varInt(list.len); per-entry{ int(+0x18); enum-id(+0x08); byte(+0x24); varInt(sublen); sublist[id]; varInt(+0x1C); varInt(+0x20) } | composed list sub-codec (count, not id) |
| `loc_718DE50` (fn:null) | varInt(list.len); varInt(first); loop varInt(int[]) | list<VarInt> sub-codec |
| `loc_4A50310` (fn:null) | varInt(+0x10); varInt((+0x08)+0x10); varInt(+0x14) | 3×VarInt struct codec |
| `loc_646F500` (fn:null) | varInt(+0x08) | single-VarInt codec |
| `sub_680BAE0` | varInt(len); writeBytes(+0x3F0) | **toolkit** writeByteArray (not a packet) |

Verified counts/structure: `writeVarInt @0x680ea30` has **exactly 96** call sites → ~50 distinct bodies; `sub_5136870` holds the most (7). `writeVarLong` is exclusive to `sub_55C9530`; **writeUUID and writeBlockPos are NOT exclusive** (additional callers exist in the unmapped fn:null cluster — `writeUUID`: `0x6bd8984/0x6fd3c68/0x6fd3d50/0x763b7ac`; `writeBlockPos`: `sub_48C0910`, `sub_6C693C0`, +~9 more), so the player/entity/block packet set is larger than the catalog above. All packet **names** are candidate guesses, not established identities.

---

## 5. Residual gap & whether dynamic capture is still required

**Yes — dynamic capture is still required** for the two things that matter: the numeric id↔packet table and the confirmed login identity. Statically we have hit the ceiling the prior rulings predicted: the id↔class registry is runtime-built (no static immediates), the descriptor `getId()` virtuals resolve only at runtime, and the token→writer path is reflection/async-indirected.

What round 3 *adds* over 08d §9 to make the hook plan sharper:

### Refined §9 hook plan
1. **Hook `writeVarInt @0x680ea30`** (entry). Log `(return-address, first-arg value)` per call. The **return address identifies the writerFn** (map to the catalog above); the **first varint of each top-level write() body = the live packet id**. This single hook yields the id↔writerFn table directly. (Filter out list/count leads by ignoring return addresses inside the known sub-codecs `loc_67378C0/loc_718DE50/loc_4A50310/loc_646F500/sub_680bae0`.)
2. **Hook `writeString @0x680df60`** at login. Capture the two consecutive UTF8 args equal to the `onTokenReceived` token/str2; the enclosing write() body (confirm `sub_66477D0` vs `sub_76D86A0`) is the **login writer**. Combined with hook #1, this also pins the login opcode value.
3. **NEW — hook `loc_76397F0 @0x76397f0`** (registry registration). At each call, read the descriptor and invoke/observe the `getId()` virtual at **descriptor+0x2A8**; log `(packet hub @+0x100 write fn, returned signed-byte id)`. This builds the **entire id↔packet map at startup**, before any traffic — the cleanest capture, independent of which packets actually get sent. Also dump `qword_AF979E8` (registry singleton) after `sub_765CF10` returns.
4. **NEW — to fingerprint statically without runtime**, walk `sub_765CF10`'s descriptor instances (hubs `off_98C2010/off_98BDE70/off_98BDD58/off_98BBEB8/off_98C1CC8`), find each descriptor's **constructor** and read the immediate stored in the id field that `getId()`(+0x2A8) returns — if the id is a compile-time constant in the ctor (not runtime-derived), the table becomes statically recoverable after all. This is the one un-chased static avenue left.
5. **Optional** — hook `sub_64D8060` (reliable flush) / `sendReliable sub_64DCFF0` to dump finished frames and cross-check the captured `writerFn`↔id mapping against on-wire bytes (zero outer framing: first varint of body = id).

Priority order for a single run: **#3 (startup registry dump) → #1 (per-send id) → #2 (login confirm)**. #3 alone, if the getId virtuals are reachable, delivers the full opcode table.

---
*Round-3 deep dive: 4 leads (opcode write-site, vtable owners, login writer, 96-caller catalog) + adversarial verification (9 agents, 188 IDA queries). idalib session `bl` = libblocklegends.so. Extends `08d_opcode_deepdive.md`.*
