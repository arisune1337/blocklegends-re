# Block Legends v218.0.0 — Master Index & Executive Summary

> **Target:** `Block Legends — Sandbox PvP` v218.0.0 (`net.blocklegends`), CraftRise-lineage voxel
> sandbox for Android (arm64-v8a) + iOS. Distributed as a 471 MB XAPK split bundle (APKPure).
> **Analysis date:** 2026-06-26 · **Tooling:** IDA Pro (idalib MCP), jadx 1.5.0, custom ELF/string extractors, bundletool/zip analysis.
> **This file** is the entry point that ties the five subsystem reports together. Read it first, then dive into the linked reports.

---

## 0. Table of contents (all reports in this folder)

| # | Report | Scope | Source target |
|---|---|---|---|
| **00** | **`00_INDEX.md`** *(this file)* | Master index, executive summary, architecture, gaps review | all |
| 01 | [`01_OVERVIEW.md`](./01_OVERVIEW.md) | TL;DR, package layout, native-lib inventory, tech stack, subsystem map | whole app |
| 02 | [`02_CLASS_HIERARCHY_AND_SBOM.md`](./02_CLASS_HIERARCHY_AND_SBOM.md) | Class/package hierarchy, obfuscation quantification, reflection/JNI metadata, full SBOM with versions | GraalVM image metadata (`raw/bl_classes.txt`, `raw/strings_all.txt`, `raw/reflect_classes.txt`) |
| 03 | [`03_native_ida_survey.md`](./03_native_ida_survey.md) | IDA landscape pass of the 229 MB engine: segments, entry points, JNI bridge census, top functions | `extracted/natives/libblocklegends.so` (IDA session `c3323093`) |
| 04 | [`04_DEX_ANDROID_AND_SECURITY.md`](./04_DEX_ANDROID_AND_SECURITY.md) | Android shell: lifecycle/bootstrap, full JNI contract, anti-cheat/anti-tamper, Play Integrity, network config, monetization, auth, voice, manifest | `jadx_base/sources/net/blocklegends`, manifest |
| 05 | [`05_NETWORKING_PROTOCOL.md`](./05_NETWORKING_PROTOCOL.md) | Multiplayer transport: stock Valve GameNetworkingSockets, crypto/handshake/certstore, app-packet layer, MC-protocol relationship | `extracted/natives/libGameNetworkingSockets.so` (IDA session `17a34222`) |
| 06 | [`06_ASSETS_AND_RESOURCES.md`](./06_ASSETS_AND_RESOURCES.md) | Asset pipeline: PAD pack, content-addressed Merkle store, AES-ECB asset encryption, Zstd `_comp` blobs, bundletool signature, asset→engine mapping | `game_assets.apk`, `config.en.apk`, base resources |
| 07 | [`07_NATIVE_DEEP_DIVE.md`](./07_NATIVE_DEEP_DIVE.md) | GraalVM isolate bootstrap, AOT game-loop + JNI→ANGLE render shim, EGL chunk-context pool, GNS native send/recv bridge, media (WebRTC/Opus/KTX/FSR), native security shims | `libblocklegends.so` (IDA `c3323093`) + sections [`07a`](./07a_native_engine_core.md)/[`07b`](./07b_native_networking.md)/[`07c`](./07c_native_media_security.md) |

> Note: `03` is the IDA **survey/landscape** pass; `07` is the **decompiled native deep-dive** built on it. Remaining engine work (full packet opcode table, worldgen internals, `.svm_heap` config/key dump) is now dynamic-analysis-shaped — see §9–§10.

---

## 1. Executive summary — what Block Legends actually is

**Block Legends is a Minecraft-Java-derived voxel sandbox game whose entire game logic is written in a JVM language (Kotlin/Java), obfuscated with R8, and Ahead-Of-Time compiled to a single 229 MB ARM64 native library (`libblocklegends.so`) via GraalVM `native-image` (SubstrateVM).** The readable Android (DEX) side is only a thin host shell. This architecture is unusual and deliberately defeats both Java decompilation (the logic is native machine code) and native decompilation (the logic is GraalVM-emitted, heavily inlined, and the binary is stripped with an 88 MB pre-initialized image heap).

The codebase has **direct CraftRise lineage** — package `net.blocklegends.cr.*` (`CRLauncher`, `PacketManagerCustom`, `PacketPlayOutPlayerChange`, `EnumConnectionState`, `IChatComponenet`), class `net.blocklegends.client.CraftRise`, and the build origin `/Users/yunus/AndroidStudioProjects/craftriseandroidstudio/`. It is the mobile sibling of the CraftRise / SonOyuncu Minecraft-protocol ecosystem, re-platformed from a desktop Minecraft client onto a GraalVM-native mobile engine.

