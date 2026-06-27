# Block Legends v218.0.0 — DEX / Android Shell & Security Deep-Dive

Target: `net.blocklegends` (Block Legends), versionCode **218**, versionName **218.0.0**, CraftRise-lineage voxel sandbox.
Scope of this report: the readable Android/Java shell (jadx output) at
`C:/Users/user/Desktop/blocklegends/analysis/jadx_base/sources/net/blocklegends` plus
`C:/Users/user/Desktop/blocklegends/analysis/jadx_base/resources`.
The real game logic is the GraalVM-AOT `libblocklegends.so`; the DEX side is a thin host that (a) loads/boots the native engine, (b) bridges Android subsystems over JNI, and (c) does the anti-cheat / anti-tamper checks.

Top-level files: `MainActivity.java` (113 KB), `MainApp.java` (38 KB), `FCMService.java`, `FlexibleUpdateHelper.java`, `R.java` (793 KB, generated).
The whole app is wrapped by **PairIP** (Google Play app-protection): `application android:name="com.pairip.application.Application"`, `com.pairip.licensecheck.LicenseActivity`, `com.android.vending.CHECK_LICENSE` permission, and a `stamp-cert-sha256` resource. PairIP is the outermost layer that decrypts/loads the real DEX and enforces a Play license check before `MainApp.onCreate` runs.

---

## 1. App lifecycle & native engine bootstrap

### 1.1 Process start → `MainApp` (Application)
`MainApp extends Application` (`net/blocklegends/MainApp.java`). `onCreate()` (line 717):
1. `app = this` (global singleton via `getApp()` / `getAppSafe()`).
2. Per-process WebView data-dir suffix (`WebView.setDataDirectorySuffix`) + `cleanupStaleWebViewDirs` (kills stale `app_webview_p<pid>` dirs after 60 s).
3. `installFirebaseSafetyHandler()` — installs a `Thread.UncaughtExceptionHandler` that **swallows** Firebase-Sessions DataStore / `/proc/self/task/.../comm` crashes (`isFirebaseInfrastructureCrash`).
4. `prepareFirebaseSessionsDataStoreFiles()` + `loadConfig()` (reads `<filesDir>/.blocklegends_config.properties`; only key today is `vulkan_enabled=true`, `config_version=1`).
5. `appInitialized = true`.
6. Deferred (8 s, main handler): **TikTok Business SDK** init — `TikTokBusinessSdk.TTConfig(this,"TTaLmfWUaDBm8ZZbPSFhse4pMu2vZ3ta").setAppId("net.blocklegends").setTTAppId("7623335695234498567").enableAutoIapTrack()` (line 641).
7. `InstallReferrerTracker.capture(this)` (Play Install-Referrer → Firebase).
8. Background thread "Firebase-Init-Thread": `initializeFirebase()` (FirebaseApp + Crashlytics, **collection set true here despite manifest `firebase_crashlytics_collection_enabled=false`**) then `initializeFCM()`.
9. Background thread "WebView-Warmup": `WebView.startSafeBrowsing`.

FCM: `initializeFCM()` fetches token, caches to `MainApp.fcmTokenCached`, and after a 30 s delay subscribes to topics `all` and `lang_<language>`. Token is pushed to native via `GNatives.onFCMTokenReceived` (see `sendFCMTokenToServer()`, line 533) — called from `GNatives.requestNotificationPermission()`, i.e. the native engine asks for the token.

### 1.2 Native library load order (`MainActivity`)
- `initANGLE()` (MainActivity:658): `System.loadLibrary("GLESv2_angle")` then `System.loadLibrary("EGL_angle")` — ANGLE provides GLES-over-Vulkan/native.
- `loadNativeLibraryFromAssetPack()` (MainActivity:1161) → `NativeLibLoader.loadAsync(this, AnonymousClass1)`.
- `NativeLibLoader` (`assets/NativeLibLoader.java`): `System.loadLibrary("blocklegends")` on a worker thread (`loadAsync`) or synchronously (`loadSync`); guarded by an `AtomicBoolean loaded`. This triggers the GraalVM `JNI_OnLoad` inside `libblocklegends.so` (isolate/heap creation happens native-side; no isolate handle is visible in DEX).
- On success (`AnonymousClass1.onSuccess`, MainActivity:204): `nativeLibrariesLoaded = true` → `GameAssetsExtractor.extractIfNeeded(...)` → on complete `initGame()`.
- The native lib ships in a **fused split module** `native_lib` (`com.android.dynamic.apk.fused.modules = base,native_lib`, manifest:532) with `android:extractNativeLibs="false"` (mapped from APK, not unpacked).

