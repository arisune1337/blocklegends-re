package net.blocklegends.ui;

import android.content.Context;
import android.graphics.Rect;
import android.util.Log;
import android.view.Surface;
import android.view.SurfaceHolder;
import android.view.SurfaceView;
import java.io.File;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicBoolean;
import net.blocklegends.MainActivity;
import net.blocklegends.natives.GNatives;
import net.blocklegends.utils.Util;

/* loaded from: classes4.dex */
public class GameSurfaceView extends SurfaceView implements SurfaceHolder.Callback {
    private static final String TAG = "GameSurfaceView";
    private static final AtomicBoolean nextStepCalled = new AtomicBoolean(false);
    private volatile boolean isInitialized;
    private volatile boolean waitingForLandscapeForeground;
    private volatile boolean waitingForLandscapeStart;

    public GameSurfaceView(Context context) {
        super(context);
        this.isInitialized = false;
        this.waitingForLandscapeStart = false;
        this.waitingForLandscapeForeground = false;
        if (context == null) {
            throw new IllegalArgumentException("Context cannot be null");
        }
        try {
            setFocusable(true);
            setFocusableInTouchMode(true);
            requestFocus();
            SurfaceHolder holder = getHolder();
            if (holder != null) {
                holder.addCallback(this);
            } else {
                Log.e(TAG, "SurfaceHolder is null in constructor");
            }
        } catch (Exception e) {
            Log.e(TAG, "Error in constructor: " + e.getMessage(), e);
            throw new RuntimeException("Failed to initialize GameSurfaceView", e);
        }
    }

    private boolean blockPortraitSurface(String str, int r4, int r5) {
        if (r4 <= 0 || r5 <= 0) {
            if (getResources().getConfiguration().orientation != 1) {
                return false;
            }
        } else if (r5 <= r4) {
            return false;
        }
        Log.w(TAG, "Blocking portrait gameplay surface from " + str + ": " + r4 + "x" + r5);
        MainActivity activity = MainActivity.getActivity();
        if (activity != null) {
            activity.forceLandscapeOrientation(str);
        }
        return true;
    }

    private boolean foregroundSurface(SurfaceHolder surfaceHolder, String str) {
        try {
            if (blockPortraitSurface(str, getSurfaceWidth(surfaceHolder), getSurfaceHeight(surfaceHolder))) {
                this.waitingForLandscapeForeground = true;
                return false;
            }
            final Surface surface = surfaceHolder.getSurface();
            if (surface == null) {
                Log.e(TAG, "Surface is null in re-initialization");
                return false;
            }
            if (!surface.isValid()) {
                Log.e(TAG, "Surface is not valid in re-initialization");
                return false;
            }
            this.waitingForLandscapeForeground = false;
            Util.run(new Runnable() { // from class: net.blocklegends.ui.GameSurfaceView$$ExternalSyntheticLambda6
                @Override // java.lang.Runnable
                public final void run() {
                    GNatives.onAppForeground(surface);
                }
            });
            return true;
        } catch (Exception e) {
            Log.e(TAG, "Error in surface re-initialization: " + e.getMessage(), e);
            return false;
        }
    }

    private int getSurfaceHeight(SurfaceHolder surfaceHolder) {
        Rect surfaceFrame = surfaceHolder.getSurfaceFrame();
        return (surfaceFrame == null || surfaceFrame.height() <= 0) ? getHeight() : surfaceFrame.height();
    }

    private int getSurfaceWidth(SurfaceHolder surfaceHolder) {
        Rect surfaceFrame = surfaceHolder.getSurfaceFrame();
        return (surfaceFrame == null || surfaceFrame.width() <= 0) ? getWidth() : surfaceFrame.width();
    }

    /* JADX INFO: Access modifiers changed from: package-private */
    public static /* synthetic */ void lambda$surfaceDestroyed$6() {
        try {
            GNatives.onSurfaceDestroyed();
        } catch (Exception e) {
            Log.e(TAG, "Error in onSurfaceDestroyed: " + e.getMessage(), e);
        }
        GNatives.onAppBackground();
    }

