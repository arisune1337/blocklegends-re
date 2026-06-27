# 08f — Static Opcode Table Attempt (round 4)

## Verdict (honest, up front)

**`ids-not-static` — the packet opcode is NOT a build-time constant readable from the image heap. This static avenue is now CLOSED.**

The single un-chased static avenue from 08e §5 #4 was: *does each descriptor's `getId()` return a stored scalar field (`LDR Wn,[descriptor,#off]`) set at build time in the heap object, so the full id↔packet table can be read from `.svm_heap` without running the app?*

The answer is **no**, with high confidence and multiple independent proofs (below). The opcode is compile-time *deterministic* (this is a closed-world AOT image) but it is realized through a **runtime-built registry + per-class virtual dispatch**, not exposed as any readable heap field or flat array. There is no `idFieldOffset`, and there is no static `id->packet` table anywhere in the image. **Zero numeric ids were recovered; none could be, by construction.**

| Deliverable | Status |
|---|---|
| Numeric id↔packet table from heap | **NOT recoverable.** No stored id field, no id-indexed array. |
| `getId()` returns a build-time-constant field? | **NO** — virtual dispatch on a runtime-resolved `PacketType`, range-checked, then stored into a freshly-allocated id-holder that does not exist in the static image. |
| Descriptor set bounded | **YES** — 126 table slots @ `0x85ED220`; ~120 distinct (tail duplicated). |
| Descriptor → serializer link | **YES** (static) — `write()=*(hub+0x100)`, `new()=*(+0x108)`, `read()=*(+0x110)`. |
| Opcode wire encoding | **KNOWN** — signed byte (−128..127) emitted as the leading `writeVarInt` of the packet body. |

---

## 1. What `getId()` actually does (and why the id is not static)

Registration happens at startup inside `sub_765CF10` (registry builder) which, per packet descriptor, allocates a fresh wrapper and calls `loc_76397F0(builder, registrySingleton qword_AF979E8, wrapperInstance)`. Disassembly-exact mechanism of `loc_76397F0` @ `0x76397f0`:

1. **Type guard:** `*(wrapper.hub + 0x12) == 0x84C` (metatype tag 2124) — confirms the wrapper's class IS the packet descriptor class.
2. **getId resolve:** `X0 = loc_40E0100(builder.@0x18, registry)` — the getId receiver is a **`PacketType` entry fetched from a runtime hashmap**, *not* the table descriptor singleton.
3. **getId call:** `LDR X1,[SP,#0x30]` (= entry.hub); `LDR X30,[X1,#0x2A8]`; `LDR X0,[SP,#0x38]` (= entry); `BLR X30` → `W0 = id`. Virtual dispatch via vtable slot **+0x2A8** on the *entry's* class.
4. **Signed-byte range check:** `ADD W1,W0,#0x80; CMP W1,#0x100; B.CC` (@ `0x76398c0`) → id ∈ [−128,127]. Out-of-range → `off_981C498`. The 256-entry Integer-box table `off_B2FE978` is indexed by `(id+0x80)` @ `0x7639984`, reconfirming the signed byte.
5. **Store + register:** `W0` is written into a freshly-allocated 2-field id-holder (`STR W0,[newobj,#8]`) and registered via virtual `hub+0x280`.

**Why this is not a readable heap field — five proofs:**

- **P1 — `+0x2A8` on the message hub is NOT code.** On descriptor #0 `off_98C2010`, `*(0x98C2010+0x2A8) = 0x96F3A10`, which lies in `.svm_heap` and disassembles to no instructions. A real per-descriptor `getId` at that slot would be a `.text` pointer. So `+0x2A8` is incidental metadata on the message class, not a getId method and not an id.
- **P2 — `+0x2A8` is non-uniform across descriptors.** Sampling the slot: #3 (`0xBA111D0`) = `0x0` (NULL); #0 and #8 *share* `0x96F3A10`; others point to assorted heap hubs (`0x98C2C20`, `0x98C4F20`, `0xAD03570`, …). A genuine opcode/getId would be present and distinct on every descriptor.
- **P3 — the getId receiver is a different class.** It is the runtime-resolved `PacketType` entry from `loc_40E0100`, whose class hub *does* carry a real `.text` method at `+0x2A8` (e.g. the map-miss placeholder class `off_96ED248` has `*(+0x2A8)=0x392AE10`). The id lives on the `PacketType`, materialized at runtime, not on the message descriptor.
- **P4 — the registered wrapper has zeroed fields.** In `sub_765CF10` the registration block does `STR descriptor,[X1]` (header) then `STP XZR,XZR,[X1,#8]`/`[X1,#0x18]` — the payload is zeroed and **no id immediate is ever passed** to `loc_76397F0`. The id-holder that finally stores the id is allocated *during* registration and does not exist in the static image.
- **P5 — the leading `writeVarInt` operand is a field load, never an immediate.** Verified across ~18 packet `write()` bodies: the opcode varint is `LDR Wn,[obj,#off]` (a runtime object field populated from the registry), not `MOV Wn,#imm`. (08e §2a.)

`loc_76397F0` and `loc_40E0100` are hand-written SVM trampolines that fail Hex-Rays decompilation, consistent with closed-world runtime registry plumbing.

**Classification:** `returnsStoredField = false`; `idFieldOffset = none`; `perDescriptorGetId` could not even be confirmed on the message hubs because they expose no getId at `+0x2A8` — the getId belongs to the `PacketType` class reached only through the runtime map lookup.

---

## 2. What IS statically known (the descriptor set + serializer link)

The full descriptor set is **bounded and enumerable** even though the ids are not.

