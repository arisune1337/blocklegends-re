# Block Legends GNS bridge — RECEIVE PATH + CONNECTION STATE MACHINE + CONFIG IDs

Database: `d3d0fa7b` = `libblocklegends.so` (GraalVM native image + statically-linked stock Valve GameNetworkingSockets).
Server: `81.8.66.123:26002` (m2.blocklegends.net). All addresses below are file/runtime VAs.

Interface dispatch globals (vtable-slot pointers cached at load):

| global | GNS interface method |
|---|---|
| `qword_DCC07B8` | `ISteamNetworkingSockets*` singleton |
| `off_DCC07F0` | `ReceiveMessagesOnConnection(conn, &msgs[], nMax)` |
| `off_DCC0838` | `ReceiveMessagesOnPollGroup(group, &msgs[], nMax)` |
| `off_DCC07F8` | `SteamNetworkingMessage_t::Release(msg)` |
| `off_DCC07E8` | `GetConnectionInfo(conn, &SteamNetConnectionInfo_t)` |
| `off_DCC0828` | `GetConnectionRealTimeStatus(conn, &status, nLanes, &laneStatus[])` |
| `off_DCC0878` | `FlushMessagesOnConnection(conn)` |
| `off_DCC07E0` | `RunCallbacks()` (driven from pollCallbacks via `sub_811C7BC`) |
| `off_DCC0890` | `SteamNetConnectionInfo_t`-detail string formatter (end-debug) |

`SteamNetworkingMessage_t` layout used by the bridge: `m_pData` @+0, `m_cbSize` @+8 (int), conn handle @+12 (uint), `m_idxLane` @+208 (uint16).
`SteamNetConnectionInfo_t.m_eState` is at **+176** (confirmed identically in `getState` and `pollCallbacks`).

---

## 1. RECEIVE PATH — bytes handed to Java == raw GNS message body (NO app framing)

### `receive` @0x811f534 — `Java_gnatives_GnsNative_receive(env, this, conn*, ByteBuffer, cap)`
```
addr = env->GetDirectBufferAddress(ByteBuffer)        // JNIEnv vtbl+1840
lock(conn->mutex @+16); recheck closed flags @+136/+137
n = off_DCC07F0(qword_DCC07B8, conn->handle, &msg, 1)  // ReceiveMessagesOnConnection, nMax=1
unlock
if n>=1 && msg:
    size = msg->m_cbSize (+8)
    if size <= cap:
        memcpy(addr, msg->m_pData (+0), size)          // <-- raw body, byte-for-byte
        len = msg->m_cbSize
        off_DCC07F8(msg)                               // Release
        return len
    else: Release; return -3  "received message exceeds Java buffer"
errors: -1 (null/closed), -2 "receive requires a direct ByteBuffer"
```
The only transform is `memcpy(GetDirectBufferAddress, msg->m_pData, msg->m_cbSize)`. **No length prefix, type byte, or any framing is stripped.** The Java ByteBuffer receives exactly the GNS message payload — the symmetric inverse of `send` (which `memcpy`s the Java bytes straight into `AllocateMessage`).

### `receiveBatch` @0x811f810 — `(env,this,conn*,payloadBuf,payloadCap, offsets[],sizes[],lanes[], maxCount)`
```
base = GetDirectBufferAddress(payloadBuf); cap = GetDirectBufferCapacity(payloadBuf)
n = off_DCC07F0(qword_DCC07B8, conn->handle, &msgArr[256], min(maxCount,256))   // single batched recv
for each msg:
    sz = msg->m_cbSize
    if sz>=1 && off+sz<=cap:
        memcpy(base+off, msg->m_pData, sz)             // <-- raw body concatenated
        offsets[k]=off; sizes[k]=sz; lanes[k]=msg->m_idxLane(+208); off+=sz
    Release(msg)
env->SetIntArrayRegion(offsets/sizes/lanes, 0, k, ...) // JNIEnv vtbl+1688
return k                                                // #messages, 0 if none, -4 "payload buffer too small"
errors: -1, -2 "receiveBatch requires a direct ByteBuffer", -3 "metadata arrays shorter than max_count"
```
Each message body copied verbatim into the shared payload buffer; offset/size/lane metadata returned as parallel int[]s. Still no framing.

