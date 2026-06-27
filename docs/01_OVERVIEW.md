# Block Legends — Reverse Engineering Overview

> Target: `Block Legends — Sandbox PvP` v218.0.0 (`net.blocklegends`)
> Package: XAPK (split APK bundle) from APKPure, total 471 MB
> Analysis date: 2026-06-26
> Tooling: IDA Pro (idalib MCP), jadx 1.5.0, custom string/ELF extractors

---

## 1. TL;DR — What this game actually is

**Block Legends is a Minecraft-Java-derived voxel sandbox game for mobile (Android + iOS), with direct CraftRise lineage, whose entire game logic is written in a JVM language (Java/Kotlin) and Ahead-Of-Time compiled to a single native ARM64 shared library via GraalVM `native-image`.**

This is an unusual and sophisticated architecture. There is almost **no game logic in the DEX/Java (Android) layer** — the Android side is only a thin launcher/Activity shell plus Google/Firebase SDKs. The real game (world, rendering, networking, physics, voice) lives inside `libblocklegends.so`, a 229 MB GraalVM Native Image.

### Direct relevance to prior work
The codebase is **CraftRise-derived**: classes such as `net.blocklegends.client.CraftRise`, the entire `net.blocklegends.cr.*` (CraftRise) package tree (`CRLauncher`, `PacketManagerCustom`, `PacketPlayOutPlayerChange`, `EnumConnectionState`, `IChatComponenet`), and the build path `/Users/yunus/AndroidStudioProjects/craftriseandroidstudio/` tie this directly to the CraftRise / SonOyuncu Minecraft-protocol work. The networking uses the classic Minecraft Java protocol idioms (`PacketPlayOut*`, `EnumConnectionState` = HANDSHAKING/STATUS/LOGIN/PLAY).

---

## 2. Package / split-APK layout

| Split APK | Size | Contents |
|---|---|---|
| `net.blocklegends.apk` | 28 MB | **Base**: 5 DEX (`classes.dex`..`classes5.dex`, ~27 MB Kotlin), `AndroidManifest.xml`, `resources.arsc`, SDK property files |
| `config.arm64_v8a.apk` | 241 MB | **Native libs** (`lib/arm64-v8a/*.so`) — the engine |
| `game_assets.apk` | 183 MB | 2969 content-addressed objects under `assets/objects/` (BNDLTOOL bundle) |
| `config.en.apk` | 60 KB | English string resources |
| `config.mdpi.apk` | (in xapk) | mdpi density resources |

Only `arm64-v8a` ABI is shipped (no armeabi-v7a / x86). `min_sdk = 32` (Android 12), `target_sdk = 37`.

---

## 3. Native library inventory (`lib/arm64-v8a/`)

| Library | Uncompressed | Role |
|---|---|---|
| **`libblocklegends.so`** | **229 MB** | **The game** — GraalVM Native Image (Java/Kotlin AOT) + bundled C/C++ via JavaCPP |
| `libGameNetworkingSockets.so` | 9.6 MB | Valve **GameNetworkingSockets** — UDP-based reliable multiplayer transport |
| `libGLESv2_angle.so` | 6.3 MB | Google **ANGLE** — GLES2 → native backend translation |
| `libEGL_angle.so` | 315 KB | ANGLE EGL |
| `libGLESv1_CM_angle.so` | 286 KB | ANGLE GLES1 |
| `libktx.so` | 2.8 MB | Khronos **KTX** GPU texture container |
| `libc++_shared.so` | 1.3 MB | LLVM libc++ runtime |
| `libcrashlytics*.so` (×4) | ~1.3 MB | Firebase Crashlytics native crash handler |
| `libdatastore_shared_counter.so` | 7.8 KB | AndroidX DataStore multi-process counter |

### `libblocklegends.so` ELF structure
- AArch64, 64-bit, **PIE / DYN**, **stripped** (only `.dynsym`, no `.symtab`).
- `.text` = **77 MB** of code; `.svm_heap` = **88.8 MB** (GraalVM SubstrateVM pre-initialized image heap); `.rodata` = 4.4 MB; `.rela.dyn` = 58 MB relocations.
- 4,773 dynamic symbols (4,391 exports / 381 imports).
- GraalVM signatures: `graal_get_current_thread`, `IsolateEnterStub__CEntryPointNativeFunctions__getIsolate`, `.svm_heap`, embedded JDK natives (`Java_sun_nio_ch_*`, `Java_java_net_*`, `JVM_*`, `VerifyFixClassname`).

---

## 4. Technology stack

