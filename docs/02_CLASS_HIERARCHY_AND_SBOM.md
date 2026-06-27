# Block Legends v218.0.0 — Class Hierarchy, Reflection Metadata & SBOM

Reconstructed from GraalVM native-image metadata embedded in `libblocklegends.so`
(229,788,616 bytes / 219 MB AArch64, GraalVM CE 23-dev native-image, stripped).

Primary sources:
- `C:/Users/user/Desktop/blocklegends/analysis/raw/bl_classes.txt` — 7,376 class names
- `C:/Users/user/Desktop/blocklegends/analysis/raw/strings_all.txt` — 317,684 strings (8.4 MB)
- `C:/Users/user/Desktop/blocklegends/extracted/natives/libblocklegends.so`
- Generated here: `C:/Users/user/Desktop/blocklegends/analysis/raw/reflect_classes.txt` (405 reflectively/JNI-registered types)

---

## 1. PACKAGE / CLASS HIERARCHY

### 1.1 Obfuscation quantification

Every one of the 7,376 entries is under `net.blocklegends.*` (R8 was told to keep the
package root but flatten everything else). Breakdown:

| Bucket | Count | Notes |
|---|---:|---|
| Top-level obfuscated classes `net.blocklegends.<x>` (NF==3, name ≤ 3 chars) | **7,308** | `_0`,`a`,`a0`,`zzz`,`zyz` … 1–3 char R8 names. Single flat namespace. |
| Obfuscated classes inside `net.blocklegends.os.<x>` | **15** | `e16 e2k e7q efz j0 mr0 q86 qhv u2q ub0 v5a v7f vr8 vx7 zda` (platform glue) |
| **Total obfuscated** | **7,323** | |
| Readable / domain-named classes (incl. `MainActivity`,`MainApp`) | **~51** | enumerated in §1.3 |
| Build artifact leaked as a "class" | 1 | `net.blocklegends.blocklegends.pom.xml` (JavaCPP pom embedded as a resource entry) |

**Obfuscation rate = 7,323 / 7,376 = 99.28 %.** Only ~0.7 % of the type graph kept
human-readable names. The kept names are exactly the classes that must survive R8 because
they cross a boundary that defeats renaming: (a) JNI/native entry points, (b) GraalVM
reflection/serialization registration, (c) platform-OS shims whose names are referenced by
the host Android/iOS runtime. Everything else (the actual game/voxel/render/entity logic)
was renamed to the `{1-3 char}` bulk and then AOT-compiled into `.text`.

The `zz*`/`zy*`/`zx*` … tail (e.g. `zzz`, `zyz`, `zxz`) shows R8's sequential name
allocator ran through the entire 3-char alphabet space — consistent with ~7.3 k classes.

### 1.2 Second-level package map (`net.blocklegends.X`)

| Segment | #entries | Meaning |
|---|---:|---|
| `os` | 36 | OS abstraction layer (Android/iOS/shared). 21 readable + 15 obfuscated. |
| `cr` | 12 | CraftRise / Minecraft-protocol lineage (client, launcher, packets, obfuscates). |
| `world` | 4 | Minecraft-style world gen + storage. |
| `game` | 4 | Platform game services (sign-in, audio, voice). |
| `client` | 2 | `CraftRise` god-object + block-model renderer. |
| `firebase` | 2 | Firebase + Play Integrity. |
| `billing` | 2 | Google/Apple billing. |
| `ad` | 2 | Ad mediation. |
| `gameservices`,`natives`,`network`,`village` | 1 each | GP Games, native bridge, packet base, village registry. |
| `<1-3 char>` (direct) | 7,310 | obfuscated bulk (incl. `MainActivity`,`MainApp`). |

### 1.3 Complete list of readable game-domain classes (with inferred role)

**App entry / host:**
- `net.blocklegends.MainActivity` — Android `Activity` entry point (DEX shell → native).
- `net.blocklegends.MainApp` — `Application` subclass (process init, reflection-registered).
- `net.blocklegends.client.CraftRise` — central client/game god-object (reflection-registered).

