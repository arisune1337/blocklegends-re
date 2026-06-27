package net.blocklegends.ui;

import android.app.Activity;
import android.content.Context;
import android.hardware.Sensor;
import android.hardware.SensorEvent;
import android.hardware.SensorEventListener;
import android.hardware.SensorManager;
import android.os.Build;
import android.os.Handler;
import android.os.Looper;
import android.os.VibrationEffect;
import android.os.Vibrator;
import android.os.VibratorManager;
import androidx.core.os.EnvironmentCompat;
import java.util.Arrays;

/* loaded from: classes4.dex */
public class GyroscopeEC implements SensorEventListener {
    private static final float ACC_STD_STATIC_MAX = 0.035f;
    private static final float ACTIVE_ACC_STD_MIN = 0.1f;
    private static final float ACTIVE_GYRO_STD_MIN = 0.01f;
    private static final long CHECK_INTERVAL_MS = 2000;
    private static final boolean DEFAULT_AUTO_HAPTIC_NUDGE = true;
    private static final float GYRO_STD_STATIC_MAX = 0.004f;
    private static final long HAPTIC_PATTERN_TOTAL_MS = 600;
    private static final long HAPTIC_TO_ACTIVE_DELAY_MS = 150;
    private static final int MAX_ACTIVE_ATTEMPTS = 2;
    private static final long PASSIVE_DURATION_MS = 12000;
    private static final int SENSOR_RATE = 1;
    private static final long SENSOR_WARMUP_MS = 800;
    private static final String TAG = "GyroEC";
    private static final int UNIQUE_MAX = 6;
    private static final int WINDOW_SIZE = 256;
    private final boolean accelerometerAvailable;
    private final Sensor accelerometerSensor;
    private final Activity activity;
    private DetectionCallback callback;
    private final boolean gyroscopeAvailable;
    private final Sensor gyroscopeSensor;
    private final boolean linearAccelAvailable;
    private final Sensor linearAccelSensor;
    private final SensorManager sensorManager;
    private Vibrator vibrator;
    private final float[] gyroMagWindow = new float[256];
    private final float[] accMagWindow = new float[256];
    private int gyroIndex = 0;
    private int gyroCount = 0;
    private int accIndex = 0;
    private int accCount = 0;
    private final float[] gravity = new float[3];
    private boolean gravityInitialized = false;
    private final int[] uniqueTempArray = new int[256];
    private boolean isDetecting = false;
    private boolean isActiveChallenge = false;
    private long detectionStartTime = 0;
    private long lastCheckTime = 0;
    private long detectionDurationMs = PASSIVE_DURATION_MS;
    private boolean challengeRequestedOnce = false;
    private boolean permanentlyDisabled = false;
    private boolean disableAfterEC = true;
    private int activeChallengeAttempts = 0;
    private final Handler mainHandler = new Handler(Looper.getMainLooper());
    private final boolean autoHapticNudgeEnabled = true;
    private boolean hasVibrator = false;

    /* loaded from: classes4.dex */
    public interface DetectionCallback {
        default void onChallengeRequested(String str) {
        }

        void onFailed(String str);

        void onSuccess();
    }

    /* JADX INFO: Access modifiers changed from: private */
    /* loaded from: classes4.dex */
    public static class Stats {
        float mean;
        float std;
        int uniqueCount;

        private Stats() {
        }
    }

    public GyroscopeEC(Activity activity) {
        this.activity = activity;
        SensorManager sensorManager = (SensorManager) activity.getSystemService("sensor");
        this.sensorManager = sensorManager;
        if (sensorManager == null) {
            this.gyroscopeSensor = null;
            this.linearAccelSensor = null;
            this.accelerometerSensor = null;
            this.gyroscopeAvailable = false;
            this.linearAccelAvailable = false;
            this.accelerometerAvailable = false;
        } else {
            Sensor defaultSensor = sensorManager.getDefaultSensor(4);
            this.gyroscopeSensor = defaultSensor;
            Sensor defaultSensor2 = sensorManager.getDefaultSensor(10);
            this.linearAccelSensor = defaultSensor2;
            Sensor defaultSensor3 = sensorManager.getDefaultSensor(1);
            this.accelerometerSensor = defaultSensor3;
            this.gyroscopeAvailable = defaultSensor != null;
            this.linearAccelAvailable = defaultSensor2 != null;
            this.accelerometerAvailable = defaultSensor3 != null;
        }
        initVibrator(activity);
    }

