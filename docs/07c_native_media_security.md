# Block Legends — Native Deep-Dive 07c: MEDIA + SECURITY + IAP

Target: `libblocklegends.so` (229 MB GraalVM Native Image / SubstrateVM, AArch64). IDB session `c3323093`.
Scope: voice (WebRTC + Opus), compression (Zstd/LZ4), textures (KTX/BasisU/FSR), Play Integrity callbacks, setenv bridge, security string spelunk.

> **JNIEnv vtable-offset key** (decoded & cross-validated against named libktx/setenv calls — applies to every stub below):
> `+904`=GetStaticMethodID `+1128`=CallStaticVoidMethod `+128`=ExceptionDescribe `+136`=ExceptionClear `+184`=DeleteLocalRef
> `+1336`=NewStringUTF `+1352`=GetStringUTFChars `+1360`=ReleaseStringUTFChars `+1368`=GetArrayLength `+1824`=ExceptionCheck
> `+1472`=GetByteArrayElements `+1488`=GetShortArrayElements `+1496`=GetIntArrayElements `+1536/+1552/+1560`=Release{Byte,Short,Int}ArrayElements
> `+1776`=GetPrimitiveArrayCritical `+1784`=ReleasePrimitiveArrayCritical `+1832`=NewDirectByteBuffer `+1840`=GetDirectBufferAddress

---

## 1. MEDIA — Voice (WebRTC AEC3/AGC + Opus)

### 1a. `Java_gnatives_VoiceProcessingNative_createProcessor` @ 0x8182850
Builds a **WebRTC `AudioProcessing` (APM)** instance. Args `a3`=sampleRate, `a4`=channels. It enforces 10 ms integer frames (`a3 / 100`), heap-allocates a 0x50 processor struct (holds APM ptr at +40, sampleRate at +48, channels at +52, samplesPerChannel `a3/100` at +56), fills a large APM config block (note `v22 = 48000`, AGC float `1.0`, NS, etc.), then:
```c
webrtc::AudioProcessingBuilder::AudioProcessingBuilder(v21);
webrtc::AudioProcessingBuilder::Create(&v20, v21);          // -> webrtc::AudioProcessing*
... (*(...+48)(apm, &cfg))   // ApplyConfig
    (*(...+192)(apm, 0))     // set_stream_delay / output config
```
Error strings: `"bad voice processor config"`, `"voice processor sample rate must provide 10ms integer frames"`, `"AudioProcessingBuilder returned null"`. The APM submodules present in the binary confirm full **AEC3** stack: `FullBandErleEstimator`, `SubbandErleEstimator`, `StationarityEstimator`, `SuppressionGain`, `SubbandNearendDetector`, `RenderSignalAnalyzer`, `AudioBuffer::SplitIntoFrequencyBands/MergeFrequencyBands`.

### 1b. `processCapture` @ 0x8182f24 / `processReverse` @ 0x8182d44
Identical shape. PCM is **int16**: pinned via `GetShortArrayElements (+1488)`. Range-checked (`sampleRate/100 * channels == frameLen`), then under the processor mutex:
```c
// processCapture: APM vtable +128 == ProcessStream
v14 = (*(*(apm)+128))(apm, pcm+2*off, cfgIn(a3+56), cfgOut(a3+56), pcm+2*off);  // in-place
// processReverse: APM vtable +144 == ProcessReverseStream (the far-end/render path for AEC)
v14 = (*(*(apm)+144))(apm, ...);
```
Released with `ReleaseShortArrayElements (+1552)`, mode `0`(commit) on success / `2`(JNI_ABORT) on failure. `setStreamDelayMs` @ 0x8182c8c feeds the AEC alignment delay. So the **echo-cancellation render reference flows through `processReverse`→ProcessReverseStream, mic capture through `processCapture`→ProcessStream**, both 10 ms int16 frames, in-place.

