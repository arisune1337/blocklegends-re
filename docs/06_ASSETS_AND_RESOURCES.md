# Block Legends v218.0.0 — Asset & Resource Packaging (Report 06)

Scope: `game_assets.apk` (content-addressed asset store), `config.en.apk` (locale split),
and the jadx-decoded base resources (`resources.arsc`, `strings.xml`, manifests).
Target: net.blocklegends, version 218.0.0 (versionCode 218), CraftRise lineage, GraalVM AOT native engine.

Artifacts produced during this analysis:
- Working extraction: `C:/Users/user/AppData/Local/Temp/.../scratchpad/ga_extract/assets/...` (all 2969 objects)
- Sample objects + signature: `C:/Users/user/Desktop/blocklegends/analysis/asset_samples/`
- Short junction used to beat Windows MAX_PATH on the deep hash tree: `C:\glx -> .../surface`

---

## 0. TL;DR

- **Format**: `game_assets.apk` is a **Play Asset Delivery (PAD) install-time asset pack** wrapping a
  **content-addressed Merkle/Git-style object store** at `assets/objects/main/surface/<treehash>/.../<bloblhash>[_comp].obj`.
  2969 objects, MD5-length (32-hex) names, variable nesting depth 1–5, with **subtree de-duplication**
  (identical subtree hashes reused under different parents → it is a Merkle DAG, not a flat list).
- **Encryption**: 2326 of 2969 objects (the structured game data: 78% of files, ~41 MB) are
  **AES, 16-byte-block, ECB mode, fixed key** — proven black-box (100% size%16==0, fixed per-format
  header ciphertext blocks, and 75–100% cross-file 16-byte block collisions at ~8.0 bits/byte entropy).
  The remaining 643 objects (MP3/Ogg/PNG, 140 MB = 77% of bytes) are stored **in plaintext, unencrypted**.
- **Dominant bytes**: audio. 581 MP3 (109 MB) + 12 Ogg dominate; 50 PNG textures (31 MB);
  ~760 encrypted compressed/data blobs (37 MB) hold meshes/world/config/localization.
- **`_comp` blobs**: **Zstd-compressed then AES-ECB-encrypted** (decompressor = `gnatives.ZstdNative`; LZ4 also linked).
- **Signature**: `META-INF/BNDLTOOL.*` = Google **bundletool** v1 JAR signature (+ APK Scheme v2/v3),
  4096-bit RSA "Android / Google Inc." key dated 2025-02-19. Integrity-only; orthogonal to the AES layer.
- **No index/manifest file** exists in the store: logical-name→hash resolution is performed by the native
  engine via the encrypted *tree objects* (the small `e538…` blobs are the directory/index nodes).
- **IAP**: no SKU/product-ids ship in the package — purchases are processed natively
  (`net.blocklegends.natives.GNatives.onProcessPurchases(String)`, reflection-registered).

---

## 1. `game_assets.apk` format

### 1.1 Container & top-level layout
`unzip -l` summary (file: `Android package (APK), with AndroidManifest.xml`):

| Entry | Count / size | Meaning |
|---|---|---|
| `AndroidManifest.xml` | 1604 B (binary AXML) | asset-pack manifest (see §4) |
| `assets/objects/main/surface/…/*.obj` | **2969 files, 190.6 MB** | the object store |
| `META-INF/MANIFEST.MF` | 528,681 B | bundletool JAR manifest (SHA1 per object) |
| `META-INF/BNDLTOOL.SF` | 528,789 B | signature file over the manifest |
| `META-INF/BNDLTOOL.RSA` | 2174 B | PKCS#7 cert + signature |

Total: 2974 entries. Every payload object lives under the single namespace
`assets/objects/main/surface/`.

### 1.2 Object naming — content-addressed hashes (NOT sequential ids)
Object and directory names are **32-hex-char (128-bit / MD5-length) hashes**. Examples:

