# Block Legends — Native Engine Core (libblocklegends.so)

Binary: `libblocklegends.so` (229 MB AArch64 GraalVM Native Image / SubstrateVM).
IDA session: `c3323093`. All addresses are file/image offsets (imagebase 0x0).

Block Legends is a Minecraft/CraftRise-derived voxel engine. The game logic is
Kotlin/Java AOT-compiled by GraalVM into this `.so`. Java methods are unnamed
(`sub_*`); the **readable boundary** is the set of `CEntryPoint`/JNI stubs that
carry the original method/class strings and JNIEnv vtable calls. This document
follows those boundaries.

---

## 1. GraalVM / SubstrateVM bootstrap

The engine starts as a normal SubstrateVM isolate. Three entry points matter.

### 1.1 `createIsolate` @ `0x3A77DF0`  (alias `graal_create_isolate`)
`IsolateEnterStub__CEntryPointNativeFunctions__createIsolate`. This is the
SubstrateVM C entry point that allocates and maps the isolate heap.

```c
result = loc_3AA6090();                  // reserve/commit isolate heap
if (!result) {
  if (atomic_load(&dword_E8)) goto LABEL_17;
  // LL/SC spinlock on the isolate state word dword_14 (3 == "initialized")
  do { v6 = __ldxr(&dword_14); v7 = (v6==3)?1:v6; } while(__stlxr(v7,&dword_14));
  __dmb(0xB);
  ...
  result = sub_3AA6560(a1, v5);          // isolate/heap object construction
}
if (!result) {
  if (a2) *a2 = 0x150150150150150LL;     // isolate sentinel/magic written to out-param
  if (a3) *a3 = 0;
  atomic_store(3u, &dword_14);           // mark isolate state = initialized
  return 0;
}
```

Key mechanics:
- `dword_14` is the global **isolate-state machine**; value `3` = fully
  initialized. Access is via ARM **LL/SC** (`__ldxr`/`__stlxr`) + `__dmb` so the
  isolate is created exactly once across threads.
- `0x150150150150150` is the SubstrateVM **isolate magic sentinel** handed back
  to the caller (reused later in re-attach, see below).
- `sub_3AA6560` (callees `sub_3AA65A0`, `sub_3AA7090`) does the actual heap
  reservation/commit for the image heap.

### 1.2 `run_main` @ `0x3A7F690`  (alias `IsolateEnterStub__JavaMainWrapper__run`)
A one-line tail-call into the AOT `JavaMainWrapper.run`:
```c
__int64 run_main() { return sub_3A19930(); }
```

`sub_3A19930` = **JavaMainWrapper.run** — the standard SubstrateVM main wrapper:
```c
off_8670AB8(&unk_8670C58, aTheCurrentMach);   // CPU-feature gate:
   // "...does not support all of the following CPU features... [FP, ASIMD]."
dword_8670E78 = 4;                            // arg struct: nParams
dword_8670E98 = a1;  qword_8670EA0 = a2;      // argc / argv
byte_8670EAC = 0; byte_8670EAD = 1;
v2 = loc_3AA6090(&dword_8670E78);             // create + attach main isolate
...
v2 = sub_3AA6560(&dword_8670E78, v9);
if (v2) sub_3AA64F0(v2, aFailedToCreate);     // "Failed to create the main Isolate."
v17 = sub_3A19B30();                          // <-- runs MainApp.main (JavaMainWrapper.run0)
v3 = sub_3AA62A0();                           // leave IsolateThread + detach thread
if (v3) sub_3AA64F0(v3, aFailedToLeaveT);     // "Failed to leave the current IsolateThread..."
v4 = sub_3AA5F40(0x150150150150150, 0, 0);    // re-attach main thread for shutdown
if (v4) sub_3AA64F0(v4, aFailedToReAtta);     // "Failed to re-attach the main thread..."
...
v5 = sub_3A19CE0();                           // tear down isolate
v6 = sub_3AA62A0(v5);
return v17;
```
So `run_main` → `sub_3A19930` → **`sub_3A19B30`** is the true "call the Java
`main`" site; everything around it is isolate create/attach/detach/teardown and
the `[FP, ASIMD]` march guard.