### 1.3 `initGame()` / surface wiring
`initGame()` is decompiler-stubbed (MainActivity:679, "Method dump skipped"), but the surrounding wiring is intact:
- `GameSurfaceView` (`ui/GameSurfaceView.java`) is a `SurfaceView`; its callback drives native lifecycle: `GNatives.onSurfaceChanged/onSurfaceDestroy/onSurfacePause/onSurfaceResume/onSurfaceDestroyed`. It creates `activity.contexts = new GLContexts()` (line 129).
- `GLContexts` (`ui/GLContexts.java`) holds the JNI `native void setSurface(Surface)` and `setupContext()`/`setupSurfaces()` push `REFRESH_RATE`, `SCREEN_X/Y` via `GNatives.setenv(...)`.
- The engine owns **many** EGL contexts/surfaces, each handed in from Java (see §2): game, splash, async, chunk, chunk-waiter, load1–load4. This is a multi-threaded GL renderer fed by Java-created `EGLContext`/`EGLSurface` handles.

### 1.4 FCMService
`FCMService extends FirebaseMessagingService` (`FCMService.java`). Channel `blocklegends_notifications`. `onMessageReceived` builds local notifications only when `!isAppInForeground`; data actions handled: `update`, `event`, `open_game`. `onNewToken` updates `MainApp.fcmTokenCached`. Exported=false; only reachable via `com.google.firebase.MESSAGING_EVENT`.

### 1.5 FlexibleUpdateHelper
`FlexibleUpdateHelper.java` wraps Play Core in-app updates (`AppUpdateManagerFactory`). `checkForUpdate()` starts a FLEXIBLE (`type 0`) update flow (request code 2001) only when `availableVersionCode > currentVersionCode` (guards against Play "false positive"); on `DOWNLOADED` shows a Snackbar "BLOCK LEGENDS! / GO!" to `completeUpdate()`. Instantiated in MainActivity around line 1559, polled in `onResume`.

---

## 2. Full JNI bridge inventory (`net.blocklegends.natives.GNatives` + others)

The Java↔native boundary is centralized in **`natives/GNatives.java`**. Two directions:
**(A) `native` methods = Java→native calls into the GraalVM engine.**
**(B) plain static methods = native→Java upcalls** (the engine calls these by name through JNI; many have no DEX caller, confirming they are invoked only from native).

### 2.A — `native` declarations (Java → native)

| Method (GNatives unless noted) | Sig | Group |
|---|---|---|
| `checkAndBindSurface` | ()Z | Surface/EGL |
| `gameLoopTick` | ()V | Lifecycle/loop |
| `getCachedGpuRenderer` | ()String | GPU info |
| `isGpuInfoCached` | ()Z | GPU info |
| `getLanguageKey(String)` | i18n lookup in native | i18n |
| `nextStep(String)` | ()V | Engine state machine / loading steps |
| `onActionDown(f,f,i)` `onActionUp(i,i,i)` `onActionMove(i,f,f)` `onActionCancel(i,i,i)` `onActionPointerDown(f,f,i)` `onActionPointerUp(i,i,i)` | touch | **Input** |
| `onMouseMove(f,f,i)` | mouse | Input |
| `onKeyDown(i,String,char)` `onKeyUp(i)` `onKeyboardListener(i,i,i,i,i)` | keyboard | Input |
| `onSensorChanged(i,float[])` | sensor sample → engine | Input/gyro |
| `onDisplayChanged(i)` | rotation | Input/display |
| `onActivityRestart` `onActivityStop` `onAppBackground` `onAppForeground(Object)` | ()V | **Lifecycle** |
| `onSaveInstanceState` `onRestoreInstanceState` | ()V | Lifecycle |
| `onResetContext` | ()Z | Lifecycle/GL reset |
| `onNetworkChanged` | ()V | Network |
| `onSurfaceChanged(i,i)` `onSurfaceDestroy` `onSurfaceDestroyed` `onSurfacePause` `onSurfaceResume` | ()V | Surface |
| `setSurfaceContext(EGLSurface)` `setUpdatedSurface(EGLSurface)` | EGL |
| `setDisplayContext(EGLDisplay)` | EGL |
| `setGameContext(EGLContext)` `setGameSurfaceContext(EGLSurface)` | EGL (main render ctx) |
| `setSplashContext` `setSplashSurfaceContext` | EGL (splash) |
| `setAsyncContext` `setAsyncSurfaceContext` | EGL (async upload) |
| `setChunkContext` `setChunkSurfaceContext` `setChunkWaiterContext` `setChunkWaiterSurfaceContext` | EGL (chunk meshing threads) |
| `setLoad1Context`…`setLoad4Context` + `setLoad1SurfaceContext`…`setLoad4SurfaceContext` | EGL (4 loader threads) |
| `setenv(String,String)` | push env vars to native (`SCREEN_X/Y`,`REFRESH_RATE`,`FORCE_PERFORMANCE_MODE`,…) | Config |
| `updatePositions(i,float[],String)` | ()V | Engine data push (entity/positions) |
| `setOnCompleteListener(i)` `setOnLoadCompleteListener(i,i)` | sound load callbacks bridged back | Audio |
| `onBillingSetupComplete()` `onProcessPurchases(String)` | **Billing** (Java→native results) |
| `onGoogleAdLoaded(Z)` `onUserEarnedReward(j,Z,String,i)` | **Ads** (results) |
| `onTokenReceived(String,String)` `onGoogleSignFailed(String,String)` `onAppleSignFailed(String,String)` | **Auth** (token/nonce or failure) |
| `onGcAuthSuccess(String,String)` `onGcServerAuthCode(String)` | **Play Games** results |
| `onFCMTokenReceived(String)` | **FCM** token push |
| `onIntegritySuccess(String,long)` `onIntegrityFailure(String,long)` `onIntegrityThrow(String,long)` | **Play Integrity** (token/err + requestId) |
| `onEmulatorUsage()` | **anti-cheat** signal to engine |
| `onCustomPopupEvent(long)` | native popup callback |
| `GLContexts.setSurface(Surface)` *(instance native)* | binds Surface to native EGL |
| `VibrationManager.nativeInit()` *(private static native, `os/android/VibrationManager.java`)* | haptics bridge |

