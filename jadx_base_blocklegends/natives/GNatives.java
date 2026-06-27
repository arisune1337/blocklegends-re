package net.blocklegends.natives;

import android.app.AlertDialog;
import android.content.ActivityNotFoundException;
import android.content.ContentResolver;
import android.content.ContentValues;
import android.content.Context;
import android.content.DialogInterface;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.graphics.Bitmap;
import android.net.Uri;
import android.opengl.EGLContext;
import android.opengl.EGLDisplay;
import android.opengl.EGLSurface;
import android.os.Build;
import android.os.Handler;
import android.os.Looper;
import android.os.PowerManager;
import android.os.Process;
import android.provider.MediaStore;
import android.telephony.TelephonyManager;
import android.util.Log;
import android.view.PixelCopy;
import android.view.inputmethod.InputMethodManager;
import com.tiktok.TikTokBusinessSdk;
import java.io.OutputStream;
import java.util.Iterator;
import java.util.Locale;
import net.blocklegends.MainActivity;
import net.blocklegends.MainApp;
import net.blocklegends.ui.GameSurfaceView;
import net.blocklegends.ui.GyroController;
import net.blocklegends.ui.GyroscopeEC;
import net.blocklegends.utils.AntiAutoClickerUtil;
import net.blocklegends.utils.Util;
import org.json.JSONObject;

/* loaded from: classes4.dex */
public class GNatives {

    /* JADX INFO: Access modifiers changed from: package-private */
    /* renamed from: net.blocklegends.natives.GNatives$5, reason: invalid class name */
    /* loaded from: classes4.dex */
    public static /* synthetic */ class AnonymousClass5 {
        static final /* synthetic */ int[] $SwitchMap$net$blocklegends$utils$AntiAutoClickerUtil$Reason;

        static {
            int[] r0 = new int[AntiAutoClickerUtil.Reason.values().length];
            $SwitchMap$net$blocklegends$utils$AntiAutoClickerUtil$Reason = r0;
            try {
                r0[AntiAutoClickerUtil.Reason.SHIZUKU_PACKAGE.ordinal()] = 1;
            } catch (NoSuchFieldError unused) {
            }
            try {
                $SwitchMap$net$blocklegends$utils$AntiAutoClickerUtil$Reason[AntiAutoClickerUtil.Reason.ROOT_PACKAGE.ordinal()] = 2;
            } catch (NoSuchFieldError unused2) {
            }
            try {
                $SwitchMap$net$blocklegends$utils$AntiAutoClickerUtil$Reason[AntiAutoClickerUtil.Reason.KNOWN_AUTOCLICKER_PACKAGE.ordinal()] = 3;
            } catch (NoSuchFieldError unused3) {
            }
            try {
                $SwitchMap$net$blocklegends$utils$AntiAutoClickerUtil$Reason[AntiAutoClickerUtil.Reason.SUSPICIOUS_ACCESSIBILITY_ENABLED.ordinal()] = 4;
            } catch (NoSuchFieldError unused4) {
            }
            try {
                $SwitchMap$net$blocklegends$utils$AntiAutoClickerUtil$Reason[AntiAutoClickerUtil.Reason.XPOSED_PRESENT.ordinal()] = 5;
            } catch (NoSuchFieldError unused5) {
            }
            try {
                $SwitchMap$net$blocklegends$utils$AntiAutoClickerUtil$Reason[AntiAutoClickerUtil.Reason.LSPOSED_PACKAGE.ordinal()] = 6;
            } catch (NoSuchFieldError unused6) {
            }
            try {
                $SwitchMap$net$blocklegends$utils$AntiAutoClickerUtil$Reason[AntiAutoClickerUtil.Reason.FRIDA_SUSPECTED.ordinal()] = 7;
            } catch (NoSuchFieldError unused7) {
            }
            try {
                $SwitchMap$net$blocklegends$utils$AntiAutoClickerUtil$Reason[AntiAutoClickerUtil.Reason.TEST_KEYS_BUILD.ordinal()] = 8;
            } catch (NoSuchFieldError unused8) {
            }
            try {
                $SwitchMap$net$blocklegends$utils$AntiAutoClickerUtil$Reason[AntiAutoClickerUtil.Reason.SU_BINARY_FOUND.ordinal()] = 9;
            } catch (NoSuchFieldError unused9) {
            }
            try {
                $SwitchMap$net$blocklegends$utils$AntiAutoClickerUtil$Reason[AntiAutoClickerUtil.Reason.MONKEY_OR_TESTHARNESS.ordinal()] = 10;
            } catch (NoSuchFieldError unused10) {
            }
        }
    }