### 1.3 `MainApp.start` @ `0x3A7F800`  (alias `blocklegends_start`)
`IsolateEnterStub__MainApp__start`. This is the **CEntryPoint the Android host
calls** (e.g. from the Activity/JNI) to enter the already-running isolate and
invoke the Kotlin `MainApp.start()`.

```c
__int64 blocklegends_start(__int64 a1 /* IsolateThread */) {
  if (!a1) sub_3AA64F0(2, aFailedToEnterT);   // "Failed to enter the specified IsolateThread context."
  if (atomic_load((a1 + 232))) goto LABEL_12; // thread already entered?
  // LL/SC enter on the per-thread state word at a1+20 (3 == entered)
  do { v4 = __ldxr(a1+20); v5=(v4==3)?1:v4; } while(__stlxr(v5,a1+20));
  __dmb(0xB);
  ...
  if (*((_BYTE*)&word_38 + off_825E678)) sub_3A2AEF0(off_825E678, off_825E680);
  result = sub_4739DE0();                      // <-- AOT MainApp.start() body
  atomic_store(3u, (a1 + 20));                 // mark thread entered
  return result;
}
```

`sub_4739DE0` is the AOT-compiled `MainApp.start()`:
```c
if ((uintptr_t)&v5 <= *(_QWORD*)(v0+8))   // stack-overflow check vs thread stack limit
  loc_3AA7650();                          // throw StackOverflowError
v1 = sub_4736150();                       // real start work
... safepoint/recurring-callback: if counter hits 0 -> loc_3B86BE0(v1)
```
Reaching the game's Java entry: **host (Android) → `MainApp.start`(0x3A7F800)
CEntryPoint → enter isolate thread → `sub_4739DE0` (`MainApp.start` body) →
`sub_4736150`**. The `*(v0+8)` stack-limit test and `loc_3B86BE0` safepoint
callback are SubstrateVM thread plumbing present on every AOT method.

---

## 2. Game loop

### `gameLoopTick` @ `0x80c31b0`
```c
void Java_net_blocklegends_os_shared_GLES30_gameLoopTick() { ; }   // single RET
```
**The JNI `gameLoopTick` is an empty `RET` no-op.** It has no callees. This is a
deliberate placeholder: the per-frame engine tick is **not** dispatched from
native code. The frame loop lives in the AOT-compiled Kotlin/Java
`MainApp`/render thread (reached via §1.3). Each frame, that Java code calls the
GLES30/31 wrapper natives directly (the §3 functions) to submit GL work and then
`swap*Context` (§4) to present.

Consequence for instrumentation: there is **no single native "tick" dispatch to
hook**. The per-frame engine entry is AOT Java; the observable native per-frame
boundary is the set of GL submit wrappers (`glMultiDraw*`, `glDrawElements*`,
`glBindVertexArray`, …) plus `swapGameContext` (present) and `makeGameContext`
(bind). To trace a frame, hook `swapGameContext`/`trySwapBuffers`.

---

## 3. Rendering bridge (Java GL → ANGLE)

The Java class `net.blocklegends.os.shared.GLES30` (plus `GLES20`, `GLES31`)
exposes the GL API as native methods. Counts found: **GLES30 ≈ 150 exported
JNI functions**, **GLES31 = 7** (compute/indirect: `glDispatchCompute`,
`glMemoryBarrier`, `glBindImageTexture`, `glVertexAttribFormat/IFormat/Binding`,
`glBindVertexBuffer`). The class also bundles non-GL platform glue (sound, IAP,
ads, voice, sensors, analytics) under the same JNI prefix.

### 3.1 Dispatch model
Imports (`.dynsym`) include only the EGL surface API and two GL funcs
(`glDrawElementsInstanced`, `glGetString`). **Almost all GL entry points are
resolved at runtime** via `eglGetProcAddress` and cached in a function-pointer
dispatch table (`off_DC3D3xx`/`off_DC3D4xx`/`off_DC3D5xx`). A global
**`byte_DB2CDC0` ("use ANGLE") toggle** selects between:
- system EGL/`eglGetProcAddress`, or
- **ANGLE** entry points held in function pointers
  (`off_DB2CDC8` = ANGLE `eglGetProcAddress`, `off_DB2CE18` = ANGLE
  `eglMakeCurrent`, `off_DB2CE20` = ANGLE `eglGetError`,
  `off_DC3D3C8` = ANGLE swap).

