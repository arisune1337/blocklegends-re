# 07b — Networking Native Bridge (`gnatives.GnsNative` → GameNetworkingSockets)

Binary: `libblocklegends.so` (229 MB GraalVM Native Image, arm64). IDB `c3323093`.
Bridge span: `Java_gnatives_GnsNative_*` @ `0x81138A8 – 0x8122AE8`.

## 0. TL;DR
The whole networking transport is **Valve GameNetworkingSockets (GNS / "Steam sockets")**, *dynamically loaded at runtime via `dlopen`* (NOT statically linked, NOT Steam-online). The JNI layer is a thin shim that resolves the GNS **flat C API** (`SteamAPI_ISteamNetworkingSockets_*`) by `dlsym` and forwards calls. It is **direct-IP / client-server only** — `ConnectByIPAddress` + `CreateListenSocketIP`. No STUN, no rendezvous/signalling server, no SDR relay, no P2P/ICE (confirmed by the embedded assert `st->signalling==0`). **No hardcoded server endpoints or ports** are in the native layer — host:port arrives from Java as an `"ip:port"` string. Wire serialization, length-framing and Zstd compression all happen **Java-side**; the native `send` just memcpy's the already-built `ByteBuffer` into a GNS message. Transport crypto (Curve25519 + AES-GCM) is GNS's and is **explicitly left enabled** (`Unencrypted` config = 0).

## 1. Enumerated `Java_gnatives_GnsNative_*`
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

## 2. GNS is dlopen-loaded; flat-API function pointers
`init` → `sub_8113A54` (the one-time loader, guarded by a mutex + `byte_DCC08A8`). It tries, in order:
`libGameNetworkingSockets.so` / `GameNetworkingSockets.dll` / `libGameNetworkingSockets.dylib`
(error string: *"Unable to load GameNetworkingSockets: "* + `dlerror()`), then resolves symbols:

```c
qword_DCC0840 = dlopen("libGameNetworkingSockets.so", RTLD_NOW);
off_DCC0848   = dlsym("GameNetworkingSockets_Init");
qword_DCC0850 = dlsym("GameNetworkingSockets_Kill");
off_DCC0858   = dlsym("SteamAPI_SteamNetworkingSockets_v009");   // ISteamNetworkingSockets factory
off_DCC0860   = dlsym("SteamAPI_SteamNetworkingUtils_v003");     // ISteamNetworkingUtils factory
// + ~22 flat-API entry points resolved by sub_81149A4 / sub_8114AD4 / ... :
off_DCC07C8 = ..._CreateListenSocketIP        off_DCC07B0 = ..._ConnectByIPAddress
off_DCC07D0 = ..._CloseListenSocket           off_DCC07F0 = ..._ReceiveMessagesOnConnection
off_DCC0838 = ..._ReceiveMessagesOnPollGroup  off_DCC07D8 = ..._DestroyPollGroup
off_DCC0800 = ..._AllocateMessage (Utils)     off_DCC0810 = ..._SendMessages
off_DCC07F8 = SteamAPI_SteamNetworkingMessage_t_Release
off_DCC0888 = SteamAPI_SteamNetworkingIPAddr_ParseString
off_DCC0880 = SteamAPI_ISteamNetworkingUtils_SetConfigValue
// custom fork extensions (epoll/wake-fd integration):
qword_DCC0898 = SteamNetworkingSockets_SetServiceThreadInitCallback
off_DCC0818   = SteamNetworkingSockets_CRGetConnRecvWakeFd       // "CR" = CraftRise fork
off_DCC0820   = SteamNetworkingSockets_CRGetPollGroupRecvWakeFd
...
off_DCC0848(0, &errMsg);                       // GameNetworkingSockets_Init
qword_DCC07B8 = off_DCC0858();                 // global ISteamNetworkingSockets singleton
qword_DCC0808 = off_DCC0860();                 // global ISteamNetworkingUtils singleton
off_DCC0880(qword_DCC0808, 201, 1, 0, 5, &sub_81173A4); // SetConfigValue(Callback_ConnectionStatusChanged=201)
```
So `qword_DCC07B8` (sockets iface) and `qword_DCC0808` (utils iface) are the two singletons every call below threads through. The global ConnectionStatusChanged callback is `sub_81173A4`. The `CRGet*RecvWakeFd` symbols are a **custom fork addition** (note the "CR" prefix; this game is CraftRise-derived) that exposes a pollable fd so the engine can `epoll`/`poll` for inbound data instead of busy-spinning.