```
assets/objects/main/surface/08cb37caa7844512fadb33b77b98f500/3410d7be65951c22d80d4b7aa967bdaa/0003e7750d8ac362c7d013f6ffd593a5_comp.obj
assets/objects/main/surface/867299a864c33c68e5620d4cead122b8/002b27c538ea82efa76d4976032421c0.obj
assets/objects/main/surface/8eaaae4065421d1eee7b74feaa6b2f37/521587eee0dd147b340eedb34877c6cc/9dde1d4c445c19bd8af0b5b0faeb9bf8/0e644c64ffdbfaf0d959fe1a1d2204b7.obj
```

Two filename classes:
- `<hash>.obj`        → object stored verbatim (uncompressed payload)
- `<hash>_comp.obj`   → **compressed** payload (Zstd, see §1.6)

Path-depth distribution (number of hash directories before the filename):

| hash-levels | objects |
|---|---|
| 1 | 1644 |
| 2 | 886 |
| 3 | 405 |
| 4 | 27 |
| 5 | 7 |

### 1.3 This is a Merkle/Git-style object DAG (with de-duplication)
There are **14 distinct top-level subtree hashes** under `surface/`. The decisive observation:
the subtree hash `08cb37caa7844512fadb33b77b98f500` appears **both** as a top-level entry **and** as a
child of `689e97d57b120c8eb312b8d7b76e384e/08cb37caa7844512fadb33b77b98f500/…`. The same subtree being
referenced from two parents is only possible in a **content-addressed Merkle tree** where a directory's
name is the hash of its contents, enabling sharing/dedup. Naming (`objects/`, the `main` "branch",
hash-named trees + blobs) mirrors a Git object database — fitting the CraftRise asset-versioning lineage
(supports delta/incremental updates: changed blob → changed parent tree hashes up to a new root).

The native bridge `Java_gnatives_GNative_calculateHash` (strings_all.txt L139) is the content-hash routine.

### 1.4 There is NO plaintext index / manifest inside the store
The only non-`.obj` entries are `AndroidManifest.xml` and the three `META-INF/*` signing files
(the `MANIFEST.MF` is the *signing* manifest, not an asset index). There is **no `.json`, `.idx`,
`index`, or catalog** file. Therefore the **mapping logical-name → object hash is not on disk** as a
table; it lives in:
  1. the encrypted **tree objects** themselves (the small `e538…` blobs — directory nodes that list
     `child-name → child-hash`), walked from a root hash, and
  2. the native engine (`MainApp`, the AOT GraalVM image) which knows the root hash and the path scheme.
This is identical in spirit to git: you only need the root tree hash; every name resolves by decrypting a
tree object and looking up the next hash.

### 1.5 Per-object byte format — magic survey (all 2969 objects)
First-4-byte magic distribution and `file(1)` identification:

| Class | Count | Total size | First 16-byte header (constant within class) | Identity |
|---|---|---|---|---|
| **ENC tree/meta** | 1566 | 3.72 MB | `e538a5ed f2a3d14d eb80f240 13f5c3f7` | AES-ECB, plaintext = small index/meta nodes |
| **ENC blob (`_comp`)** | 648 | 7.94 MB | `34164b01 c7838123 eab7380e 8ef51d57` | Zstd → AES-ECB |
| **MP3** | 581 | 109.36 MB | `ID3\x04`(282), `ID3\x03`(277), `\xff\xfb…`(22) | MPEG-1 L3 audio, **plaintext** |
| **PNG** | 50 | 30.60 MB | `89 50 4E 47 0D0A1A0A` | PNG textures, **plaintext** |
| **ENC other** | 112 | 28.96 MB | 25 distinct fixed headers (b650c438×26, 1e4be776×29, 7abcd90e×8, d329ebb2×4 …) | AES-ECB, other asset formats |
| **Ogg** | 12 | 0.25 MB | `OggS` | Ogg (Opus/Vorbis), **plaintext** |

`file(1)` reports the encrypted blobs as `data` / occasional false positives ("OpenPGP Secret Key",
"PDP-11 executable", "WonderSwan ROM") — these are mis-identifications of high-entropy random-looking
ciphertext, not real formats.

