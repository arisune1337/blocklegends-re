# Block Legends — Native Deep-Dive (`libblocklegends.so`)

**Binary:** `libblocklegends.so` — 229 MB AArch64 (ARM64) **GraalVM Native Image / SubstrateVM**.
**IDA session:** `c3323093` (live, analysis-complete; IDB `…\extracted\natives\libblocklegends.so.i64`).
**Addressing:** all addresses are file/image offsets, imagebase `0x0`.
MD5 `dcd05c8d821fcbed01dea03dc4063da9` · SHA-256 `675f36db89b1dd7193247e52c5837d5c3b9962b9d5ed4537011e7175c33bebf1`.

Block Legends is a Minecraft / CraftRise-derived voxel engine. The game logic is
Kotlin/Java **AOT-compiled by GraalVM** into this `.so`. Java methods are unnamed
(`sub_*`); the **only readable boundary** is the set of `CEntryPoint`/JNI stubs
that still carry the original method/class strings and call through the JNIEnv
vtable. This document is organized around those boundaries.

This file consolidates three native sub-surveys — engine core (07a),
networking (07b), media/security (07c) — over the IDA landscape pass (03).

---

## 0. Native engine architecture (overview)

```
Android host (ART VM)
   │  JNI
   ▼
graal_create_isolate (0x3A77DF0)  ── maps image heap, LL/SC state machine ──┐
   │                                                                         │
run_main (0x3A7F690) → JavaMainWrapper.run (sub_3A19930)                     │
   │   gate on [FP, ASIMD]; create+attach isolate; run Java main (sub_3A19B30)
   ▼                                                                         │
MainApp.start  CEntryPoint (0x3A7F800) → sub_4739DE0 (AOT MainApp.start)     │
   │            └─ enter isolate thread, stack-limit check, safepoint        │
   ▼                                                                         │
sub_4736150  (AOT Kotlin game init) ── the game's real entry ───────────────┘
   │
   ▼
╔══════════════ AOT-compiled Kotlin/Java game loop (NOT native) ═══════════════╗
║  per-frame tick lives in the compiled MainApp / render thread.               ║
║  Each frame it calls native subsystem wrappers directly:                     ║
║                                                                              ║
║   GLES20/30/31 JNI  ──► ANGLE/EGL  (render, present via swapGameContext)      ║
║   GnsNative JNI     ──► GameNetworkingSockets (dlopen)  (transport)          ║
║   Zstd/LZ4/Opus/WebRTC/KTX/FSR JNI  ──► codec farm  (media)                  ║
║   GNatives JNI      ──► host callbacks (integrity, IAP, lifecycle, input)    ║
╚══════════════════════════════════════════════════════════════════════════════╝
```

**The single most important architectural fact:** there are **two distinct
JVMs** in one process — the Android host **ART VM** (which calls the JNI stubs),
and the **GraalVM SubstrateVM isolate(s)** holding the AOT game. Native code is a
**thin bidirectional marshaling layer** between them, plus a farm of statically/
dynamically linked C/C++ media & transport libraries. There are at least two
isolates: the **game** isolate (`qword_DB2CF28`, worker thread `"BL-Worker"`) and
a separate **studio** isolate (`"BL-Studio"`, `qword_DB2CF60`).

Engine surface size (from the survey): **47,097 functions** total (5,803 named,
41,294 `sub_*`), **378,438 strings**, `.text` = 0x386C000–0x8216928 (~73 MB),
with the game's JNI bridge thunks clustered high in `.text` around
0x8033000–0x8125000 and 0x8113000–0x8183000. Bridge inventory:
`Java_net_blocklegends*` = 336, `Java_gnatives*` = 88, all `Java_*` = 747.

---

## 1. Bootstrap / Isolate (SubstrateVM)

Pure SubstrateVM. Three entry points matter (survey table @ 03).

### 1.1 `createIsolate` @ `0x3A77DF0` (alias `graal_create_isolate`)
`IsolateEnterStub__CEntryPointNativeFunctions__createIsolate` — allocates and
maps the isolate heap.

```c
result = loc_3AA6090();                  // reserve/commit isolate heap
if (!result) {
  // LL/SC spinlock on isolate state word dword_14 (3 == "initialized")
  do { v6 = __ldxr(&dword_14); v7 = (v6==3)?1:v6; } while(__stlxr(v7,&dword_14));
  __dmb(0xB);
  result = sub_3AA6560(a1, v5);          // isolate/heap object construction
}
if (!result) {
  if (a2) *a2 = 0x150150150150150LL;     // SubstrateVM isolate magic sentinel
  atomic_store(3u, &dword_14);           // mark isolate state = initialized
}
```

- `dword_14` is the global **isolate-state machine** (`3` = fully initialized),
  guarded by ARM **LL/SC** (`__ldxr`/`__stlxr`) + `__dmb` so the isolate is
  created exactly once across threads.
- `0x150150150150150` is the SubstrateVM **isolate magic sentinel** handed to the
  caller and reused on re-attach.
- `sub_3AA6560` (→ `sub_3AA65A0`, `sub_3AA7090`) does the heap reservation/commit.

Companion stubs (survey): `attachThread` @0x3A77CF0, `detachThread` @0x3A78020,
`getIsolate` @0x3A78240, `tearDownIsolate` @0x3A78270,
`JNI_CreateJavaVM` @0x3A7EC20, `graal_get_current_thread` @0x3A78140,
`graal_detach_all_threads_and_tear_down_isolate` @0x3A77F10.