    private static String buildDetectionMessage(AntiAutoClickerUtil.DetectionResult detectionResult) {
        boolean equals = "tr".equals(Locale.getDefault().getLanguage());
        StringBuilder sb = new StringBuilder();
        if (equals) {
            sb.append("Cihazınızda şüpheli aktivite tespit edildi:\n\n");
        } else {
            sb.append("Suspicious activity has been detected on your device:\n\n");
        }
        Iterator it = detectionResult.reasons.iterator();
        while (it.hasNext()) {
            switch (AnonymousClass5.$SwitchMap$net$blocklegends$utils$AntiAutoClickerUtil$Reason[((AntiAutoClickerUtil.Reason) it.next()).ordinal()]) {
                case 1:
                    sb.append(equals ? "• Shizuku servisi tespit edildi\n" : "• Shizuku service detected\n");
                    break;
                case 2:
                    sb.append(equals ? "• Root yönetim uygulaması tespit edildi\n" : "• Root management app detected\n");
                    break;
                case 3:
                    sb.append(equals ? "• Auto clicker uygulaması tespit edildi\n" : "• Auto clicker app detected\n");
                    break;
                case 4:
                    sb.append(equals ? "• Şüpheli erişilebilirlik servisi aktif\n" : "• Suspicious accessibility service enabled\n");
                    break;
                case 5:
                    sb.append(equals ? "• Xposed framework tespit edildi\n" : "• Xposed framework detected\n");
                    break;
                case 6:
                    sb.append(equals ? "• LSPosed framework tespit edildi\n" : "• LSPosed framework detected\n");
                    break;
                case 7:
                    sb.append(equals ? "• Frida instrumentation tespit edildi\n" : "• Frida instrumentation detected\n");
                    break;
                case 8:
                    sb.append(equals ? "• Test-keys build tespit edildi\n" : "• Test-keys build detected\n");
                    break;
                case 9:
                    sb.append(equals ? "• Root erişimi (su binary) tespit edildi\n" : "• Root access (su binary) detected\n");
                    break;
                case 10:
                    sb.append(equals ? "• Test harness veya monkey runner tespit edildi\n" : "• Test harness or monkey runner detected\n");
                    break;
            }
        }
        if (!detectionResult.hitPackages.isEmpty()) {
            if (equals) {
                sb.append("\nTespit edilen uygulamalar:\n");
            } else {
                sb.append("\nDetected applications:\n");
            }
            Iterator<String> it2 = detectionResult.hitPackages.iterator();
            while (it2.hasNext()) {
                sb.append("• ").append(getAppNameFromPackage(it2.next())).append("\n");
            }
            if (equals) {
                sb.append("\nNot: Yukarıdaki uygulamaları silmenizi tavsiye ederiz, giriş yapabilmek için.\n");
            } else {
                sb.append("\nNote: We recommend removing the above applications to be able to log in.\n");
            }
        }
        if (equals) {
            sb.append("\nOyun, tüm oyunculara adil bir deneyim sunmak için tasarlanmıştır. Lütfen hile araçlarını veya root erişimini kaldırarak oynamaya devam edin. Bunun bir hata olduğunu düşünüyorsanız, lütfen destek ekibiyle iletişime geçin.");
        } else {
            sb.append("\nThe game is designed to provide a fair experience for all players. Please remove any cheating tools or root access to continue playing. If you believe this is an error, please contact support.");
        }
        return sb.toString();
    }

    public static native boolean checkAndBindSurface();