> Note: `onBillingSetupComplete`, `onProcessPurchases`, `onGoogleAdLoaded`, `onUserEarnedReward`, `onTokenReceived`, `onGc*`, `onIntegrity*`, `onFCMTokenReceived`, `onEmulatorUsage`, `onCustomPopupEvent` are **`native`** — i.e. Java collects an Android result and pushes it *into* the engine. The engine then drives further logic (server packets, UI) itself.

### 2.B — native → Java upcalls (no/foreign DEX caller; invoked by the engine)
Grouped by subsystem (all in `GNatives` unless noted):
- **Keyboard/UI:** `openKeyboard`, `closeKeyboard`, `openLink(String)`, `openStore`, `takeScreenshot`, `setThreadPriorityDisplay`, `setGyroscopeOrientationLock(int)`, `showCustomPopup(long,String,String,boolean)`, `showEmulatorPopup`, `showAutoClickerPopup(DetectionResult)`.
- **Device info:** `getAndroidVersion`, `getAppVersion`, `getDeviceModel`, `getDefaultCountryCode`, `getSimCountryCode`, `isPowerSavingActive`, `isThermalStateHigh`.
- **Gyro look-controls (in-game, NOT anti-cheat):** `nativeInitGyroController`, `nativeDisableGyroController`, `nativeIsSupportedSensorService`, `nativeUpdateCoordinateSystem` → delegate to `ui/GyroController.java`, which registers a `SensorManager` `TYPE_GYROSCOPE`/`TYPE_ROTATION_VECTOR` listener and feeds `GNatives.onSensorChanged`/`onDisplayChanged`.
- **Vulkan toggle:** `nativeIsVulkanEnabled`, `nativeSetVulkanEnabled` → `MainApp` config.
- **Anti-cheat / emulator:** `startGyroscope` (confirmed only-native-invoked: sole DEX references are its own body, GNatives:806) → spins up `GyroscopeEC`.
- **Lifecycle:** `resetContext`/`restartApp(Context)` → `MainActivity.restartApplication()`.
- **Integrity:** `firebase/Integrity.run(String nonce, long requestId)` — **no DEX caller** (engine-invoked), see §4.
- **Analytics/Review:** `firebase/FirebaseNatives` — `logEvent`, `logLevelUp`, `logEarnVirtualCurrency`, `logSpendVirtualCurrency`, `setUserProperty`, `showAppReviewPopup`, `startPerformanceTrace`/`stopPerformanceTrace` (no-ops).
- **TikTok:** `GNatives.trackTikTokEvent(String,String)`.
- **Voice:** `game/GameVoice` (`startCapture`,`stopCapture`,`readCapture`,`startPlayback`,`stopPlayback`,`writePlayback`,`getPlaybackDelayMs`,`setNativeAecActive`,`hasRecordPermission`,`requestRecordPermission`,…). See §8.
- **Sound:** `game/GameSound` (`init`,`load`,`play`,`playMedia`,`stop`,`setVolume`,`setRate`,`setLoop`,`setPriority`,`pause`,`resume`,`autoPause`,`autoResume`,`shutdown`).
- **Ads:** `ad/AdManager` (`startAdManager`,`init`,`loadRewardedAd`,`rewardAdRequest`,`isLoadedGoogleAd`,`openAdPrivacyOptions`).
- **Billing:** `billing/Billing` (`startBillingService`,`launchBillingFlow`,`acknowledgePurchase`,…) + `BillingProducts.openBuyScreen`.
- **Auth:** `game/GoogleSign.openLogin/signOut`, `game/AppleSign.openLogin`, `gameservices/GameServicesNatives.pgs_*`.

This map *is* the engine's complete Android attack surface: anything reachable from native must go through one of these Java methods.

---

## 3. Anti-cheat / anti-tamper

Two independent subsystems: **`utils/AntiAutoClickerUtil`** (static signature scanner) and **`ui/GyroscopeEC`** (emulator/sensor challenge). Both surface to the user via `GNatives.showAutoClickerPopup` / `showEmulatorPopup` and to the engine via `GNatives.onEmulatorUsage`.