Commercially it is a **lobby + minigames + cosmetics/housing live-ops product**: Google/Apple/Play-Games login, Play Billing (battle pass `pass_1` + `rc_box_*` loot boxes), AdMob rewarded video, TikTok/Firebase attribution, FCM push, and Play Integrity + a TR/AZ-gated anti-cheat scanner.

---

## 2. Full architecture (layer by layer)

```
┌──────────────────────────────────────────────────────────────────────────────┐
│ PLAY / OS LAYER                                                                │
│  PairIP app-protection wrapper (com.pairip.application.Application)            │
│   → LicenseActivity (com.android.vending.CHECK_LICENSE) → decrypts/loads DEX   │
├──────────────────────────────────────────────────────────────────────────────┤
│ DEX / ANDROID SHELL  (5 dex, ~27 MB Kotlin, 99% of game logic ABSENT here)    │
│  MainApp(Application)  ── boot: Firebase/FCM, TikTok SDK, InstallReferrer      │
│  MainActivity(Activity) ─ loadLibrary(GLESv2_angle,EGL_angle), then            │
│                           NativeLibLoader.loadAsync("blocklegends")            │
│  GameSurfaceView/GLContexts ─ create EGLContext/EGLSurface, hand to native     │
│  GNatives  ◄──── THE JNI CONTRACT (Java⇄engine) ────►  ~71 bridge methods      │
│  Subsystem shims: GameVoice, GameSound, AdManager, Billing, GoogleSign,        │
│   AppleSign, GameServicesNatives, FirebaseNatives, Integrity, GyroController,  │
│   AntiAutoClickerUtil, GyroscopeEC                                             │
├──────────────────────────────────────────────────────────────────────────────┤
│ NATIVE ENGINE  libblocklegends.so  (229 MB GraalVM Native Image / SubstrateVM)│
│  • Java 23 class library + Kotlin stdlib baked in (sun.nio, java.net, JCE...)  │
│  • .text 0x386C000–0x8216928 (~73 MiB / 77 MB) of AOT game code               │
│  • .svm_heap ~88 MB pre-initialized object heap (config, strings, tables)      │
│  • 47,097 functions; entry = IsolateEnterStub__MainApp__start @0x3A7F800       │
│  • Game systems: voxel world/worldgen/structures/villages, chunk streaming,    │
│    GLES2/3/3.1 renderer, entity/block models, physics, packet stack, voice     │
│  • Eclipse Collections 11.1.0 = primary in-memory data lib (254 reflect types) │
│  • JNI bridges OUT to bundled C/C++ via JavaCPP/bytedeco (gnatives.*)          │
├───────────────┬───────────────────┬──────────────────────┬────────────────────┤
│ BUNDLED C/C++ │ DYNAMIC: ANGLE     │ DYNAMIC: GameNetworking│ DYNAMIC: Crashlytics│
│ (static, via  │ libGLESv2_angle.so │ Sockets (Valve)        │ native (Firebase)   │
│  JavaCPP)     │ libEGL_angle.so    │ libGameNetworking      │                     │
│  Opus/SILK    │ libGLESv1_CM_angle │  Sockets.so (9.6 MB)   │                     │
│  WebRTC AEC3  │ → GLES→native/Vk   │ UDP + SNP reliability  │                     │
│  libpng/jpeg  │                    │ X25519/Ed25519/AES-GCM │                     │
│  lcms2 LZ4    │ libktx.so (KTX2/   │ stock OSS build        │                     │
│  Zstd abseil  │  Basis transcode)  │ (gnatives.GnsNative)   │                     │
│  pffft AMD-FSR│                    │                        │                     │
└───────────────┴───────────────────┴──────────────────────┴────────────────────┘
```

**Split-APK layout** (`min_sdk 32`, `target_sdk 37`, arm64-v8a only):

| Split | Size | Contents |
|---|---|---|
| `net.blocklegends.apk` (base) | 28 MB | 5 DEX (Kotlin shell), manifest, `resources.arsc`, R |
| `config.arm64_v8a.apk` | 241 MB | `lib/arm64-v8a/*.so` — the engine + ANGLE + GNS + KTX + Crashlytics |
| `game_assets.apk` | 183 MB | PAD install-time asset pack: 2969 content-addressed objects |
| `config.en.apk` / `config.mdpi.apk` | 60 KB / — | locale + density resource splits (library strings only) |

---

## 3. Subsystem map (where each system lives + key evidence)

