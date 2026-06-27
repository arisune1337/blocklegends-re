package net.blocklegends.ui;

import android.content.Context;
import android.hardware.Sensor;
import android.hardware.SensorEvent;
import android.hardware.SensorEventListener;
import android.hardware.SensorManager;
import android.hardware.display.DisplayManager;
import android.os.Handler;
import android.os.HandlerThread;
import android.util.Log;
import android.view.Display;
import android.view.OrientationEventListener;
import com.google.firebase.messaging.Constants;
import net.blocklegends.MainActivity;
import net.blocklegends.natives.GNatives;
import net.blocklegends.utils.Util;

/* loaded from: classes4.dex */
public class GyroController implements SensorEventListener, DisplayManager.DisplayListener {
    public static final GyroController INSTANCE = new GyroController();
    public static volatile int cachedDisplayRotation = -9999;
    private static boolean checked;
    private static DisplayManager displayManager;
    private static Sensor gyroSensor;
    private static OrientationEventListener orientationEventListener;
    private static Handler sensorHandler;
    private static SensorManager sensorManager;
    private static HandlerThread sensorThread;

    private static Handler getOrCreateSensorHandler() {
        Handler handler;
        Handler handler2 = sensorHandler;
        if (handler2 != null) {
            return handler2;
        }
        synchronized (GyroController.class) {
            if (sensorHandler == null) {
                HandlerThread handlerThread = new HandlerThread("GyroSensor", -4);
                handlerThread.start();
                sensorThread = handlerThread;
                sensorHandler = new Handler(handlerThread.getLooper());
            }
            handler = sensorHandler;
        }
        return handler;
    }

    /* JADX INFO: Access modifiers changed from: package-private */
    public static /* synthetic */ void lambda$onDisplayChanged$0(int r2) {
        try {
            GNatives.onDisplayChanged(r2);
        } catch (Exception e) {
            Log.e("GYRO", "Error calling native onDisplayChanged: " + e.getMessage());
        }
    }

    public static void nativeDisableGyroController() {
        SensorManager sensorManager2 = sensorManager;
        if (sensorManager2 != null) {
            sensorManager2.unregisterListener(INSTANCE);
        }
        DisplayManager displayManager2 = displayManager;
        if (displayManager2 != null) {
            displayManager2.unregisterDisplayListener(INSTANCE);
        }
        OrientationEventListener orientationEventListener2 = orientationEventListener;
        if (orientationEventListener2 != null) {
            orientationEventListener2.disable();
            Log.i("GYRO", "OrientationEventListener disabled");
        }
        cachedDisplayRotation = -9999;
        shutdownSensorThread();
    }

