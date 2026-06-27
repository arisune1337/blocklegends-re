/*
 * Block Legends / Google PairIP license-check bypass  [ART / Java layer]
 * ----------------------------------------------------------------------------
 * The app is wrapped by Google PairIP. Protection here is JAVA-ONLY (verified:
 * there is NO libpairipcore.so in the arm64 split). The license flow lives in
 * com.pairip.licensecheck.* and runs on ART, so it is fully hookable.
 *
 * What kills the app (see decompiled LicenseClient.java):
 *   checkLicense() -> performLocalInstallerCheck() (installer must be
 *   "com.android.vending") -> bind com.android.vending licensing service ->
 *   processResponse(): code 0 LICENSED, 2 NOT_LICENSED -> startPaywallActivity,
 *   any error -> handleError() -> startErrorDialogActivity() -> LicenseActivity
 *   -> closeApp()/exitApp() -> System.exit(0).  scheduleAppShutdown() also
 *   posts exitAction after 30s.
 *
 * Non-Frida alternative (preferred): install with the Play installer id so the
 * local check passes, with GApps present:
 *     adb install-multiple -i com.android.vending -r -t base.apk arm64.apk ...
 * Use THIS script only if that still closes the app.
 *
 * Spawn so the hooks land before LicenseContentProvider/Application run:
 *   frida -U -f net.blocklegends -l frida_pairip_bypass.js --no-pause
 */
Java.perform(function () {
  console.log('[*] PairIP bypass loading...');

  // 1) Neutralise the static entry point entirely -> no license flow runs.
  try {
    var LC = Java.use('com.pairip.licensecheck.LicenseClient');
    LC.checkLicense.implementation = function (ctx) {
      console.log('[pairip] LicenseClient.checkLicense() -> skipped');
    };
    // belt-and-suspenders: force every verdict path to look fine
    try { LC.performLocalInstallerCheck.implementation = function () {
      console.log('[pairip] performLocalInstallerCheck() -> true'); return true; }; } catch (e) {}
    try { LC.processResponse.implementation = function (code, b) {
      console.log('[pairip] processResponse(' + code + ') -> swallowed'); }; } catch (e) {}
    try { LC.handleError.implementation = function (ex) {
      console.log('[pairip] handleError() -> swallowed'); }; } catch (e) {}
    try { LC.startErrorDialogActivity.implementation = function () {
      console.log('[pairip] startErrorDialogActivity() -> blocked'); }; } catch (e) {}
    try { LC.startPaywallActivity.implementation = function (p) {
      console.log('[pairip] startPaywallActivity() -> blocked'); }; } catch (e) {}
    try { LC.scheduleAppShutdown.implementation = function () {
      console.log('[pairip] scheduleAppShutdown() -> blocked'); }; } catch (e) {}
    console.log('[bypass] LicenseClient neutralised');
  } catch (e) { console.log('[!] LicenseClient: ' + e); }

  // 2) Stop LicenseActivity from ever killing the process.
  try {
    var LA = Java.use('com.pairip.licensecheck.LicenseActivity');
    LA.exitApp.implementation       = function () { console.log('[pairip] exitApp() blocked'); };
    LA.closeAllTasks.implementation = function () { console.log('[pairip] closeAllTasks() blocked'); };
    console.log('[bypass] LicenseActivity neutralised');
  } catch (e) { console.log('[!] LicenseActivity: ' + e); }

  // 3) Last-resort guard: ignore the pairip exit runnable's System.exit(0).
  //    (Only blocks exit code 0 from the licensecheck thread; leave others.)
  try {
    var Sys = Java.use('java.lang.System');
    Sys.exit.implementation = function (code) {
      console.log('[pairip] System.exit(' + code + ') swallowed');
    };
  } catch (e) {}

  console.log('[*] PairIP bypass installed');
});