| Subsystem | Lives in | Key symbols / classes |
|---|---|---|
| **Boot / license** | DEX (PairIP) → DEX `MainApp`/`MainActivity` → native isolate | `com.pairip.application.Application`, `IsolateEnterStub__MainApp__start` @0x3A7F800, `run_main` @0x3A7F690 |
| **Rendering** | Engine + ANGLE | `os.shared.GLES20/30/31` (336 `Java_net_blocklegends*` JNI), `GLES20_initGLES` @0x80B2818, named EGL contexts `splash/display/game/async/chunk/chunkWaiter/load1-4`, ANGLE, KTX2/Basis, AMD FSR (`FsrEasuGetConsts`/`FsrRcasGetConsts`) |
| **Game loop** | Engine | `GLES30_gameLoopTick` @0x80C31B0, `GNatives.setGameContext`, `onSurfaceResume` |
| **World / worldgen** | Engine | `world.gen.structure.{StructureStart,StructureComponent,MapGenStructureData}`, `world.storage.MapData`, `village.VillageCollection`, `client.renderer.block.model.ModelBlock`, `cr.client.animation.BedrockAnimation` |
| **Chunk streaming** | Engine | `GNatives.setChunkContext`/`setChunkWaiterContext`/`makeChunkWaiterContext`, `GLES30.makeChunkContext`/`swapChunkContext` |
| **Networking transport** | `libGameNetworkingSockets.so` via `gnatives.GnsNative` (24 JNI) | `init/connect/createServer/serverAccept/send/sendBatch/receive/receiveBatch/pollGroup*`; UDP + SNP + AES-256-GCM |
| **App packet layer** | Engine | `network.Packet`, `cr.client.manager.PacketManagerCustom`, `cr.client.packets.{PacketRequest,PacketResponse,PacketPlayOutPlayerChange}`, `cr.obfuscates.EnumConnectionState` |
| **Voice chat** | Engine (DSP) + DEX `GameVoice` (PCM bridge) | `gnatives.OpusNative` (Opus/SILK), `gnatives.VoiceProcessingNative` (WebRTC AEC3/AGC), pffft; `voiceStartCapture`/`voiceWritePlayback`, `setNativeAecActive` |
| **Audio SFX/music** | Engine + DEX `GameSound` (SoundPool/MediaPlayer) | `GLES30_playSound`/`stopSound`/`android_sound_load` |
| **Compression** | Engine | `gnatives.ZstdNative` (+legacy `cr.zstd.ZstdNative`, 13 JNI), LZ4 frame API |
| **Assets** | `game_assets.apk` + engine | content-addressed Merkle store, `gnatives.GNative.calculateHash`, AES-ECB + Zstd `_comp`, `gnatives.KTXTexture.nativeTranscodeBasisU` |
| **OS abstraction** | DEX + engine | `os.shared.*` (WindowManager/KeyboardManager/VibrationManager/Bitmap/EGLHelper), `os.android.*`, `os.ios.*` (dual-target) |
| **Monetization** | DEX shims, engine-driven | `billing.Billing` (Play Billing 9.0.0, `pass_1`/`rc_box_*`), `ad.AdManager` (AdMob rewarded), TikTok SDK, InstallReferrer |
| **Auth** | DEX shims → engine | `game.GoogleSign`/`game.AppleSign`/`gameservices.GameServicesNatives` → all converge on `GNatives.onTokenReceived` |
| **Platform services** | DEX | `firebase.*` (Analytics/Crashlytics/Messaging), `firebase.Integrity` (Play Integrity), Play Games v2 |

---

## 4. Security & anti-cheat posture

**Layered, with the meaningful enforcement native/server-side:**