- **Descriptor table:** contiguous pointer array @ `0x85ED220`, **126 qwords** → 126 `DynamicHub`s (each `+0x00 == 0x097D3C00` meta-hub marker; metatype `+0x12 == 0x84C`). The count is corroborated by `xrefs_to loc_76397F0` = 126 register call-sites (`sub_765CF10` ~111, `sub_76406F0` 6, `sub_764D0F0` 1, `sub_764E780` 6).
- **Distinct count ~120:** the table tail is duplicated — #121/#125 = `0xAF89C48` (= #113), #122 = `0xBA0FDD8` (= #112), #123 = `0x9D7C790` (= #114), #124 = `0xBA1D9C8` (= #108). Treat #121..#125 as padding/non-authoritative.
- **Two descriptor kinds:**
  - **Concrete message classes** (`0x98xxxxx` / `0x9xxxxxx` hubs): `+0x100` holds a real `.text` `write()`. Example #0 `0x98C2010` = `net.blocklegends.mi5` (obfuscated): `write=0x562B020`, `new=0x562B0F0`, `read=0x562B180`. `write()` begins `STR X5,[X0,#0x10]` and serializes instance fields — **no leading constant opcode byte** (the framing layer prepends `getId()`).
  - **Abstract / supertype descriptors** (`0xB9Fxxxxx` / `0xBAxxxxx` hubs, e.g. #3,#19,#22,#25,#29,#34,…,#124): `+0x100` holds a *heap reference* (or NULL), not a `.text` serializer — no concrete inline writer, no concrete id.
- **Descriptor → serializer is a direct static link:** for any concrete descriptor hub `H`, `write()=*(H+0x100)`, `new()=*(H+0x108)`, `read()=*(H+0x110)`. This is the bridge that lets the dynamic capture join a captured id to a field layout.

The 126 hub addresses were read verbatim from the table; they are trivially re-readable as consecutive 8-byte pointers from `0x85ED220`.

---

## 3. Opcode encoding (settled)

- The opcode is a **signed byte** (−128..127), produced by `PacketType.getId()` at registration, range-checked `(id+0x80) < 0x100`.
- On the wire it is emitted as the **leading `writeVarInt`** (`writeVarInt @0x680ea30`) of the top-level packet `write()` body, sourced from a runtime object field (`LDR Wn,[obj,#off]`).
- Framing is **zero outer framing** (08d §5): one GNS message == one packet body, so `byte[0..] = opcode-varint + payload`. A signed byte VarInt-encodes to 1 byte for 0..127 and 2 bytes (sign-extended) for −128..−1.

---

## 4. Bottom line for the custom client

Combining 08d (serializer primitives + transport), 08e (field-layout catalog + opcode mechanism), and this round (id-not-static, descriptor set bounded):

**KNOWN statically (sufficient to BUILD the codec, not to NUMBER it):**
- Complete wire-encoding toolkit: `writeVarInt 0x680ea30`, `writeVarLong 0x680eb40`, `writeString 0x680df60`, `writeUUID 0x680a900`, `writeBlockPos 0x680abb0`, `writeIdentifier 0x680e510`, `writeByteArray 0x680bae0`, enum-id `0x680c0a0`, registry-id `0x5ca34b0`; ByteBuf vtable map. (08d §3.)
- Per-packet field layouts for ~20 writer bodies, including the login candidate `sub_66477D0` = `(varint, string, string, varint, byte)`. (08e §4.)
- The full transport / JNI chain and framing: `GnsNative.send @0x811fd3c`, `receive @0x811f534`, zero framing, reliable flags=9. (08d §5.)
- The packet class set: 126 descriptor hubs (~120 distinct) @ `0x85ED220`, each with `write/new/read` at `+0x100/+0x108/+0x110`. (this round.)
- The opcode **shape**: signed byte, leading VarInt.

**STILL MISSING (requires exactly one dynamic capture):**
- The numeric **id ↔ packet** pairing. It is runtime-assigned; nothing in the static image numbers the packets. This is now proven un-recoverable statically — **do not retry this avenue.**
- The confirmed **login identity** (which writer body is the auth/token packet) — token→writer path is reflection/async-indirected (08e §3).

**Sharpest single hook (one startup run yields the entire table):**

> **Hook `loc_76397F0 @0x76397f0`.** It runs 126 times at startup, once per packet, *before any traffic*. At each call read: (a) the wrapper's class hub from `X2` (= the descriptor; map to its `write fn = *(hub+0x100)` and thus to the 08e field-layout catalog), and (b) the signed-byte id returned by the `getId()` virtual at `X0.hub+0x2A8` (capture `W0` right after the `BLR` @ `0x76398b0`, before the `(id+0x80)` range check). Logging `(descriptorHub, write fn @+0x100, signed-byte id)` for all 126 calls reconstructs the **complete id↔packet↔serializer table** in a single launch, independent of which packets ever get sent. Afterward, dump the registry singleton `qword_AF979E8` to cross-check.

Fallback hooks (08d §9 / 08e §5): hook `writeVarInt @0x680ea30` and log `(return-address → writerFn, first-varint = live id)` per send; hook `writeString @0x680df60` at login to pin the login writer and its opcode. Priority for one run: **registry dump (`loc_76397F0`) → per-send id (`writeVarInt`) → login confirm (`writeString`)**.

---
*Round-4: chased and closed the last static avenue (build-time-constant id field). Result: opcodes are deterministic but runtime-registry-assigned via virtual `getId()` (descriptor entry slot +0x2A8), not a readable heap field; no static id→packet array exists. idalib session `bl` = libblocklegends.so. Extends `08e_opcode_writesite_login.md` / `08d_opcode_deepdive.md`.*
