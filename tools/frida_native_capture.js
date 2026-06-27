/*
 * Block Legends — native GNS packet & server capture
 * ----------------------------------------------------------------------------
 * WHY native: gnatives.GnsNative AND the whole game engine run inside a GraalVM
 * isolate (SubstrateVM). They are NOT on ART, so Frida's Java bridge cannot see
 * them. The ONLY way to observe the app protocol is at the native boundary.
 *
 * Capture point = the stock Valve GameNetworkingSockets flat API. At this layer
 * the payload is the PLAINTEXT application packet (the game's custom Packet
 * serialization + any app-side Zstd is already applied; the GNS transport
 * Curve25519/AES-256-GCM is applied AFTER send / stripped BEFORE recv). So this
 * is exactly the bytes you must reproduce to talk to m2.blocklegends.net:26002.
 *
 * Confirmed exports (verified in IDA):
 *   libGameNetworkingSockets.so:
 *     SteamAPI_ISteamNetworkingSockets_ConnectByIPAddress          (server ip:port)
 *     SteamAPI_ISteamNetworkingSockets_SendMessageToConnection     (one msg)
 *     SteamAPI_ISteamNetworkingSockets_SendMessages                (batch)
 *     SteamAPI_ISteamNetworkingSockets_ReceiveMessagesOnConnection (recv)
 *     SteamAPI_ISteamNetworkingSockets_ReceiveMessagesOnPollGroup  (recv, server)
 *
 * Run on a REAL device (engine + Integrity won't run on an emulator anyway):
 *   frida -U -f net.blocklegends -l frida_native_capture.js --no-pause
 * Hook installs lazily once libGameNetworkingSockets.so is dlopen'd.
 */
'use strict';
var LIB = 'libGameNetworkingSockets.so';
var PTR = Process.pointerSize;

function dump(tag, p, len) {
  if (p.isNull() || len <= 0) { console.log(tag + ' <empty>'); return; }
  var n = Math.min(len, 1024);
  console.log(tag + ' len=' + len);
  console.log(hexdump(p, { length: n, ansi: false, header: false }));
}

// SteamNetworkingIPAddr: uint8 m_ipv6[16]; uint16 m_port;  (IPv4 => ::ffff:a.b.c.d in bytes 12..15)
function fmtAddr(p) {
  try {
    var b = [];
    for (var i = 0; i < 16; i++) b.push(p.add(i).readU8());
    var port = p.add(16).readU16();
    var isV4 = (b[10] === 0xff && b[11] === 0xff);
    var host = isV4 ? (b[12] + '.' + b[13] + '.' + b[14] + '.' + b[15])
                    : b.map(function (x) { return ('0' + x.toString(16)).slice(-2); }).join('');
    return host + ':' + port;
  } catch (e) { return '<addr?>'; }
}

// SteamNetworkingMessage_t: void* m_pData (0x00); int m_cbSize (0x08); HConn m_conn (0x0C); ...
function dumpMsg(tag, pMsg) {
  try {
    var pData = pMsg.readPointer();          // m_pData @ 0
    var cb    = pMsg.add(8).readU32();        // m_cbSize @ 8
    dump(tag, pData, cb);
  } catch (e) { console.log(tag + ' <msg parse err ' + e + '>'); }
}

function attach(name, cbs) {
  var a = Module.findExportByName(LIB, name);
  if (!a) { console.log('[miss] ' + name); return false; }
  Interceptor.attach(a, cbs);
  console.log('[hook] ' + name + ' @ ' + a);
  return true;
}

function installHooks() {
  // server endpoint at connect time -> this prints 81.8.66.123:26002 (or whatever matchmaking returned)
  attach('SteamAPI_ISteamNetworkingSockets_ConnectByIPAddress', {
    onEnter: function (args) { console.log('\n=== ConnectByIPAddress -> ' + fmtAddr(args[1]) + ' ==='); }
  });

  // SendMessageToConnection(self, hConn, pData, cbData, nSendFlags, pOutMsgNum)
  attach('SteamAPI_ISteamNetworkingSockets_SendMessageToConnection', {
    onEnter: function (args) {
      dump('\n>>> SEND conn=' + args[1].toInt32() + ' flags=' + args[4].toInt32(), args[2], args[3].toInt32());
    }
  });

  // SendMessages(self, nMessages, SteamNetworkingMessage_t** pMessages, int64* pOutMsgNumOrResult)
  attach('SteamAPI_ISteamNetworkingSockets_SendMessages', {
    onEnter: function (args) {
      var n = args[1].toInt32(), arr = args[2];
      console.log('\n>>> SEND_BATCH n=' + n);
      for (var i = 0; i < n; i++) dumpMsg('  [send#' + i + ']', arr.add(i * PTR).readPointer());
    }
  });

  // ReceiveMessagesOnConnection(self, hConn, SteamNetworkingMessage_t** ppMsgs, int nMax) -> int count
  function recvHook(label) {
    return {
      onEnter: function (args) { this.pp = args[2]; },
      onLeave: function (ret) {
        var got = ret.toInt32(); if (got <= 0) return;
        console.log('\n<<< ' + label + ' got=' + got);
        for (var i = 0; i < got; i++) dumpMsg('  [recv#' + i + ']', this.pp.add(i * PTR).readPointer());
      }
    };
  }
  attach('SteamAPI_ISteamNetworkingSockets_ReceiveMessagesOnConnection', recvHook('RECV'));
  attach('SteamAPI_ISteamNetworkingSockets_ReceiveMessagesOnPollGroup',  recvHook('RECV_POLLGROUP'));

  console.log('[*] GNS capture hooks installed');
}

// libGameNetworkingSockets.so is dlopen'd after launch -> wait for it.
if (Module.findBaseAddress(LIB)) {
  installHooks();
} else {
  var dlopen = Module.findExportByName(null, 'android_dlopen_ext') || Module.findExportByName(null, 'dlopen');
  Interceptor.attach(dlopen, {
    onEnter: function (a) { this.n = a[0].isNull() ? '' : a[0].readCString(); },
    onLeave: function () {
      if (this.n && this.n.indexOf('GameNetworkingSockets') >= 0 && Module.findBaseAddress(LIB)) {
        console.log('[*] ' + LIB + ' loaded; installing hooks');
        installHooks();
      }
    }
  });
  console.log('[*] waiting for ' + LIB + ' to load...');
}

/* Also useful (uncomment): see the game-side ByteBuffer the engine hands to JNI,
 * at libblocklegends.so!Java_gnatives_GnsNative_send @0x811fd3c — but that needs
 * GetDirectBufferAddress via JNIEnv; the GNS-layer hooks above already give you
 * the same plaintext bytes with a raw pointer, so start there. */