## 3. connect / createServer (direct-IP, 12 tuning config values)
`connect`:
```c
sub_811AC38(&jstr, ...);                     // parse "ip:port" via SteamNetworkingIPAddr_ParseString
                                             //   err: "Invalid GNS address: ..."
sub_811ADE4(v41);                            // fill 12 SteamNetworkingConfigValue_t options
v16 = off_DCC07B0(qword_DCC07B8, &addr, 12, v41);   // ConnectByIPAddress(addr, 12 opts, opts)
//   err: "ConnectByIPAddress returned invalid connection"
// -> alloc 0xA0 conn-tracker, steady_clock::now(), insert into global hash map (qword_DCC08A0+104)
```
`createServer`:
```c
sub_811B990(env, intArr1, intArr2, ...);     // build lane priority[] + weight[] from two Java int[]
v16[1] = off_DCC07C0(qword_DCC07B8);         // CreatePollGroup
sub_811ADE4(v72);                            // same 12 config options
v18 = off_DCC07C8(qword_DCC07B8, &addr, 12, v72);   // CreateListenSocketIP(addr,12opts,opts)
//   err: "CreateListenSocketIP/CreatePollGroup failed"
```
The 12 connection config values (`sub_811ADE4`, IDs read from `.rodata`, values from a global config blob at `qword_DCC08A0+184..220`) decode to `ESteamNetworkingConfigValue`:

| ID | Name | Set value |
|----|------|-----------|
| 23 | IP_AllowWithoutAuth | **1** (run standalone GNS, no Steam identity/auth) |
| 34 | Unencrypted | **0** (transport encryption left ON) |
| 9  | SendBufferSize | configurable |
| 47 | RecvBufferSize | configurable |
| 48 | RecvBufferMessages | configurable |
| 49 | RecvMaxMessageSize | configurable |
| 10 | SendRateMin | configurable |
| 11 | SendRateMax | configurable |
| 12 | NagleTime | configurable |
| 32 | MTU_PacketSize | configurable |
| 24 | TimeoutInitial | configurable |
| 25 | TimeoutConnected | configurable |

## 4. Send path — how a Java `Packet` becomes wire bytes
`Java_gnatives_GnsNative_send(env, this, connObj, ByteBuffer, len, lane, flags)`:
```c
if (!a4) ... "send requires a direct ByteBuffer";
v15 = (*(env+1840))(env, a4);                // JNIEnv->GetDirectBufferAddress(buf)  [1840=idx 230]
v16 = off_DCC0800(qword_DCC0808, len);       // ISteamNetworkingUtils::AllocateMessage(len)
//   on null: ",AllocateMessage failed"
memcpy(*v16, v15, len);                       // raw ByteBuffer bytes -> message->m_pData
*((DWORD*)v16 + 3)  = *(DWORD*)connObj;       // m_conn   (HSteamNetConnection, off +12)
*((WORD *)v16 + 104)= max(lane,0);            // m_idxLane (off +208)
*((DWORD*)v16 + 49) = flags (| 0x10 cond.);   // m_nFlags  (off +196)  [0x10 = UseCurrentThread]
off_DCC0810(qword_DCC07B8, 1, &v16, &outMsgNum, 1);  // SendMessages(1, &msg, &outMsgNum)
return (outMsgNum < 0) ? error : 0;          // outMsgNum = assigned reliable message number
```
Key points:
- **The payload is the raw `ByteBuffer` contents, shipped verbatim.** There is **no native serialization, no length-prefix framing, and no compression** here — those are all done in Java (`net.blocklegends.network.Packet` → byte[] → direct ByteBuffer) before the call. GNS supplies its own internal framing/fragmentation/reassembly on the wire.
- **Reliability** = the `flags` arg (GNS `k_nSteamNetworkingSend_*`: 0 unreliable, 8 reliable, 1 NoNagle, 4 NoDelay; the shim ORs `0x10` UseCurrentThread for low-latency unreliable-NoDelay sends, gated by `sub_8120788`/`byte_DCC08E0`).
- **Channel** = the `lane` arg → `m_idxLane`; lanes are pre-configured per connection from two Java `int[]` (priorities + uint16 weights) in `sub_811B990` (feeds `SteamAPI_ISteamNetworkingSockets_ConfigureConnectionLanes`).
- The error branch builds a very verbose diagnostic: `"SendMessages failed result=… er=… lane=… length=… state=… closed=… retired=…"`.
- `sendBatch` (`0x8120a70`) is the bulk version (allocates N messages, one `SendMessages` call).