    private String buildFingerprintString() {
        return "Build: brand=" + Build.BRAND + ", device=" + Build.DEVICE + ", model=" + Build.MODEL + ", hardware=" + Build.HARDWARE + ", product=" + Build.PRODUCT + ", fingerprint=" + Build.FINGERPRINT + '\n';
    }

    /* JADX WARN: Removed duplicated region for block: B:49:0x00bb  */
    /* JADX WARN: Removed duplicated region for block: B:60:0x00cf  */
    /* JADX WARN: Removed duplicated region for block: B:76:0x00fe  */
    /*
        Code decompiled incorrectly, please refer to instructions dump.
        To view partially-correct add '--show-bad-code' argument
    */
    private void finishDetection() {
        /*
            Method dump skipped, instructions count: 308
            To view this dump add '--comments-level debug' option
        */
        throw new UnsupportedOperationException("Method not decompiled: net.blocklegends.ui.GyroscopeEC.finishDetection():void");
    }

    private void handleAccelWithGravitySeparation(float[] fArr) {
        if (fArr == null || fArr.length < 3 || System.currentTimeMillis() - this.detectionStartTime < SENSOR_WARMUP_MS) {
            return;
        }
        boolean z = this.gravityInitialized;
        float[] fArr2 = this.gravity;
        if (!z) {
            fArr2[0] = fArr[0];
            fArr2[1] = fArr[1];
            fArr2[2] = fArr[2];
            this.gravityInitialized = true;
            return;
        }
        float f = (fArr2[0] * 0.9f) + (fArr[0] * 0.100000024f);
        fArr2[0] = f;
        float f2 = (fArr2[1] * 0.9f) + (fArr[1] * 0.100000024f);
        fArr2[1] = f2;
        float f3 = (fArr2[2] * 0.9f) + (fArr[2] * 0.100000024f);
        fArr2[2] = f3;
        float f4 = fArr[0] - f;
        float f5 = fArr[1] - f2;
        float f6 = fArr[2] - f3;
        float sqrt = (float) Math.sqrt((f4 * f4) + (f5 * f5) + (f6 * f6));
        float[] fArr3 = this.accMagWindow;
        int r1 = this.accIndex;
        fArr3[r1] = sqrt;
        this.accIndex = (r1 + 1) % 256;
        int r0 = this.accCount;
        if (r0 < 256) {
            this.accCount = r0 + 1;
        }
    }

    private void handleGyro(float[] fArr) {
        if (fArr == null || fArr.length < 3 || System.currentTimeMillis() - this.detectionStartTime < SENSOR_WARMUP_MS) {
            return;
        }
        float f = fArr[0];
        float f2 = fArr[1];
        float f3 = fArr[2];
        float sqrt = (float) Math.sqrt((f * f) + (f2 * f2) + (f3 * f3));
        float[] fArr2 = this.gyroMagWindow;
        int r2 = this.gyroIndex;
        fArr2[r2] = sqrt;
        this.gyroIndex = (r2 + 1) % 256;
        int r0 = this.gyroCount;
        if (r0 < 256) {
            this.gyroCount = r0 + 1;
        }
    }

    private void handleLinearAccel(float[] fArr) {
        if (fArr == null || fArr.length < 3 || System.currentTimeMillis() - this.detectionStartTime < SENSOR_WARMUP_MS) {
            return;
        }
        float f = fArr[0];
        float f2 = fArr[1];
        float f3 = fArr[2];
        float sqrt = (float) Math.sqrt((f * f) + (f2 * f2) + (f3 * f3));
        float[] fArr2 = this.accMagWindow;
        int r2 = this.accIndex;
        fArr2[r2] = sqrt;
        this.accIndex = (r2 + 1) % 256;
        int r0 = this.accCount;
        if (r0 < 256) {
            this.accCount = r0 + 1;
        }
    }

    private void initVibrator(Context context) {
        try {
            if (Build.VERSION.SDK_INT >= 31) {
                VibratorManager vibratorManager = (VibratorManager) context.getSystemService("vibrator_manager");
                this.vibrator = vibratorManager != null ? vibratorManager.getDefaultVibrator() : null;
            } else {
                this.vibrator = (Vibrator) context.getSystemService("vibrator");
            }
            Vibrator vibrator = this.vibrator;
            this.hasVibrator = vibrator != null && vibrator.hasVibrator();
        } catch (Throwable unused) {
            this.vibrator = null;
            this.hasVibrator = false;
        }
    }