### 1.2 `run_main` @ `0x3A7F690` → `JavaMainWrapper.run` (`sub_3A19930`)
```c
__int64 run_main() { return sub_3A19930(); }
```
`sub_3A19930` is the standard SubstrateVM main wrapper:
```c
off_8670AB8(&unk_8670C58, aTheCurrentMach);   // CPU march gate:
   // "...does not support all of the following CPU features... [FP, ASIMD]."
dword_8670E98 = a1;  qword_8670EA0 = a2;       // argc / argv
v2 = loc_3AA6090(&dword_8670E78);              // create + attach main isolate
v2 = sub_3AA6560(&dword_8670E78, v9);
if (v2) sub_3AA64F0(v2, aFailedToCreate);      // "Failed to create the main Isolate."
v17 = sub_3A19B30();                           // <-- runs MainApp.main (JavaMainWrapper.run0)
v3 = sub_3AA62A0();                            // leave IsolateThread + detach
v4 = sub_3AA5F40(0x150150150150150, 0, 0);     // re-attach main thread for shutdown
v5 = sub_3A19CE0();                            // tear down isolate
return v17;
```
So `run_main → sub_3A19930 → **sub_3A19B30**` is the true "call the Java `main`"
site; everything else is isolate create/attach/detach/teardown plus the
`[FP, ASIMD]` march guard.

### 1.3 `MainApp.start` @ `0x3A7F800` (alias `blocklegends_start`)
`IsolateEnterStub__MainApp__start` — **the CEntryPoint the Android host calls**
to enter the already-running isolate and invoke Kotlin `MainApp.start()`.

```c
__int64 blocklegends_start(__int64 a1 /* IsolateThread */) {
  if (!a1) sub_3AA64F0(2, aFailedToEnterT);   // "Failed to enter the specified IsolateThread context."
  // LL/SC enter on the per-thread state word at a1+20 (3 == entered)
  do { v4 = __ldxr(a1+20); v5=(v4==3)?1:v4; } while(__stlxr(v5,a1+20));
  __dmb(0xB);
  result = sub_4739DE0();                      // <-- AOT MainApp.start() body
  atomic_store(3u, (a1 + 20));
}
```
`sub_4739DE0` (AOT `MainApp.start()`) does the SubstrateVM per-method preamble —
stack-overflow check vs the thread stack limit (`*(v0+8)`, → `loc_3AA7650`
StackOverflowError), a safepoint/recurring-callback check (`loc_3B86BE0`), then
calls **`sub_4736150`**, the real start work where Kotlin game code begins.

**Reaching the game:** host (Android) → `MainApp.start`(0x3A7F800) CEntryPoint →
enter isolate thread → `sub_4739DE0` → `sub_4736150`.

Per-module JNI_OnLoad init: `JNI_OnLoad_java`@0x8034F84, `_net`@0x8034FB4,
`_nio`@0x8036ECC, `_zip`@0x8036F08, `_extnet`@0x803474C.

---

## 2. Game loop & rendering (Java GL → ANGLE)

### 2.1 The game loop is AOT Java, not native
```c
void Java_net_blocklegends_os_shared_GLES30_gameLoopTick() { ; }   // @0x80c31b0, single RET
```
`gameLoopTick` is a **bare `RET` no-op** with no callees — a deliberate
placeholder. The per-frame tick lives in the AOT-compiled `MainApp`/render thread
(reached via §1.3). Each frame that Java code calls the GLES wrappers directly to
submit GL work, then `swap*Context` to present.

**Instrumentation consequence:** there is **no single native "tick" dispatch to
hook**. The observable per-frame native boundary is the set of GL submit wrappers
(`glMultiDraw*`, `glDrawElements*`, `glBindVertexArray`, …) plus
`swapGameContext` (present) and `makeGameContext` (bind). To trace a frame, hook
`swapGameContext` / `trySwapBuffers`.

### 2.2 Rendering bridge = thin JNI → ANGLE shim
`net.blocklegends.os.shared.GLES20/30/31` expose the GL API as native methods:
**GLES30 ≈ 150** exported JNI funcs, **GLES31 = 7** (compute/indirect:
`glDispatchCompute`, `glMemoryBarrier`, `glBindImageTexture`,
`glVertexAttribFormat/IFormat/Binding`, `glBindVertexBuffer`). The same class
bundles non-GL platform glue (sound, IAP, ads, voice, sensors, analytics).

**Dispatch model:** `.dynsym` imports only the EGL surface API plus two GL funcs
(`glDrawElementsInstanced`, `glGetString`). Almost all GL entry points are
resolved at runtime via `eglGetProcAddress` into a cached fn-ptr dispatch table
(`off_DC3D3xx`/`off_DC3D4xx`/`off_DC3D5xx`). A global **`byte_DB2CDC0`
("use ANGLE") toggle** selects system EGL vs **ANGLE** pointers
(`off_DB2CDC8`=ANGLE eglGetProcAddress, `off_DB2CE18`=ANGLE eglMakeCurrent,
`off_DB2CE20`=ANGLE eglGetError, `off_DC3D3C8`=ANGLE swap).

Trivial wrapper (lazy table dispatch):
```c
__int64 GLES30_glBindVertexArray(env, this, a3) { return off_DC3D338(a3); }
```

**Representative submit wrapper — `glMultiDrawElementsIndirect` @ `0x80c0538`**
shows the full proc-resolve + ANGLE-vs-fallback pattern (the chunk batch-draw
path):
```c
if (a6 < 1) return;                       // drawcount
if ((byte_DC3D518 & 1) == 0) {
  ProcAddress = byte_DB2CDC0
      ? off_DB2CDC8(aGlmultidrawele)      // ANGLE eglGetProcAddress("glMultiDrawElementsIndirectEXT")
      : eglGetProcAddress(aGlmultidrawele);
  qword_DC3D520 = ProcAddress;            // cache
  if (ProcAddress) { ProcAddress(mode, type, 0, drawcount, stride); return; }
  else byte_DC3D518 = 1;                  // mark unsupported -> fallback
}
// CPU fallback: map indirect buffer, loop glDrawElementsInstanced per cmd
v14 = off_DC3D4A0(36671, 0, 20*drawcount, 1);   // glMapBufferRange(GL_DRAW_INDIRECT_BUFFER)
do { if (count) glDrawElementsInstanced(...); } while(--n);
off_DC3D4A8(36671);                              // glUnmapBuffer
```
The engine prefers hardware `glMultiDrawElementsIndirectEXT` (ANGLE-exposed) and
degrades to a per-command `glDrawElementsInstanced` loop.

