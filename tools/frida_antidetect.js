/*
 * Block Legends — local anti-detection neutraliser  [ART / Java layer]
 * ----------------------------------------------------------------------------
 * ALL of Block Legends' local protection lives in two DEX classes that run on
 * ART (not in the GraalVM engine), so they are fully hookable:
 *   - net.blocklegends.utils.AntiAutoClickerUtil  (root / su / Frida / Xposed /
 *     LSPosed / Shizuku / autoclicker / test-keys / monkey)
 *   - net.blocklegends.ui.GyroscopeEC             (emulator / sensor challenge)
 *
 * IMPORTANT WEAKNESSES (why this is easy):
 *   - The whole AntiAutoClickerUtil scanner ONLY runs for TR/AZ users
 *     (MainActivity.isTurkishOrAzeriUser(): SIM/network/locale country or
 *     language == TR/AZ). Set the phone to English (US) + no TR/AZ SIM and the
 *     scanner is never even called — this script becomes optional.
 *   - The "Frida" check is just: read /proc/self/maps, flag if >=2 lines contain
 *     "frida"/"gum-js"/"libfrida". A RENAMED frida-server/gadget defeats it with
 *     zero hooks.
 *   - The root check is a package-list + su-path probe -> Magisk DenyList+Shamiko
 *     hides it.
 *   - There is NO native anti-cheat; only Play Integrity is server-side (use a
 *     Play Integrity Fix Magisk module if you need a passing device verdict).
 *
 * Use spawn so hooks land before any check:
 *   frida -U -f net.blocklegends -l frida_antidetect.js -l frida_native_capture.js --no-pause
 */
Java.perform(function () {
  console.log('[*] BlockLegends anti-detect loaded');
  function hook(cls, fn) { try { fn(Java.use(cls)); } catch (e) { console.log('[!] ' + cls + ': ' + e); } }

  // --- AntiAutoClickerUtil: force every probe clean ---
  hook('net.blocklegends.utils.AntiAutoClickerUtil', function (A) {
    A.looksLikeFridaPresent.implementation = function () { return false; };
    A.hasSuBinary.implementation = function () { return false; };
    A.classExists.implementation = function (n) { return false; };           // de.robv...XposedBridge
    A.anyPackageInstalled.implementation = function (c, l, s) { return false; }; // shizuku/root/lsposed/autoclicker
    A.isSuspiciousAccessibilityEnabled.implementation = function (c, l, s) { return false; };
    console.log('[bypass] AntiAutoClickerUtil probes -> clean');
  });

  // --- GyroscopeEC: never flag emulator ---
  hook('net.blocklegends.ui.GyroscopeEC', function (G) {
    G.isProbablyEByBuild.implementation = function () { return false; };
  });

  // --- GNatives: swallow every "you're caught" popup/verdict ---
  hook('net.blocklegends.natives.GNatives', function (N) {
    ['onEmulatorUsage', 'showEmulatorPopup', 'showAutoClickerPopup'].forEach(function (m) {
      if (N[m]) { try { N[m].implementation = function () { console.log('[bypass] ' + m + ' swallowed'); }; } catch (e) {} }
    });
  });

  // --- platform-level tells ---
  hook('android.os.Build', function (B) {
    B.TAGS.value = 'release-keys';
    B.BRAND.value = 'google'; B.DEVICE.value = 'panther'; B.MODEL.value = 'Pixel 7';
    B.PRODUCT.value = 'panther'; B.MANUFACTURER.value = 'Google'; B.HARDWARE.value = 'panther';
    B.FINGERPRINT.value = 'google/panther/panther:14/UQ1A.240105.004/11206848:user/release-keys';
  });
  hook('android.os.SystemProperties', function (SP) {
    SP.get.overload('java.lang.String').implementation = function (k) {
      return (k === 'ro.kernel.qemu') ? '0' : this.get(k);
    };
  });
  hook('android.app.ActivityManager', function (AM) {
    try { AM.isUserAMonkey.implementation = function () { return false; }; } catch (e) {}
  });

  console.log('[*] anti-detect hooks installed');
});