Trivial wrapper (lazy table dispatch):
```c
__int64 GLES30_glBindVertexArray(env, this, a3) { return off_DC3D338(a3); }
```

### 3.2 Representative submit wrapper — `glMultiDrawElementsIndirect` @ `0x80c0538`
Shows the full proc-address-resolve + ANGLE-vs-fallback pattern:
```c
if (a6 < 1) return;                       // drawcount
if ((byte_DC3D518 & 1) == 0) {            // not-yet-known-unsupported
  ProcAddress = byte_DB2CDC0
      ? off_DB2CDC8(aGlmultidrawele)      // ANGLE eglGetProcAddress("glMultiDrawElementsIndirectEXT")
      : eglGetProcAddress(aGlmultidrawele);
  qword_DC3D520 = ProcAddress;            // cache
  if (ProcAddress) { ... ProcAddress(mode, type, 0, drawcount, stride); return; }
  else byte_DC3D518 = 1;                  // mark unsupported -> use fallback
}
// CPU fallback: map the indirect buffer and loop glDrawElementsInstanced per cmd
v14 = off_DC3D4A0(36671, 0, 20*drawcount, 1);   // glMapBufferRange(GL_DRAW_INDIRECT_BUFFER)
do { if (count) glDrawElementsInstanced(mode, cmd.count, type, cmd.offset<<shift); ... } while(--n);
off_DC3D4A8(36671);                              // glUnmapBuffer
```
So the engine prefers the hardware `glMultiDrawElementsIndirectEXT` path
(ANGLE-exposed) and degrades to a per-command `glDrawElementsInstanced` loop —
this is the **chunk batch-draw** path. (`glMultiDrawIElementsBaseVertexANGLE`,
`glMultiDrawArraysANGLE`, etc. are sibling ANGLE multi-draw wrappers.)

### 3.3 Vulkan path — `nativeIsVulkanEnabled` @ `0x80c2abc`
Not a native renderer switch in the engine core; it **calls back into Java**.
It attaches the current thread to the `studioEnv` isolate, resolves the static
method `nativeIsVulkanEnabled ()Z` on the cached `BL-Studio` class
(`qword_DB2CF90`), and returns its result:
```c
... attach to studioEnv isolate (CEntryPoint enter, TLS attached_here_studio) ...
mid = GetStaticMethodID(cls, "nativeIsVulkanEnabled", "()Z");  // vtable +904
return CallStaticBooleanMethod(cls, mid);                      // vtable +936
```
`nativeSetVulkanEnabled (Z)V` @ `0x80c2c4c` is the mirror setter
(`CallStaticVoidMethod`, vtable +1128). The actual Vulkan rendering is delivered
by **ANGLE's Vulkan backend** (selected by the `byte_DB2CDC0` ANGLE toggle); the
`GLES*` wrappers stay identical — ANGLE translates GLES3/3.1 to Vulkan
underneath. So "Vulkan enabled" = ANGLE-over-Vulkan, configured Java-side and
read here.

### 3.4 `logEvent` @ `0x80bed44` (analytics bridge, representative JNIEnv usage)
Not GL, but the cleanest example of the JNI/AOT call convention used everywhere:
attach to `studioEnv`, cache `jclass`+`jmethodID`
(`logEvent (Ljava/lang/String;[Ljava/lang/String;)V`), marshal a Java String +
String[] via `GetStringUTFChars`(+1336)/`NewObjectArray`(+1376)/
`SetObjectArrayElement`(+1392), then `CallStaticVoidMethod` (+1128). The same
`vtable_offset → JNI function` map (248=GetObjectClass, 264=GetMethodID,
184=DeleteLocalRef, 416=CallLongMethod, 904=GetStaticMethodID,
936=CallStaticBooleanMethod, 1128=CallStaticVoidMethod) is reused by every
bridge function below.

---

## 4. Chunk / EGL multi-context pipeline

The engine runs a **pool of EGL contexts that all share one `EGLDisplay` and one
shared GL object namespace**, so worker threads can build chunk meshes / upload
VBOs in parallel with the render thread. One global per role.