    public static boolean nativeInitGyroController() {
        try {
            Context applicationContext = MainActivity.getActivity().getApplicationContext();
            if (applicationContext == null) {
                return false;
            }
            SensorManager sensorManager2 = (SensorManager) applicationContext.getSystemService("sensor");
            sensorManager = sensorManager2;
            if (sensorManager2 == null) {
                Log.e("GYRO", "SensorManager is null!");
                return false;
            }
            Sensor defaultSensor = sensorManager2.getDefaultSensor(4);
            gyroSensor = defaultSensor;
            if (defaultSensor == null) {
                Log.e("GYRO", "TYPE_GYROSCOPE not available!");
                return false;
            }
            Sensor defaultSensor2 = sensorManager.getDefaultSensor(15);
            if (defaultSensor2 == null) {
                defaultSensor2 = sensorManager.getDefaultSensor(11);
            }
            DisplayManager displayManager2 = (DisplayManager) applicationContext.getSystemService(Constants.ScionAnalytics.MessageType.DISPLAY_NOTIFICATION);
            if (displayManager2 != null) {
                DisplayManager displayManager3 = displayManager;
                if (displayManager3 != null) {
                    try {
                        displayManager3.unregisterDisplayListener(INSTANCE);
                        Log.d("GYRO", "Existing DisplayListener unregistered before re-init");
                    } catch (Exception e) {
                        Log.e("GYRO", "Error unregistering existing DisplayListener: " + e.getMessage());
                    }
                }
                displayManager = displayManager2;
                displayManager2.registerDisplayListener(INSTANCE, null);
                Display display = displayManager.getDisplay(0);
                if (display != null) {
                    cachedDisplayRotation = display.getRotation();
                    Log.i("GYRO", "Initial display rotation: " + cachedDisplayRotation);
                }
            }
            OrientationEventListener orientationEventListener2 = orientationEventListener;
            if (orientationEventListener2 != null) {
                try {
                    orientationEventListener2.disable();
                    Log.d("GYRO", "Existing OrientationEventListener disabled before re-init");
                } catch (Exception e2) {
                    Log.e("GYRO", "Error disabling existing OrientationEventListener: " + e2.getMessage());
                }
            }
            OrientationEventListener orientationEventListener3 = new OrientationEventListener(applicationContext, 3) { // from class: net.blocklegends.ui.GyroController.1
                @Override // android.view.OrientationEventListener
                public void onOrientationChanged(int r3) {
                    int rotation;
                    if (GyroController.displayManager != null) {
                        try {
                            Display display2 = GyroController.displayManager.getDisplay(0);
                            if (display2 == null || GyroController.cachedDisplayRotation == (rotation = display2.getRotation())) {
                                return;
                            }
                            GyroController.cachedDisplayRotation = rotation;
                            Log.d("GYRO", "Rotation updated via OrientationEventListener: " + rotation);
                        } catch (Exception e3) {
                            Log.e("GYRO", "Error updating rotation in OrientationEventListener: " + e3.getMessage());
                        }
                    }
                }
            };
            orientationEventListener = orientationEventListener3;
            if (orientationEventListener3.canDetectOrientation()) {
                orientationEventListener.enable();
                Log.i("GYRO", "OrientationEventListener enabled");
            } else {
                Log.w("GYRO", "OrientationEventListener cannot detect orientation");
            }
            SensorManager sensorManager3 = sensorManager;
            if (sensorManager3 != null) {
                try {
                    sensorManager3.unregisterListener(INSTANCE);
                    Log.d("GYRO", "Existing sensor listeners unregistered before re-init");
                } catch (Exception e3) {
                    Log.e("GYRO", "Error unregistering existing sensor listeners: " + e3.getMessage());
                }
            }
            Handler orCreateSensorHandler = getOrCreateSensorHandler();
            SensorManager sensorManager4 = sensorManager;
            GyroController gyroController = INSTANCE;
            if (!sensorManager4.registerListener(gyroController, gyroSensor, 5000, orCreateSensorHandler)) {
                Log.e("GYRO", "Failed to register gyroscope listener!");
                return false;
            }
            if (defaultSensor2 != null && !sensorManager.registerListener(gyroController, defaultSensor2, 1, orCreateSensorHandler)) {
                Log.w("GYRO", "Failed to register rotation vector sensor (non-critical)");
            }
            Log.i("GYRO", "Gyroscope initialized successfully");
            return true;
        } catch (Exception e4) {
            Log.e("GYRO", "Exception during gyro initialization: " + e4.getMessage());
            return false;
        }
    }

    public static boolean nativeIsSupportedSensorService() {
        if (!checked) {
            checked = true;
            Context applicationContext = MainActivity.getActivity().getApplicationContext();
            if (sensorManager == null) {
                sensorManager = (SensorManager) applicationContext.getSystemService("sensor");
            }
            if (gyroSensor == null) {
                gyroSensor = sensorManager.getDefaultSensor(4);
            }
        }
        return (sensorManager == null || gyroSensor == null) ? false : true;
    }

    public static int nativeUpdateCoordinateSystem() {
        return cachedDisplayRotation;
    }

    private static void shutdownSensorThread() {
        synchronized (GyroController.class) {
            HandlerThread handlerThread = sensorThread;
            if (handlerThread != null) {
                try {
                    handlerThread.quitSafely();
                } catch (Throwable unused) {
                }
                sensorThread = null;
                sensorHandler = null;
            }
        }
    }

    @Override // android.hardware.SensorEventListener
    public void onAccuracyChanged(Sensor sensor, int r2) {
    }

    @Override // android.hardware.display.DisplayManager.DisplayListener
    public void onDisplayAdded(int r1) {
    }

    @Override // android.hardware.display.DisplayManager.DisplayListener
    public void onDisplayChanged(final int r2) {
        Display display;
        DisplayManager displayManager2 = displayManager;
        if (displayManager2 != null && (display = displayManager2.getDisplay(r2)) != null) {
            cachedDisplayRotation = display.getRotation();
            Log.i("GYRO", "Display rotation updated: " + cachedDisplayRotation);
        }
        Util.run(new Runnable() { // from class: net.blocklegends.ui.GyroController$$ExternalSyntheticLambda0
            @Override // java.lang.Runnable
            public final void run() {
                GyroController.lambda$onDisplayChanged$0(r2);
            }
        });
    }

    @Override // android.hardware.display.DisplayManager.DisplayListener
    public void onDisplayRemoved(int r1) {
    }

    @Override // android.hardware.SensorEventListener
    public void onSensorChanged(SensorEvent sensorEvent) {
        if (sensorEvent == null || sensorEvent.values == null) {
            return;
        }
        try {
            GNatives.onSensorChanged(sensorEvent.sensor.getType(), (float[]) sensorEvent.values.clone());
        } catch (Throwable th) {
            Log.e("GYRO", "Error in onSensorChanged: " + th.getMessage());
        }
    }
}