- **Game language**: Java/Kotlin, **obfuscated** (R8/ProGuard-style minification → `net.blocklegends.{a-z}{1-3}` package/class names). ~714 classes registered for reflection.
- **AOT compiler**: **GraalVM CE 23-dev** `native-image` (SubstrateVM).
- **Native C/C++ bridge**: **JavaCPP / bytedeco** (`com.oracle.svm.shadowed.org.bytedeco.javacpp`) wraps the bundled C libraries into JVM-callable code.
- **Collections**: **Eclipse Collections** (primitive collections — memory-efficient, typical for voxel engines).
- **Build origin**: Android Studio project `craftriseandroidstudio` by developer **"yunus"** (macOS); NDK module `native_lib/src/main/cpp` with `third_party/`.

### Bundled C/C++ third-party libraries (statically linked into `libblocklegends.so`)
abseil-cpp · **Opus** (CELT + SILK) · **WebRTC** (AEC3 echo cancellation, AGC, clipping predictor — voice DSP) · libpng · libjpeg · **Little CMS (lcms2)** · **LZ4** · **Zstandard** · pffft (FFT) · **AMD FidelityFX Super Resolution (FSR)** upscaling.

### Dynamically linked
ANGLE (GLES), GameNetworkingSockets, Crashlytics.

---

## 5. Subsystem map (from JNI exports + readable class names)

| Subsystem | Evidence |
|---|---|
| **Rendering** | `net.blocklegends.os.shared.GLES20/30/31`, ANGLE, `client.renderer.block.model.ModelBlock`, FSR (`FsrRcasGetConsts`), KTX textures, `EGLHelper`/`EGLSurface`/`GameSurfaceView` |
| **World / terrain** | `world.gen.structure.{StructureStart,StructureComponent,MapGenStructureData}`, `world.storage.MapData`, `village.VillageCollection` (Minecraft-style worldgen) |
| **Chunk streaming** | JNI `GNatives.setChunkContext` / `setChunkWaiterContext` / `makeChunkWaiterContext` |
| **Game loop** | JNI `GLES30.gameLoopTick`, `GNatives.setGameContext`, `onSurfaceResume` |
| **Networking** | `network.Packet`, `cr.client.packets.{PacketPlayOutPlayerChange,PacketRequest,PacketResponse}`, `cr.client.manager.PacketManagerCustom`, `cr.obfuscates.EnumConnectionState`, `gnatives.GnsNative` → GameNetworkingSockets |
| **Voice chat** | `gnatives.OpusNative`, `gnatives.VoiceProcessingNative` (createProcessor), WebRTC AEC3/AGC, `voiceStopPlayback` |
| **Compression** | `gnatives.ZstdNative` (+ `cr.zstd.ZstdNative`), LZ4 |
| **OS abstraction** | `os.shared.{SHAREDOS,WindowManager,KeyboardManager,VibrationManager,Bitmap,AdManager}`, `os.android.*`, `os.ios.*` (shared Android+iOS layer) |
| **Monetization** | `os.shared.AdManager`, `billing.*`, Google Play Billing, `logSpendVirtualCurrency` (analytics) |
| **Platform services** | `firebase.*`, `gameservices.*` (Google Play Games v2), Firebase Analytics/Crashlytics/Messaging |

---

## 6. Analysis artifacts in this folder (`Desktop\blocklegends\analysis\`)
- `00_INDEX.md` — **master index + executive summary** (read this first)
- `01_OVERVIEW.md` — this file
- `02_CLASS_HIERARCHY_AND_SBOM.md` — package tree, obfuscation map, library versions
- `03_native_ida_survey.md` — IDA function landscape (47,097 funcs) of `libblocklegends.so`
- `04_DEX_ANDROID_AND_SECURITY.md` — Android shell, PairIP, anti-cheat, integrity, servers, billing, auth
- `05_NETWORKING_PROTOCOL.md` — GameNetworkingSockets transport + crypto + CraftRise app protocol
- `06_ASSETS_AND_RESOURCES.md` — content-addressed AES-encrypted asset store, IAP, resources
- `07_NATIVE_DEEP_DIVE.md` — GraalVM bootstrap, game loop, chunk/render, native bridges (+ `07a/07b/07c` sections)
- `raw/strings_all.txt` — 317 K extracted strings from `libblocklegends.so`
- `raw/bl_classes.txt` — 7,376 `net.blocklegends.*` class names; `raw/bl_readable.txt` — readable subset
- `jadx_base/` — 17,962 decompiled DEX Java files (Android shell)