### `pollGroupReceive` @0x8122280 — same, on a poll group
```
n = off_DCC0838(qword_DCC07B8, group, &msgArr[257], maxCount)  // ReceiveMessagesOnPollGroup
per msg: look up the owning connection-wrapper via g_state hashmap (qword_DCC08A0+104),
         skip if closed (+136); memcpy(payloadBase+off, msg->m_pData, msg->m_cbSize)
fills 4 arrays: connHandles[] (long[] via SetLongArrayRegion vtbl+1696), offsets[], sizes[], lanes[]
return #messages; -5 if ReceiveMessagesOnPollGroup vtbl ptr (off_DCC0838) is null
```
Identical copy semantics. **Confirmed a third time: the bytes delivered to Java are the unframed GNS message body.**

---

## 2. CONNECTION STATE MACHINE

`k_ESteamNetworkingConnectionState`: `None=0, Connecting=1, FindingRoute=2, Connected=3, ClosedByPeer=4, ProblemDetectedLocally=5`.
The bridge does **not** use Valve's `SteamNetConnectionStatusChangedCallback` to drive app logic; instead each poll it calls `GetConnectionInfo` per connection and switches on `info.m_eState (+176)`.

### `getState` @0x811eef8 — `Java_gnatives_GnsNative_getState(...) -> jint`
```
RunCallbacks (sub_811C7BC) if iface ready
lock(conn->mutex @+16); if closed(+136)/closing(+137): return cached state conn[+56] (a3+14)
ok = off_DCC07E8(qword_DCC07B8, conn->handle, &info); unlock      // GetConnectionInfo
if ok:
    atomic_store(info.m_eState, conn+56)                          // cache state for Java
    format end-debug string (off_DCC0890) -> std::string conn+16  // human reason
    if m_eState==3 (Connected) AND there are queued outbound reliable msgs
       AND not-already-flushed flag(+138)==0:
          off_DCC0878(conn, queuedCount)   // FlushMessagesOnConnection on transition to Connected
return atomic_load(conn+56)                                       // the jint state 1..5
```
So **Connected(3)** is surfaced by caching `3` into the wrapper and flushing any backlog; **Closed(4)/ProblemDetectedLocally(5)** are returned as the raw int and the end-debug reason string is stored at `conn+16` for Java to read. Java polls `getState()` and sees the integer state directly.

### `pollCallbacks` @0x811d2e8 — periodic census + stale reaper (runs at most every 15 s; `qword_DCC08D0` throttle)
```
sub_811C7BC(...) -> RunCallbacks(off_DCC07E0)        // drains GNS status-changed callbacks
snapshot g_connections registry (qword_DCC08A0+120 linked list)
for each conn: GetConnectionInfo; switch(info.m_eState @ v192/+176):
     ==3 -> connected++          (v176)
     ==1 -> connecting++         (lo v175)            ; if stuck Connecting > 60 s -> mark stale
     ==2 -> findingRoute++       (hi v175)
     ==4/5 -> dead++             (hi v174)
     no info -> noinfo++         (v180)
reap: stale Connecting conns -> sub_811A044(conn, "reaped stale")  (CloseConnection)
optional stderr census (byte_DCC08C0 gate):
  "[GNS-native census] g_connections=%d connected=%d connecting=%d findroute=%d dead=%d noinfo=%d stuckConnecting=%d reaped=%d reapEnabled=%d"
```
This is the real state machine: a self-polled classifier/reaper, not an event callback into Java. Connected vs Closed is observed by re-reading `m_eState` each cycle.

### `getConnectionStatus` @0x8121778 — realtime telemetry to a Java int[]
```
n = off_DCC0828(qword_DCC07B8, conn, &rtStatus, nLanes, &laneStatus[])  // GetConnectionRealTimeStatus
packs eState, ping, quality, send/recv rates, pending bytes, lane data into &outArr
env->SetIntArrayRegion(outArr) (vtbl+1696); return 1, or -eResult on failure
```

---

## 3. THE 12 ConnectByIPAddress CONFIG VALUES (built by `sub_811ADE4` @0x811ade4)

Each `SteamNetworkingConfigValue_t` (16 B): `m_eValue`(+0) `m_eDataType`(+4, all Int32=1) `m_val.m_int32`(+8).
Entries 0/1 take hardcoded values; entries 2–11 read their int from the config singleton `qword_DCC08A0` at +184..+220 (initialized from `xmmword_822E3E0` @+184, `xmmword_822EF00` @+200, `xmmword_822F280` @+216).

