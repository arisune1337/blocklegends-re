package net.blocklegends.os.android;

import android.content.Context;
import android.media.AudioAttributes;
import android.os.Build;
import android.os.VibrationEffect;
import android.os.Vibrator;
import android.os.VibratorManager;

/* loaded from: classes4.dex */
public class VibrationManager {
    private static VibrationManager instance;
    private final Vibrator vibrator;

    private VibrationManager(Context context) {
        if (Build.VERSION.SDK_INT < 31) {
            this.vibrator = (Vibrator) context.getSystemService("vibrator");
        } else {
            VibratorManager vibratorManager = (VibratorManager) context.getSystemService("vibrator_manager");
            this.vibrator = vibratorManager != null ? vibratorManager.getDefaultVibrator() : null;
        }
    }

    public static void init(Context context) {
        if (instance == null) {
            instance = new VibrationManager(context);
            try {
                nativeInit();
            } catch (Throwable th) {
                th.printStackTrace();
            }
        }
    }

    private static native void nativeInit();

    public static void stopVibrating() {
        Vibrator vibrator;
        VibrationManager vibrationManager = instance;
        if (vibrationManager == null || (vibrator = vibrationManager.vibrator) == null) {
            return;
        }
        vibrator.cancel();
    }

    public static void vibrate(int r4) {
        Vibrator vibrator;
        VibrationManager vibrationManager = instance;
        if (vibrationManager == null || (vibrator = vibrationManager.vibrator) == null || !vibrator.hasVibrator()) {
            return;
        }
        try {
            instance.vibrator.vibrate(VibrationEffect.createOneShot(Math.max(20L, r4), -1), new AudioAttributes.Builder().setUsage(14).setContentType(4).build());
        } catch (SecurityException e) {
            e.printStackTrace();
        }
    }

    public static void vibratePattern(long[] jArr, int r2) {
        Vibrator vibrator;
        VibrationManager vibrationManager = instance;
        if (vibrationManager == null || (vibrator = vibrationManager.vibrator) == null || !vibrator.hasVibrator()) {
            return;
        }
        try {
            instance.vibrator.vibrate(VibrationEffect.createWaveform(jArr, r2), new AudioAttributes.Builder().setUsage(14).setContentType(4).build());
        } catch (SecurityException e) {
            e.printStackTrace();
        }
    }
}