### 3.1 `AntiAutoClickerUtil.detect(Context)` (cached 60 s; `utils/AntiAutoClickerUtil.java`)
Detection reasons (`enum Reason`) and how each is found:
- **SHIZUKU_PACKAGE** — `getPackageInfo` of `moe.shizuku.privileged.api`, `moe.shizuku.manager`, `moe.shizuku.api`.
- **ROOT_PACKAGE** — `com.topjohnwu.magisk`, `eu.chainfire.supersu`, `com.koushikdutta.superuser`, `com.noshufou.android.su`.
- **KNOWN_AUTOCLICKER_PACKAGE** — `com.panda.touch`, `com.zjx.gamebox`, `com.easytouch.assistivetouch`, `com.zjx.ztezscreenshot`, `com.autoclicker.clicker`, `com.truongauto.autoclicker`, `com.kok_autoclicker`, `com.auto.clicker`, `com.maxwell.autoclick`.
- **LSPOSED_PACKAGE** — `org.lsposed.manager`, `org.lsposed.lspd`.
- **SUSPICIOUS_ACCESSIBILITY_ENABLED** — enumerates enabled accessibility services (`AccessibilityManager.getEnabledAccessibilityServiceList`) + `Settings.Secure "enabled_accessibility_services"`; flags any in the autoclicker list or whose pkg/name/description contains `auto`+`click`, `autoclick(er)`, `macro`, `tapper`, `tap autom`.
- **XPOSED_PRESENT** — `Class.forName("de.robv.android.xposed.XposedBridge")`.
- **FRIDA_SUSPECTED** — reads `/proc/self/maps`, counts lines containing `frida` / `gum-js` / `libfrida`; ≥2 hits ⇒ flagged (`looksLikeFridaPresent`).
- **TEST_KEYS_BUILD** — `Build.TAGS` contains `test-keys`.
- **SU_BINARY_FOUND** — checks `/system/bin/su`, `/system/xbin/su`, `/sbin/su`, `/system/app/Superuser.apk`, `/system/app/SuperSU.apk`, `/system/bin/.ext/.su`, `/system/usr/we-need-root/su-backup`.
- **MONKEY_OR_TESTHARNESS** — `ActivityManager.isUserAMonkey()` or `isRunningInUserTestHarness()`.

Note the manifest `<queries>` (lines 33–51) pre-declares the autoclicker/Shizuku/root/LSPosed packages so `getPackageInfo` works under Android 11+ package visibility. (Discrepancy: code lists `uit.quocnguyen.autoclicker` in manifest but not in `KNOWN_AUTOCLICKER_PACKAGES`.)

### 3.2 Scheduling & **region gating** (important)
`AntiAutoClickerScheduler` runs `detect()` every 30 s (daemon thread), UI-throttled to 60 s, calling `GNatives.showAutoClickerPopup(result)` on detect.
**Crucial:** in `MainActivity.onStart()` (line 2256) the scheduler is started **only if `cachedIsTurkishOrAzeri`** is true (`isTurkishOrAzeriUser()` via network/SIM/locale country = TR/AZ, MainActivity:~990–1024). So the package/root/Frida popup scanner is **TR/AZ-only**; non-TR users do not get the AntiAutoClicker popup loop. (The native engine may still react to `onEmulatorUsage`.)

### 3.3 On-detection behavior
`GNatives.buildDetectionMessage` builds a TR/EN bilingual list of reasons + offending app labels and `GNatives.lambda$showAutoClickerPopup$7` shows a **non-cancelable** `AlertDialog` "Suspicious Activity Detected"; pressing OK calls `MainActivity.finish()` (hard exit). Suppressed if `GyroscopeEC.isProbablyEByBuild()` (i.e. emulator path takes priority).

### 3.4 `GyroscopeEC` — emulator / sensor-spoof challenge (`ui/GyroscopeEC.java`)
Purpose: prove real motion sensors exist and produce real noise (emulators have static/absent sensors).
- Sensors: `TYPE_GYROSCOPE(4)`, `TYPE_LINEAR_ACCELERATION(10)`, `TYPE_ACCELEROMETER(1)`.
- **Passive phase** 12 s; sliding windows of 256 magnitude samples; computes mean/std/`uniqueCount`.
- "Static" thresholds: gyro `std ≤ 0.004` & `uniqueCount ≤ 6`; accel `std ≤ 0.035` & `uniqueCount ≤ 6` — i.e. suspiciously flat/quantized signal ⇒ likely emulator.
- If static detected (after ≥6 s, once), triggers **active haptic challenge**: a vibration waveform (`createWaveform({0,120,80,120,80,120},…)`) then a 5 s active window expecting induced motion (`triggerHapticThenActiveChallenge` → `startActiveChallenge(5000)`).
- `isProbablyEByBuild()` (static): brand/device/fingerprint `generic`/`unknown`, `Build.HARDWARE` `goldfish`/`ranchu`, models `google_sdk`/`Emulator`/`Android SDK built for x86`, `Genymotion`, products `sdk*`,`vbox86p`,`nox`,`bluestacks`,`ldplayer`,`simulator`,`emulator`, and `SystemProperties.get("ro.kernel.qemu")=="1"`.
- **Outcome:** `startGyroscope()` (GNatives:806) installs a `DetectionCallback`; `onFailed(...)` ⇒ `GNatives.onEmulatorUsage()` (native decides), and the engine drives `GNatives.showEmulatorPopup()` → non-cancelable "Emulator Detected" dialog → OK calls `finish()`. No motion sensors + emulator build traces ⇒ permanently disabled / failed.