| # | descriptor global | id | k_ESteamNetworkingConfig_* | value src | **value** |
|---|---|---|---|---|---|
| 0 | qword_822C700 | 23 | **IP_AllowWithoutAuth** | hardcoded a1+8 | **1** |
| 1 | qword_822CB98 | 34 | **Unencrypted** | hardcoded a1+24 | **0** |
| 2 | qword_822C438 | 9 | SendBufferSize | +184 = `00 00 10 00` | 1048576 (1 MiB) |
| 3 | qword_822CEE0 | 47 | RecvBufferSize | +188 = `00 00 10 00` | 1048576 (1 MiB) |
| 4 | qword_822C220 | 48 | RecvBufferMessages | +192 = `00 04 00 00` | 1024 |
| 5 | qword_822C078 | 49 | RecvMaxMessageSize | +196 = `00 00 08 00` | 524288 (512 KiB) |
| 6 | qword_822C228 | 10 | SendRateMin | +200 = `00 00 08 00` | 524288 (512 KB/s) |
| 7 | qword_822C598 | 11 | SendRateMax | +204 = `00 00 20 00` | 2097152 (2 MB/s) |
| 8 | qword_822C8B8 | 12 | NagleTime | +208 = `00 00 00 00` | 0 (Nagle OFF) |
| 9 | qword_822CA60 | 32 | MTU_PacketSize | +212 = `9c 04 00 00` | 1180 |
| 10 | qword_822CAF0 | 24 | TimeoutInitial | +216 = `e0 2e 00 00` | 12000 ms |
| 11 | qword_822C3C0 | 25 | TimeoutConnected | +220 = `98 3a 00 00` | 15000 ms |

(ids 47/48/49 = `RecvBufferSize`/`RecvBufferMessages`/`RecvMaxMessageSize` — present only in the open-source GameNetworkingSockets enum, confirming a stock OSS GNS build, not the Steam SDK.)

### Client must replicate at `ConnectByIPAddress(&addr, 12, opts)`:
```
IP_AllowWithoutAuth = 1     // accept server with no Steam identity / cert auth
Unencrypted         = 0     // encryption REQUIRED (Curve25519 + AES-256-GCM, default)
SendBufferSize      = 1048576       RecvBufferSize     = 1048576
RecvBufferMessages  = 1024          RecvMaxMessageSize = 524288
SendRateMin         = 524288        SendRateMax        = 2097152
NagleTime           = 0             MTU_PacketSize     = 1180
TimeoutInitial      = 12000ms       TimeoutConnected   = 15000ms
```

---

## 6-LINE SUMMARY
1. Receive (`receive`/`receiveBatch`/`pollGroupReceive`) = `ReceiveMessages*` -> `memcpy(GetDirectBufferAddress, msg->m_pData, msg->m_cbSize)` -> Release; **zero app framing stripped**, Java bytes == raw GNS message body (matches send).
2. No Java-facing status-changed callback: the bridge polls `GetConnectionInfo.m_eState (+176)` itself; `getState` caches the int into the conn wrapper (+56) and returns it (Connecting=1/FindingRoute=2/Connected=3/ClosedByPeer=4/ProblemDetectedLocally=5).
3. On transition to **Connected(3)** `getState` runs `FlushMessagesOnConnection`; `pollCallbacks` is a 15s census/reaper that counts connected/connecting/findroute/dead and `CloseConnection`s connections stuck Connecting >60s.
4. The 12 connect options are built by `sub_811ADE4`; the two decisive security toggles are **IP_AllowWithoutAuth = 1** (server needs no Steam auth/cert) and **Unencrypted = 0** (link stays encrypted: Curve25519 + AES-256-GCM).
5. Tuning values: SendBuffer/RecvBuffer 1 MiB, RecvBufferMessages 1024, RecvMaxMessageSize 512 KiB, SendRate 512KB–2MB/s, **NagleTime 0 (Nagle disabled)**, MTU_PacketSize 1180, TimeoutInitial 12000 ms, TimeoutConnected 15000 ms.
6. Identity `RecvBufferSize/Messages/MaxMessageSize` (ids 47/48/49) prove a stock open-source GameNetworkingSockets build; a replica client must pass exactly these 12 values to `ConnectByIPAddress(&addr, 12, opts)` to be accepted.