    public static void closeKeyboard() {
        try {
            final MainActivity activity = MainActivity.getActivity();
            if (activity == null) {
                Log.w("GNatives", "MainActivity is null in closeKeyboard");
            } else {
                activity.runOnUiThread(new Runnable() { // from class: net.blocklegends.natives.GNatives$$ExternalSyntheticLambda9
                    @Override // java.lang.Runnable
                    public final void run() {
                        GNatives.lambda$closeKeyboard$1(MainActivity.this);
                    }
                });
            }
        } catch (Exception e) {
            Log.e("GNatives", "Error closing keyboard: " + e.getMessage());
        }
    }

    public static native void gameLoopTick();

    public static int getAndroidVersion() {
        try {
            return Build.VERSION.SDK_INT;
        } catch (Throwable unused) {
            return -1;
        }
    }

    private static String getAppNameFromPackage(String str) {
        try {
            MainActivity activity = MainActivity.getActivity();
            if (activity == null) {
                return str;
            }
            PackageManager packageManager = activity.getPackageManager();
            return packageManager.getApplicationLabel(packageManager.getApplicationInfo(str, 0)).toString();
        } catch (Exception unused) {
            Log.e("GNatives", "Failed to get app name for package: " + str);
            return str;
        }
    }

    public static String getAppVersion() {
        try {
            MainActivity activity = MainActivity.getActivity();
            if (activity == null) {
                Log.w("GNatives", "MainActivity is null in getAppVersion");
                return null;
            }
            PackageManager packageManager = activity.getPackageManager();
            if (packageManager != null) {
                return packageManager.getPackageInfo(activity.getPackageName(), 0).versionName;
            }
            Log.w("GNatives", "PackageManager is null");
            return null;
        } catch (Throwable th) {
            Log.e("GNatives", "Error getting app version: " + th.getMessage());
            return null;
        }
    }

    public static native String getCachedGpuRenderer();

    public static String getDefaultCountryCode() {
        try {
            String simCountryCode = getSimCountryCode();
            return (simCountryCode == null || simCountryCode.isEmpty()) ? Locale.getDefault().getCountry() : simCountryCode;
        } catch (Throwable unused) {
            return null;
        }
    }

    public static String getDeviceModel() {
        try {
            return Build.MANUFACTURER + " " + Build.MODEL;
        } catch (Throwable unused) {
            return null;
        }
    }

    public static native String getLanguageKey(String str);

    public static String getSimCountryCode() {
        try {
            MainActivity activity = MainActivity.getActivity();
            if (activity == null) {
                Log.w("GNatives", "MainActivity is null in getSimCountryCode");
                return null;
            }
            TelephonyManager telephonyManager = (TelephonyManager) activity.getSystemService("phone");
            if (telephonyManager == null) {
                Log.w("GNatives", "TelephonyManager is null");
                return null;
            }
            String simCountryIso = telephonyManager.getSimCountryIso();
            if (simCountryIso != null && !simCountryIso.isEmpty()) {
                return simCountryIso.toUpperCase();
            }
            return null;
        } catch (Throwable th) {
            Log.e("GNatives", "Error getting SIM country code: " + th.getMessage());
            return null;
        }
    }

    public static native boolean isGpuInfoCached();

    public static boolean isPowerSavingActive() {
        try {
            MainActivity activity = MainActivity.getActivity();
            if (activity == null) {
                Log.w("GNatives", "MainActivity is null in isPowerSavingActive");
                return false;
            }
            PowerManager powerManager = (PowerManager) activity.getSystemService("power");
            if (powerManager != null) {
                return powerManager.isPowerSaveMode();
            }
            return false;
        } catch (Throwable th) {
            Log.e("GNatives", "Error checking power saving mode: " + th.getMessage());
            return false;
        }
    }

    public static boolean isThermalStateHigh() {
        try {
            if (Build.VERSION.SDK_INT >= 29) {
                MainActivity activity = MainActivity.getActivity();
                if (activity == null) {
                    Log.w("GNatives", "MainActivity is null in isThermalStateHigh");
                    return false;
                }
                PowerManager powerManager = (PowerManager) activity.getSystemService("power");
                if (powerManager != null && powerManager.getCurrentThermalStatus() > 2) {
                    return true;
                }
            }
            return false;
        } catch (Throwable th) {
            Log.e("GNatives", "Error checking thermal state: " + th.getMessage());
            return false;
        }
    }