### 4.1 The globals (display / surfaces / contexts)
| Global | Role |
|---|---|
| `qword_DB2CD70` | shared **EGLDisplay** |
| `byte_DB2CDC0` | **use-ANGLE** flag (picks `off_DB2CE18` vs `eglMakeCurrent`) |
| `off_DB2CE18` / `off_DB2CE20` / `off_DC3D3C8` | ANGLE `eglMakeCurrent` / `eglGetError` / swap |
| `qword_DB2CD78` | game/splash **surface** (window surface) |
| `qword_DB2CD38` | chunk surface |
| `qword_DB2CD40` | chunkWaiter surface |
| `qword_DB2CD48` | async surface |
| `qword_DB2CD50` | load1 surface (load2–4 follow contiguously) |
| `qword_DB2CCE0` | **game context** (set by `setGameContext`) |
| `qword_DB2CCE8` | splash context |
| `qword_DB2CCF0` | **chunk context** (set by `setChunkContext`) |
| `qword_DB2CCF8` | **chunkWaiter context** (set by `setChunkWaiterContext`) |
| `qword_DB2CD00` | async context |
| `qword_DB2CD08` | load1 context (load2–4 follow) |

`makeSplashContext`, `makeChunkContext`, `makeChunkWaiterContext`,
`makeGameContext`, `makeAsyncContext`, `makeLoad1Context`..`makeLoad4Context`
are all the **same shape**: bind that role's `(display, surface, surface,
context)` current on the calling thread.

### 4.2 `setGameContext` @ `0x80c3aa4` (and setChunk/ChunkWaiter)
Pulls the native `EGLContext` handle out of a Java `EGLContext` wrapper object
and stores it into the role global:
```c
if (!a3) return log("[JNI] setGameContext: context is NULL");
cls = GetObjectClass(env, a3);                                  // +248
mid = GetMethodID(env, cls, "getNativeHandle", "()J");          // +264
qword_DB2CCE0 = CallLongMethod(env, a3, mid);                   // +416  -> game EGLContext
DeleteLocalRef(env, cls);                                       // +184
```
`setChunkContext` (0x80c3c64) is identical but stores `qword_DB2CCF0`;
`setChunkWaiterContext` (0x80c3d34) stores `qword_DB2CCF8`. So the Java side
creates the shared EGL contexts and registers their native handles here.

### 4.3 `makeGameContext` @ `0x80b6914`
```c
result = byte_DB2CDC0
   ? off_DB2CE18(display, surface, surface, gameCtx)   // ANGLE eglMakeCurrent
   : eglMakeCurrent(display, surface, surface, gameCtx);