### 1c. Opus codec — `Java_gnatives_OpusNative_*`
**`createEncoder` @ 0x8181d6c**: `opus_encoder_create(sampleRate, channels, 2048 /*OPUS_APPLICATION_VOIP*/, &err)` then `opus_encoder_ctl`:
```c
ctl(4002, bitrate);        // OPUS_SET_BITRATE       (a5, if >0)
ctl(4010, min(complexity,10)); // OPUS_SET_COMPLEXITY (a6, clamped to 10)
ctl(4006, 1);              // OPUS_SET_VBR = 1
ctl(4024, 3001);           // OPUS_SET_SIGNAL = OPUS_SIGNAL_VOICE
ctl(4016, 0);              // OPUS_SET_DTX = 0
```
**`encode` @ 0x8182154**: pins int16 PCM (`+1488`) + output byte[] (`+1472`), `opus_encode(enc, pcm+2*off, frameSize, out+off, maxBytes)`. **`decode` @ 0x8182414**: pins payload byte[] + int16 PCM out, `opus_decode(dec, payload+off, len, pcmOut+2*off, frameSize, fec /*a10*/)` — last arg is the **FEC/PLC** flag. `decodePlc` @ 0x8182664 + `configureEncoderLossProtection` @ 0x8182084 implement inband-FEC + packet-loss-percentage for lossy VoIP. **Pipeline: mic → APM(AEC3/AGC/NS) ProcessStream → Opus VOIP encode → network; network → Opus decode(+FEC/PLC) → APM ProcessReverseStream(render ref) → playback.**

---

## 2. MEDIA — Compression (Zstd + LZ4)

### 2a. Zstd — `Java_gnatives_ZstdNative_*` (0x8122c40 region)
A full **statically-linked libzstd** (compressBound, loadDictionary, hasDictionary, clearDictionaries, clearThreadContext, compress, decompress, compressBuffer/decompressBuffer, trainFromBuffer/ZDICT).

- **`compress` @ 0x8123c28 → sub_8123EC0**: byte[]-pinned (`+1472`). Uses a **thread-local CCtx** (`calloc 0x1498`, lazily created, freed via `__cxa_thread_atexit`). When `dictId(a6)==0` it derives ZSTD params from the classic preset table at `unk_8661978` (indexed `[size-bucket][level]` → windowLog/chainLog/hashLog/searchLog/minMatch/targetLength/strategy) and runs `ZSTD_compressBegin`+block (`sub_8131500`/`sub_8131368`). When `dictId!=0` it locks a global **dictionary hash-map** (`xmmword_DCC0918`, open-addressed by dictId) and calls `ZSTD_compress_usingCDict`-equivalent `sub_8134A84`. Errors: `"dictionary not loaded"`, `"create CCtx failed"`.
- **`decompress` @ 0x812447c → sub_8124708**: symmetric, dictId `a9`.
- **`loadDictionary` @ 0x8122da8**: pins the raw dict byte[], computes a **dictId** (reads the magic `0xEC30A437` / little-endian `-332356553` + embedded dictID field, else FNV-1a hash of dict bytes), builds a CDict (`sub_8134314`) and a DDict (`malloc 0x6AD8` + `sub_817C714`), and inserts both into the global dictId→{CDict,DDict,rawcopy} hash map (rehash via `std::__next_prime`). `trainFromBuffer` @ 0x8124f98 is ZDICT training.

**Where used:** dictionary-keyed Zstd is the signature of **network packet / asset-stream compression** (shared trained dictionaries per channel) — same family as the CraftRise-derived protocol stack. The thread-local CCtx + per-dictId CDict map = per-connection streaming compression, not one-shot world-save.