### 1.6 `_comp` = Zstd, decompressed natively
`_comp.obj` payloads are compressed. The engine links both Zstd and LZ4 with JNI bridges:
- `Java_gnatives_ZstdNative_compress` / `_compressBuffer` / `_compressBound` (strings_all.txt L621–628;
  decompress counterparts present) — the `_comp` decompressor.
- `LZ4_compress_*`, `LZ4F_*` frame API (strings_all.txt L557–1141) — also available.
The constant 16-byte prefix `34164b01…` is the *encrypted* header of the Zstd container (no cleartext
`0x28B52FFD` zstd magic is visible because compression happens **before** AES). Direct
zlib/gzip/raw-deflate decompression of the body at every 4-byte offset fails (confirmed), consistent with
"compress(Zstd) → encrypt(AES)" rather than a standard zip/gzip wrapper.

---

## 2. Encryption / obfuscation of assets (proof)

The structured-data objects are **encrypted with a 16-byte (128-bit) block cipher in ECB mode under a
single fixed key** — almost certainly **AES**. Evidence chain (black-box, no key required):

1. **Block-size alignment.** 100% of the 2326 non-media objects have `size % 16 == 0`
   (ENC-e538 1566/1566, ENC-comp 648/648, ENC-other 112/112), while media files satisfy it only at the
   ~1/16 chance rate (MP3 9%, PNG 6%, Ogg 8%). A strict 16-byte multiple across thousands of files ⇒
   block cipher with block padding, 128-bit block (AES/Rijndael-128).

2. **Fixed per-format header ciphertext.** All 1566 `e538…` objects share the *identical* first 16 bytes
   `e538a5edf2a3d14deb80f24013f5c3f7`; all 648 `_comp` objects share `34164b01c7838123eab7380e8ef51d57`;
   each of the 25 "other" formats has its own constant first block. A fixed key encrypting a fixed
   plaintext header block ⇒ constant ciphertext block (ECB/CBC-fixed-IV signature).