**Vulkan path — `nativeIsVulkanEnabled` @ `0x80c2abc`:** not a native renderer
switch; it attaches the `studioEnv` isolate and calls the static Java method
`nativeIsVulkanEnabled ()Z` on the cached `BL-Studio` class (`qword_DB2CF90`,
vtable +904 GetStaticMethodID / +936 CallStaticBooleanMethod).
`nativeSetVulkanEnabled (Z)V` @ `0x80c2c4c` is the mirror setter. Actual Vulkan
rendering is delivered by **ANGLE's Vulkan backend** (selected by `byte_DB2CDC0`);
the GLES wrappers are unchanged — ANGLE translates GLES3/3.1 to Vulkan
underneath. So "Vulkan" = **ANGLE-over-Vulkan**, decided Java-side.

**JNIEnv convention (used by every bridge):** the cleanest example is
`logEvent` @ `0x80bed44` — attach `studioEnv`, cache `jclass`+`jmethodID`, marshal
String + String[], `CallStaticVoidMethod`. The vtable-offset map is reused
everywhere: 248=GetObjectClass, 264=GetMethodID, 184=DeleteLocalRef,
416=CallLongMethod, 904=GetStaticMethodID, 936=CallStaticBooleanMethod,
1128=CallStaticVoidMethod, 1336=NewStringUTF, 1352=GetStringUTFChars, … ,
1840=GetDirectBufferAddress (full key in §5/§6).

---

## 3. Chunk / EGL multi-context pipeline

The engine runs a **pool of EGL contexts sharing one `EGLDisplay` and one shared
GL object namespace**, so worker threads can build chunk meshes / upload VBOs in
parallel with the render thread.

### 3.1 Globals (display / surfaces / contexts)
| Global | Role |
|---|---|
| `qword_DB2CD70` | shared **EGLDisplay** |
| `byte_DB2CDC0` | **use-ANGLE** flag |
| `off_DB2CE18`/`off_DB2CE20`/`off_DC3D3C8` | ANGLE eglMakeCurrent / eglGetError / swap |
| `qword_DB2CD78` | game/splash window **surface** |
| `qword_DB2CD38` / `…40` / `…48` / `…50` | chunk / chunkWaiter / async / load1 surfaces (load2–4 contiguous) |
| `qword_DB2CCE0` | **game context** (`setGameContext`) |
| `qword_DB2CCE8` | splash context |
| `qword_DB2CCF0` | **chunk context** (`setChunkContext`) |
| `qword_DB2CCF8` | **chunkWaiter context** (`setChunkWaiterContext`) |
| `qword_DB2CD00` / `…08` | async / load1 context (load2–4 contiguous) |

`makeSplashContext`/`makeChunkContext`/`makeChunkWaiterContext`/`makeGameContext`/
`makeAsyncContext`/`makeLoad1..4Context` are all the **same shape**: bind that
role's `(display, surface, surface, context)` current on the calling thread.

### 3.2 Context registration — `setGameContext` @ `0x80c3aa4`
Pulls the native `EGLContext` out of a Java `EGLContext` wrapper and stores it:
```c
cls = GetObjectClass(env, a3);                                  // +248
mid = GetMethodID(env, cls, "getNativeHandle", "()J");          // +264
qword_DB2CCE0 = CallLongMethod(env, a3, mid);                   // +416 -> game EGLContext
DeleteLocalRef(env, cls);                                       // +184
```
`setChunkContext`(0x80c3c64)→`qword_DB2CCF0`,
`setChunkWaiterContext`(0x80c3d34)→`qword_DB2CCF8`. The Java side creates the
shared EGL contexts and registers their native handles here.

### 3.3 Bind — `makeGameContext` @ `0x80b6914`
```c
result = byte_DB2CDC0
   ? off_DB2CE18(display, surface, surface, gameCtx)   // ANGLE eglMakeCurrent
   : eglMakeCurrent(display, surface, surface, gameCtx);
if ((byte_DC3D3C0 & 1)==0) { byte_DC3D3C0=1; setThreadPriorityDisplay(result); }  // once: bump render-thread prio
```
`makeChunkContext`@0x80b669c / `makeAsyncContext` / `makeLoad1Context` add error
reporting (`printf("[EGL] makeChunkContext failed: 0x%x", …eglGetError())`).
`makeSplashContext`(0x80b6648) reuses the game window surface with the splash
context; `makeChunkWaiterContext`(0x80b6744) binds `qword_DB2CD40`/`…CCF8`.

### 3.4 Present — `swapGameContext` @ `0x80b7220` (frame present + watchdog)
```c
if (trySwapBuffers()) { dword_DC3D3D0 = 0; return 1; }   // success -> reset fail counter
if (dword_DC3D3D0++ < 3) return 0;                       // tolerate transient fails
if (count <= 0x63) { log("[EGL] Swap buffer %d kez basarisiz oldu", n); return 0; }
log("[EGL] Swap buffer surekli basarisiz... context lost olabilir", n);
restartApp();                                            // EGL context lost -> restart
```
`swapChunkContext` @ `0x80b6e40` shows **off-thread present under a mutex**:
```c
if (pthread_mutex_trylock(&stru_DB2CD80)) return ...;   // non-blocking; skip if busy
MakeCurrent(display, 0, 0, chunkCtx);                   // bind chunk ctx with no surface
off_DC3D3C8(display, gameSurf);                         // ANGLE / eglSwapBuffers
pthread_mutex_unlock(&stru_DB2CD80);
```
`destroyContext`@0x80b6d28 = `eglMakeCurrent(display,0,0,0)` (unbind).
**`stru_DB2CD80` is the EGL serialization mutex** guarding cross-thread
make-current/swap so the parallel chunk/loader contexts don't race the render
thread on the shared display.