### 2b. LZ4 — `Java_gnatives_GNative_LZ4_*` @ 0x80b1f88
`LZ4_compress_limitedOutput`, `LZ4_compressHC`, `LZ4_decompress_fast/_safe`, `LZ4_compressBound`. Buffers pinned with **`GetPrimitiveArrayCritical (+1776)`** (max-throughput, GC-paused) and a **0x4020-byte on-stack ext-state** (`LZ4_compress_fast_extState`). Critical-array + stack hash-table = the **hot per-frame path: chunk/mesh & GPU upload buffers** (latency-critical, in contrast to Zstd's dictionary network path).

---

## 3. MEDIA — Textures (KTX2 / BasisU / AMD FSR)

### 3a. KTX / Basis Universal — `Java_gnatives_KTXTexture_*` @ 0x80ae35c (libktx)
```c
nativeCreateFromMemory: data = GetDirectBufferAddress(+1840, byteBuffer);
                        ktxTexture_CreateFromMemory(data, len, 1, &tex);      // KTX1/KTX2 loader
nativeTranscodeBasisU : ktxTexture2_TranscodeBasis(tex, fmt /*a4*/, 0);        // BasisU/UASTC -> GPU fmt
nativeGetImageData    : d=ktxTexture_GetData(tex); n=ktxTexture_GetDataSize(tex);
                        return NewDirectByteBuffer(+1832, d, n);               // zero-copy back to Java
```
**Pipeline:** KTX2 container (direct ByteBuffer, zero-copy) → BasisU/UASTC transcode to the device's preferred compressed GPU format (`a4` = target, e.g. ASTC/ETC2/BC7) → image data handed back as a direct buffer for `glCompressedTexImage2D`. Width/Height getters @ 0x80ae3ec/0x80ae400.

### 3b. AMD FSR 1.0 — `Java_gnatives_GNative_Fsr*` @ 0x80b1c54 / 0x80b1d5c
Pure CPU-side **constant-block generators** writing into Java int[]/IntBuffer (pinned `GetIntArrayElements +1496`, released `+1560`).
- **`FsrEasuGetConsts` @ 0x80b1d5c** (Edge-Adaptive Spatial Upsampling): from input viewport (a7,a8) and render size (a9,a10) computes scale `in/out`, fills **con0..con3** vec4s (`{rcpScaleX,rcpScaleY,0.5*scale-0.5,...}`, `1/srcW`, `1/srcH`, `2/srcH`…) — the classic FSR EASU constants. Optional 5th buffer (a11) stores debug scale/offset.
- **`FsrRcasGetConsts` @ 0x80b1c54** (Robust Contrast-Adaptive Sharpening): `sharpness` → `exp2f(-a2)`, packs the sharpening factor as `65537 * (...)` half-float pair into con[1], plus the float in con[0] (lookup tables `word_865EC88`/`byte_865F088`). **GPU upscaling pipeline:** render at lower res → EASU upscale pass (con0-3) → RCAS sharpen pass (con0-1) → present; consts are computed natively each resize and uploaded as shader uniforms.

---

## 4. SECURITY / INTEGRITY — Play Integrity callbacks (THE KEY FINDING)

**There is NO native integrity verification or native anti-cheat.** The three callbacks are pure **cross-VM marshaling bridges**. Block Legends runs **two distinct JVMs**: the Android host ART VM (which invokes these JNI stubs) and the **GraalVM SubstrateVM isolate** that contains the AOT-compiled game ("game" isolate `qword_DB2CF28`, worker thread `"BL-Worker"`; plus a separate `"BL-Studio"` isolate `qword_DB2CF60`).

`initGameEnv` @ 0x80aa144 attaches the calling thread to the game isolate and caches the engine-side class:
```c
AttachCurrentThreadAsDaemon(sjavaVM /*DB2CF28*/, &gameEnv, "BL-Worker");
cls = FindClass(gameEnv, "android/Natives");      // +48
qword_DB2CF30 = NewGlobalRef(gameEnv, cls);       // +168  (cached engine class)
```

`onIntegritySuccess` @ 0x80c71f4, `onIntegrityFailure` @ 0x80c7090, `onIntegrityThrow` @ 0x80c6f2c are **byte-for-byte identical** apart from the cached method-id slot (success=`unk_8672CB0`, failure=`unk_8672C90`, throw=`unk_8672C70`). Each does:
```c
initGameEnv();                                              // attach to game isolate
mid = GetStaticMethodID(gameEnv, android.Natives, "onIntegrity{Success|Failure|Throw}", "(Ljava/lang/String;J)V"); // cached
utf = GetStringUTFChars(hostEnv, a3 /*verdict/token jstring*/, 0);
js  = NewStringUTF(gameEnv, utf);
ReleaseStringUTFChars(hostEnv, a3, utf);
CallStaticVoidMethod(gameEnv, android.Natives, mid, js, a4 /*jlong nonce/requestHash*/);  // +1128
DeleteLocalRef(gameEnv, js);
```
**i.e. the Play Integrity verdict String + a long are simply re-marshaled from the host VM into the GraalVM isolate and handed to AOT-compiled `android.Natives.onIntegrity*`. The actual verdict-token consumption, server transmission, and play-gating ALL live in managed AOT Java (`sub_*`), not in native.** Native neither parses, validates, nor gates on the token. `runIntegrity` @ 0x80c1a28 is the inverse: it attaches the **`"BL-Studio"`** isolate and calls static `run("(Ljava/lang/String;J)V")` on `qword_DB2CFB0` — the engine *requesting* an integrity check from the host. `gc_fetchIdentitySignature` @ 0x80c34dc is an **empty no-op stub** (identity-signature handling is fully managed-side).

Same bridge pattern (all → `CallStaticVoidMethod` on `android.Natives`) is used by the sibling auth/IAP/ads callbacks: `onTokenReceived`/`onFCMTokenReceived` (FCM/auth token, sig `(Ljava/lang/String;Ljava/lang/String;)V`, heavily logged to logcat tag `IMG`), `onGoogleSignFailed`, `onAppleSignFailed`, `onGcAuthSuccess`, `onGcServerAuthCode`, `onProcessPurchases`, `onBillingSetupComplete`, `onUserEarnedReward`.

### 4b. IAP — `onProcessPurchases` @ 0x80c6d2c
Identical bridge: `GetStringUTFChars(hostEnv, purchaseJson)` → `NewStringUTF(gameEnv)` → `CallStaticVoidMethod(android.Natives, onProcessPurchases_mid, str)` (single String arg — the Google Play Billing purchase list/JSON). **Purchase verification/acknowledgement is entirely managed-side**; native only ferries the billing payload into the isolate. `onBillingSetupComplete` and `onUserEarnedReward` (rewarded-ad → currency) follow the same path.

## 5. SECURITY — `setenv` bridge @ 0x80c39d0
`Java_net_blocklegends_natives_GNatives_setenv(key, value)`:
```c
k = GetStringUTFChars(env, a3); v = GetStringUTFChars(env, a4);
if (k && v) setenv(k, v, 1 /*overwrite*/);
ReleaseStringUTFChars(env, a3, k); ReleaseStringUTFChars(env, a4, v);
```
A thin pass-through letting managed code set **arbitrary process env vars** before/around engine init (the *which* vars are decided in managed code — typically GraalVM/SubstrateVM tuning, locale, GL/EGL, `MALLOC_`/heap, and feature flags). No filtering or allow-list in native.

## 6. SECURITY string spelunk (find_regex on c3323093)
- Integrity surface is small and entirely bridge-side: only `runIntegrity`, `onIntegrity{Success,Failure,Throw}`, `gc_fetchIdentitySignature` exports + method-name strings `"onIntegritySuccess"`/`"onIntegrityFailure"`. **No** `hwid`, `tamper`, `anti-cheat`, `ban`, `cheat`, `license`, `attest`, `nonce`, `secret` strings anywhere in native.
- `"signature"` hits are **library-internal only**: `png_get_signature`, `cmsGetTagSignature` (lcms2), `"Bad tag signature %lx found."` — image/ICC parsing, not security.
- `"verify"`/`"Verify"` hits are GraalVM runtime internals (`VerifyClassname`, `VerifyFixClassname`) and Opus DSP (`silk_*`, `*_bands`), not integrity checks.
- Auth/token tokens: `onTokenReceived`, `onFCMTokenReceived` (bridges, §4). No native crypto/HMAC of tokens.

**Conclusion:** native layer is a media codec farm + a thin JNI marshaling shim between the Android VM and the GraalVM game/studio isolates. All trust decisions (Play Integrity verdict consumption, purchase verification, identity signatures, ban/anti-cheat) are deferred to AOT-compiled managed code and/or the server — none are enforced in native.

---

## 5-LINE SUMMARY
1. **Integrity gating is NOT native** — `onIntegritySuccess/Failure/Throw` (0x80c71f4/0x80c7090/0x80c6f2c) are identical JNI shims that only `GetStringUTFChars`→`NewStringUTF`→`CallStaticVoidMethod(android.Natives,"onIntegrity*","(Ljava/lang/String;J)V", verdictStr, longNonce)`, relaying the Play Integrity verdict+nonce into the GraalVM "BL-Worker" isolate; the actual verdict consumption / play-gating lives in AOT-compiled managed Java (`runIntegrity`@0x80c1a28 is the engine→host request via the "BL-Studio" isolate).
2. **No native anti-cheat / tamper / hwid / license / signature-verify logic exists** — string sweep found only libpng/lcms2 "signature" and GraalVM "Verify*" internals; `gc_fetchIdentitySignature`@0x80c34dc is an empty stub; IAP `onProcessPurchases`@0x80c6d2c likewise just ferries the billing JSON to managed code; `setenv`@0x80c39d0 is an unfiltered env pass-through.
3. **Voice** = WebRTC APM (AEC3/AGC/NS, `AudioProcessingBuilder::Create`, ProcessStream@vt+128 / ProcessReverseStream@vt+144, 10 ms int16 frames) feeding **Opus VOIP** (app=2048, VBR, OPUS_SIGNAL_VOICE, complexity≤10, inband-FEC/PLC on decode).
4. **Compression**: dictionary-keyed **Zstd** (thread-local CCtx + global dictId→CDict/DDict map, ZDICT training) for the network/asset stream; **LZ4** with `GetPrimitiveArrayCritical` + stack ext-state for the hot per-frame chunk/GPU-buffer path.
5. **Textures**: **libktx** KTX2 load + `ktxTexture2_TranscodeBasis` (BasisU/UASTC→GPU format) over zero-copy direct ByteBuffers, plus CPU-side **AMD FSR 1.0** constant generators (`FsrEasuGetConsts`/`FsrRcasGetConsts`) writing EASU/RCAS uniforms into IntBuffers for the low-res→upscale→sharpen present pipeline.