    /* JADX INFO: Access modifiers changed from: package-private */
    public static /* synthetic */ void lambda$closeKeyboard$1(MainActivity mainActivity) {
        try {
            InputMethodManager inputMethodManager = (InputMethodManager) mainActivity.getSystemService("input_method");
            if (inputMethodManager == null || mainActivity.editText == null) {
                Log.w("GNatives", "IMM or editText is null in closeKeyboard");
            } else {
                inputMethodManager.hideSoftInputFromWindow(mainActivity.editText.getWindowToken(), 0);
            }
            if (mainActivity.editText != null) {
                mainActivity.editText.clearTextWithoutNotify();
            }
            if (mainActivity.windowManager != null) {
                mainActivity.windowManager.onWindowFocusChanged(true);
            }
        } catch (Exception e) {
            Log.e("GNatives", "Error in closeKeyboard UI thread: " + e.getMessage());
        }
    }

    /* JADX INFO: Access modifiers changed from: package-private */
    public static /* synthetic */ void lambda$openKeyboard$0(MainActivity mainActivity) {
        try {
            if (mainActivity.editText == null) {
                Log.w("GNatives", "editText is null in openKeyboard");
                return;
            }
            mainActivity.editText.clearTextWithoutNotify();
            mainActivity.editText.requestFocus();
            InputMethodManager inputMethodManager = (InputMethodManager) mainActivity.getSystemService("input_method");
            if (inputMethodManager != null) {
                inputMethodManager.showSoftInput(mainActivity.editText, 1);
            } else {
                Log.w("GNatives", "InputMethodManager is null in openKeyboard");
            }
        } catch (Exception e) {
            Log.e("GNatives", "Error in openKeyboard UI thread: " + e.getMessage());
        }
    }

    /* JADX INFO: Access modifiers changed from: package-private */
    public static /* synthetic */ void lambda$openLink$3(String str, MainActivity mainActivity) {
        try {
            Intent intent = new Intent("android.intent.action.VIEW");
            intent.setData(Uri.parse(str));
            mainActivity.startActivity(intent);
        } catch (Exception e) {
            Log.e("GNatives", "Error opening URL: " + str, e);
        }
    }

    /* JADX INFO: Access modifiers changed from: package-private */
    public static /* synthetic */ void lambda$openStore$4(MainActivity mainActivity) {
        try {
            try {
                Intent intent = new Intent("android.intent.action.VIEW", Uri.parse("market://details?id=net.blocklegends"));
                intent.setPackage("com.android.vending");
                intent.addFlags(268435456);
                mainActivity.startActivity(intent);
            } catch (Exception e) {
                Log.e("GNatives", "Error opening store: ", e);
            }
        } catch (ActivityNotFoundException unused) {
            Intent intent2 = new Intent("android.intent.action.VIEW", Uri.parse("https://play.google.com/store/apps/details?id=net.blocklegends"));
            intent2.addFlags(268435456);
            mainActivity.startActivity(intent2);
        } catch (Exception e2) {
            Log.e("GNatives", "Error opening store: ", e2);
        }
    }

    /* JADX INFO: Access modifiers changed from: package-private */
    public static /* synthetic */ void lambda$requestNotificationPermission$2(MainActivity mainActivity) {
        try {
            mainActivity.requestNotificationPermission();
        } catch (Exception e) {
            Log.e("GNatives", "Error in requestNotificationPermission UI thread: " + e.getMessage());
        }
    }

    /* JADX INFO: Access modifiers changed from: package-private */
    public static /* synthetic */ void lambda$showAutoClickerPopup$7(AntiAutoClickerUtil.DetectionResult detectionResult, final MainActivity mainActivity) {
        try {
            new AlertDialog.Builder(mainActivity).setTitle("Suspicious Activity Detected").setMessage(buildDetectionMessage(detectionResult)).setCancelable(false).setPositiveButton("OK", new DialogInterface.OnClickListener() { // from class: net.blocklegends.natives.GNatives.3
                @Override // android.content.DialogInterface.OnClickListener
                public void onClick(DialogInterface dialogInterface, int r2) {
                    dialogInterface.dismiss();
                    MainActivity.this.finish();
                }
            }).show();
        } catch (Exception e) {
            Log.e("GNatives", "Failed to show auto clicker dialog: " + e.getMessage());
        }
    }