### 3.5 Surface lifecycle — `onSurfaceResume` @ `0x80c66a8`
After the Android surface is recreated, re-attach to the game isolate and notify
Java (`GNatives.onSurfaceResume()V`, +904/+1128). The surface lifecycle is owned
Java-side; native just bridges resume so the GL contexts/surfaces are rebuilt and
`make*Context` can rebind. Siblings: `onSurfaceChanged`@0x80c6544,
`onSurfaceDestroyed`@0x80c6604, `onSurfacePause`@0x80c674c.

**Turkish EGL log strings** ("kez basarisiz oldu" = "failed N times",
"context lost olabilir" = "context may be lost") confirm the
**CraftRise/SonOyuncu code lineage**.

---

## 4. Networking bridge (`gnatives.GnsNative` → GameNetworkingSockets)

Bridge span: `Java_gnatives_GnsNative_*` @ `0x81138A8 – 0x8122AE8`.

**The whole transport is Valve GameNetworkingSockets (GNS / "Steam sockets"),
`dlopen`-loaded at runtime** (NOT statically linked, NOT Steam-online). The JNI
layer is a thin shim that resolves the GNS **flat C API**
(`SteamAPI_ISteamNetworkingSockets_*`) by `dlsym` and forwards calls. It is
**direct-IP / client-server only** (`ConnectByIPAddress` + `CreateListenSocketIP`),
no STUN / rendezvous / SDR relay / P2P-ICE (confirmed by the embedded assert
`st->signalling==0`).

### 4.1 Enumerated entry points
```
0x81138a8 init                 0x811eef8 getState            0x8120a70 sendBatch
0x811a6b8 lastError            0x811f344 getRemoteAddress    0x8121778 getConnectionStatus
0x811a88c connect              0x811f534 receive             0x8121b48 getConnectionQuality
0x811bd78 createServer         0x811f810 receiveBatch        0x8121d3c setConnectionSendRate
0x811c5ec serverAccept         0x811fd3c send                0x8121efc closeConnection
0x811ca28 closeServer          0x81207c8 waitForMessages     0x81220cc getServerPollGroup
0x811d2e8 pollCallbacks        0x812099c pollGroupWaitForMessages  0x81220ec createPollGroup
                               0x8122124 destroyPollGroup    0x812214c setConnectionPollGroup
                               0x8122280 pollGroupReceive
```

### 4.2 dlopen loader + flat-API pointers
`init` → `sub_8113A54` (one-time, guarded by mutex + `byte_DCC08A8`). Tries
`libGameNetworkingSockets.so` / `.dll` / `.dylib` (error
*"Unable to load GameNetworkingSockets: "* + `dlerror()`), then `dlsym`s ~22 flat
entry points. Two singletons thread through every call:
`qword_DCC07B8` = `ISteamNetworkingSockets` (factory
`SteamAPI_SteamNetworkingSockets_v009`), `qword_DCC0808` =
`ISteamNetworkingUtils` (factory `…Utils_v003`). The global ConnectionStatusChanged
callback (`SetConfigValue` id 201) is `sub_81173A4`.

**Custom fork extensions** — `SteamNetworkingSockets_CRGetConnRecvWakeFd` /
`CRGetPollGroupRecvWakeFd` (note the **"CR" = CraftRise** prefix) expose a
pollable fd so the engine can `epoll`/`poll` for inbound data instead of
busy-spinning.

### 4.3 connect / createServer (direct-IP, 12 tuning config values)
`connect`: parse `"ip:port"` via `SteamNetworkingIPAddr_ParseString` (err
"Invalid GNS address: …"), fill 12 `SteamNetworkingConfigValue_t`
(`sub_811ADE4`), `ConnectByIPAddress(addr, 12, opts)`, then alloc a 0xA0
conn-tracker and insert into the global hash map at `qword_DCC08A0+104`.
`createServer`: build lane priority[]+weight[] from two Java int[]
(`sub_811B990` → `ConfigureConnectionLanes`), `CreatePollGroup`,
`CreateListenSocketIP(addr, 12, opts)`.

The 12 config values (IDs from `.rodata`, values from `qword_DCC08A0+184..220`):

| ID | Name | Set value |
|----|------|-----------|
| 23 | IP_AllowWithoutAuth | **1** (standalone GNS, no Steam identity/auth) |
| 34 | Unencrypted | **0** (transport encryption left ON) |
| 9/47/48/49 | Send/Recv buffer sizes, RecvBufferMessages, RecvMaxMessageSize | configurable |
| 10/11/12 | SendRateMin / SendRateMax / NagleTime | configurable |
| 32 | MTU_PacketSize | configurable |
| 24/25 | TimeoutInitial / TimeoutConnected | configurable |

### 4.4 send / receive
`send(env, this, connObj, ByteBuffer, len, lane, flags)`:
```c
v15 = (*(env+1840))(env, a4);                // GetDirectBufferAddress(buf)
v16 = off_DCC0800(qword_DCC0808, len);       // Utils::AllocateMessage(len)
memcpy(*v16, v15, len);                       // raw ByteBuffer bytes -> message->m_pData
*((DWORD*)v16 + 3)  = *(DWORD*)connObj;       // m_conn   (HSteamNetConnection)
*((WORD *)v16 + 104)= max(lane,0);            // m_idxLane
*((DWORD*)v16 + 49) = flags (| 0x10 cond.);   // m_nFlags  (0x10 = UseCurrentThread)
off_DCC0810(qword_DCC07B8, 1, &v16, &outMsgNum, 1);  // SendMessages
```
- **Payload is the raw `ByteBuffer`, shipped verbatim** — no native serialization,
  no length-prefix framing, no compression. All of that is done **Java-side**
  (`net.blocklegends.network.Packet` → byte[] → direct ByteBuffer) before the
  call; GNS supplies its own wire framing/fragmentation/reassembly.
- **Reliability** = GNS `flags` (0 unreliable, 8 reliable, 1 NoNagle, 4 NoDelay;
  shim ORs `0x10` UseCurrentThread for low-latency sends).
- **Channel** = `lane` → `m_idxLane`; lanes pre-configured from two Java int[].
- `sendBatch`(0x8120a70) = bulk (N messages, one `SendMessages`).

