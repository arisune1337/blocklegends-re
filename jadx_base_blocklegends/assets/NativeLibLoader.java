package net.blocklegends.assets;

import android.content.Context;
import android.util.Log;
import java.util.concurrent.atomic.AtomicBoolean;
import net.blocklegends.assets.NativeLibLoader;

/* loaded from: classes4.dex */
public final class NativeLibLoader {
    private static final String TAG = "NativeLibLoader";
    private static final AtomicBoolean loaded = new AtomicBoolean(false);
    private static String loadError = null;

    /* loaded from: classes4.dex */
    public interface LoadCallback {
        void onError(String str);

        void onProgress(long j, long j2);

        void onSuccess();
    }

    public static String getLoadError() {
        return loadError;
    }

    public static boolean isLoaded() {
        return loaded.get();
    }

    /* JADX INFO: Access modifiers changed from: package-private */
    public static /* synthetic */ void lambda$loadAsync$0(LoadCallback loadCallback) {
        try {
            System.loadLibrary("blocklegends");
            loaded.set(true);
            Log.i(TAG, "Native library loaded successfully");
            loadCallback.onSuccess();
        } catch (UnsatisfiedLinkError e) {
            Log.e(TAG, "Failed to load native library", e);
            loadError = e.getMessage();
            loadCallback.onError("Native library not available: " + e.getMessage());
        }
    }

    public static void loadAsync(Context context, final LoadCallback loadCallback) {
        if (loaded.get()) {
            loadCallback.onSuccess();
        } else {
            new Thread(new Runnable() { // from class: net.blocklegends.assets.NativeLibLoader$$ExternalSyntheticLambda0
                @Override // java.lang.Runnable
                public final void run() {
                    NativeLibLoader.lambda$loadAsync$0(NativeLibLoader.LoadCallback.this);
                }
            }).start();
        }
    }

    public static boolean loadSync(Context context, long j) {
        AtomicBoolean atomicBoolean = loaded;
        if (atomicBoolean.get()) {
            return true;
        }
        try {
            System.loadLibrary("blocklegends");
            atomicBoolean.set(true);
            Log.i(TAG, "Native library loaded successfully (sync)");
            return true;
        } catch (UnsatisfiedLinkError e) {
            Log.e(TAG, "Failed to load native library", e);
            loadError = e.getMessage();
            return false;
        }
    }
}