### 3.5 Capture-side emulator block
`GameVoice.shouldBlockCaptureOnThisRuntime()` (stubbed, but logs reveal intent at line 957/977) blocks `AudioRecord` on emulator/QEMU runtimes ("to avoid HAL pcm_read stalls"), keyed off `Build.MANUFACTURER/BRAND/MODEL/DEVICE/PRODUCT/HARDWARE` and the same `android sdk built for x86` heuristic.

---

## 4. Integrity (Play Integrity API) & anti-debug

`firebase/Integrity.java`:
- `GOOGLE_CLOUD_PROJECT_NUMBER = Long.parseLong(R.string.game_services_project_id)` = **810329497878**.
- Flow is **engine-driven**: native calls `Integrity.run(String nonce, long requestId)` (no DEX caller). It builds `IntegrityTokenRequest.builder().setNonce(nonce).setCloudProjectNumber(810329497878)` via `IntegrityManagerFactory.create(activity).requestIntegrityToken(...)`.
  - Success → `GNatives.onIntegritySuccess(token, requestId)` (token forwarded into native; native verifies / forwards to server).
  - Failure → `GNatives.onIntegrityFailure(formatIntegrityFailure(exc), requestId)` (`formatIntegrityFailure` packs `errorCode:SimpleName:msg`, `IntegrityServiceException.getErrorCode()` else `-1001`, sanitized to ≤160 chars).
  - Throw at request time → `GNatives.onIntegrityThrow(...)`.
- Callbacks run on a dedicated daemon `IntegrityCallback` single-thread executor.
- **Nonce origin:** the nonce is generated **inside the native engine** (passed into `Integrity.run`); the integrity token round-trips token→native→(server). The Java side never inspects/validates the token — it is opaque transport. So integrity verification & nonce binding live entirely in `libblocklegends.so` / the backend.
- **Anti-debug:** no explicit `Debug.isDebuggerConnected`/`TracerPid` checks in DEX; anti-debug (if any) is native. The DEX-visible tamper surface is the Frida/Xposed/root scan in §3 plus PairIP's integrity/license enforcement.

---

## 5. Network config & servers

### 5.1 Hard-coded hosts in DEX (`grep` of `net/blocklegends`)
| Host / URL | File | Purpose |
|---|---|---|
| `https://appleid.apple.com/auth/authorize` | `game/AppleSign.java:35` | Apple Sign-In authorize endpoint |
| `https://blocklegends.net/apple` (`REDIRECT_URI`) | `AppleSign.java:48` | Apple OAuth redirect (form_post) |
| `https://blocklegends.net/apple-callback` + `*.blocklegends.net` | `AppleSign.java:39/41`, `AppleSignActivity.java:21/66` | Apple callback host/path; App Links `autoVerify` |
| `blocklegends://apple-callback` | manifest:130-133 | custom-scheme deep link for Apple callback |
| `https://play.google.com/store/apps/details?id=net.blocklegends` / `market://details?id=net.blocklegends` | `GNatives.java:363/371` | "rate/open store" |
| `block-legends-4a313.firebasestorage.app` | `res/values/strings.xml:106` | Firebase Storage bucket |

**The Apple `consume` step** POSTs to `https://blocklegends.net/apple` with `action=consume&callback_id=…&state=…&nonce=…` (`AppleSign.consumeCallbackBlocking`, 10 s timeouts) and parses `{ok, code, id_token, error}` — so `blocklegends.net` is the app's own auth backend bridge.

### 5.2 Firebase / Google / Ad identifiers (`res/values/strings.xml`)
- `project_id` = **block-legends-4a313**
- `google_app_id` = **1:810329497878:android:f99db6a67e639da13a7037**
- `gcm_defaultSenderId` / `game_services_project_id` = **810329497878**
- `google_api_key` = **AIzaSyBxn6n3-d32Xbrc_noiwyHwUNwQCy1y4Uc**
- `google_storage_bucket` = **block-legends-4a313.firebasestorage.app**
- `default_web_client_id` (OAuth) = **810329497878-qhcq8r0bpqo1dl0ajkjb282gmq3akhj1.apps.googleusercontent.com**
- AdMob app id (manifest:187) = **ca-app-pub-5716702531231887~2520413633**
- AdMob rewarded unit (`strings.xml:30` `ad_unit_id_0`) = **ca-app-pub-5716702531231887/9732689249**
- Apple Service ID `CLIENT_ID` = **net.blocklegends.applesignin**
- TikTok: token **TTaLmfWUaDBm8ZZbPSFhse4pMu2vZ3ta**, TTAppId **7623335695234498567**