    /* JADX INFO: Access modifiers changed from: package-private */
    public static /* synthetic */ void lambda$showCustomPopup$8(final MainActivity mainActivity, String str, String str2, final boolean z, final long j) {
        try {
            new AlertDialog.Builder(mainActivity).setTitle(str).setMessage(str2).setCancelable(z).setPositiveButton("OK", new DialogInterface.OnClickListener() { // from class: net.blocklegends.natives.GNatives.4
                @Override // android.content.DialogInterface.OnClickListener
                public void onClick(DialogInterface dialogInterface, int r2) {
                    dialogInterface.dismiss();
                    if (z) {
                        GNatives.onCustomPopupEvent(j);
                    } else {
                        mainActivity.finish();
                    }
                }
            }).show();
        } catch (Exception e) {
            Log.e("GNatives", "Failed to show auto clicker dialog: " + e.getMessage());
        }
    }

    /* JADX INFO: Access modifiers changed from: package-private */
    public static /* synthetic */ void lambda$showEmulatorPopup$6(final MainActivity mainActivity) {
        try {
            new AlertDialog.Builder(mainActivity).setTitle("Emulator Detected").setMessage("Your device has been detected as an emulator. The game is designed to provide a fair experience on real devices. Please try playing on a real device. If you believe this is an error, please contact support.").setCancelable(false).setPositiveButton("OK", new DialogInterface.OnClickListener() { // from class: net.blocklegends.natives.GNatives.2
                @Override // android.content.DialogInterface.OnClickListener
                public void onClick(DialogInterface dialogInterface, int r2) {
                    dialogInterface.dismiss();
                    MainActivity.this.finish();
                }
            }).show();
        } catch (Exception e) {
            Log.e("GNatives", "Failed to show emulator dialog: " + e.getMessage());
        }
    }

    /* JADX INFO: Access modifiers changed from: package-private */
    public static /* synthetic */ void lambda$startGyroscope$5() {
        try {
            GyroscopeEC gyroscopeEC = new GyroscopeEC(MainActivity.getActivity());
            MainActivity.getActivity().gyroscopeDetector = gyroscopeEC;
            if (!gyroscopeEC.hasSensors()) {
                Log.w("MainActivity", "No motion sensors");
            }
            gyroscopeEC.startDetection(new GyroscopeEC.DetectionCallback() { // from class: net.blocklegends.natives.GNatives.1
                @Override // net.blocklegends.ui.GyroscopeEC.DetectionCallback
                public void onChallengeRequested(String str) {
                }

                @Override // net.blocklegends.ui.GyroscopeEC.DetectionCallback
                public void onFailed(String str) {
                    GNatives.onEmulatorUsage();
                }

                @Override // net.blocklegends.ui.GyroscopeEC.DetectionCallback
                public void onSuccess() {
                }
            });
        } catch (Exception e) {
            Log.e("MainActivity", "Failed to initialize gyroscope detector: " + e.getMessage());
        }
    }

    /* JADX INFO: Access modifiers changed from: package-private */
    public static /* synthetic */ void lambda$takeScreenshot$9(Bitmap bitmap, MainActivity mainActivity, int r9) {
        if (r9 != 0) {
            return;
        }
        try {
            ContentValues contentValues = new ContentValues();
            contentValues.put("_display_name", "BlockLegends_" + System.currentTimeMillis() + ".png");
            contentValues.put("mime_type", "image/png");
            contentValues.put("relative_path", "Pictures/BlockLegends");
            ContentResolver contentResolver = mainActivity.getContentResolver();
            Uri insert = contentResolver.insert(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, contentValues);
            if (insert != null) {
                OutputStream openOutputStream = contentResolver.openOutputStream(insert);
                if (openOutputStream != null) {
                    try {
                        bitmap.compress(Bitmap.CompressFormat.PNG, 100, openOutputStream);
                    } finally {
                    }
                }
                if (openOutputStream != null) {
                    openOutputStream.close();
                }
            }
            Log.i("GNatives", "Screenshot saved to gallery");
        } catch (Throwable th) {
            try {
                Log.e("GNatives", "takeScreenshot save failed: " + th.getMessage());
            } finally {
                bitmap.recycle();
            }
        }
    }