    /* JADX WARN: Removed duplicated region for block: B:45:0x009b  */
    /* JADX WARN: Removed duplicated region for block: B:49:0x00a3  */
    /*
        Code decompiled incorrectly, please refer to instructions dump.
        To view partially-correct add '--show-bad-code' argument
    */
    private void internalStart(boolean r2, long r3, net.blocklegends.ui.GyroscopeEC.DetectionCallback r5) {
        /*
            r1 = this;
            boolean r0 = r1.isDetecting
            if (r0 == 0) goto L6
            goto La2
        L6:
            r1.callback = r5
            boolean r0 = r1.gyroscopeAvailable
            if (r0 != 0) goto L15
            if (r5 == 0) goto L11
            r5.onSuccess()
        L11:
            r1.permanentlyDisable()
            return
        L15:
            boolean r0 = r1.hasSensors()
            if (r0 != 0) goto L38
            boolean r2 = isProbablyEByBuild()
            if (r5 == 0) goto L2e
            if (r2 == 0) goto L29
            java.lang.String r3 = "Hareket sensörü yok + build emülatör izleri mevcut"
            r5.onFailed(r3)
            goto L2e
        L29:
            java.lang.String r3 = "Hareket sensörü yok"
            r5.onFailed(r3)
        L2e:
            boolean r3 = r1.disableAfterEC
            if (r3 == 0) goto La2
            if (r2 == 0) goto La2
            r1.permanentlyDisable()
            return
        L38:
            r1.isActiveChallenge = r2
            r1.detectionDurationMs = r3
            long r3 = java.lang.System.currentTimeMillis()
            r1.detectionStartTime = r3
            r1.lastCheckTime = r3
            r3 = 0
            if (r2 != 0) goto L4b
            r1.challengeRequestedOnce = r3
            r1.activeChallengeAttempts = r3
        L4b:
            r2 = r3
        L4c:
            r4 = 3
            r0 = 0
            if (r2 >= r4) goto L57
            float[] r4 = r1.gravity
            r4[r2] = r0
            int r2 = r2 + 1
            goto L4c
        L57:
            r1.gravityInitialized = r3
            r2 = r3
        L5a:
            r4 = 256(0x100, float:3.59E-43)
            if (r2 >= r4) goto L69
            float[] r4 = r1.gyroMagWindow
            r4[r2] = r0
            float[] r4 = r1.accMagWindow
            r4[r2] = r0
            int r2 = r2 + 1
            goto L5a
        L69:
            r1.gyroCount = r3
            r1.gyroIndex = r3
            r1.accCount = r3
            r1.accIndex = r3
            boolean r2 = r1.gyroscopeAvailable
            r4 = 1
            if (r2 == 0) goto L7e
            android.hardware.SensorManager r2 = r1.sensorManager
            android.hardware.Sensor r3 = r1.gyroscopeSensor
            boolean r3 = r2.registerListener(r1, r3, r4)
        L7e:
            boolean r2 = r1.linearAccelAvailable
            if (r2 == 0) goto L8c
            android.hardware.SensorManager r2 = r1.sensorManager
            android.hardware.Sensor r0 = r1.linearAccelSensor
            boolean r2 = r2.registerListener(r1, r0, r4)
        L8a:
            r3 = r3 | r2
            goto L99
        L8c:
            boolean r2 = r1.accelerometerAvailable
            if (r2 == 0) goto L99
            android.hardware.SensorManager r2 = r1.sensorManager
            android.hardware.Sensor r0 = r1.accelerometerSensor
            boolean r2 = r2.registerListener(r1, r0, r4)
            goto L8a
        L99:
            if (r3 != 0) goto La3
            if (r5 == 0) goto La2
            java.lang.String r1 = "Sensör dinleyicileri kaydedilemedi"
            r5.onFailed(r1)
        La2:
            return
        La3:
            r1.isDetecting = r4
            return
        */
        throw new UnsupportedOperationException("Method not decompiled: net.blocklegends.ui.GyroscopeEC.internalStart(boolean, long, net.blocklegends.ui.GyroscopeEC$DetectionCallback):void");
    }