## 5. Receive path
`Java_gnatives_GnsNative_receive(env, this, connObj, ByteBuffer, cap)`:
```c
v11 = (*(env+1840))(env, buf);               // GetDirectBufferAddress
n = off_DCC07F0(qword_DCC07B8, *(uint*)connObj, &pMsg, 1); // ReceiveMessagesOnConnection(hConn,&msg,1)
if (n>=1 && pMsg) {
   if (msg->m_cbSize <= cap) { memcpy(buf, msg->m_pData, msg->m_cbSize);
                               len = msg->m_cbSize; off_DCC07F8(msg); return len; }  // Release
   else { off_DCC07F8(msg); return "received message exceeds Java buffer"; }
}
```
`receiveBatch` / `pollGroupReceive` are the multi-message variants (server side drains the poll group).

## 6. pollCallbacks — connection state machine / census / reaper
`Java_gnatives_GnsNative_pollCallbacks` (0x1688, 1403 insns) drives `RunCallbacks`, iterates the tracked-connection map, calls `GetConnectionInfo`/`GetConnectionRealTimeStatus`, and classifies each by GNS connection state. It logs a census and **reaps stale connections** (`"reaped stale"`):
```
[GNS-native census] g_connections=%d connected=%d connecting=%d findroute=%d
  dead=%d noinfo=%d stuckConnecting=%d reaped=%d reapEnabled=%d
```
States tracked map to GNS `k_ESteamNetworkingConnectionState_{Connecting=1, FindingRoute=2, Connected=3, ClosedByPeer=4, ProblemDetectedLocally=5}` (see constants 1/2/3/0xfffffffe/0xffffffff in the disasm).

## 7. Endpoints / ports / STUN / SDR / rendezvous
- **None hardcoded in native.** `find_regex` + the full `strings_all.txt` sweep returned **no ip:port literals, no STUN/relay/rendezvous hosts, no `*.valvesoftware.com` SDR config, no FakeIP**.
- Only GNS-related strings present are the flat-API symbol names, `SteamNetworkingIPAddr_ParseString`/`ToString`, and the assert **`assertion failed: st->signalling==0`** — i.e. this build deliberately runs GNS in the *no-signalling* (direct connection) mode; there is no rendezvous/matchmaking transport compiled into the path.
- Host + port are entirely supplied from Java as the `"ip:port"` string passed to `connect`/`createServer`. (Game/login server IPs live in the Java/Kotlin layer, not here.)

## 8. Encryption boundary
- **Transport layer (GNS):** Curve25519 ECDH handshake + per-packet **AES-256-GCM** AEAD is GNS's built-in transport crypto. The shim *keeps it on*: config `Unencrypted(34)=0`. `IP_AllowWithoutAuth(23)=1` only drops the *Steam identity/cert* requirement (self-hosted server, no Steam backend) — it does **not** disable the symmetric encryption of the data channel.
- **App layer:** payload bytes handed to `send` are already-serialized `Packet`s. Any app-level obfuscation/compression is Java-side: **`gnatives.ZstdNative`** (`0x8122c40+`, with `loadDictionary`/`trainFromBuffer`/`compress`/`decompress` — dictionary-trained Zstd, the likely packet compressor) and **`gnatives.OpusNative`** (voice). These are *separate* native modules and are **not** invoked on the GNS send/recv path — the engine compresses (Zstd) in Java, then ships the compressed buffer through `GnsNative_send`. No additional app-layer cipher was found inside the native networking bridge.

---
### 5-line summary
1. Transport = Valve **GameNetworkingSockets**, **dlopen-loaded** at runtime (libGameNetworkingSockets.so) and driven through the dlsym'd `SteamAPI_ISteamNetworkingSockets_*` flat C API via singletons `qword_DCC07B8`(sockets)/`qword_DCC0808`(utils); a custom "CR" fork adds `CRGet*RecvWakeFd` for epoll integration.
2. **Direct-IP client/server only** — `ConnectByIPAddress` + `CreateListenSocketIP`, with 12 tuning config values (timeouts, buffers, send-rate, MTU, NagleTime); IP_AllowWithoutAuth=1, Unencrypted=0.
3. **No hardcoded endpoints/ports/STUN/relay/rendezvous** anywhere in the native layer — host:port comes from Java as an `"ip:port"` string; `st->signalling==0` (no P2P/signalling transport).
4. **Framing/compression are Java-side**: native `send` just `GetDirectBufferAddress` → `AllocateMessage` → memcpy raw bytes → `SendMessages`; reliability via GNS `flags`, channel via lane index/`ConfigureConnectionLanes`; Zstd (dictionary-trained `ZstdNative`) and Opus are separate modules applied before the buffer reaches GNS.
5. **Crypto**: GNS Curve25519+AES-GCM transport encryption is left enabled at the boundary; no extra app-layer cipher inside the bridge.