    public static void nativeDisableGyroController() {
        try {
            GyroController.nativeDisableGyroController();
        } catch (Throwable th) {
            Log.e("GNatives", "Error disabling gyro controller: " + th.getMessage());
        }
    }

    public static boolean nativeInitGyroController() {
        try {
            return GyroController.nativeInitGyroController();
        } catch (Throwable th) {
            Log.e("GNatives", "Error initializing gyro controller: " + th.getMessage());
            return false;
        }
    }

    public static boolean nativeIsSupportedSensorService() {
        try {
            return GyroController.nativeIsSupportedSensorService();
        } catch (Throwable th) {
            Log.e("GNatives", "Error checking sensor support: " + th.getMessage());
            return false;
        }
    }

    public static boolean nativeIsVulkanEnabled() {
        return MainApp.getApp().isVulkanEnabled();
    }

    public static void nativeSetVulkanEnabled(boolean z) {
        MainApp.getApp().setVulkanEnabled(z);
    }

    public static int nativeUpdateCoordinateSystem() {
        try {
            return GyroController.nativeUpdateCoordinateSystem();
        } catch (Throwable th) {
            Log.e("GNatives", "Error updating coordinate system: " + th.getMessage());
            return -9999;
        }
    }

    public static native void nextStep(String str);

    public static native void onActionCancel(int r0, int r1, int r2);

    public static native void onActionDown(float f, float f2, int r2);

    public static native void onActionMove(int r0, float f, float f2);

    public static native void onActionPointerDown(float f, float f2, int r2);

    public static native void onActionPointerUp(int r0, int r1, int r2);

    public static native void onActionUp(int r0, int r1, int r2);

    public static native void onActivityRestart();

    public static native void onActivityStop();

    public static native void onAppBackground();

    public static native void onAppForeground(Object obj);

    public static native void onAppleSignFailed(String str, String str2);

    public static native void onBillingSetupComplete();

    public static native void onCustomPopupEvent(long j);

    public static native void onDisplayChanged(int r0);

    public static native void onEmulatorUsage();

    public static native void onFCMTokenReceived(String str);

    public static native void onGcAuthSuccess(String str, String str2);

    public static native void onGcServerAuthCode(String str);

    public static native void onGoogleAdLoaded(boolean z);

    public static native void onGoogleSignFailed(String str, String str2);

    public static native void onIntegrityFailure(String str, long j);

    public static native void onIntegritySuccess(String str, long j);

    public static native void onIntegrityThrow(String str, long j);

    public static native void onKeyDown(int r0, String str, char c);

    public static native void onKeyUp(int r0);

    public static native void onKeyboardListener(int r0, int r1, int r2, int r3, int r4);

    public static native void onMouseMove(float f, float f2, int r2);

    public static native void onNetworkChanged();

    public static native void onProcessPurchases(String str);

    public static native boolean onResetContext();

    public static native void onRestoreInstanceState();

    public static native void onSaveInstanceState();

    public static native void onSensorChanged(int r0, float[] fArr);

    public static native void onSurfaceChanged(int r0, int r1);

    public static native void onSurfaceDestroy();

    public static native void onSurfaceDestroyed();

    public static native void onSurfacePause();

    public static native void onSurfaceResume();

    public static native void onTokenReceived(String str, String str2);

    public static native void onUserEarnedReward(long j, boolean z, String str, int r4);