    public static boolean isProbablyEByBuild() {
        boolean z;
        boolean z2 = (Build.BRAND.startsWith("generic") && Build.DEVICE.startsWith("generic")) || Build.FINGERPRINT.startsWith("generic") || Build.FINGERPRINT.startsWith(EnvironmentCompat.MEDIA_UNKNOWN) || Build.HARDWARE.contains("goldfish") || Build.HARDWARE.contains("ranchu") || Build.MODEL.contains("google_sdk") || Build.MODEL.contains("Emulator") || Build.MODEL.contains("Android SDK built for x86") || Build.MANUFACTURER.contains("Genymotion") || Build.PRODUCT.contains("sdk_google") || Build.PRODUCT.contains("google_sdk") || Build.PRODUCT.equals("sdk") || Build.PRODUCT.startsWith("sdk_") || Build.PRODUCT.contains("sdk_x86") || Build.PRODUCT.contains("sdk_gphone") || Build.PRODUCT.contains("vbox86p") || Build.PRODUCT.contains("emulator") || Build.PRODUCT.contains("simulator") || Build.PRODUCT.contains("nox") || Build.PRODUCT.contains("bluestacks") || Build.PRODUCT.contains("ldplayer");
        try {
            z = "1".equals((String) Class.forName("android.os.SystemProperties").getMethod("get", String.class).invoke(null, "ro.kernel.qemu"));
        } catch (Throwable unused) {
            z = false;
        }
        return z2 || z;
    }

    private void performPeriodicCheck() {
        boolean z = this.gyroscopeAvailable;
        if (!z || this.gyroCount >= 30) {
            if ((this.linearAccelAvailable || this.accelerometerAvailable) && this.accCount < 30) {
                return;
            }
            Stats stats = stats(this.gyroMagWindow, this.gyroIndex, this.gyroCount, z);
            boolean z2 = false;
            Stats stats2 = stats(this.accMagWindow, this.accIndex, this.accCount, this.linearAccelAvailable || this.accelerometerAvailable);
            boolean z3 = this.gyroscopeAvailable && stats.std <= GYRO_STD_STATIC_MAX && stats.uniqueCount <= 6;
            boolean z4 = (this.linearAccelAvailable || this.accelerometerAvailable) && stats2.std <= ACC_STD_STATIC_MAX && stats2.uniqueCount <= 6;
            if (!this.gyroscopeAvailable || (!this.linearAccelAvailable && !this.accelerometerAvailable) ? z3 || z4 : z3 && z4) {
                z2 = true;
            }
            if (this.isActiveChallenge || !z2 || this.challengeRequestedOnce || System.currentTimeMillis() - this.detectionStartTime < 6000) {
                return;
            }
            this.challengeRequestedOnce = true;
            m2140lambda$finishDetection$1$netblocklegendsuiGyroscopeEC();
        }
    }

    private void permanentlyDisable() {
        this.permanentlyDisabled = true;
        stopDetection();
    }

    private Stats stats(float[] fArr, int r10, int r11, boolean z) {
        int r5;
        int[] r4;
        Stats stats = new Stats();
        float f = 0.0f;
        if (!z || r11 <= 0) {
            stats.mean = 0.0f;
            stats.std = 0.0f;
            stats.uniqueCount = 0;
            return stats;
        }
        int r102 = ((r10 - r11) + 256) % 256;
        int r42 = r102;
        float f2 = 0.0f;
        int r12 = 0;
        while (true) {
            if (r12 >= r11) {
                break;
            }
            f2 += fArr[r42];
            r12++;
            r42 = (r42 + 1) % 256;
        }
        float f3 = f2 / r11;
        int r43 = r102;
        int r122 = 0;
        while (r122 < r11) {
            float f4 = fArr[r43] - f3;
            f += f4 * f4;
            r122++;
            r43 = (r43 + 1) % 256;
        }
        float max = f / Math.max(1, r11 - 1);
        int r123 = 0;
        while (true) {
            r4 = this.uniqueTempArray;
            if (r123 >= r11) {
                break;
            }
            r4[r123] = Math.round(fArr[r102] * 1000.0f);
            r123++;
            r102 = (r102 + 1) % 256;
        }
        Arrays.sort(r4, 0, r11);
        int r2 = r11 > 0 ? 1 : 0;
        for (r5 = 1; r5 < r11; r5++) {
            int[] r9 = this.uniqueTempArray;
            if (r9[r5] != r9[r5 - 1]) {
                r2++;
            }
        }
        stats.mean = f3;
        stats.std = (float) Math.sqrt(max);
        stats.uniqueCount = r2;
        return stats;
    }

