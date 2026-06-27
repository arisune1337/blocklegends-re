/*
 * berberis / libndk_translation crash workaround for Block Legends. (frida 17 API)
 * GraalVM isolate thread "BL-Studio" exits ~1-2s after start; berberis 0.2.3
 * SIGSEGV (null@0x18) running that thread's pthread-key destructors:
 *   RunGuestPthreadKeyDtor -> RunGuestCall -> ... -> ProcessPendingSignalsImpl.
 * libndk_translation.so is the HOST (x86_64) translator -> hook from Frida.
 * RunGuestPthreadKeyDtor lives at module_base + 0x19ce60 (from the tombstone:
 *   pc 0x19ceb7 = RunGuestPthreadKeyDtor+87).  No-op it to skip guest TLS dtors.
 */
'use strict';
var LIB = 'libndk_translation.so';
var OFF = 0x19ce60;

function hookDtor() {
  var mod = Process.findModuleByName(LIB);
  if (!mod) { send('[fix] ' + LIB + ' not mapped'); return false; }
  var addr = mod.base.add(OFF);
  // sanity cross-check against symbol table if available
  try {
    var syms = mod.enumerateSymbols();
    for (var i = 0; i < syms.length; i++) {
      if (syms[i].name.indexOf('RunGuestPthreadKeyDtor') >= 0) { addr = syms[i].address; break; }
    }
  } catch (e) {}
  send('[fix] base=' + mod.base + '  RunGuestPthreadKeyDtor@' + addr + ' -> no-op');
  Interceptor.replace(addr, new NativeCallback(function (a, b) {
    send('[fix] (skipped a guest pthread-key dtor)');
  }, 'void', ['uint64', 'pointer']));
  return true;
}

if (Process.findModuleByName(LIB)) {
  hookDtor();
} else {
  var dl = Module.findGlobalExportByName('android_dlopen_ext') || Module.findGlobalExportByName('dlopen');
  Interceptor.attach(dl, {
    onEnter: function (a) { this.n = a[0].isNull() ? '' : a[0].readCString(); },
    onLeave: function () {
      if (this.n && this.n.indexOf('ndk_translation') >= 0 && Process.findModuleByName(LIB)) hookDtor();
    }
  });
  send('[fix] waiting for ' + LIB + ' ...');
}