    public static void openKeyboard() {
        try {
            final MainActivity activity = MainActivity.getActivity();
            if (activity == null) {
                Log.w("GNatives", "MainActivity is null in openKeyboard");
            } else {
                activity.runOnUiThread(new Runnable() { // from class: net.blocklegends.natives.GNatives$$ExternalSyntheticLambda2
                    @Override // java.lang.Runnable
                    public final void run() {
                        GNatives.lambda$openKeyboard$0(MainActivity.this);
                    }
                });
            }
        } catch (Exception e) {
            Log.e("GNatives", "Error opening keyboard: " + e.getMessage());
        }
    }

    public static void openLink(final String str) {
        final MainActivity activity = MainActivity.getActivity();
        activity.runOnUiThread(new Runnable() { // from class: net.blocklegends.natives.GNatives$$ExternalSyntheticLambda6
            @Override // java.lang.Runnable
            public final void run() {
                GNatives.lambda$openLink$3(str, activity);
            }
        });
    }

    public static void openStore() {
        final MainActivity activity = MainActivity.getActivity();
        activity.runOnUiThread(new Runnable() { // from class: net.blocklegends.natives.GNatives$$ExternalSyntheticLambda7
            @Override // java.lang.Runnable
            public final void run() {
                GNatives.lambda$openStore$4(MainActivity.this);
            }
        });
    }

    public static void requestNotificationPermission() {
        try {
            MainApp.sendFCMTokenToServer();
            final MainActivity activity = MainActivity.getActivity();
            if (activity == null) {
                Log.w("GNatives", "MainActivity is null in requestNotificationPermission");
            } else {
                activity.runOnUiThread(new Runnable() { // from class: net.blocklegends.natives.GNatives$$ExternalSyntheticLambda8
                    @Override // java.lang.Runnable
                    public final void run() {
                        GNatives.lambda$requestNotificationPermission$2(MainActivity.this);
                    }
                });
            }
        } catch (Exception e) {
            Log.e("GNatives", "Error requesting notification permission: " + e.getMessage());
        }
    }

    public static boolean resetContext() {
        MainActivity.restartApplication();
        return true;
    }

    public static void restartApp(Context context) {
        Intent intent = new Intent(context, (Class<?>) MainActivity.class);
        intent.addFlags(268468224);
        context.startActivity(intent);
        Runtime.getRuntime().exit(0);
    }

    public static native void setAsyncContext(EGLContext eGLContext);

    public static native void setAsyncSurfaceContext(EGLSurface eGLSurface);

    public static native void setChunkContext(EGLContext eGLContext);

    public static native void setChunkSurfaceContext(EGLSurface eGLSurface);

    public static native void setChunkWaiterContext(EGLContext eGLContext);

    public static native void setChunkWaiterSurfaceContext(EGLSurface eGLSurface);

    public static native void setDisplayContext(EGLDisplay eGLDisplay);

    public static native void setGameContext(EGLContext eGLContext);

    public static native void setGameSurfaceContext(EGLSurface eGLSurface);

    public static void setGyroscopeOrientationLock(int r5) {
        try {
            MainActivity activity = MainActivity.getActivity();
            if (activity == null) {
                Log.e("GNatives", "MainActivity is null in setGyroscopeOrientationLock");
            } else {
                if (MainActivity.isExternalUiInProgress()) {
                    Log.i("GNatives", "Skipping orientation lock while external UI is active");
                    return;
                }
                if (r5 != 6) {
                    Log.i("GNatives", "Normalized native orientation " + r5 + " to SENSOR_LANDSCAPE");
                }
                activity.forceLandscapeOrientation("nativeOrientation:" + r5);
            }
        } catch (Throwable th) {
            Log.e("GNatives", "Error setting orientation lock: " + th.getMessage());
        }
    }

    public static native void setLoad1Context(EGLContext eGLContext);

    public static native void setLoad1SurfaceContext(EGLSurface eGLSurface);

    public static native void setLoad2Context(EGLContext eGLContext);

    public static native void setLoad2SurfaceContext(EGLSurface eGLSurface);

    public static native void setLoad3Context(EGLContext eGLContext);

    public static native void setLoad3SurfaceContext(EGLSurface eGLSurface);

    public static native void setLoad4Context(EGLContext eGLContext);

    public static native void setLoad4SurfaceContext(EGLSurface eGLSurface);

    public static native void setOnCompleteListener(int r0);