**OS abstraction (`net.blocklegends.os.*`):**
- `os.shared.SHAREDOS` — common OS facade.
- `os.shared.GLES`, `GLES20`, `GLES30`, `GLES31` — OpenGL ES binding tiers (shared).
- `os.android.GLES20`, `os.android.GLES30` — Android GLES (JNI: 72 + 131 native methods).
- `os.ios.GLES20`, `os.ios.GLES30` — iOS GLES bindings (confirms dual Android+iOS target).
- `os.shared.EGLHelper`, `os.shared.EGLSurface`, `os.shared.SurfaceHolder`,
  `os.shared.GameSurfaceView` — EGL/surface lifecycle.
- `os.shared.WindowManager`, `os.shared.KeyboardManager`, `os.shared.VibrationManager`,
  `os.android.VibrationManager`, `os.ios.GLES*` — input/window/haptics.
- `os.shared.Bitmap` — image surface.
- `os.shared.AdManager` — ad surface (mirrors `ad.AdManager`).
- `os.{e16,e2k,e7q,efz,j0,mr0,q86,qhv,u2q,ub0,v5a,v7f,vr8,vx7,zda}` — 15 obfuscated
  per-platform GL/driver shims (kept in `os` for native lookup but names stripped).

**Native bridges (`net.blocklegends.natives` / `firebase` / `gameservices`):**
- `natives.GNatives` — **the master native bridge: 71 JNI methods** (game loop, input,
  lifecycle, billing, integrity, ads callbacks — see §5/§8).
- `firebase.FirebaseNatives` — Firebase/FCM bridge.
- `firebase.Integrity` — Google **Play Integrity** attestation bridge.
- `gameservices.GameServicesNatives` — Google Play Games / Game Center bridge.

**CraftRise / Minecraft lineage (`net.blocklegends.cr.*` + `world` + `village` + `client.renderer`)** — see §2.

**Platform game features (`net.blocklegends.game.*`):**
- `game.GoogleSign`, `game.AppleSign` — federated auth (Android + iOS).
- `game.GameSound` — SFX/music engine.
- `game.GameVoice` — in-game voice chat (drives Opus + WebRTC AEC3; see §4).

**Commerce/ads:**
- `billing.Billing`, `billing.BillingProducts` — IAP catalog.
- `ad.AdManager` — rewarded/interstitial ad mediation (`onUserEarnedReward`, `onGoogleAdLoaded`).

**Networking:**
- `network.Packet` — base wire packet (reflection-registered).

---

## 2. CraftRise / Minecraft LINEAGE

### 2.1 `net.blocklegends.cr.*` (CraftRise client protocol)

| Class | Implication |
|---|---|
| `cr.obfuscates.EnumConnectionState` | Minecraft-style connection FSM (HANDSHAKING/STATUS/LOGIN/PLAY). The package name `obfuscates` literally tells you CraftRise re-mapped MC vanilla classes. Reflection-registered. |
| `cr.client.manager.PacketManagerCustom` | Custom packet registry/dispatcher (replaces vanilla `NetHandler`). "Custom" = bespoke opcode table on top of the MC frame. |
| `cr.client.packets.PacketRequest` / `PacketResponse` | Generic request/response RPC pair layered over the MC stream. |
| `cr.client.packets.PacketPlayOutPlayerChange` | MC-vanilla naming convention (`PacketPlayOut…` = clientbound PLAY). Player state/skin/profile update. |
| `cr.client.game.GameType` | Vanilla `WorldSettings.GameType` analogue (survival/creative/adventure/spectator). |
| `cr.client.gamemode.GameMode` | Minigame mode selector (lobby/Creative Housing/etc — see §7). |
| `cr.client.gamemap.GameMap` | Per-match map/arena descriptor (server-pushed minigame maps). |
| `cr.client.animation.BedrockAnimation` | **Bedrock-format entity animations** — the client renders Bedrock `.json` animation rigs (cosmetics, mobs), not just Java models. |
| `cr.launcher.main.CRLauncher` | Embedded CraftRise launcher/bootstrap (account → server handoff). |
| `cr.launcher.utilsGlobal.IChatComponenet` | Chat component tree (MC `IChatComponent`; note original typo `Componenet` preserved). Drives the `&s<id>` styled-text system seen in localized strings. |

This is a CraftRise minigame-network client: a Minecraft-Java protocol core (`EnumConnectionState`,
`PacketPlayOut*`, `IChatComponent`, `GameType`) extended with a custom packet manager and a
request/response RPC, plus Bedrock animation support.