1. **PairIP** (Google Play app-protection) is the outermost wrapper — the manifest `application` is `com.pairip.application.Application`, with `LicenseActivity` + `com.android.vending.CHECK_LICENSE` + a `stamp-cert-sha256` resource. It decrypts/loads the real DEX and enforces a Play license check before `MainApp.onCreate`. *(Documented only in report 04 — see Gaps §10.)*
2. **Play Integrity API** (`firebase.Integrity` → cloud project **810329497878**). Engine-driven: native generates the nonce and calls `Integrity.run(nonce, requestId)`; the opaque token round-trips `token → GNatives.onIntegritySuccess → native → server`. Nonce generation and token verification are entirely native/backend; the Java side never inspects the token.
3. **DEX anti-tamper scanner** (`utils/AntiAutoClickerUtil`, cached 60 s) detects Shizuku, Magisk/SuperSU/root packages, known autoclickers, LSPosed/Xposed, suspicious accessibility services, **Frida** (`/proc/self/maps` scan for `frida`/`gum-js`/`libfrida`, ≥2 hits), test-keys builds, `su` binaries, monkey/test-harness. **Critically TR/AZ-region-gated** — the popup scanner loop runs only if `cachedIsTurkishOrAzeri`; non-TR/AZ users do not get this scan loop (the native engine may still react to `onEmulatorUsage`).
4. **Emulator / sensor-spoof challenge** (`ui/GyroscopeEC`): 12 s passive motion-noise analysis (gyro/accel std + uniqueCount thresholds) + active haptic-induced-motion challenge; plus build-fingerprint heuristics (`goldfish`/`ranchu`/`qemu`/Genymotion/Nox/BlueStacks/LDPlayer). On failure → `GNatives.onEmulatorUsage()` → engine → non-cancelable "Emulator Detected" dialog → `finish()`. Voice capture is also blocked on emulator runtimes.
5. **Server-authoritative cheat enforcement.** No client-side cheat-scanner vocabulary (killaura/xray) surfaced. Enforcement is via the server + `PacketManagerCustom` + integrity gating (consistent with CraftRise `rac`/"2" launcher-proof blocker from prior project memory).
6. **Transport security** is provided by GameNetworkingSockets: X25519 ECDHE + Ed25519 cert signatures + AES-256-GCM AEAD + HKDF, with **CA pinning to a single hardcoded Ed25519 root key** in the certstore (not Valve's live CA — CraftRise's own, or insecure self-signed mode if `AllowWithoutAuth` is set). Unsigned peer ⇒ rejected with end-reason 4003 unless insecure mode is configured.
7. **Anti-debug:** no DEX-level `Debug.isDebuggerConnected`/TracerPid checks; any anti-debug is native and not yet confirmed.

**Net assessment:** The protections are real but the *secrets* (server addresses, asset AES key, integrity nonce logic, packet serialization) are all inside the GraalVM image / `.svm_heap`. The high-value attacker move is dynamic instrumentation, but PairIP + Frida-map scanning + Play Integrity + emulator challenge raise the cost. The asset AES is **obfuscation, not confidentiality** (fixed embedded key, recoverable once).

---

## 5. Networking model

- **Transport:** **stock, unmodified Valve GameNetworkingSockets** (OSS `GameNetworkingSockets-master`, built from `/Users/yunus/git/GameNetworkingSockets-master/`), with its own bundled OpenSSL 3.x + abseil. UDP datagrams + **SNP** (Steam Networking Protocol) reliability/ordering/fragmentation/Nagle/lanes. No game-specific protocol patches; the only non-vanilla exports are additive `..._CRGetConnRecvWakeFd`/`...PollGroupRecvWakeFd` helpers (the `CR` = CraftRise) that expose a wake FD so the JVM can `select()` for non-blocking receive.
- **Crypto:** X25519 ECDHE key agreement, Ed25519 identity/cert signatures (64-byte sigs), AES-256-GCM record encryption (ARMv8 HW path), ChaCha20-Poly1305 available, HKDF derivation. Standard GNS `CCrypto`/certstore model with a single pinned root key.
- **Topology:** **direct-IP** (`ConnectByIPAddress`) and/or **ICE P2P with the game's own custom signaling** (`CMsgSteamNetworkingP2PRendezvous`, `CMsgICERendezvous`). SDR relay + FakeIP are compiled in but **inert** ("requires Steam"). Both client connect and `createServer`/`serverAccept` exist ⇒ the client can also host (P2P / local minigame server).
- **App layer:** a **Minecraft/CraftRise-derived packet *model*** — per-connection `EnumConnectionState` phase machine, `PacketPlayOut*` clientbound packets, `PacketRequest`/`PacketResponse` RPC, dispatched by `PacketManagerCustom` — serialized to a **custom binary blob** carried as opaque GNS messages via direct `java.nio.ByteBuffer` (zero-copy). It is **not** the Mojang TCP wire format: no length-prefix/VarInt framing, no Netty pipeline on the game path. GNS supplies framing/encryption/reliability; the MC layer supplies only the message taxonomy.
- **Out-of-band:** account login (Google/Apple/Play-Games tokens) happens over **HTTPS** before the GNS connection; the only first-party HTTPS host visible in Java is **`blocklegends.net`** (Apple OAuth bridge / App Links). The live game/lobby/matchmaking server address is **not in the DEX or plaintext native strings** — it is resolved at runtime inside the `.svm_heap`.

---

## 6. Asset pipeline