`receive`: `GetDirectBufferAddress` → `ReceiveMessagesOnConnection(hConn,&msg,1)`
→ `memcpy` if `m_cbSize <= cap` (else "received message exceeds Java buffer") →
`Release`. `receiveBatch` / `pollGroupReceive` are the multi-message / server
poll-group variants.

### 4.5 pollCallbacks — state machine / census / reaper
`pollCallbacks` (0x811d2e8, 1403 insns) drives `RunCallbacks`, iterates the
tracked-connection map, calls `GetConnectionInfo`/`GetConnectionRealTimeStatus`,
classifies by GNS state, logs a census and **reaps stale connections**:
```
[GNS-native census] g_connections=%d connected=%d connecting=%d findroute=%d
  dead=%d noinfo=%d stuckConnecting=%d reaped=%d reapEnabled=%d
```
States map to `k_ESteamNetworkingConnectionState_{Connecting=1, FindingRoute=2,
Connected=3, ClosedByPeer=0xfffffffe, ProblemDetectedLocally=0xffffffff}`.

### 4.6 Endpoints / crypto boundary
- **No hardcoded endpoints/ports/STUN/relay/rendezvous** anywhere in native —
  `find_regex` + full strings sweep returned nothing; host:port arrives from Java
  as the `"ip:port"` string. `st->signalling==0` (no P2P/signalling transport).
  Game/login server IPs live in the Java/Kotlin layer.
- **Transport crypto** = GNS Curve25519 ECDH + per-packet **AES-256-GCM** AEAD,
  left **enabled** (`Unencrypted(34)=0`). `IP_AllowWithoutAuth(23)=1` only drops
  the Steam *identity/cert* requirement (self-hosted server) — it does **not**
  disable data-channel encryption.
- **App-layer** obfuscation/compression is Java-side: Zstd (`ZstdNative`, §5) and
  Opus (`OpusNative`, §5) are *separate* modules applied **before** the buffer
  reaches `GnsNative_send`; no extra app-layer cipher exists inside the bridge.

---

## 5. Media (voice / codec / texture / compression)

> JNIEnv vtable-offset key (validated vs named libktx/setenv calls):
> +904 GetStaticMethodID · +1128 CallStaticVoidMethod · +184 DeleteLocalRef ·
> +1336 NewStringUTF · +1352 GetStringUTFChars · +1360 ReleaseStringUTFChars ·
> +1368 GetArrayLength · +1472/+1488/+1496 Get{Byte,Short,Int}ArrayElements ·
> +1536/+1552/+1560 Release{Byte,Short,Int}ArrayElements ·
> +1776/+1784 Get/ReleasePrimitiveArrayCritical · +1832 NewDirectByteBuffer ·
> +1840 GetDirectBufferAddress.

### 5.1 Voice — WebRTC APM (AEC3/AGC/NS) + Opus
**`VoiceProcessingNative_createProcessor` @ 0x8182850** builds a WebRTC
`AudioProcessing` (APM) instance. Enforces 10 ms integer frames (`sampleRate/100`),
heap-allocates a 0x50 processor struct (APM ptr +40, sampleRate +48, channels +52,
samplesPerChannel +56), fills the APM config, then
`AudioProcessingBuilder::Create` → `ApplyConfig`. Errors: "bad voice processor
config", "voice processor sample rate must provide 10ms integer frames",
"AudioProcessingBuilder returned null". Full **AEC3** stack confirmed by submodule
symbols (`FullBandErleEstimator`, `SubbandErleEstimator`, `StationarityEstimator`,
`SuppressionGain`, `RenderSignalAnalyzer`, `AudioBuffer::Split/MergeFrequencyBands`).

**`processCapture` @ 0x8182f24 / `processReverse` @ 0x8182d44** — int16 PCM pinned
via `GetShortArrayElements (+1488)`, range-checked, in-place under the processor
mutex: capture → APM vtable **+128 ProcessStream**, reverse → **+144
ProcessReverseStream** (far-end render reference for AEC). `setStreamDelayMs`
@ 0x8182c8c feeds AEC alignment delay.

**Opus — `OpusNative_*`:** `createEncoder` @ 0x8181d6c =
`opus_encoder_create(rate, ch, 2048 /*OPUS_APPLICATION_VOIP*/, &err)` then ctls:
BITRATE(4002), COMPLEXITY(4010, ≤10), VBR(4006)=1, SIGNAL(4024)=VOICE(3001),
DTX(4016)=0. `encode` @ 0x8182154, `decode` @ 0x8182414 (last arg = FEC/PLC flag),
`decodePlc` @ 0x8182664, `configureEncoderLossProtection` @ 0x8182084
(inband-FEC + packet-loss-%).

**Full pipeline:** mic → APM(AEC3/AGC/NS) ProcessStream → Opus VOIP encode →
network; network → Opus decode(+FEC/PLC) → APM ProcessReverseStream(render ref) →
playback. (High-level `GLES30.voice*` wrappers @0x80BAB*–0x80BBF* are the Java
entry surface.)

### 5.2 Compression — Zstd (network) + LZ4 (per-frame)
**Zstd — `ZstdNative_*` (0x8122c40 region)**, a full statically-linked libzstd.
- `compress` @ 0x8123c28 → `sub_8123EC0`: byte[] pinned (+1472), **thread-local
  CCtx** (`calloc 0x1498`, freed via `__cxa_thread_atexit`). `dictId==0` →
  classic preset table at `unk_8661978`; `dictId!=0` → locks the global
  **dictionary hash-map** (`xmmword_DCC0918`, open-addressed by dictId) and uses a
  `compress_usingCDict`-equivalent. Errors "dictionary not loaded", "create CCtx
  failed".