### 2.2 Minecraft world classes (`net.blocklegends.world.*`, `village`, `client.renderer`)

| Class | Implication |
|---|---|
| `world.gen.structure.StructureStart` | MC procedural structure anchor (start piece). |
| `world.gen.structure.StructureComponent` | MC structure piece/bounding-box component. |
| `world.gen.structure.MapGenStructureData` | Persisted structure-generation NBT/state. |
| `world.storage.MapData` | MC in-world map-item data (cartography). |
| `village.VillageCollection` | MC village registry (door/villager bookkeeping → AI, raids, trading). |
| `client.renderer.block.model.ModelBlock` | MC `ModelBlock` JSON block-model loader (Java resource-pack block models). |

Together these confirm a **full Minecraft-Java terrain/structure/village stack** plus
**Java resource-pack block models** AND **Bedrock entity animations** — i.e. the engine
ingests both MC ecosystems. World gen, villages, structures and map items are all present,
so this is a sandbox survival/creative engine, not just a thin minigame lobby client.

---

## 3. REFLECTION / JNI / SERIALIZATION CONFIG

The binary embeds the standard GraalVM metadata files (paths found as strings):
`META-INF/native-image/{reflect-config,jni-config,serialization-config,resource-config,proxy-config}.json`.

There are **719 `"name":` entries** total; after stripping method (`<init>`) and field
entries, **405 distinct fully-qualified types** are reflectively/JNI/serialization
registered. Full list saved to:
`C:/Users/user/Desktop/blocklegends/analysis/raw/reflect_classes.txt`

Category breakdown of the 405:

| Category | Count | Examples |
|---|---:|---|
| **Eclipse Collections** | 254 | `org.eclipse.collections.impl.bag.immutable.ImmutableBagFactoryImpl`, factory impls — primary data-structure library, serialized at runtime. |
| `com.sun.scenario` (JavaFX animation) | 13 | `com.sun.scenario.*` timeline/animation engine (UI tweening). |
| `net.blocklegends.cr.*` | 11 | all §2.1 classes (protocol types must reflect for (de)serialization). |
| `io.grpc.netty.shaded.*` | 9 | gRPC transport (Firebase/remote-config channel). |
| `net.blocklegends.os.*` | 6 | GLES20/30 (Android+iOS+shared) — JNI lookup. |
| `io.netty.util.*` | 6 | Netty internals (jctools queues, `AbstractByteBufAllocator`). |
| `java.lang.*` | 28 | `jdk.internal.misc.Unsafe`/`sun.misc.Unsafe`(`theUnsafe`,`invokeCleaner`), reflection core. |
| `net.blocklegends.world.*` | 4 | StructureStart/Component, MapGenStructureData, MapData. |
| `com.sun.{pisces,prism}` | 6 | JavaFX Pisces/Prism software rasterizer remnants. |
| `com.ibm.icu.*` | 3 | ICU4J calendar/currency service shims (i18n). |
| `com.google.common.*` | 2 | Guava. |
| `gnatives.*` | 8 | native wrapper classes (see §4): `GNative`,`GnsNative`,`OpusNative`,`ZstdNative`,`VoiceProcessingNative`,`KTXTexture`,`BatchObject`,`BatchShadow`. |
| `java.net.* / java.nio.* / sun.nio.*` | ~17 | sockets, channels, `Net`, datagram. |
| misc (`natives.*`,`ios.Natives`,`android.Natives`,`shared.nativec.*`,`build.Build`,`cr.gns.*`) | rest | JavaCPP-generated JNI shims. |

Runtime-critical takeaways: **Eclipse Collections is the dominant in-memory data library**
(254/405 entries — used for the entity/block/chunk maps); **`sun.misc.Unsafe` +
`invokeCleaner`** are registered (off-heap buffer management for chunk/mesh data); the
**CraftRise protocol types and world/structure types are reflection-registered**, meaning
they are (de)serialized at runtime (save files and/or wire) rather than purely
compiled-in.

---

## 4. SBOM — Bundled third-party libraries with versions