- **Container:** `game_assets.apk` is a **Play Asset Delivery install-time pack** (`dist:delivery install-time`, `fusing include`) — 2969 objects, 190.6 MB, all under `assets/objects/main/surface/`.
- **Store model:** a **content-addressed Merkle/Git-style object DAG** with subtree de-duplication. Names are 32-hex (128-bit, MD5-length) content hashes; nesting depth 1–5; `<hash>.obj` (verbatim) vs `<hash>_comp.obj` (compressed). Content hashing = `gnatives.GNative.calculateHash`. There is **no plaintext index/manifest** — name→hash resolution walks encrypted *tree objects* (the small `e538…` nodes) from a root hash baked into the engine.
- **Encryption:** the structured game data — 2326/2969 objects (~41 MB; the `e538…` tree/meta nodes, the `_comp` blobs, and 112 "other" formats) — is **AES, 128-bit block, ECB mode, fixed embedded key** (proven black-box: 100% size%16==0, constant per-format header ciphertext, 75–100% cross-file block collisions at ~8.0 bits/byte). The remaining 643 objects — **581 MP3 (109 MB) + 12 Ogg + 50 PNG (31 MB), ~140 MB = 77% of bytes — are PLAINTEXT** and rippable directly with `unzip`, no key.
- **Compression:** `_comp` blobs are **Zstd-compressed then AES-ECB-encrypted** (`gnatives.ZstdNative`; LZ4 also linked). Zstd magic is invisible because compression precedes encryption.
- **Runtime:** the app **extracts/decrypts the store to local storage on first run** (string set `storage_error_*` warns "extract game files / free up at least 150 MB") → the **decrypted on-disk cache is the lowest-effort plaintext source**.
- **Signature:** `META-INF/BNDLTOOL.*` = Google **bundletool** JAR v1 + APK Scheme v2/v3 signature (4096-bit RSA "Android / Google Inc.", 2025-02-19→2055). Integrity-only; orthogonal to the AES layer.
- **Texture path:** PNG via bundled libpng; KTX2/Basis Universal via `gnatives.KTXTexture.nativeTranscodeBasisU` for GPU-compressed atlases.

---

## 7. CraftRise / Minecraft lineage (provenance)

- **Build origin:** `/Users/yunus/AndroidStudioProjects/craftriseandroidstudio/` (macOS, dev "yunus") — the literal CraftRise Android Studio project. GNS built from `/Users/yunus/git/GameNetworkingSockets-master/`. Crashlytics version-control commit `5a115b771aa4177b3ca475a954c5bbb33611806c`.
- **Code lineage:** `net.blocklegends.client.CraftRise` god-object; `net.blocklegends.cr.*` package (`CRLauncher`, `PacketManagerCustom`, `PacketPlayOutPlayerChange`, `PacketRequest`/`Response`, `EnumConnectionState`, `IChatComponenet` [original typo preserved], `GameType`, `GameMode`, `GameMap`, `BedrockAnimation`). The package literally named `cr.obfuscates` documents CraftRise's remap of vanilla Minecraft classes. Fossil dual namespace `cr.zstd.ZstdNative` + `gnatives.ZstdNative` shows a refactor from `cr.*` to `gnatives.*`.
- **Minecraft engine heritage:** full Java terrain/structure/village stack (`StructureStart`/`StructureComponent`/`MapGenStructureData`/`MapData`/`VillageCollection`), Java resource-pack block models (`ModelBlock`), **plus** Bedrock entity animations (`BedrockAnimation`) — ingests both MC ecosystems. CraftRise `&s<id>` styled-chat token system (driven by `IChatComponenet`) is visible across localized strings.
- **Relationship to prior project work:** this is the mobile, GraalVM-native re-platforming of the CraftRise/SonOyuncu client family. The desktop wire-format/login work (memory: `craftrise-login-protocol`, `sonoyuncu-0624-update`) is conceptually adjacent but the *transport* here is GNS/UDP, not the desktop TCP Minecraft pipeline — the packet *model* carries over, the wire framing does not.

---

## 8. Key facts (quick reference)