    /* JADX INFO: Access modifiers changed from: private */
    /* renamed from: triggerHapticThenActiveChallenge, reason: merged with bridge method [inline-methods] */
    public void m2140lambda$finishDetection$1$netblocklegendsuiGyroscopeEC() {
        stopDetection();
        if (this.hasVibrator) {
            try {
                this.vibrator.vibrate(VibrationEffect.createWaveform(new long[]{0, 120, 80, 120, 80, 120}, new int[]{0, 180, 0, 200, 0, 220}, -1));
            } catch (Throwable unused) {
            }
        } else {
            DetectionCallback detectionCallback = this.callback;
            if (detectionCallback != null) {
                detectionCallback.onChallengeRequested("Lütfen cihazınızı hafifçe hareket ettirin");
            }
        }
        this.mainHandler.postDelayed(new Runnable() { // from class: net.blocklegends.ui.GyroscopeEC$$ExternalSyntheticLambda1
            @Override // java.lang.Runnable
            public final void run() {
                GyroscopeEC.this.m2141xcb020a4();
            }
        }, 750L);
    }

    public String getSensorInfo() {
        StringBuilder sb = new StringBuilder("Gyroscope: ");
        sb.append(this.gyroscopeAvailable ? "Available" : "Not Available").append("\nLinear Accel: ");
        sb.append(this.linearAccelAvailable ? "Available" : "Not Available").append("\nAccelerometer: ");
        sb.append(this.accelerometerAvailable ? "Available" : "Not Available").append('\n');
        if (this.gyroscopeSensor != null) {
            sb.append("Gyro vendor: ").append(this.gyroscopeSensor.getVendor()).append(" vers: ").append(this.gyroscopeSensor.getVersion()).append('\n');
        }
        if (this.linearAccelSensor != null) {
            sb.append("LinAcc vendor: ").append(this.linearAccelSensor.getVendor()).append(" vers: ").append(this.linearAccelSensor.getVersion()).append('\n');
        }
        if (this.accelerometerSensor != null) {
            sb.append("Accel vendor: ").append(this.accelerometerSensor.getVendor()).append(" vers: ").append(this.accelerometerSensor.getVersion()).append('\n');
        }
        sb.append(buildFingerprintString());
        return sb.toString();
    }

    public boolean hasGyroscope() {
        return this.gyroscopeAvailable;
    }

    public boolean hasSensors() {
        return this.gyroscopeAvailable || this.linearAccelAvailable || this.accelerometerAvailable;
    }

    public boolean isPermanentlyDisabled() {
        return this.permanentlyDisabled;
    }

    /* JADX INFO: Access modifiers changed from: package-private */
    /* renamed from: lambda$triggerHapticThenActiveChallenge$0$net-blocklegends-ui-GyroscopeEC, reason: not valid java name */
    public /* synthetic */ void m2141xcb020a4() {
        startActiveChallenge(5000L);
    }

    @Override // android.hardware.SensorEventListener
    public void onAccuracyChanged(Sensor sensor, int r2) {
    }

    @Override // android.hardware.SensorEventListener
    public void onSensorChanged(SensorEvent sensorEvent) {
        if (!this.isDetecting || sensorEvent == null || sensorEvent.sensor == null) {
            return;
        }
        long currentTimeMillis = System.currentTimeMillis();
        if (currentTimeMillis - this.detectionStartTime >= this.detectionDurationMs) {
            finishDetection();
            return;
        }
        int type = sensorEvent.sensor.getType();
        if (type == 4) {
            handleGyro(sensorEvent.values);
        } else if (type == 10) {
            handleLinearAccel(sensorEvent.values);
        } else if (type == 1) {
            handleAccelWithGravitySeparation(sensorEvent.values);
        }
        if (currentTimeMillis - this.lastCheckTime >= CHECK_INTERVAL_MS) {
            this.lastCheckTime = currentTimeMillis;
            performPeriodicCheck();
        }
    }

    public void startActiveChallenge(long j) {
        if (this.permanentlyDisabled) {
            return;
        }
        if (j <= 0) {
            j = 5000;
        }
        internalStart(true, j, this.callback);
    }

    public void startDetection(DetectionCallback detectionCallback) {
        if (this.permanentlyDisabled) {
            return;
        }
        internalStart(false, PASSIVE_DURATION_MS, detectionCallback);
    }

    public void stopDetection() {
        if (this.isDetecting) {
            this.isDetecting = false;
            try {
                this.sensorManager.unregisterListener(this);
            } catch (Throwable unused) {
            }
        }
    }
}
