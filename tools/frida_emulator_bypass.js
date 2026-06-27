/*
 * Block Legends (net.blocklegends) — emulator / anti-tamper bypass  [ART / Java layer]
 * ----------------------------------------------------------------------------------
 * Blocker analysed: net.blocklegends.ui.GyroscopeEC  +  net.blocklegends.natives.GNatives
 *
 * How the game blocks emulators:
 *   1) GyroscopeEC.isProbablyEByBuild()  -> Build-prop / ro.kernel.qemu heuristic
 *      (catches nox, bluestacks, ldplayer, genymotion, goldfish/ranchu, AVD).
 *   2) Gyro/accel "stillness" challenge -> if sensors are flat (fake) it fires
 *      DetectionCallback.onFailed -> GNatives.onEmulatorUsage() (native) ->
 *      GNatives.showEmulatorPopup() -> Activity.finish().  (App closes.)
 *
 * These two classes ARE in the DEX (run on ART) -> hookable here.
 * NOTE: the game ENGINE + gnatives.GnsNative run inside a GraalVM isolate
 *       (SubstrateVM), NOT on ART -> they are NOT reachable from this script.
 *
 * CAVEATS:
 *   - AntiAutoClickerUtil scans /proc/self/maps for Frida (FRIDA_SUSPECTED), but
 *     that scanner is TR/AZ region-gated. Run with a NON-TR/AZ locale + SIM, or use
 *     a renamed frida-gadget / hidden frida-server so the maps scan finds nothing.
 *   - Play Integrity device-verdict is checked server-side. Even with this bypass,
 *     JOINING a server from an emulator may still be refused by the backend.
 *     For reliable play/test use a REAL device; use this only for local analysis.
 *
 * Usage:  frida -U -f net.blocklegends -l frida_emulator_bypass.js   (spawn)
 *     or  frida -U -n "Block Legends" -l frida_emulator_bypass.js     (attach)
 */
Java.perform(function () {
  console.log('[*] BlockLegends emulator-bypass loaded');

  // 1) Build-prop emulator heuristic -> always "not an emulator"
  try {
    var G = Java.use('net.blocklegends.ui.GyroscopeEC');
    G.isProbablyEByBuild.implementation = function () {
      console.log('[bypass] isProbablyEByBuild() -> false');
      return false;
    };
  } catch (e) { console.log('[!] GyroscopeEC not loaded yet (' + e + ')'); }

  // 2) Neutralise the verdict path (works regardless of which detector fired)
  try {
    var N = Java.use('net.blocklegends.natives.GNatives');
    N.onEmulatorUsage.implementation = function () {
      console.log('[bypass] GNatives.onEmulatorUsage() swallowed');
    };
    N.showEmulatorPopup.implementation = function () {
      console.log('[bypass] GNatives.showEmulatorPopup() swallowed');
    };
    // Optional: also swallow the root/Frida/autoclicker popup (only fires in TR/AZ):
    // N.showAutoClickerPopup.implementation = function (d) { console.log('[bypass] autoclicker popup swallowed'); };
  } catch (e) { console.log('[!] GNatives hook error (' + e + ')'); }

  // 3) Spoof Build identity to a real Pixel 7 (defeats every isProbablyEByBuild branch)
  try {
    var B = Java.use('android.os.Build');
    B.BRAND.value = 'google';   B.DEVICE.value = 'panther';
    B.MODEL.value = 'Pixel 7';  B.PRODUCT.value = 'panther';
    B.MANUFACTURER.value = 'Google'; B.HARDWARE.value = 'panther';
    B.FINGERPRINT.value = 'google/panther/panther:14/UQ1A.240105.004/11206848:user/release-keys';
    console.log('[bypass] android.os.Build spoofed -> Pixel 7');
  } catch (e) { console.log('[!] Build spoof error (' + e + ')'); }

  // 4) ro.kernel.qemu -> "0"
  try {
    var SP = Java.use('android.os.SystemProperties');
    SP.get.overload('java.lang.String').implementation = function (k) {
      if (k === 'ro.kernel.qemu') { return '0'; }
      return this.get(k);
    };
  } catch (e) {}

  // 5) ALTERNATIVE to fixing sensors: hide the gyroscope so GyroscopeEC takes its
  //    "no gyroscope -> onSuccess() + permanentlyDisable()" early-exit path.
  //    Enable this if your emulator exposes a FAKE gyro that fails the stillness test.
  /*
  try {
    var SM = Java.use('android.hardware.SensorManager');
    SM.getDefaultSensor.overload('int').implementation = function (t) {
      if (t === 4) { console.log('[bypass] hiding gyroscope (TYPE_GYROSCOPE)'); return null; }
      return this.getDefaultSensor(t);
    };
  } catch (e) {}
  */

  console.log('[*] hooks installed');
});