- **Identity:** Block Legends v218.0.0 (`versionCode 218`), `net.blocklegends`, CraftRise-lineage voxel sandbox, Android arm64-v8a + iOS, 471 MB XAPK.
- **Architecture:** thin DEX shell → GraalVM-AOT engine `libblocklegends.so` (229,788,616 bytes) → bundled C/C++ via JavaCPP + dynamic ANGLE / GameNetworkingSockets / KTX / Crashlytics.
- **Engine binary:** SHA-256 `675f36db89b1dd7193247e52c5837d5c3b9962b9d5ed4537011e7175c33bebf1`, MD5 `dcd05c8d821fcbed01dea03dc4063da9`; AArch64 PIE, stripped (`.dynsym` only); 47,097 functions; `.text` ~73 MiB (0x386C000–0x8216928), `.svm_heap` ~88 MB.
- **Toolchain:** GraalVM CE 23-dev (Java 23 class library), R8 minification (99.28% of 7,376 types obfuscated to 1–3-char names), JavaCPP/bytedeco for native bridges.
- **Obfuscation:** only ~51 readable domain classes kept (those crossing JNI/reflection/platform boundaries); all real game logic AOT-compiled into `.text`.
- **Primary data lib:** Eclipse Collections 11.1.0 (254/405 reflect-registered types); `sun.misc.Unsafe` + `invokeCleaner` registered (off-heap chunk/mesh buffers).
- **Engine entry:** `IsolateEnterStub__MainApp__start` @0x3A7F800, `run_main` @0x3A7F690.
- **JNI surface:** 336 `Java_net_blocklegends*` + 88 `Java_gnatives*` (747 `Java_*` incl. JDK runtime). `GNatives` = ~71-method Java⇄engine contract; `GLES30` 131, `GLES20` 72.
- **Networking:** stock Valve GameNetworkingSockets (UDP+SNP, X25519/Ed25519/AES-256-GCM), `gnatives.GnsNative` (24 methods), single pinned Ed25519 root; SDR/FakeIP disabled.
- **Voice:** Opus/SILK + WebRTC AEC3/AGC + pffft, native DSP, DEX `GameVoice` is a 16-bit-mono PCM bridge (240 ms jitter buffer).
- **Assets:** content-addressed Merkle store; 41 MB AES-ECB-encrypted (fixed key) + 140 MB plaintext audio/PNG; `_comp` = Zstd→AES; first-run decrypt-to-disk (~150 MB).
- **Security:** PairIP wrapper + Play Integrity (cloud project 810329497878) + TR/AZ-gated Frida/root/emulator scanner + GyroscopeEC + server-authoritative anti-cheat. No DEX anti-debug.
- **Backends/IDs:** Firebase `block-legends-4a313` (google_app_id `1:810329497878:android:f99db6a67e639da13a7037`, API key `AIzaSyBxn6n3-d32Xbrc_noiwyHwUNwQCy1y4Uc`); AdMob `ca-app-pub-5716702531231887/9732689249`; TikTok token `TTaLmfWUaDBm8ZZbPSFhse4pMu2vZ3ta`; first-party host `blocklegends.net` (auth bridge). **Live game server: not statically present.**
- **Monetization:** Play Billing 9.0.0 (`pass_1` battle pass ±discount SUBS, `rc_box_1..6` INAPP loot boxes), AdMob rewarded video, TikTok + InstallReferrer + Firebase attribution.
- **Bundled C/C++ SBOM (with versions):** Opus 1.6.1, WebRTC-audio-processing (AEC3/AGC/AGC2/VAD), libpng 1.6.48, libjpeg, lcms2, Zstd 1.5.x, LZ4, abseil `lts_20240722`, pffft, AMD FSR 1.x, KTX/Basis, OpenSSL 3.x (in GNS), Netty+gRPC (shaded), Guava, ICU4J, JavaFX rasterizer remnants (prism/pisces/marlin/scenario).

---

## 9. Open questions / next steps for deeper analysis

**Native engine deep-dive (the big gap — would become report 07):**
- Decompile `IsolateEnterStub__MainApp__start` (0x3A7F800) / `run_main` (0x3A7F690) to recover the Java entry class, argument wiring, and isolate/config bootstrap.
- Locate and decompile the **game loop** (`gameLoopTick` @0x80C31B0 callee tree), **chunk meshing/streaming** (the `chunk`/`chunkWaiter` context pipeline), and **worldgen** (`StructureStart`/`MapGenStructureData` consumers) — none decompiled yet; report 03 is a survey only.
- Recover the **packet serialization format**: decompile `PacketManagerCustom` dispatch and `network.Packet` read/write to get the actual opcode table and field encodings the GNS messages carry.
- Recover **runtime config from `.svm_heap`**: the live game/lobby/matchmaking server host(s), pinned Ed25519 CA root key (32 bytes; path `sub_385F70`/`sub_3851F8` in GNS), and the integrity nonce-generation logic — all live in the 88 MB image heap, not in `.text` strings.
- Extract the **asset AES key + mode** (confirm ECB vs CBC-fixed-IV, key size) by decompiling the `Cipher`/`SecretKeySpec` setup in the engine, or by hooking it dynamically.