Evidence is strong because the binary leaks original compile-time source paths under
`/Users/yunus/AndroidStudioProjects/craftriseandroidstudio/native_lib/src/main/cpp/third_party/…`
plus version banners and JavaCPP-generated JNI wrappers (`gnatives.*`).

| Library | Version (evidence) | How bundled / purpose |
|---|---|---|
| **GraalVM Native Image / Substrate VM** | **CE 23-dev+25.1** (`com.oracle.svm.core.VM=GraalVM CE 23-dev+25.1`); JVMCI `23+25-jvmci-b01`; component `<version>23.1.4</version>` | AOT compiler producing `libblocklegends.so`. |
| **OpenJDK / JDK class library** | **Java 23** (`com.oracle.svm.core.VM.Java.Version=23`) | Java runtime baked in (`sun.nio`, `java.net`, JCE, JSSE, xmldsig). |
| **Kotlin runtime** | present (Kotlin/Java game logic) | stdlib compiled into bulk classes (no standalone version banner survived R8). |
| **Eclipse Collections** | **11.1.0** (`<version>11.1.0</version>`, `org.eclipse.collections:eclipse-collections-api`) | Primary collections lib (254 reflect entries). |
| **JavaCPP (bytedeco)** | embedded `pom.xml`, `Implementation-Vendor: Bytedeco`, group `com.oracle.svm.shadowed.org.bytedeco` | Generates all `gnatives.*` JNI wrappers; shadowed into SVM. |
| **bytedeco LLVM preset** | referenced (`org/bytedeco/llvm`) | build-time codegen toolchain. |
| **libpng** | **1.6.48** (`libpng version 1.6.48`) | PNG decode (`Java_…JNIPicture`, `gnatives` picture path). |
| **libjpeg / jpeg** | present (`jpeg` decode symbols) | JPEG decode. |
| **Little CMS (lcms2)** | present (`LittleCMS`, `lcms …virtual profile`, `Java_…NativeCMM`) | ICC color management. |
| **Opus** (incl. CELT + SILK) | **libopus 1.6.1** (`libopus 1.6.1`; `third_party/opus/{celt,silk,src}`) | Voice-chat codec (`gnatives.OpusNative`, 9 JNI methods). |
| **WebRTC audio-processing (AEC3)** | webrtc-audio-processing fork (`third_party/webrtc-audio-processing`, AEC3/AGC/AGC2/VAD modules) | Echo cancel + noise/gain for voice (`gnatives.VoiceProcessingNative`). |
| **Zstandard (zstd)** | 1.5.x family (`ZSTD_versionString`/`versionNumber`; version table 1.5.1–1.5.9) | Chunk/asset compression (`gnatives.ZstdNative` / `cr.zstd.ZstdNative`, 13 JNI methods). |
| **LZ4** | present (`LZ4_versionNumber`, `LZ4F_getVersion`) | Fast compression (frame API). |
| **abseil-cpp** | **LTS `lts_20240722`** (mangled `absl::lts_20240722::…`; `third_party/abseil-cpp`) | C++ base (strings/numbers) for GNS/WebRTC. |
| **Valve GameNetworkingSockets** | present (`GameNetworkingSockets_Init/_Kill`, `SteamAPI_ISteamNetworkingSockets_*`) | UDP reliable transport (`gnatives.GnsNative`, 24 JNI methods). |
| **KTX / Basis Universal** | libktx (`libktx.so`, `gnatives.KTXTexture` w/ `nativeTranscodeBasisU`) | GPU texture container + Basis transcode (ETC1S/UASTC). |
| **AMD FidelityFX Super Resolution (FSR)** | FSR 1.x EASU/RCAS (`FsrEasuCon`, `FsrRcasF`, resource `fsr/.*`) | Spatial upscaling shader pack. |
| **pffft** | (per project context; FFT) | audio/spectral FFT (paired with Opus/WebRTC). |
| **ICU4J** | present (`com.ibm.icu.*`; JDK ICU data `icudt51b`) | i18n / collation / calendars. |
| **Netty + gRPC (shaded)** | `io.netty.*`, `io.grpc.netty.shaded.*` (jctools queues) | gRPC channel for Firebase/remote services. |
| **Guava** | `com.google.common.*` | utility. |
| **JavaFX rasterizer remnants** | `com.sun.{prism,pisces,scenario,marlin}` (37 scenario + 4 prism + 4 pisces reflect entries) | software 2D rendering / animation timelines (UI). |
| **jQuery 3.7.1** | embedded JS (`var t="3.7.1"`, jQuery bootstrap) | inside a bundled HTML/JS asset (web UI or JDK script-engine resource). |
| **ANGLE (GLES→Vulkan/Metal)** | dynamic lib (per project context; no in-binary banner — separate `.so`) | GLES driver translation. |