    private boolean startInitialSurface(SurfaceHolder surfaceHolder, String str) {
        MainActivity activity = MainActivity.getActivity();
        if (activity == null) {
            Log.e(TAG, "MainActivity is null, cannot initialize surface");
            return false;
        }
        if (blockPortraitSurface(str, getSurfaceWidth(surfaceHolder), getSurfaceHeight(surfaceHolder))) {
            this.waitingForLandscapeStart = true;
            return false;
        }
        this.waitingForLandscapeStart = false;
        try {
            if (activity.contexts == null) {
                activity.contexts = new GLContexts();
                if (activity.contexts == null) {
                    Log.e(TAG, "Failed to create GLContexts");
                    return false;
                }
            }
            if (activity.contexts.setupContext(surfaceHolder)) {
                this.isInitialized = true;
                startNativeGame(activity);
                return true;
            }
            Log.e(TAG, "[CRITICAL] Contexts failed!");
            this.isInitialized = false;
            return false;
        } catch (Exception e) {
            Log.e(TAG, "Error setting up contexts: " + e.getMessage(), e);
            this.isInitialized = false;
            return false;
        }
    }

    private void startNativeGame(MainActivity mainActivity) {
        try {
            AtomicBoolean atomicBoolean = nextStepCalled;
            if (!atomicBoolean.compareAndSet(false, true)) {
                Log.w(TAG, "nextStep already called, skipping duplicate");
                return;
            }
            File filesDir = mainActivity.getFilesDir();
            if (filesDir == null) {
                Log.e(TAG, "Files directory is null");
                atomicBoolean.set(false);
                return;
            }
            final String absolutePath = filesDir.getAbsolutePath();
            if (absolutePath != null && !absolutePath.isEmpty()) {
                File file = new File(filesDir, "game_data");
                File file2 = new File(file, ".extraction_complete");
                if (file.exists() && file2.exists()) {
                    Util.run(new Runnable() { // from class: net.blocklegends.ui.GameSurfaceView$$ExternalSyntheticLambda5
                        @Override // java.lang.Runnable
                        public final void run() {
                            GNatives.nextStep(absolutePath);
                        }
                    });
                    return;
                }
                Log.e(TAG, "Game data incomplete — gameData exists: " + file.exists() + ", marker exists: " + file2.exists());
                atomicBoolean.set(false);
                return;
            }
            Log.e(TAG, "Folder path is null or empty");
            atomicBoolean.set(false);
        } catch (Exception e) {
            Log.e(TAG, "Error calling nextStep: " + e.getMessage(), e);
            nextStepCalled.set(false);
        }
    }

    public void onDestroy() {
        try {
            Log.d(TAG, "onDestroy called");
            this.isInitialized = false;
            this.waitingForLandscapeStart = false;
            this.waitingForLandscapeForeground = false;
            Util.run(new Runnable() { // from class: net.blocklegends.ui.GameSurfaceView$$ExternalSyntheticLambda2
                @Override // java.lang.Runnable
                public final void run() {
                    GNatives.onSurfaceDestroy();
                }
            });
        } catch (Exception e) {
            Log.e(TAG, "Error in onDestroy: " + e.getMessage(), e);
        }
    }

    public void onPause() {
        try {
            Log.d(TAG, "onPause called");
            Util.run(new Runnable() { // from class: net.blocklegends.ui.GameSurfaceView$$ExternalSyntheticLambda4
                @Override // java.lang.Runnable
                public final void run() {
                    GNatives.onSurfacePause();
                }
            });
        } catch (Exception e) {
            Log.e(TAG, "Error in onPause: " + e.getMessage(), e);
        }
    }

    public void onResume() {
        try {
            Log.d(TAG, "onResume called");
            Util.run(new Runnable() { // from class: net.blocklegends.ui.GameSurfaceView$$ExternalSyntheticLambda3
                @Override // java.lang.Runnable
                public final void run() {
                    GNatives.onSurfaceResume();
                }
            });
        } catch (Exception e) {
            Log.e(TAG, "Error in onResume: " + e.getMessage(), e);
        }
    }