**Dynamic analysis (caveated by anti-cheat):**
- Frida/instrumentation is **directly detected** by `AntiAutoClickerUtil.looksLikeFridaPresent` (`/proc/self/maps` scan) and gated by Play Integrity + PairIP. Mitigations: TR/AZ scanner is region-gated (a non-TR/AZ device avoids that popup loop); use a stealth Frida (gadget renamed, no `frida`/`gum-js`/`libfrida` map names); patch/avoid the GyroscopeEC emulator path (run on a real device with real sensors). Hooking the **Java `GNatives` boundary** (e.g. `onTokenReceived`, `onIntegritySuccess`, `onProcessPurchases`, `readCapture`/`writePlayback`, `Cipher.doFinal`) is the highest-yield, lowest-native-risk interception point.
- The **decrypted asset cache** written on first run (§6) is the easiest plaintext recovery — pull it off-device after a real-device launch, no key extraction needed.
- For wire capture: GNS is UDP+AES-256-GCM, so on-path capture is opaque; intercept at the `gnatives.GnsNative.send`/`receive` direct-ByteBuffer boundary (pre-encryption) instead.

**Confirm the iOS co-target** (`os.ios.*`, `game.AppleSign`, Game Center) against an actual IPA — currently inferred only from shared-codebase class names in the Android binary.

---

## 10. Gaps & verification notes (critical review of reports 01–07)

**Update — report 07 (native deep-dive) now complete; resolved/refined:**
- **Gap 1 (paths):** fixed — report 01 §6 now points at `raw/`.
- **Gap 8 (server address):** confirmed runtime-resolved — the native bridge receives the game server as an `"ip:port"` **string passed from Java** (`gnatives.GnsNative` → `ConnectByIPAddress`); nothing static in `.text`. Origin is the `blocklegends.net` auth/matchmaking response → requires dynamic capture.
- **Gap 9 (peer auth):** refined — the engine sets **`IP_AllowWithoutAuth=1`** with **`Unencrypted=0`**: transport stays AES-256-GCM encrypted, but direct-IP peer **cert verification is relaxed** (the pinned-CA path is not strictly enforced for IP connections).
- **Integrity/anti-cheat:** **no native integrity check exists** — `onIntegrity*`/`onProcessPurchases`/`setenv` are pure cross-VM marshaling shims into the GraalVM `BL-Worker`/`BL-Studio` isolates; all trust gating is AOT-managed-code + server-authoritative.
- **Gap 14 (IDA session):** resolved — session `c3323093` reopened cleanly and drove the full decompilation in report 07.
- **New (07):** the engine runs **multiple named GraalVM isolates** (`BL-Worker`, `BL-Studio`); `gameLoopTick`@0x80C31B0 is a bare `RET` (the real loop is AOT Java); chunk parallelism is an EGL multi-context pool with a context-lost watchdog (`swapGameContext`@0x80B7220 → `restartApp()`).

**Still open** (inferences/single-sourced; none invalidate the overall picture):

1. **Path inconsistency in report 01 §6.** Report 01 lists data files under `natives/strings_all.txt`, `natives/bl_classes.txt`, `natives/bl_readable.txt`. Verified: these actually live in **`analysis/raw/`** (there is no `analysis/natives/` dir; `extracted/natives/` holds the `.so` files + copies of `bl_classes.txt`/`bl_readable.txt`). Reports 02/05/06 correctly use `raw/`. Treat `raw/` as canonical.

2. **`.text` size: 77 MB vs 73 MB.** Report 01 says 77 MB; report 03 says ~73 MB for the same span 0x386C000–0x8216928 (= 0x49AA928 = 77,179,688 bytes). Both are correct — 77 MB (decimal) ≈ 73.6 MiB (binary). Not a contradiction, just unit ambiguity; this index uses "~73 MiB / 77 MB".