### 5.3 The actual game / multiplayer server
**Not present in DEX or in plaintext native strings.** `grep` of `analysis/raw/strings_all.txt` for game hosts returns only library/CA noise (jQuery, Maven, DigiCert/Comodo CRLs, GraalVM/bytedeco). The class `net.blocklegends.network.Packet` exists in the native image (string table), and per the established context multiplayer uses **Valve GameNetworkingSockets** (UDP). Conclusion: the realm/lobby/matchmaking host(s) are resolved **at runtime inside the GraalVM `.svm_heap`** (88 MB) — they are not exposed on the Java side. CraftRise lineage strings (`CraftRise`, `CRAFTRISE-C`, `net.blocklegends.*` obfuscated classes) appear in the native image but no `sonoyuncu`/`craftrise` *hostname* is in plaintext. Recovering the live server requires dumping the native image / runtime memory (the heap-resolved config), not the DEX.

`MainApp`/`MainActivity` only touch the network for: connectivity monitoring (`onNetworkChanged`, `ConnectivityManager` callback → network-error dialog), Firebase, Play, AdMob, TikTok, Apple bridge.

---

## 6. Monetization

### 6.1 Billing (`billing/Billing.java`, `billing/BillingProducts.java`)
- Play Billing Library **9.0.0** (`BillingClient.newBuilder(...).enablePendingPurchases(...)`). Singleton via `Billing.startBillingService()`; reconnect with exp backoff (max 4 / `MAX_RECONNECT`), product-query backoff (max 3).
- Products enumerated in `BillingProducts` enum:
  - SUBS: `PASS` (`pass_1`/base-plan `pass-1`), `PASS_25_DISCOUNT` (`pass_1`/`pass-2`), `PASS_10_DISCOUNT` (`pass_1`/`pass-3`).
  - INAPP: `rc_box_1_`, `rc_box_1_discount`, `rc_box_2`…`rc_box_6` (loot/"RC box" consumables).
- Purchases serialized to JSON `{purchaseToken%###%orderId: [products]}` and pushed to engine via `GNatives.onProcessPurchases(...)`; setup completion via `GNatives.onBillingSetupComplete()`. Buy flow: `BillingProducts.openBuyScreen(userUuid)` → `setObfuscatedAccountId(uuid)` (ties purchase to in-game account). Billing UI is wrapped as `EXTERNAL_UI_BILLING_FLOW` (180 s timeout).

### 6.2 Ads (`ad/AdManager.java`, `ad/GoogleMobileAdsConsentManager.java`)
- Google Mobile Ads (AdMob), **rewarded video only** (`RewardedAd.load(activity, AD_UNIT_ID, …)`), unit `ca-app-pub-5716702531231887/9732689249`.
- UMP consent gathering (`GoogleMobileAdsConsentManager.gatherConsent`); `CHILD_MODE` flag disables ads + analytics + sends `npa=1`. SDK init deferred ~20 s after `startAdManager`; analytics toggled at 15 s.
- Reward result → `GNatives.onUserEarnedReward(id, given, type, amount)`; load result → `GNatives.onGoogleAdLoaded(bool)`. Show is wrapped as `EXTERNAL_UI_ADMOB_REWARDED`.

### 6.3 Attribution & analytics
- **TikTok Business SDK** init in `MainApp` (§1.1) + `GNatives.trackTikTokEvent(name, jsonProps)` (engine-driven).
- **Install Referrer** (`attribution/InstallReferrerTracker.java`): Play Install-Referrer → parses `utm_source/medium/campaign`, `gclid` → Firebase user-properties (`ir_source/medium/campaign`) + `install_referrer` event; persisted in SharedPrefs `install_referrer` (one-shot `captured`).
- **Firebase Analytics** (`firebase/FirebaseNatives.java`): `logEvent(name,String[] kv)`, `logLevelUp(level,char)`, `logEarnVirtualCurrency(name,value)`, `logSpendVirtualCurrency(name,value,item)`, `setUserProperty`. Manifest disables auto-collection (`firebase_analytics_collection_enabled=false`, `firebase_crashlytics_collection_enabled=false`, `firebase_sessions_enabled=false`) but code re-enables Crashlytics at runtime and toggles Analytics via `AdManager`/`CHILD_MODE`.
- Google Play in-app review: `FirebaseNatives.showAppReviewPopup()`.

---

## 7. Auth (token flow to native)

All three auth paths converge on **`GNatives.onTokenReceived(idToken, nonce/authCode)`** (or a `…SignFailed` callback) — the engine consumes the token and talks to the backend.