- `decompress` @ 0x812447c → `sub_8124708` (symmetric).
- `loadDictionary` @ 0x8122da8: computes dictId (magic `0xEC30A437` embedded
  dictID, else FNV-1a hash), builds CDict+DDict, inserts both into the global
  dictId→{CDict,DDict,rawcopy} map. `trainFromBuffer` @ 0x8124f98 = ZDICT.

  **Dictionary-keyed Zstd is the signature of network packet / asset-stream
  compression** (shared trained dictionaries per channel) — same family as the
  CraftRise protocol stack. Per-dictId CDict map + thread-local CCtx = per-conn
  streaming compression, not one-shot world-save. (A second `Java_cr_zstd_
  ZstdNative_*` binding @0x81253BC also exists.)

**LZ4 — `GNative_LZ4_*` @ 0x80b1f88** (`compress_limitedOutput`, `compressHC`,
`decompress_fast/_safe`, `compressBound`). Buffers pinned with
`GetPrimitiveArrayCritical (+1776)` (max-throughput, GC-paused) + a 0x4020-byte
on-stack ext-state. Critical-array + stack hash-table = the **hot per-frame path:
chunk/mesh & GPU upload buffers** (latency-critical, vs Zstd's network path).

### 5.3 Textures — KTX2/BasisU + AMD FSR 1.0
**KTX/Basis — `KTXTexture_*` @ 0x80ae35c (libktx):**
```c
nativeCreateFromMemory: data = GetDirectBufferAddress(+1840, byteBuffer);
                        ktxTexture_CreateFromMemory(data, len, 1, &tex);   // KTX1/KTX2
nativeTranscodeBasisU : ktxTexture2_TranscodeBasis(tex, fmt /*a4*/, 0);     // BasisU/UASTC -> GPU fmt
nativeGetImageData    : return NewDirectByteBuffer(+1832, ktxTexture_GetData(tex), size);  // zero-copy back
```
Pipeline: KTX2 container (zero-copy direct ByteBuffer) → BasisU/UASTC transcode to
the device's preferred GPU format (`a4` = ASTC/ETC2/BC7) → image data back as a
direct buffer for `glCompressedTexImage2D`.

**AMD FSR 1.0 — `GNative_Fsr*` @ 0x80b1c54 / 0x80b1d5c** — CPU-side constant-block
generators writing into Java int[]/IntBuffer (pinned +1496 / released +1560).
`FsrEasuGetConsts` (Edge-Adaptive Spatial Upsampling) fills con0..con3 vec4s from
input viewport + render size; `FsrRcasGetConsts` (Robust Contrast-Adaptive
Sharpening) packs `sharpness → exp2f(-a2)` into con[0..1]. **GPU upscaling:**
render low-res → EASU upscale → RCAS sharpen → present; consts recomputed natively
each resize and uploaded as shader uniforms.

---

## 6. Security / Integrity / IAP

> **KEY FINDING: there is NO native integrity verification and NO native
> anti-cheat.** The integrity/IAP/auth callbacks are pure **cross-VM marshaling
> bridges** — they relay payloads from the host ART VM into the GraalVM game
> isolate, where managed AOT code (and/or the server) makes all trust decisions.

### 6.1 The isolate-attach helper
`initGameEnv` @ 0x80aa144:
```c
AttachCurrentThreadAsDaemon(sjavaVM /*DB2CF28*/, &gameEnv, "BL-Worker");
cls = FindClass(gameEnv, "android/Natives");   // +48
qword_DB2CF30 = NewGlobalRef(gameEnv, cls);    // +168  (cached engine class)
```

### 6.2 Play Integrity callbacks
`onIntegritySuccess` @ 0x80c71f4, `onIntegrityFailure` @ 0x80c7090,
`onIntegrityThrow` @ 0x80c6f2c are **byte-for-byte identical** apart from the
cached method-id slot (success=`unk_8672CB0`, failure=`unk_8672C90`,
throw=`unk_8672C70`):
```c
initGameEnv();
mid = GetStaticMethodID(gameEnv, android.Natives, "onIntegrity{Success|Failure|Throw}",
                        "(Ljava/lang/String;J)V");        // cached
utf = GetStringUTFChars(hostEnv, a3 /*verdict/token jstring*/, 0);
js  = NewStringUTF(gameEnv, utf);
ReleaseStringUTFChars(hostEnv, a3, utf);
CallStaticVoidMethod(gameEnv, android.Natives, mid, js, a4 /*jlong nonce*/);  // +1128
DeleteLocalRef(gameEnv, js);
```
The Play Integrity verdict String + long are simply re-marshaled host VM →
GraalVM isolate and handed to AOT `android.Natives.onIntegrity*`. **Native
neither parses, validates, nor gates on the token.** `runIntegrity` @ 0x80c1a28 is
the inverse (engine *requesting* a check via the **"BL-Studio"** isolate,
`qword_DB2CFB0.run("(Ljava/lang/String;J)V")`). `gc_fetchIdentitySignature`
@ 0x80c34dc is an **empty no-op stub**.

The same bridge pattern (→ `CallStaticVoidMethod` on `android.Natives`) serves the
sibling auth/IAP/ads callbacks: `onTokenReceived` / `onFCMTokenReceived`
(@0x80c7748, FCM/auth, heavily logged to logcat tag `IMG`), `onGoogleSignFailed`,
`onAppleSignFailed`, `onGcAuthSuccess`, `onGcServerAuthCode`, `onProcessPurchases`,
`onBillingSetupComplete`, `onUserEarnedReward`.

### 6.3 IAP — `onProcessPurchases` @ 0x80c6d2c
Identical bridge: `GetStringUTFChars(hostEnv, purchaseJson)` →
`NewStringUTF(gameEnv)` → `CallStaticVoidMethod(android.Natives,
onProcessPurchases_mid, str)` (single String = Google Play Billing purchase
JSON). **Purchase verification/acknowledgement is entirely managed-side**; native
only ferries the billing payload. `onBillingSetupComplete` @0x80c6e88 and
`onUserEarnedReward` (rewarded-ad → currency) follow the same path. IAP pricing
getters `getProductPrice`/`…Symbol`/`…Code` @0x80B88*–0x80B8F* are platform
queries, not verification.

### 6.4 `setenv` bridge @ 0x80c39d0
`GNatives.setenv(key, value)` = thin pass-through: `GetStringUTFChars` ×2 →
`setenv(k, v, 1 /*overwrite*/)` → release. Lets managed code set **arbitrary
process env vars** (GraalVM/SubstrateVM tuning, locale, GL/EGL, `MALLOC_`/heap,
feature flags) before/around engine init. **No filtering or allow-list in native.**

### 6.5 Security string sweep (find_regex on c3323093)
- Integrity surface is small and entirely bridge-side: only `runIntegrity`,
  `onIntegrity{Success,Failure,Throw}`, `gc_fetchIdentitySignature` exports +
  the method-name strings. **No** `hwid`, `tamper`, `anti-cheat`, `ban`, `cheat`,
  `license`, `attest`, `nonce`, `secret` strings anywhere in native.
- `"signature"` hits are library-internal only (`png_get_signature`,
  `cmsGetTagSignature` lcms2, "Bad tag signature %lx found.") — image/ICC parsing.
- `"verify"`/`"Verify"` hits are GraalVM runtime internals (`VerifyClassname`,
  `VerifyFixClassname`) and Opus DSP — not integrity checks.

**Conclusion:** the native layer is a **media codec farm + a thin JNI marshaling
shim** between the Android ART VM and the GraalVM game/studio isolates. All trust
decisions (Play Integrity verdict consumption, purchase verification, identity
signatures, ban/anti-cheat) are deferred to AOT-compiled managed code and/or the
server — **none are enforced in native.**

---

## 7. GraalVM Native Image reversing notes

### 7.1 What IS recoverable
- **The JNI / CEntryPoint boundary is the readable surface.** Every
  `Java_*`/`IsolateEnterStub__*` thunk keeps its **original class+method+signature
  strings** and calls real C/C++ libraries through the JNIEnv vtable. 747 such
  `Java_*` stubs (336 `Java_net_blocklegends*`, 88 `Java_gnatives*`) plus the
  isolate stubs are fully named and decompile cleanly. This is where 100% of the
  useful semantics in 07a/07b/07c came from.
- **Statically-linked C/C++ libraries decompile normally** — WebRTC APM, Opus,
  Zstd, LZ4, libpng, libktx, FSR, GameNetworkingSockets flat API: all retain
  symbol names and standard call shapes. Behavior is recoverable by matching
  against the upstream open-source code.
- **SubstrateVM runtime plumbing is identifiable by pattern**: the LL/SC isolate
  state machine (`dword_14`, magic `0x150150150150150`), the per-method
  stack-limit check (`*(threadbase+8)`), safepoint/recurring-callback stubs
  (`loc_3B86BE0`), GC write barriers and allocation slow-paths (the top-xref
  functions: `sub_3A9F950` 165k xrefs, `sub_3A2AEF0` 152k, etc.). Once recognized
  these can be filtered out as noise.
- **Global state is reachable.** The EGL topology globals (`qword_DB2CCE0…`,
  `byte_DB2CDC0`), the GNS singletons (`qword_DCC07B8`/`…0808`) and config blob
  (`qword_DCC08A0+…`), and the dictionary map (`xmmword_DCC0918`) are all static
  data the stubs read/write — they give a near-complete picture of runtime wiring.

### 7.2 What ISN'T recoverable
- **The AOT-compiled Kotlin/Java game logic is effectively opaque.** All 41,294
  `sub_*` functions are nameless; there is **no DEX, no class file, no method
  table** to recover original names from. The game loop, world model, packet
  serialization (`net.blocklegends.network.Packet`), integrity-verdict
  consumption, and anti-cheat logic all live here as anonymous `sub_*`.
- **Java-side control flow is invisible at the native edge.** Because the per-frame
  tick, packet (de)serialization, length-framing, and trust gating are all AOT
  Kotlin, the native stubs show you *the inputs/outputs* (a raw ByteBuffer, a
  verdict String) but not *what is done with them*. `gameLoopTick` being a bare
  `RET` is the canonical example.
- **No reflection/JNI string metadata for the AOT classes** beyond what an
  individual CEntryPoint hard-codes (e.g. `"android/Natives"`,
  `"onIntegritySuccess"`, `"getNativeHandle"`). You only learn the names the native
  side explicitly references.

### 7.3 How to continue (method)
1. **String-xref method (primary).** Pick a behavior, grep its log/error string in
   `strings_all.txt`, xref to the `sub_*` that loads it, and walk callers/callees.
   This is how the GNS census, the Turkish EGL watchdog, and the Zstd error paths
   were all located. The 378k-string table is the single best index into the
   anonymous `sub_*` space.
2. **The `.svm_heap` / image-heap metadata.** The huge first `LOAD`
   (0x0–0x3820658) is the SubstrateVM **image heap**: pre-initialized Java objects,
   interned strings, class metadata and the `DynamicHub` (Class) structures laid
   down at build time. Parsing it (or just scanning it for UTF-8 class/method
   names and object references) can recover class layouts and constant data that
   the AOT code indexes into — the closest thing to a symbol table this binary
   has. Cross-reference image-heap string offsets against code xrefs to attach
   names to `sub_*`.
3. **The JNI boundary as the readable surface (anchor outward).** Start from a
   named `Java_*` stub, follow the first AOT `sub_*` it calls into managed code
   (e.g. `MainApp.start` → `sub_4739DE0` → `sub_4736150`), and name functions
   incrementally by what data/strings they touch. Each named library call (an
   `opus_*`, `ZSTD_*`, `eglMakeCurrent`, `SteamAPI_*`) is a fixed anchor that
   constrains the surrounding anonymous code.
4. **Dynamic confirmation** (see §8) to validate static guesses about the AOT
   side that cannot be read directly.

---

## 8. Next steps for deeper RE

**Specific `sub_*` worth tracing (static):**
- **`sub_4736150`** — the real body of `MainApp.start()` (after the SubstrateVM
  preamble in `sub_4739DE0`). This is the doorway into all AOT game init; naming
  its callees is the highest-leverage next move.
- **`sub_3A19B30`** — `JavaMainWrapper.run0`, i.e. the AOT `MainApp.main`. Pairs
  with `sub_4736150` to bracket the whole managed startup.
- **`sub_81173A4`** — the GNS global ConnectionStatusChanged callback; trace it to
  recover how connection-state transitions are surfaced to Kotlin (the live
  netcode state machine).
- **`sub_8123EC0` / `sub_8124708`** and the dictionary map at `xmmword_DCC0918` —
  to recover the **Zstd dictionary id ↔ channel mapping**; combined with the
  Java-side `Packet` code this yields the packet-compression keys (the original
  07b/07c goal). `loadDictionary`@0x8122da8 callers in the image heap will reveal
  the embedded dictionaries.
- **`sub_811ADE4`** — the 12-value GNS config filler; map each `.rodata` ID to the
  live `qword_DCC08A0+…` config blob to dump the exact tuning the server runs.
- **`runIntegrity`@0x80c1a28 → `qword_DB2CFB0.run`** in the **BL-Studio** isolate —
  the AOT consumer of the Play-Integrity request; trace into the studio isolate to
  see how/whether the verdict actually gates play (it is *not* gated in native).

**Best native hook points (frame/transport/lifecycle):**
- `swapGameContext`(0x80b7220) / `trySwapBuffers` — frame timing & present.
- `make*Context` / `set*Context` — EGL multi-context topology.
- `GnsNative_send`(0x811fd3c) / `receive`(0x811f534) — **the cleanest wire-capture
  point**: hook `GetDirectBufferAddress` + `len` to dump fully-built, Zstd-already-
  compressed packet buffers right before they hit GNS (and inbound after recv).
- `ZstdNative.compress`/`decompress` and `OpusNative.encode`/`decode` — to dump
  plaintext packet/voice frames *before* compression/after decompression.
- `setGameContext`/`onSurfaceResume` — engine init / surface lifecycle.

**Frida-on-native caveats (given the anti-cheat posture):**
- The **anti-cheat is server-side + managed**, not native (§6) — so native Frida
  hooks on the codec/transport stubs are unlikely to trip a *native* tamper check
  (there isn't one). The real risk is **Play Integrity** (`onIntegrity*`): a
  rooted/Frida-detectable device fails the host-side attestation that the managed
  code forwards to the server, which can gate play independently of any native
  hook. Frida's presence (process maps, `frida-agent`, gum ports) is exactly what
  Play Integrity's hardware attestation is designed to catch.
- Because the game is **GraalVM AOT, not ART**, **Java-method hooking does not
  apply** — `Java.use`/`Java.perform` see only the thin host shim, not the game
  classes. All game logic must be hooked at the **native `sub_*` / JNI-stub
  level** (`Interceptor.attach` on addresses), or at the **C-library boundary**
  (`opus_*`, `ZSTD_*`, `SteamAPI_*`, `eglSwapBuffers`).
- GNS is **`dlopen`-loaded**: hook `dlopen`/`dlsym` (or wait for `byte_DCC08A8`
  init) before attaching to the `SteamAPI_ISteamNetworkingSockets_*` pointers in
  `qword_DCC07B8` — they don't exist until `init`(`sub_8113A54`) runs.
- The 229 MB image + ~47 s analysis warmup means **address stability matters**:
  pin to the named exports / string-anchored offsets above rather than blind
  `sub_*` addresses across rebuilds.
- The `setenv`(0x80c39d0) unfiltered pass-through is a soft spot worth watching —
  managed code can set SubstrateVM/GL env knobs through it, and it is a candidate
  for influencing engine init from the managed side.

---

## Appendix — key address quick-reference

| Subsystem | Function | Addr |
|---|---|---|
| Bootstrap | createIsolate / run_main / MainApp.start | 0x3A77DF0 / 0x3A7F690 / 0x3A7F800 |
| Bootstrap | JavaMainWrapper.run / AOT start / start body | sub_3A19930 / sub_4739DE0 / sub_4736150 |
| Loop/Render | gameLoopTick (RET) / glMultiDrawElementsIndirect | 0x80c31b0 / 0x80c0538 |
| Render | nativeIsVulkanEnabled / logEvent | 0x80c2abc / 0x80bed44 |
| EGL pipeline | setGameContext / makeGameContext / swapGameContext / swapChunkContext | 0x80c3aa4 / 0x80b6914 / 0x80b7220 / 0x80b6e40 |
| EGL pipeline | onSurfaceResume / destroyContext | 0x80c66a8 / 0x80b6d28 |
| Networking | GnsNative init / connect / createServer / pollCallbacks | 0x81138a8 / 0x811a88c / 0x811bd78 / 0x811d2e8 |
| Networking | send / receive / sendBatch / pollGroupReceive | 0x811fd3c / 0x811f534 / 0x8120a70 / 0x8122280 |
| Voice | VoiceProcessing createProcessor / processCapture / processReverse | 0x8182850 / 0x8182f24 / 0x8182d44 |
| Voice | Opus createEncoder / encode / decode / decodePlc | 0x8181d6c / 0x8182154 / 0x8182414 / 0x8182664 |
| Compression | Zstd loadDictionary / compress / decompress / trainFromBuffer | 0x8122da8 / 0x8123c28 / 0x812447c / 0x8124f98 |
| Compression | LZ4 region | 0x80b1f88 |
| Textures | KTX createFromMemory / transcodeBasisU / getImageData | 0x80ae35c / 0x80ae3d0 / 0x80ae414 |
| Textures | FsrRcasGetConsts / FsrEasuGetConsts | 0x80b1c54 / 0x80b1d5c |
| Security | initGameEnv / onIntegrity{Success,Failure,Throw} | 0x80aa144 / 0x80c71f4 / 0x80c7090 / 0x80c6f2c |
| Security | runIntegrity / onProcessPurchases / setenv / gc_fetchIdentitySignature | 0x80c1a28 / 0x80c6d2c / 0x80c39d0 / 0x80c34dc |