3. **ECB confirmed by cross-file block collisions at maximal entropy.** For the `b650c438` group (26
   similar assets): byte entropy 7.98 bits/byte (near-random) yet **75% of all 16-byte blocks are
   duplicated across the group** (164,609 / 219,449), and the collisions are at *unaligned* offsets
   between files. For `7abcd90e` (8 assets): the two largest are **100% block-identical** (7126/7126
   aligned blocks). Independent random/compressed data cannot collide on 128-bit blocks (2^-128); the only
   explanation is **ECB-mode encryption of plaintext that shares 16-byte blocks** (the classic "ECB
   penguin"). The "all-unique-content" groups (`e538`, `_comp`) correctly show ~0% collisions because
   their plaintexts are unique/high-entropy.

4. **Algorithm provenance.** The GraalVM native image bundles the JDK SunJCE provider:
   `javax.crypto.spec.SecretKeySpec` (strings_all.txt L141344, L152677), SunJCE banner (L142448),
   `AES/GCM/NoPadding`, AES KeyWrap variants (L154101–L170789). The asset cipher is therefore a standard
   `Cipher.getInstance("AES/ECB/…")` + `SecretKeySpec` with an **embedded fixed key** (the key is constant —
   all files produce the same header ciphertext). (The `AES/CBC/ISO10126Padding` and `RSA/ECB` strings at
   L53917–L54030 are from a *bundled Apache Santuario xmlsec config*, unrelated to assets.)

**Caveat:** ECB + AES-128 is a high-confidence *inference* from binary statistics + the available JCE
primitives. Exact mode/keysize and the key bytes require dynamic instrumentation (Frida hook on
`Cipher.init`/`doFinal` or `SecretKeySpec.<init>`) or decompiling the AOT `MainApp` in `libblocklegends.so`.

**Security posture / takeaways for an RE researcher:**
- All **audio (109 MB MP3 + Ogg) and the 50 large PNG textures (31 MB) are plaintext** — they can be
  ripped directly from the APK with `unzip`, no key needed (e.g. the 6 MB music tracks under
  `surface/689e97…/bf8aa7…/`).
- Only the **game logic data** (meshes/geometry, world/surface data, block models, config, localization —
  the `.obj`/`_comp.obj` blobs, ~41 MB) is AES-protected. The key is hard-coded in the native image, so the
  protection is **obfuscation, not real confidentiality**: recoverable once.
- ECB leaks structure (identical models/chunks share ciphertext blocks), so even without the key one can
  cluster duplicate/near-duplicate assets (as done in §2.3).

---

## 3. BNDLTOOL signature — meaning

`META-INF/BNDLTOOL.{SF,RSA}` + `MANIFEST.MF` are a **standard JAR v1 signature**, where the signer alias
is `BNDLTOOL` = Google's **bundletool** (the tool Play uses to split/sign AAB→APK and asset packs). Details:
- `MANIFEST.MF`: `Manifest-Version: 1.0`, then a `Name:` + `SHA1-Digest:` stanza for **every** object and
  `AndroidManifest.xml` (e.g. `AndroidManifest.xml` → `RIMV72CZU14xZiwaDfPQh0HPwMg=`).
- `BNDLTOOL.SF`: `Signature-Version: 1.0`, `Created-By: 1.0 (Android)`, `SHA1-Digest-Manifest`, and
  `X-Android-APK-Signed: 2, 3` (also signed with APK Signature Scheme **v2/v3** at the ZIP level).
- `BNDLTOOL.RSA` (PKCS#7): `sha256WithRSAEncryption`, **4096-bit RSA**, self-signed
  `CN=Android, O=Google Inc., OU=Android, C=US` (Google-managed Play App Signing key),
  validity **2025-02-19 → 2055-02-19**.

Meaning: this is **install-time integrity/authenticity** (tamper-evident; Play verifies the digests and the
v2/v3 signature). It is **not** the asset encryption and provides no confidentiality — the AES layer (§2)
is the game's own, applied *inside* each object before packaging.

---

## 4. AndroidManifest / asset-delivery / versioning

### 4.1 `game_assets.apk` AndroidManifest (asset-pack descriptor)
Decoded AXML strings reveal a **Play Asset Delivery pack**, not a normal feature split:
```
application, asset-pack, dist:delivery, dist:fusing(include), dist:module,
install-time, isFeatureSplit, split="game_assets", package="net.blocklegends",
xmlns dist=http://schemas.android.com/apk/distribution
```
→ module **`game_assets`**, delivery type **`install-time`** (downloaded with the base app, not on-demand),
`fusing include` (fused into the monolithic APK when installed on pre-Lollipop / sideloaded). So the 183 MB
object store is shipped as an install-time asset pack alongside the base APK.

### 4.2 Split set & versioning (from `extracted/manifest.json`)
```
package_name : net.blocklegends     name: "Block Legends"
version_code : 218                   version_name: 218.0.0
min_sdk: 32   target_sdk: 37         total_size: 471,307,367
split_configs: config.arm64_v8a, config.en, config.mdpi, game_assets
split_apks   : base(net.blocklegends.apk), config.arm64_v8a, config.en, config.mdpi, game_assets
```
Relevant permissions: `com.android.vending.BILLING` + `…CHECK_LICENSE` (IAP/licensing),
`RECORD_AUDIO`+`MODIFY_AUDIO_SETTINGS` (voice chat — WebRTC/Opus), `INTERNET`, `USE_BIOMETRIC/FINGERPRINT`,
`ACCESS_ADSERVICES_*` + `AD_ID` (AdMob), `FOREGROUND_SERVICE_DATA_SYNC`, `c2dm…RECEIVE` (FCM push).

### 4.3 Runtime extraction (important behavioral detail)
Base `strings.xml` (`res/values/strings.xml`) contains a dedicated storage-error string set proving the
app **unpacks/decrypts the asset store to local storage on first run**:
- `storage_error_title` = "Insufficient Storage Space"
- `storage_error_message` = "There is not enough storage space to **extract game files**.\n\nPlease free up
  at least **150 MB** by deleting unnecessary files and apps."
- plus `storage_error_retry/exit/settings/detail`.

So the on-disk pipeline is: PAD pack → read object by hash → AES-ECB-decrypt → (Zstd-inflate if `_comp`) →
materialize/cache locally. A live decrypted asset cache (~150 MB) is the easiest place to recover plaintext.

---

## 5. Asset-type breakdown & engine mapping

### 5.1 By detected type (counts / bytes — full 2969 set)
| Type | Files | Bytes | Notes |
|---|---:|---:|---|
| MP3 audio (plaintext) | 581 | 109.4 MB | music + SFX; ID3v2.3/2.4 tagged; largest 6.16 MB track |
| PNG textures (plaintext) | 50 | 30.6 MB | UI / large atlases; median 0.49 MB, max 1.53 MB |
| Ogg audio (plaintext) | 12 | 0.25 MB | Opus/Vorbis SFX |
| ENC blob `_comp` (Zstd+AES) | 648 | 7.94 MB | compressed game data; max 0.54 MB |
| ENC "other" (AES, 25 formats) | 112 | 28.96 MB | larger encrypted assets (meshes/world); max 2.96 MB |
| ENC tree/meta (AES, `e538`) | 1566 | 3.72 MB | **directory/index nodes** + small metadata; median 272 B |
| **Total** | **2969** | **190.6 MB** | |

Interpretation of the encrypted classes (cross-referenced with native bridges):
- **`e538…` (1566, tiny, median 272 B):** the **tree/index objects** of the Merkle store (name→hash
  listings) plus small config/metadata. Their sheer count and small size match directory nodes.
- **`_comp` (648):** compressed serialized game data (block models / geometry / world-surface chunks /
  localization tables) — `gnatives.ZstdNative`.
- **"other" (112, up to ~3 MB):** larger binary asset formats (likely meshes/voxel surface data, possibly
  KTX2/Basis texture payloads) each with its own header magic; ECB de-dup shows many are near-duplicates.

### 5.2 Engine consumers (native JNI bridges in `libblocklegends.so`; line refs = strings_all.txt)
- **Textures:** `gnatives.KTXTexture.nativeCreateFromMemory` (L120), **`nativeTranscodeBasisU`** (L121),
  `nativeGetImageData/Width/Height` (L122–125), `ktxTexture2_TranscodeBasis` — **KTX2 + Basis Universal**
  GPU-texture transcoding from memory. (PNGs are decoded via the bundled libpng — `png_*` symbols.)
- **Geometry/meshes:** `gnatives.GNative` — `vertexBuffer(I)`, `native_pos_tex_color_normal`,
  `native_dobatchobject`, `native_fastadd`, `glBufferStorageEXT`, plus AMD **FSR** (`FsrEasuGetConsts`,
  `FsrRcasGetConsts`) and `calculateHash` (content addressing).
- **World/render contexts:** `…GLES30.makeChunkContext` / `swapChunkContext` /
  `setChunkSurfaceContext` (L273–286, 449–459) — "surface" in the asset path aligns with the engine's
  chunk/surface GL contexts; `main/surface` is the asset namespace for world/surface content.
- **Audio:** MP3/Ogg/Opus decoded by bundled Opus/SILK + WebRTC AEC3 (voice) — audio kept as native
  compressed formats (hence stored plaintext in the pack).
- **Compression:** `gnatives.ZstdNative.*` (L621–628) + `LZ4*` (L557–1141).

### 5.3 How a logical name resolves to an object (reconstructed)
1. Engine holds the **root tree hash** for `main/surface` (baked into the AOT image / config).
2. Fetch `surface/<root>.obj`, **AES-ECB-decrypt** → a tree node (an `e538…`-class object) listing
   `child-name → child-hash`.
3. For each path segment, look up the child hash, descend (`surface/<root>/<child>/…`), repeat — the
   APK's directory nesting (depth 1–5) mirrors the tree depth, with subtree dedup.
4. Leaf = blob: if `_comp` → AES-decrypt then `ZstdNative` inflate; else AES-decrypt; media leaves
   (MP3/PNG/Ogg) are consumed directly.

---

## 6. `config.en.apk` + base resources — notable strings & IAP

### 6.1 `config.en.apk` (60 KB)
Contents: `AndroidManifest.xml`, `resources.arsc` (40,688 B), `stamp-cert-sha256`. It is the **English
locale split** and contains **only generic AndroidX / Material3 / Play-services / AdMob library strings**
(buttons, time/date pickers, fingerprint, "Sign in with Google", "Test Ad", offline-ad dialogs). **No
game-specific text and no IAP ids** — the game's own UI/localization is in the native engine and/or the
encrypted asset store (`e538`/`_comp` objects), not in Android resources.

### 6.2 Base `strings.xml` — notable game/server-facing strings
(`analysis/jadx_base/resources/res/values/strings.xml`)
- `app_name` = **Block Legends**
- **AdMob** ad unit: `ad_unit_id_0` = `ca-app-pub-5716702531231887/9732689249`; `watermark_label_prefix` = "AdMob - "
- **Firebase / Google Cloud project** `block-legends-4a313`:
  - `google_app_id` = `1:810329497878:android:f99db6a67e639da13a7037`
  - `project_id` = `block-legends-4a313`; `google_storage_bucket` = `block-legends-4a313.firebasestorage.app`
  - `google_api_key` / `google_crash_reporting_api_key` = `AIzaSyBxn6n3-d32Xbrc_noiwyHwUNwQCy1y4Uc`
  - `gcm_defaultSenderId` / `game_services_project_id` = `810329497878`
  - `default_web_client_id` = `810329497878-qhcq8r0bpqo1dl0ajkjb282gmq3akhj1.apps.googleusercontent.com`
  - `play_games_sdk_version` = 21.0.0
- **Crashlytics / build provenance**: `…crashlytics_version_control_info` →
  `repositories { system: GIT … revision: "5a115b771aa4177b3ca475a954c5bbb33611806c" }`
  (the exact source commit of this build); mapping_file_id present.
- **Runtime asset-extraction UX**: the `storage_error_*` set (see §4.3), incl. the "extract game files /
  free up at least 150 MB" message.

### 6.3 In-app purchases
- Play Billing **is** integrated: permission `com.android.vending.BILLING`, and
  `res/raw/com_android_billingclient_registration_info.binarypb` /
  `com_android_billingclient_heterodyne_info` (Play Billing client registration).
- **No SKU / product-id strings exist anywhere in the APK or resources.** Purchases are validated/applied
  **natively**: JNI `Java_net_blocklegends_natives_GNatives_onProcessPurchases` (strings_all.txt L500;
  reflection-registered as `{ "name":"onProcessPurchases", "parameterTypes":["java.lang.String"] }` at
  L109333) and `Java_net_blocklegends_os_shared_GLES30_restoreApplePurchases` (L315, iOS-parity restore).
  Product catalog therefore comes from the Play Console / game server at runtime — it is not shippable
  from static analysis. (Wordlist hits like "GEM STONE", "SHOP", "BISHOP" in the native strings are from a
  bundled dictionary, not IAP ids.)

---

## 7. Concrete pointers for follow-up
- Recover all plaintext audio/textures now: `unzip game_assets.apk 'assets/objects/*'` then filter by
  magic `ID3`/`\xff\xfb` (MP3), `OggS` (Ogg), `\x89PNG` (PNG) — 643 files, ~140 MB, no key needed.
- To break the AES layer: hook the AOT engine (`libblocklegends.so` `MainApp`) at the JCE boundary —
  `javax.crypto.Cipher.doFinal` / `SecretKeySpec.<init>` (Frida) to dump the fixed key, or trace
  `gnatives.ZstdNative` decompress to capture post-AES plaintext for `_comp` blobs.
- The decrypted asset cache (created at first run; see §4.3) is the lowest-effort plaintext source.
- Root-tree resolution + the `main/surface` namespace logic are in the GraalVM-compiled `MainApp` /
  `gnatives.GNative.calculateHash`; the on-disk store carries no plaintext index.