    @Override // android.view.SurfaceHolder.Callback
    public void surfaceChanged(SurfaceHolder surfaceHolder, int r8, final int r9, final int r10) {
        try {
            Log.d(TAG, "surfaceChanged called: " + r9 + "x" + r10 + ", format: " + r8);
            if (surfaceHolder == null) {
                Log.e(TAG, "SurfaceHolder is null in surfaceChanged");
                return;
            }
            if (r9 > 0 && r10 > 0) {
                if (blockPortraitSurface("surfaceChanged", r9, r10)) {
                    return;
                }
                if (!this.isInitialized) {
                    if (this.waitingForLandscapeStart) {
                        Log.i(TAG, "Starting delayed landscape surface after " + r9 + "x" + r10);
                    }
                    if (!startInitialSurface(surfaceHolder, "surfaceChangedStart")) {
                        return;
                    }
                } else if (this.waitingForLandscapeForeground && !foregroundSurface(surfaceHolder, "surfaceChangedForeground")) {
                    return;
                }
                Util.run(new Runnable() { // from class: net.blocklegends.ui.GameSurfaceView$$ExternalSyntheticLambda0
                    @Override // java.lang.Runnable
                    public final void run() {
                        GNatives.onSurfaceChanged(r9, r10);
                    }
                });
                if (MainActivity.getActivity() == null) {
                    Log.w(TAG, "MainActivity is null in surfaceChanged");
                    return;
                }
                return;
            }
            Log.e(TAG, "Invalid surface dimensions: " + r9 + "x" + r10);
        } catch (Exception e) {
            Log.e(TAG, "Error in surfaceChanged: " + e.getMessage(), e);
        }
    }

    @Override // android.view.SurfaceHolder.Callback
    public void surfaceCreated(SurfaceHolder surfaceHolder) {
        try {
            Log.d(TAG, "surfaceCreated called, isInitialized: " + this.isInitialized);
            if (surfaceHolder == null) {
                Log.e(TAG, "SurfaceHolder is null in surfaceCreated");
            } else if (!this.isInitialized) {
                startInitialSurface(surfaceHolder, "surfaceCreated");
            } else {
                Log.d(TAG, "Re-initializing surface after lifecycle event");
                foregroundSurface(surfaceHolder, "surfaceRecreated");
            }
        } catch (Exception e) {
            Log.e(TAG, "Error in surfaceCreated: " + e.getMessage(), e);
            this.isInitialized = false;
        }
    }

    /* JADX WARN: Unsupported multi-entry loop pattern (BACK_EDGE: B:21:0x0054 -> B:9:0x0080). Please report as a decompilation issue!!! */
    @Override // android.view.SurfaceHolder.Callback
    public void surfaceDestroyed(SurfaceHolder surfaceHolder) {
        String str = "Error in surface cleanup: ";
        try {
            Log.d(TAG, "surfaceDestroyed called");
            if (surfaceHolder == null) {
                Log.w(TAG, "SurfaceHolder is null in surfaceDestroyed");
            }
            try {
                Future<?> run = Util.run(new Runnable() { // from class: net.blocklegends.ui.GameSurfaceView$$ExternalSyntheticLambda1
                    @Override // java.lang.Runnable
                    public final void run() {
                        GameSurfaceView.lambda$surfaceDestroyed$6();
                    }
                });
                if (run != null) {
                    try {
                        run.get(2L, TimeUnit.SECONDS);
                        Log.d(TAG, "Surface cleanup completed successfully");
                    } catch (InterruptedException unused) {
                        Log.e(TAG, "Surface cleanup interrupted");
                        Thread.currentThread().interrupt();
                    } catch (ExecutionException e) {
                        Log.e(TAG, "Surface cleanup error: " + e.getMessage());
                    } catch (TimeoutException unused2) {
                        Log.e(TAG, "Surface cleanup timeout (2s) - proceeding anyway");
                    }
                }
            } catch (Exception e2) {
                StringBuilder sb = new StringBuilder(str);
                str = e2.getMessage();
                Log.e(TAG, sb.append(str).toString(), e2);
            }
        } catch (Exception e3) {
            Log.e(TAG, "Error in surfaceDestroyed: " + e3.getMessage(), e3);
        }
    }
}