3. **String counts differ by extractor.** Report 02 cites 317,684 strings (`raw/strings_all.txt`, GNU `strings`); report 03 cites 378,438 (IDA's string analysis). Different tools/min-length, not a discrepancy, but neither number is authoritative for "total embedded strings."

4. **Reflection-registered type count: "~714" (01) vs 405 (02).** Report 01 §4 says "~714 classes registered for reflection"; report 02 refines this to **719 `"name":` entries → 405 distinct FQ types** (after removing `<init>`/field entries). The 405 figure is the verified one; 01's 714 is a loose earlier count.

5. **GraalVM version coordinates are mixed.** Report 02 cites `GraalVM CE 23-dev+25.1`, JVMCI `23+25-jvmci-b01`, component `<version>23.1.4</version>`, and Java version 23. These are different coordinate systems (SVM build vs JVMCI vs Maven component vs JDK feature) and are mutually consistent, but "GraalVM 23" should not be read as a single SemVer. Worth a one-line normalization when finalized.

6. **AES-ECB asset encryption is a black-box *inference*, not confirmed.** Report 06 is explicit about this: mode (ECB vs CBC-fixed-IV), key size (128 vs 256), and the key bytes are inferred from size%16, constant header blocks, and cross-file block collisions + available JCE primitives. **Unverified.** Confirm by decompiling/hooking `Cipher.init`/`doFinal`/`SecretKeySpec.<init>` in the engine. (Note: the engine also bundles `AES/GCM/NoPadding` and `AES/CBC/ISO10126Padding` strings, but 06 attributes the latter to bundled xmlsec — so the asset cipher identity rests on the statistical argument.)

7. **App-packet-over-GNS structure is inferred from class names, not from decompiled serialization.** Report 05's model (MC-style `Packet` objects serialized to a custom blob, opaque to GNS) is reconstructed from reflection metadata + GNS's generic message API. The actual opcode table, framing, and field encoding have **not** been recovered (requires decompiling `PacketManagerCustom`/`network.Packet` in the engine — see §9).

8. **Live game server address is absent everywhere static.** Confirmed not in DEX and not in plaintext native strings (report 04 §5.3, report 05 §4). The claim that it is "resolved at runtime in `.svm_heap`" is the most likely explanation but is itself **unproven** until the heap/config is dumped. Same status for the pinned Ed25519 CA root key (report 05 §2.4 — code path identified, key bytes not extracted).

9. **Pinned root is "CraftRise's own CA" — assumed, not proven.** Report 05 establishes the certstore pins a single hardcoded root and that it is *not* Valve's live CA (no Steam backend); whether it is a CraftRise-issued CA vs the connection running in `AllowWithoutAuth` insecure/self-signed mode is **not determined**. Decompile the certstore singleton initializer to settle it.

10. **PairIP is single-sourced (report 04 only).** The PairIP app-protection wrapper is a first-order fact about the boot chain (it, not `MainApp`, is the manifest `application`) yet appears only in report 04; reports 01–03 omit it. It belongs in the architecture mental model (§2) and affects any dynamic-analysis plan. Cross-confirm the PairIP version and whether it does native-library integrity checks.

11. **iOS target is inferred, not tested.** `os.ios.GLES20/30`, `ios.Natives`, `game.AppleSign`, Game Center (`onGcAuthSuccess`) prove the *codebase* compiles for iOS, but no IPA was analyzed. "Dual Android+iOS" is well-supported but should be stated as inference until an IPA is examined.

12. **Some bundled-lib versions rest on single banner strings.** e.g. **Opus "1.6.1"** (no public Opus 1.6.1 existed as of the knowledge cutoff — likely a master/dev build string), libpng 1.6.48, Zstd 1.5.x range, abseil `lts_20240722`. Treat exact version banners as "as-embedded" — useful but worth a sanity check against upstream release history before citing externally.

13. **"jQuery 3.7.1 embedded" purpose unknown.** Report 02 flags a `var t="3.7.1"` jQuery bootstrap inside a bundled HTML/JS asset; whether it backs an in-game web UI, a JDK script-engine resource, or is dead bundled data is **unresolved**.

14. **Two IDA sessions, no persisted cross-reference for the engine deep-dive.** Report 03 uses session `c3323093` on `libblocklegends.so`; report 05 uses `17a34222` on `libGameNetworkingSockets.so`. The engine session completed auto-analysis but no functions were decompiled; report 07 work depends on that `.i64` still being valid/openable (the 229 MB binary had a ~47 s warmup that previously dropped the MCP connection — see report 03 header).

15. **Region-gating scope.** Report 04 establishes the *AntiAutoClicker popup scanner loop* is TR/AZ-gated, but explicitly notes the **native engine may still act on `onEmulatorUsage` regardless of region**. Do not over-read "TR/AZ-only" as "non-TR/AZ devices are unprotected" — the native and Integrity/PairIP layers are region-independent.

---

*Index compiled 2026-06-26 from reports 01–07 (all complete). Remaining work is dynamic/runtime: dump `.svm_heap` config (live server host, pinned CA key), recover the packet opcode table, and confirm the asset AES key — all best done by hooking the JNI boundary (`gnatives.GnsNative.send/receive`, `Cipher.doFinal`) on a real device.*