if ((byte_DC3D3C0 & 1)==0) { byte_DC3D3C0=1; setThreadPriorityDisplay(result); }  // once: bump render-thread prio
```
- `qword_DB2CD78`,`qword_DB2CD78`,`qword_DB2CCE0` = (draw surf, read surf, game
  ctx). First bind also raises the render thread to display priority.

`makeChunkContext` @ `0x80b669c` / `makeAsyncContext` / `makeLoad1Context` add
error reporting:
```c
result = MakeCurrent(display, chunkSurf, chunkSurf, chunkCtx);
if (result) return result;
printf("[EGL] makeChunkContext failed: 0x%x\n", useAngle?off_DB2CE20():eglGetError());
```
`makeSplashContext` (0x80b6648) reuses the game window surface `qword_DB2CD78`
with the splash context `qword_DB2CCE8`. `makeChunkWaiterContext` (0x80b6744)
binds `qword_DB2CD40`/`qword_DB2CCF8`.

### 4.4 Presentation — `swapGameContext` / `swapChunkContext`
`swapGameContext` @ `0x80b7220` is the **frame present** + watchdog:
```c
if (trySwapBuffers()) { dword_DC3D3D0 = 0; return 1; }   // success -> reset fail counter
if (dword_DC3D3D0++ < 3) return 0;                       // tolerate a few transient fails
if (count <= 0x63) { log("[EGL] Swap buffer %d kez basarisiz oldu", n); return 0; }
log("[EGL] Swap buffer surekli basarisiz... context lost olabilir", n);
restartApp();                                            // EGL context lost -> restart
dword_DC3D3D0 = 0;
```
(Turkish log strings confirm CraftRise lineage: "kez basarisiz oldu" = "failed N
times", "context lost olabilir" = "context may be lost".)

`swapChunkContext` @ `0x80b6e40` shows the **off-thread present under a mutex**:
```c
if (pthread_mutex_trylock(&stru_DB2CD80)) return ...;   // non-blocking; skip if busy
if (!display || !gameSurf || !chunkCtx) return unlock;
MakeCurrent(display, 0, 0, chunkCtx);                   // bind chunk ctx with no surface
off_DC3D3C8(display, gameSurf) / eglSwapBuffers(display, gameSurf);  // flush/present
pthread_mutex_unlock(&stru_DB2CD80);
```
`destroyContext` @ `0x80b6d28` = `eglMakeCurrent(display,0,0,0)` (unbind).
`stru_DB2CD80` is the **EGL serialization mutex** guarding cross-thread
make-current/swap so the parallel chunk/loader contexts don't race the render
thread on the shared display.

### 4.5 `onSurfaceResume` @ `0x80c66a8`
After the Android surface is recreated, re-attach to the game isolate and notify
Java:
```c
if (initGameEnv()) {                                     // (re)attach thread to gameEnv isolate
  mid = cachedTLS(unk_8672B30);
  if (!mid) mid = GetStaticMethodID(gameCls, ...);       // +904, cached in TLS
  return CallStaticVoidMethod(gameEnv, qword_DB2CF30, mid);  // +1128 -> GNatives.onSurfaceResume()V
}
```
i.e. the surface lifecycle is owned Java-side; native just bridges the resume so
the GL contexts/surfaces (above) are rebuilt and `make*Context` can rebind.

---

## Engine-core architecture (summary)

- **Bootstrap is pure SubstrateVM.** `graal_create_isolate`(0x3A77DF0) maps the
  image heap behind an LL/SC state machine (`dword_14`, magic
  `0x150150150150150`). `run_main`(0x3A7F690)→`JavaMainWrapper.run`(sub_3A19930)
  gates on `[FP, ASIMD]`, creates/attaches the isolate, runs Java `main`
  (sub_3A19B30), then detaches/tears down. The Android host enters the live
  isolate through the `MainApp.start` CEntryPoint(0x3A7F800)→`sub_4739DE0`
  (AOT `MainApp.start()`), which is where Kotlin game code begins.
- **The per-frame loop is AOT Java, not native.** `GLES30.gameLoopTick`
  (0x80c31b0) is a bare `RET`; the tick lives in the compiled `MainApp`/render
  thread, which drives the engine by calling the native GL wrappers and
  `swap*Context` each frame. There is no native tick dispatcher to hook — the
  per-frame native boundary is the GL submit set + `swapGameContext`.
- **Rendering is a thin JNI→ANGLE shim.** ~150 `GLES30` + 7 `GLES31` JNI
  functions resolve GL entry points via `eglGetProcAddress` into a cached
  fn-ptr table, with a global ANGLE toggle (`byte_DB2CDC0`) swapping system EGL
  for ANGLE pointers. Hardware paths (`glMultiDrawElementsIndirectEXT`, ANGLE
  multi-draw) degrade to `glDrawElementsInstanced` loops. "Vulkan" =
  ANGLE-over-Vulkan, decided Java-side (`nativeIsVulkanEnabled`/`...Set...`).
- **Chunk parallelism is an EGL multi-context pool.** One shared `EGLDisplay`
  (`qword_DB2CD70`) + one shared GL namespace, with per-role surfaces/contexts:
  game(CCE0)/splash(CCE8)/chunk(CCF0)/chunkWaiter(CCF8)/async(CD00)/
  load1-4(CD08…). Each worker thread calls its `make<Role>Context` to bind
  `(display, surface, surface, ctx)` and meshes/uploads chunk geometry off the
  render thread; `set<Role>Context` registers the Java-created context handles
  via `getNativeHandle()J`. Cross-thread make-current/swap is serialized by
  `pthread_mutex stru_DB2CD80`; `swapGameContext` present is guarded by a
  context-lost watchdog that calls `restartApp()`.
- **Lineage & instrumentation hooks.** Turkish EGL log strings confirm the
  CraftRise/SonOyuncu code lineage. Best native hook points: `swapGameContext`
  (0x80b7220)/`trySwapBuffers` for frame timing, `make*Context`/`set*Context`
  for the EGL topology, and `MainApp.start`(0x3A7F800)/`sub_4739DE0` for game
  init.
```