Native C/C++ libs are all linked through **JavaCPP**, surfaced to Kotlin/Java as the
`gnatives.*` wrapper classes and reflected in `reflect-config`/`jni-config`.

---

## 5. PROTOCOL strings

- **Connection FSM**: `net.blocklegends.cr.obfuscates.EnumConnectionState` (Minecraft
  HANDSHAKING/STATUS/LOGIN/PLAY model; the literal state token strings are renamed/inlined,
  but the class is reflection-registered).
- **Packet types** (string-visible): `PacketManagerCustom`, `PacketPlayOutPlayerChange`,
  `PacketRequest`, `PacketResponse`, base `net.blocklegends.network.Packet`, plus
  `PacketSize` framing token. Naming follows MC `Packet{Play|Login|Status|Handshake}{In|Out}`.
- **Transport**: Valve GameNetworkingSockets. `gnatives.GnsNative` API surface (24 methods)
  is the wire layer:
  `init, connect, createServer, closeServer, serverAccept, closeConnection,
  createPollGroup/destroyPollGroup, setConnectionPollGroup, getServerPollGroup,
  send, sendBatch, receive, receiveBatch, pollGroupReceive, pollGroupWaitForMessages,
  waitForMessages, pollCallbacks, getConnectionQuality, getConnectionStatus, getState,
  getRemoteAddress, setConnectionSendRate, lastError`.
  Note **`createServer`/`serverAccept`** ⇒ the client can also *host* (P2P / local-host
  minigame server), not only connect.
- **Serialization keys / styled text**: CraftRise `&s<id>` chat style codes appear all over
  localized strings (e.g. `&s1118 Lobby Cosmetics Unlocked`, `&s208 Map Selection Right:
  Unlimited!`, `&s298 Sky Features Active`) — a numeric style-id token system driven by
  `IChatComponenet`.
- JSON RPC envelope evidence: `PacketRequest`/`PacketResponse` plus generic
  `"name"/"cat"/"ph"/"ts"/"pid"/"tid"` trace-event schema (Perfetto/Chrome-trace style).

---

## 6. CRYPTO & SECURITY

**Transport crypto** is provided by GameNetworkingSockets (Curve25519 key-exchange +
AES-GCM record encryption + Ed25519 cert signing are intrinsic to GNS; symbols present via
the `SteamAPI_ISteamNetworkingSockets_*` surface).

**JDK crypto stack baked in** (standard, from xmldsig/JSSE/SunJCE — *not* game-specific):
RSA-SHA256/512, RSASSA-PSS, ECDSA (SHA-1..512, RIPEMD-160), Ed25519, HmacSHA1/224/256/384/512,
AES/CBC, DESede, Camellia, SEED, RSA/ECB/OAEP & PKCS1, X509 cert resolvers, full TLS handshake
strings (ClientHello, CertificateVerify, ECDH/RSA KeyExchange, KeyUpdate). These back HTTPS/TLS
to Firebase/CraftRise backends.

**Anti-cheat / device-integrity / abuse controls** (game-specific, via `GNatives`):
- `onIntegritySuccess`, `onIntegrityFailure`, `onIntegrityThrow` + `firebase.Integrity`
  ⇒ **Google Play Integrity API** attestation (with `ticket_nonce`).