    public static native void setOnLoadCompleteListener(int r0, int r1);

    public static native void setSplashContext(EGLContext eGLContext);

    public static native void setSplashSurfaceContext(EGLSurface eGLSurface);

    public static native void setSurfaceContext(EGLSurface eGLSurface);

    public static void setThreadPriorityDisplay() {
        try {
            Process.setThreadPriority(-4);
        } catch (Throwable unused) {
        }
    }

    public static native void setUpdatedSurface(EGLSurface eGLSurface);

    public static native void setenv(String str, String str2);

    public static void showAutoClickerPopup(final AntiAutoClickerUtil.DetectionResult detectionResult) {
        final MainActivity activity;
        if (GyroscopeEC.isProbablyEByBuild() || (activity = MainActivity.getActivity()) == null || activity.isFinishing()) {
            return;
        }
        activity.runOnUiThread(new Runnable() { // from class: net.blocklegends.natives.GNatives$$ExternalSyntheticLambda3
            @Override // java.lang.Runnable
            public final void run() {
                GNatives.lambda$showAutoClickerPopup$7(AntiAutoClickerUtil.DetectionResult.this, activity);
            }
        });
    }

    public static void showCustomPopup(final long j, final String str, final String str2, final boolean z) {
        final MainActivity activity = MainActivity.getActivity();
        if (activity == null || activity.isFinishing()) {
            return;
        }
        activity.runOnUiThread(new Runnable() { // from class: net.blocklegends.natives.GNatives$$ExternalSyntheticLambda1
            @Override // java.lang.Runnable
            public final void run() {
                GNatives.lambda$showCustomPopup$8(MainActivity.this, str, str2, z, j);
            }
        });
    }

    public static void showEmulatorPopup() {
        final MainActivity activity = MainActivity.getActivity();
        if (activity == null || activity.isFinishing()) {
            return;
        }
        activity.runOnUiThread(new Runnable() { // from class: net.blocklegends.natives.GNatives$$ExternalSyntheticLambda0
            @Override // java.lang.Runnable
            public final void run() {
                GNatives.lambda$showEmulatorPopup$6(MainActivity.this);
            }
        });
    }

    public static void startGyroscope() {
        Util.run(new Runnable() { // from class: net.blocklegends.natives.GNatives$$ExternalSyntheticLambda4
            @Override // java.lang.Runnable
            public final void run() {
                GNatives.lambda$startGyroscope$5();
            }
        });
    }

    public static void takeScreenshot() {
        GameSurfaceView gameSurfaceView;
        try {
            final MainActivity activity = MainActivity.getActivity();
            if (activity != null && (gameSurfaceView = activity.surfaceView) != null && gameSurfaceView.getWidth() > 0 && gameSurfaceView.getHeight() > 0) {
                final Bitmap createBitmap = Bitmap.createBitmap(gameSurfaceView.getWidth(), gameSurfaceView.getHeight(), Bitmap.Config.ARGB_8888);
                PixelCopy.request(gameSurfaceView, createBitmap, new PixelCopy.OnPixelCopyFinishedListener() { // from class: net.blocklegends.natives.GNatives$$ExternalSyntheticLambda5
                    @Override // android.view.PixelCopy.OnPixelCopyFinishedListener
                    public final void onPixelCopyFinished(int r2) {
                        GNatives.lambda$takeScreenshot$9(createBitmap, activity, r2);
                    }
                }, new Handler(Looper.getMainLooper()));
            }
        } catch (Throwable th) {
            Log.e("GNatives", "takeScreenshot failed: " + th.getMessage());
        }
    }

    public static void trackTikTokEvent(String str, String str2) {
        JSONObject jSONObject;
        if (str2 != null) {
            try {
                if (!str2.isEmpty()) {
                    jSONObject = new JSONObject(str2);
                    TikTokBusinessSdk.trackEvent(str, jSONObject);
                }
            } catch (Throwable unused) {
                return;
            }
        }
        jSONObject = new JSONObject();
        TikTokBusinessSdk.trackEvent(str, jSONObject);
    }

    public static native void updatePositions(int r0, float[] fArr, String str);
}