### 7.1 Google — `game/GoogleSign.java`
- Primary: **Credential Manager** + `GetSignInWithGoogleOption(default_web_client_id)` with a 32-byte SecureRandom **nonce** (`createNonce`, Base64 url-safe). 12 s CM timeout → 30 s "stuck" → **legacy `GoogleSignIn` fallback** (`requestIdToken(default_web_client_id).requestEmail()`, request code 9012, 120 s).
- Rich state machine (attempt counters, stale-lock reset at 147 s, phantom-cancel handling) + Firebase funnel telemetry (`google_signin_funnel`, Crashlytics keys `gsi_*`).
- On success: `GNatives.onTokenReceived(idToken, nonce)` (legacy path sends empty nonce). Failure: `GNatives.onGoogleSignFailed(type, telemetry)`.

### 7.2 Apple — `game/AppleSign.java` + `AppleSignActivity.java`
- Builds `https://appleid.apple.com/auth/authorize?response_type=code id_token&response_mode=form_post&client_id=net.blocklegends.applesignin&redirect_uri=https://blocklegends.net/apple&scope=name email&state=bl2_<rand24>&nonce=<rand32>`.
- Opens **Custom Tab** (preferred) or in-app **WebView** (`AppleSignActivity`, exported=false). Callback via App Link `https://blocklegends.net/apple-callback` or `blocklegends://apple-callback`; **state** is validated, then `consume` POST to `https://blocklegends.net/apple` exchanges `callback_id`→`{id_token, code}`.
- Success → `GNatives.onTokenReceived(idToken, authCode)`; failure → `GNatives.onAppleSignFailed`. State/nonce persisted in `apple_sign_state` SharedPrefs.

### 7.3 Play Games v2 — `gameservices/GameServicesNatives.java`
- `PlayGamesSdk.initialize`; `pgs_init` checks auth, fetches `Player` → `GNatives.onGcAuthSuccess(playerId, displayName)`. `pgs_serverAuthCode()` → `requestServerSideAccess(default_web_client_id,false)` → `GNatives.onGcServerAuthCode(code)` (server-side token exchange). Also achievements/leaderboards UI (`pgs_unlockAchievement`, `pgs_submitScore`, etc.). PGS v2 has no SDK sign-out (`pgs_signOut` just clears cached id).

`MainActivity.onActivityResult` (line 1895) routes `9001`→AppleSign, `9012`→GoogleSign legacy.

---

## 8. Voice (`game/GameVoice.java`) + sound (`game/GameSound.java`)

`GameVoice` is the **PCM bridge**; all DSP (Opus/SILK codec, **WebRTC AEC3** echo cancellation) is native (`libblocklegends.so` bundles Opus/SILK + WebRTC AEC3 + pffft per context). Java only moves 16-bit mono PCM and manages the platform audio session.

### 8.1 Capture
- `startCapture(sampleRate, frameSamples)`: blocks on emulator (`shouldBlockCaptureOnThisRuntime`), checks `RECORD_AUDIO`, sets `AudioManager` mode `MODE_IN_COMMUNICATION(3)`, selects built-in speaker comm device (API 31+), unmutes mic.
- Capture sources tried in order `CAPTURE_SOURCES = {VOICE_COMMUNICATION(7), MIC(1), VOICE_RECOGNITION(6), DEFAULT(0)}`. When native WebRTC AEC is active, first source is **MIC(1)** (`firstCaptureSourceIndex`), so AEC3 sees a raw mic; otherwise source 0 and the **platform `AcousticEchoCanceler`+`NoiseSuppressor`** are attached (`configureEffectsLocked`). Health logic: ≥3 read failures or ≥100 silent reads ⇒ mark source unhealthy, probe alternates (`handleCaptureDigitalSilenceLocked`).
- `readCapture(short[], off, len)` is the JNI read path (`AudioRecord.read(...,READ_BLOCKING)`); tracks peak/silence.
- `setNativeAecActive(boolean)` (native→Java) flips `nativeWebRtcAecActive` + system property `cr.voice.webrtcAec.active`; when active, platform AEC is skipped ("WebRTC AEC3 native processor active").

### 8.2 Playback
- `startPlayback(sampleRate, frameSamples)`: `AudioTrack` (`USAGE_VOICE_COMMUNICATION`, `CONTENT_TYPE_SPEECH`, mono, PCM16), dedicated daemon thread "CRVoicePlayout" running `runPlaybackLoop`. Jitter buffer: ring buffer sized for **240 ms** max, **60 ms** prebuffer, **80 ms** target queue (`PLAYOUT_BUFFER_MS/PREBUFFER_MS/TARGET_QUEUE_MS`), playback delay clamped 20–240 ms.
- `writePlayback(short[],off,len)` is the JNI write path → ring buffer (overrun-drops oldest). `getPlaybackDelayMs()` exposes smoothed delay (also via property `cr.voice.playbackDelayMs`) so the native echo canceller knows the far-end delay.
- Head-stall detection (200 ms) switches to write-paced playback when `AudioTrack` head stops advancing.