- `onEmulatorUsage` ⇒ **emulator detection** reported to native.
- `onGcAuthSuccess`, `onGcServerAuthCode` ⇒ Play-Games server-side auth code (account binding).
- Localized moderation strings present (Turkish `REKLAM`/advert, name-rules "non viola le
  nostre regole" = username profanity/violation enforcement) ⇒ server-side name/chat moderation.
- No standalone client cheat-scanner vocabulary (killaura/xray/etc.) surfaced — cheat
  enforcement appears server-authoritative (PacketManagerCustom + integrity gating), consistent
  with the CraftRise `rac`/"2" launcher-proof blocker noted in prior login-protocol analysis.

Tokens/sessions: `ticket_nonce`, `onFCMTokenReceived`/`onTokenReceived` (FCM + auth tokens),
TLS `"session id"` — handled by the JSSE stack.

---

## 7. CONFIG / FEATURE FLAGS

- **WebRTC field trials** (string-configurable AEC3 tuning): ~40+ `WebRTC-Aec3*` flags, e.g.
  `WebRTC-Aec3AecStateFullResetKillSwitch`, `WebRTC-Aec3EchoSaturationDetectionKillSwitch`,
  `WebRTC-Aec3EnforceConservativeHfSuppression`,
  `WebRTC-Aec3SuppressorDominantNearendEnrThresholdOverride`,
  `WebRTC-Aec3DelayEstimateSmoothingOverride` … (full AEC3/AGC2 trial set) — parsed via
  `rtc_base/experiments/field_trial_parser.cc`.
- **Game-mode / product flags** (from localized config strings): `Lobby`, `Creative Housing`
  (free-build housing mode), `Lobby Cosmetics`, map-selection rights, "Sky Features",
  cosmetic items (`Adventure Hat`, `Blue Adventure`). Indicates a lobby + minigame-modes +
  cosmetics/housing live-ops model.
- **Remote config / messaging**: Firebase + FCM (`onFCMTokenReceived`), gRPC channel
  (`io.grpc.netty.shaded`) — remote-config/feature-gating transport.
- **GL pipeline contexts** (engine threading config, `GNatives`): named EGL contexts
  `splash, display, game, async, chunk, chunkWaiter, load1..load4` (each with a `*SurfaceContext`
  variant) ⇒ a multi-context, multi-threaded streaming renderer (separate chunk-build,
  async-upload, and staged-load GL contexts).

---

## 8. EMBEDDED BUILD / DEV ARTIFACTS

- **Developer / build root**: `/Users/yunus/AndroidStudioProjects/craftriseandroidstudio/`
  (macOS, user **`yunus`**). 91 distinct absolute source paths leaked — the only user home is
  `/Users/yunus/`.
- **Native source layout** (`native_lib/src/main/cpp/`):
  - `third_party/opus/{celt,silk,silk/float,src}` — full Opus tree.
  - `third_party/webrtc-audio-processing/webrtc/{common_audio,modules/audio_processing/{aec3,agc,agc2,vad},rtc_base/experiments,system_wrappers}`.
  - `third_party/abseil-cpp/absl/strings/`.
- **JavaCPP build descriptors**: a complete JavaCPP `pom.xml` is embedded (leaked as the
  pseudo-class `net.blocklegends.blocklegends.pom.xml`), exposing every `javacpp.*` mojo
  parameter and the `com.oracle.svm.shadowed.org.bytedeco` shading.
- **Native JNI surface (export census)**: ~over a hundred distinct `Java_*` exports. Top:
  `GLES30` (131), `GLES20` (72), `GNatives` (71 game-bridge callbacks), `GnsNative` (24),
  `ZstdNative` (13 ×2 under both `gnatives` and legacy `cr.zstd`), `OpusNative` (9),
  `VoiceProcessingNative` (6), `KTXTexture` (6), `GNative` (8) — plus the JDK's own
  `sun.nio.*`/`java.net.*`/`java.io.*` natives compiled in.
- **Legacy-namespace tell**: both `cr.zstd.ZstdNative` and `gnatives.ZstdNative` exist ⇒
  the project was refactored from a `cr.*`-rooted native package into `gnatives.*`, leaving
  the old CraftRise namespace fossilized in the binary.
- **iOS co-target confirmed**: `os.ios.GLES20/30` + `ios.Natives` + `game.AppleSign` +
  Game Center (`onGcAuthSuccess`) ⇒ the same Kotlin/native codebase builds for iOS, not just Android.

---

### Output files
- `C:/Users/user/Desktop/blocklegends/analysis/raw/reflect_classes.txt` — 405 reflectively/JNI/serialization-registered FQ types.
- This report: `C:/Users/user/Desktop/blocklegends/analysis/02_CLASS_HIERARCHY_AND_SBOM.md`.
