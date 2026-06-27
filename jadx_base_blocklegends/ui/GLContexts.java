package net.blocklegends.ui;

import android.util.Log;
import android.view.Surface;
import android.view.SurfaceHolder;
import net.blocklegends.MainActivity;
import net.blocklegends.natives.GNatives;

/* loaded from: classes4.dex */
public final class GLContexts {
    private static final String TAG = "GLContexts";

    public native void setSurface(Surface surface);

    public boolean setupContext(SurfaceHolder surfaceHolder) {
        Log.i(TAG, "Setting up EGL context via native");
        MainActivity activity = MainActivity.getActivity();
        GNatives.setenv("REFRESH_RATE", String.valueOf(Math.round(activity.getWindowManager().getDefaultDisplay().getRefreshRate())));
        MainActivity.changeScreenXY(String.valueOf(activity.surfaceView.getWidth()), String.valueOf(activity.surfaceView.getHeight()));
        return setupSurfaces(surfaceHolder);
    }

    public boolean setupSurfaces(SurfaceHolder surfaceHolder) {
        Log.i(TAG, "Setting up EGL surfaces via native");
        setSurface(surfaceHolder.getSurface());
        return true;
    }
}