### 8.3 SFX/music — `GameSound.java`
`SoundPool` (10 streams, polyphony cap 2/sample, 35 ms per-sample cooldown, global 5 starts/16 ms) + up to 2 `MediaPlayer`s for streamed media. Single "GameSound-Audio" executor thread. Load/complete callbacks bridged via `GNatives.setOnLoadCompleteListener`/`setOnCompleteListener`.

---

## 9. Permissions, components & manifest (`resources/AndroidManifest.xml`, 551 lines)

### 9.1 Permissions
`VIBRATE`, `INTERNET`, `ACCESS_NETWORK_STATE`, `c2dm.permission.RECEIVE`, `com.android.vending.BILLING`, `gms.permission.AD_ID`, `RECORD_AUDIO`, `MODIFY_AUDIO_SETTINGS`, `POST_NOTIFICATIONS`, `USE_BIOMETRIC`, `USE_FINGERPRINT`, `ACCESS_ADSERVICES_AD_ID/ATTRIBUTION/TOPICS`, `FOREGROUND_SERVICE`, `FOREGROUND_SERVICE_DATA_SYNC`, `WAKE_LOCK`, `finsky…BIND_GET_INSTALL_REFERRER_SERVICE`, `com.android.vending.CHECK_LICENSE`. Custom signature perm `net.blocklegends.DYNAMIC_RECEIVER_NOT_EXPORTED_PERMISSION`.
SDK: minSdk **32**, target/compile **37**. `uses-feature glEsVersion=0x30000 required`. `<queries>` enumerates the anti-cheat target packages (§3.1) + CustomTabs/billing/browser intents.

### 9.2 Components & exported flags
- **`net.blocklegends.MainActivity`** — `exported=true`, `singleTask`, `sensorLandscape`. Intent filters: LAUNCHER+GAME; `VIEW` for `blocklegends://apple-callback`; `autoVerify` App Link `https://blocklegends.net/apple-callback`. The only externally reachable app activity (deep-link surface → AppleSign callback handling).
- `net.blocklegends.game.AppleSignActivity` — exported=false (WebView OAuth fallback).
- `com.google.android.gms.ads.AdActivity`, `ProxyBillingActivity`/`ProxyBillingActivityV2`, GMS sign-in/Games/credentials hidden activities — all exported=false (or framework-standard).
- **Service `net.blocklegends.FCMService`** — exported=false, filter `com.google.firebase.MESSAGING_EVENT`.
- Exported framework receivers/services: `com.google.firebase.iid.FirebaseInstanceIdReceiver` (perm `c2dm…SEND`), `gms.auth…RevocationBoundService` (perm-guarded), Play Asset-Pack `SessionStateBroadcastReceiver` (perm `INSTALL_PACKAGES`), `PlayGamesAppShortcutsActivity`, WorkManager `SystemJobService`/`DiagnosticsReceiver`/`ProfileInstallReceiver` (perm `BIND_JOB_SERVICE`/`DUMP`) — standard library components, permission-gated.
- Providers: `MobileAdsInitProvider`, `PlayGamesInitProvider`, `androidx.startup.InitializationProvider`, `FirebaseInitProvider` — exported=false.
- AdMob app-id, Play Games app-id, billing version 9.0.0, asset-pack version 20300 declared as `<meta-data>`. Firebase performance/sessions/analytics/crashlytics auto-collection all `=false` in manifest. `android:appCategory="game"`, `largeHeap=true`, `extractNativeLibs=false`, `requestLegacyExternalStorage=true`.

### 9.3 Net security
No `android:networkSecurityConfig` and no `usesCleartextTraffic` override (defaults: cleartext disabled on target 37). All app-controlled traffic is HTTPS (`appleid.apple.com`, `blocklegends.net`, Firebase, Play). The game's UDP multiplayer (GameNetworkingSockets) bypasses these and is native-configured.

---

## Researcher takeaways
- The DEX is a **host shim**; treat `GNatives` (§2) as the canonical engine↔Android contract. Hooking any of those Java methods (e.g. `onTokenReceived`, `onIntegritySuccess`, `onProcessPurchases`, `readCapture`/`writePlayback`) intercepts the corresponding data crossing the boundary.
- Real game logic, server addresses, nonce generation and integrity/token verification are inside `libblocklegends.so` (GraalVM image) — recover them from the native side, not here.
- Anti-tamper that matters in DEX: the Frida/Xposed/root/su/emulator scan (`AntiAutoClickerUtil`, **TR/AZ-gated**) + the `GyroscopeEC` motion challenge + PairIP's license/integrity wrapper + Play Integrity (`Integrity.run` → 810329497878). No DEX-level anti-debug.
- Notable artifacts/IDs are all in §5.2; the only first-party backend host visible in Java is **`blocklegends.net`** (auth bridge / App Links).
