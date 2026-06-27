package net.blocklegends;

import android.app.ActivityManager;
import android.app.AlertDialog;
import android.content.Context;
import android.content.DialogInterface;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.content.res.Configuration;
import android.hardware.input.InputManager;
import android.net.ConnectivityManager;
import android.net.Network;
import android.net.NetworkCapabilities;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.os.Process;
import android.provider.Settings;
import android.telephony.TelephonyManager;
import android.text.Editable;
import android.text.InputFilter;
import android.text.Spanned;
import android.text.TextWatcher;
import android.util.AttributeSet;
import android.util.Log;
import android.view.InputDevice;
import android.view.KeyCharacterMap;
import android.view.KeyEvent;
import android.view.MotionEvent;
import android.view.View;
import android.view.inputmethod.EditorInfo;
import android.view.inputmethod.InputConnection;
import android.view.inputmethod.InputConnectionWrapper;
import android.view.inputmethod.InputMethodManager;
import android.widget.EditText;
import android.widget.FrameLayout;
import android.widget.TextView;
import androidx.activity.OnBackPressedCallback;
import androidx.appcompat.app.AppCompatActivity;
import androidx.appcompat.widget.AppCompatEditText;
import androidx.core.app.ActivityCompat;
import androidx.core.content.ContextCompat;
import androidx.core.os.EnvironmentCompat;
import androidx.core.view.InputDeviceCompat;
import androidx.core.view.ViewCompat;
import androidx.core.view.WindowCompat;
import androidx.credentials.provider.CredentialEntry;
import com.google.common.net.HttpHeaders;
import com.google.firebase.analytics.FirebaseAnalytics;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import net.blocklegends.MainActivity;
import net.blocklegends.ad.AdManager;
import net.blocklegends.assets.GameAssetsExtractor;
import net.blocklegends.assets.NativeLibLoader;
import net.blocklegends.game.AppleSign;
import net.blocklegends.game.GameSound;
import net.blocklegends.game.GoogleSign;
import net.blocklegends.natives.GNatives;
import net.blocklegends.os.android.VibrationManager;
import net.blocklegends.ui.GLContexts;
import net.blocklegends.ui.GameSurfaceView;
import net.blocklegends.ui.GyroscopeEC;
import net.blocklegends.ui.Keyboard;
import net.blocklegends.ui.RidevIntroView;
import net.blocklegends.ui.WindowManager;
import net.blocklegends.utils.AntiAutoClickerScheduler;
import net.blocklegends.utils.AntiAutoClickerUtil;
import net.blocklegends.utils.Util;

/* loaded from: classes4.dex */
public class MainActivity extends AppCompatActivity {
    public static final String EXTERNAL_UI_ADMOB_REWARDED = "admob_rewarded";
    public static final String EXTERNAL_UI_APPLE_SIGN = "apple_sign";
    private static final String EXTERNAL_UI_AUTH_LEGACY = "auth";
    public static final String EXTERNAL_UI_BILLING_FLOW = "billing_flow";
    public static final String EXTERNAL_UI_GOOGLE_SIGN = "google_sign";
    public static String LAST_SCREEN_X = "";
    public static String LAST_SCREEN_Y = "";
    private static final long NETWORK_CHANGE_CONFIRM_DELAY_MS = 1500;
    private static final int NOTIFICATION_PERMISSION_REQUEST_CODE = 1001;
    private static MainActivity activity;
    private static volatile boolean externalUiInProgress;
    private static KeyCharacterMap keyCharacterMap;
    public AdManager adManager;
    private ScheduledExecutorService analyticsHeartbeat;
    private AntiAutoClickerScheduler anti;
    public GLContexts contexts;
    public GameEditText editText;
    private volatile FirebaseAnalytics fa;
    private FlexibleUpdateHelper flexibleUpdateHelper;
    public FrameLayout frameLayout;
    public GyroscopeEC gyroscopeDetector;
    private volatile boolean initialNetworkCallbackReceived;
    private InputManager inputManager;
    private ScheduledExecutorService keyboardCheckScheduler;
    private volatile Network lastKnownNetwork;
    private volatile int lastKnownNetworkTransportMask;
    private ConnectivityManager.NetworkCallback networkCallback;
    private int networkChangeGeneration;
    public RidevIntroView ridevIntroView;
    public GameSurfaceView surfaceView;
    public WindowManager windowManager;
    private static final boolean KEEP_GNS_GAME_CONNECTION_ON_NETWORK_CHANGE = Boolean.parseBoolean(System.getProperty("cr.gns.keepGameOnNetworkChange", CredentialEntry.TRUE_STRING));
    private static final Object EXTERNAL_UI_LOCK = new Object();
    private static final Map<String, Integer> externalUiSources = new HashMap();
    private static boolean nativeLibrariesLoaded = false;
    private static String libraryLoadError = null;
    private static final String[] LOW_END_GPU_NAMES = {"powervr ge8100", "powervr ge8300", "powervr ge8320", "powervr sgx", "mali-400", "mali-450", "mali-t720", "mali-t820", "mali-t830", "mali-t860", "mali-g31", "mali-g51", "mali-g52", "mali-g57", "adreno (tm) 3", "adreno 3", "adreno (tm) 4", "adreno 4", "adreno 505", "adreno 506", "adreno 508", "adreno 509", "adreno 510", "adreno 512", "adreno 610", "vivante gc", "videocore"};
    private static AlertDialog networkDialog = null;
    private final Handler networkChangeHandler = new Handler(Looper.getMainLooper());
    private final Object networkChangeLock = new Object();
    private final List<Runnable> runnableList = new ArrayList(32);
    private volatile boolean blockHardwareKeyboard = false;
    private Boolean cachedIsTurkishOrAzeri = null;
    private AlertDialog physicalKeyboardDialog = null;

    /* JADX INFO: Access modifiers changed from: package-private */
    /* renamed from: net.blocklegends.MainActivity$1, reason: invalid class name */
    /* loaded from: classes4.dex */
    public class AnonymousClass1 implements NativeLibLoader.LoadCallback {

        /* JADX INFO: Access modifiers changed from: package-private */
        /* renamed from: net.blocklegends.MainActivity$1$1, reason: invalid class name and collision with other inner class name */
        /* loaded from: classes4.dex */
        public class C00421 implements GameAssetsExtractor.ExtractionCallback {
            C00421() {
            }

            /* JADX INFO: Access modifiers changed from: package-private */
            /* renamed from: lambda$onComplete$0$net-blocklegends-MainActivity$1$1, reason: not valid java name */
            public /* synthetic */ void m2097lambda$onComplete$0$netblocklegendsMainActivity$1$1() {
                MainActivity.this.initGame();
            }

            /* JADX INFO: Access modifiers changed from: package-private */
            /* renamed from: lambda$onError$1$net-blocklegends-MainActivity$1$1, reason: not valid java name */
            public /* synthetic */ void m2098lambda$onError$1$netblocklegendsMainActivity$1$1(GameAssetsExtractor.ErrorCode errorCode, String str) {
                if (errorCode == GameAssetsExtractor.ErrorCode.INSUFFICIENT_STORAGE) {
                    MainActivity.this.showStorageErrorDialog(str);
                } else {
                    MainActivity.libraryLoadError = "Asset extraction failed: " + str;
                    MainActivity.this.showIncompatibleDeviceDialog();
                }
            }

            @Override // net.blocklegends.assets.GameAssetsExtractor.ExtractionCallback
            public void onComplete(String str) {
                MainActivity.this.runOnUiThread(new Runnable() { // from class: net.blocklegends.MainActivity$1$1$$ExternalSyntheticLambda0
                    @Override // java.lang.Runnable
                    public final void run() {
                        MainActivity.AnonymousClass1.C00421.this.m2097lambda$onComplete$0$netblocklegendsMainActivity$1$1();
                    }
                });
            }

            @Override // net.blocklegends.assets.GameAssetsExtractor.ExtractionCallback
            public void onError(final GameAssetsExtractor.ErrorCode errorCode, final String str) {
                MainActivity.this.runOnUiThread(new Runnable() { // from class: net.blocklegends.MainActivity$1$1$$ExternalSyntheticLambda1
                    @Override // java.lang.Runnable
                    public final void run() {
                        MainActivity.AnonymousClass1.C00421.this.m2098lambda$onError$1$netblocklegendsMainActivity$1$1(errorCode, str);
                    }
                });
            }

            @Override // net.blocklegends.assets.GameAssetsExtractor.ExtractionCallback
            public void onProgress(int r1, int r2, String str) {
            }
        }

        AnonymousClass1() {
        }

        /* JADX INFO: Access modifiers changed from: package-private */
        /* renamed from: lambda$onError$0$net-blocklegends-MainActivity$1, reason: not valid java name */
        public /* synthetic */ void m2096lambda$onError$0$netblocklegendsMainActivity$1() {
            MainActivity.this.showIncompatibleDeviceDialog();
        }

        @Override // net.blocklegends.assets.NativeLibLoader.LoadCallback
        public void onError(String str) {
            MainActivity.libraryLoadError = str;
            MainActivity.this.runOnUiThread(new Runnable() { // from class: net.blocklegends.MainActivity$1$$ExternalSyntheticLambda0
                @Override // java.lang.Runnable
                public final void run() {
                    MainActivity.AnonymousClass1.this.m2096lambda$onError$0$netblocklegendsMainActivity$1();
                }
            });
        }

        @Override // net.blocklegends.assets.NativeLibLoader.LoadCallback
        public void onProgress(long j, long j2) {
        }

        @Override // net.blocklegends.assets.NativeLibLoader.LoadCallback
        public void onSuccess() {
            MainActivity.nativeLibrariesLoaded = true;
            GameAssetsExtractor.extractIfNeeded(MainActivity.this, new C00421());
        }
    }

    /* JADX INFO: Access modifiers changed from: package-private */
    /* renamed from: net.blocklegends.MainActivity$2, reason: invalid class name */
    /* loaded from: classes4.dex */
    public class AnonymousClass2 implements GameAssetsExtractor.ExtractionCallback {
        AnonymousClass2() {
        }

        /* JADX INFO: Access modifiers changed from: package-private */
        /* renamed from: lambda$onComplete$0$net-blocklegends-MainActivity$2, reason: not valid java name */
        public /* synthetic */ void m2099lambda$onComplete$0$netblocklegendsMainActivity$2() {
            MainActivity.this.initGame();
        }

        /* JADX INFO: Access modifiers changed from: package-private */
        /* renamed from: lambda$onError$1$net-blocklegends-MainActivity$2, reason: not valid java name */
        public /* synthetic */ void m2100lambda$onError$1$netblocklegendsMainActivity$2(GameAssetsExtractor.ErrorCode errorCode, String str) {
            if (errorCode == GameAssetsExtractor.ErrorCode.INSUFFICIENT_STORAGE) {
                MainActivity.this.showStorageErrorDialog(str);
            } else {
                MainActivity.libraryLoadError = "Asset extraction failed: " + str;
                MainActivity.this.showIncompatibleDeviceDialog();
            }
        }

        @Override // net.blocklegends.assets.GameAssetsExtractor.ExtractionCallback
        public void onComplete(String str) {
            MainActivity.this.runOnUiThread(new Runnable() { // from class: net.blocklegends.MainActivity$2$$ExternalSyntheticLambda0
                @Override // java.lang.Runnable
                public final void run() {
                    MainActivity.AnonymousClass2.this.m2099lambda$onComplete$0$netblocklegendsMainActivity$2();
                }
            });
        }

        @Override // net.blocklegends.assets.GameAssetsExtractor.ExtractionCallback
        public void onError(final GameAssetsExtractor.ErrorCode errorCode, final String str) {
            MainActivity.this.runOnUiThread(new Runnable() { // from class: net.blocklegends.MainActivity$2$$ExternalSyntheticLambda1
                @Override // java.lang.Runnable
                public final void run() {
                    MainActivity.AnonymousClass2.this.m2100lambda$onError$1$netblocklegendsMainActivity$2(errorCode, str);
                }
            });
        }

        @Override // net.blocklegends.assets.GameAssetsExtractor.ExtractionCallback
        public void onProgress(int r1, int r2, String str) {
        }
    }

    /* JADX INFO: Access modifiers changed from: package-private */
    /* renamed from: net.blocklegends.MainActivity$5, reason: invalid class name */
    /* loaded from: classes4.dex */
    public class AnonymousClass5 implements TextWatcher {
        AnonymousClass5() {
        }

        /* JADX INFO: Access modifiers changed from: package-private */
        public static /* synthetic */ void lambda$onTextChanged$0(int r5, String str, int[] r7) {
            for (int r1 = 0; r1 < r5; r1++) {
                GNatives.onKeyDown(14, null, '.');
            }
            if (str != null) {
                for (int r0 = 0; r0 < str.length(); r0++) {
                    char charAt = str.charAt(r0);
                    GNatives.onKeyDown(r7[r0], String.valueOf(charAt), charAt);
                }
            }
        }

        @Override // android.text.TextWatcher
        public void afterTextChanged(Editable editable) {
            if (MainActivity.this.editText.ignoreTextChanges || editable == null) {
                return;
            }
            try {
                if (editable.length() > 100) {
                    String substring = editable.toString().substring(editable.length() - 100);
                    MainActivity.this.editText.ignoreTextChanges = true;
                    MainActivity.this.editText.setText(substring);
                    MainActivity.this.editText.setSelection(substring.length());
                    MainActivity.this.editText.ignoreTextChanges = false;
                }
            } catch (Throwable th) {
                MainActivity.this.editText.ignoreTextChanges = false;
                Log.e("MainActivity", "Error in afterTextChanged: " + th.getMessage());
            }
        }

        @Override // android.text.TextWatcher
        public void beforeTextChanged(CharSequence charSequence, int r2, int r3, int r4) {
        }

        @Override // android.text.TextWatcher
        public void onTextChanged(CharSequence charSequence, int r3, int r4, int r5) {
            final int deleteCountForTextWatcher;
            final String str;
            final int[] r32;
            if (MainActivity.this.editText.ignoreTextChanges) {
                return;
            }
            if ((MainActivity.this.isTestEnvironment() || !MainActivity.this.blockHardwareKeyboard) && charSequence != null) {
                if (r4 > 0) {
                    try {
                        deleteCountForTextWatcher = MainActivity.this.editText.getDeleteCountForTextWatcher(r4);
                    } catch (Throwable th) {
                        Log.e("MainActivity", "Error in onTextChanged: " + th.getMessage());
                        return;
                    }
                } else {
                    deleteCountForTextWatcher = 0;
                }
                if (r5 > 0) {
                    String obj = charSequence.toString();
                    str = obj.substring(r3, Math.min(r5 + r3, obj.length()));
                    r32 = new int[str.length()];
                    for (int r0 = 0; r0 < str.length(); r0++) {
                        r32[r0] = MainActivity.getKeyCodeFromChar(str.charAt(r0));
                    }
                } else {
                    str = null;
                    r32 = null;
                }
                if (deleteCountForTextWatcher <= 0 && str == null) {
                    return;
                }
                Util.run(new Runnable() { // from class: net.blocklegends.MainActivity$5$$ExternalSyntheticLambda0
                    @Override // java.lang.Runnable
                    public final void run() {
                        MainActivity.AnonymousClass5.lambda$onTextChanged$0(deleteCountForTextWatcher, str, r32);
                    }
                });
            }
        }
    }

    /* loaded from: classes4.dex */
    public static class GameEditText extends AppCompatEditText {
        private OnDeleteListener deleteListener;
        volatile boolean ignoreTextChanges;
        private volatile int pendingDeleteCount;

        /* loaded from: classes4.dex */
        private class GameInputConnectionWrapper extends InputConnectionWrapper {
            public GameInputConnectionWrapper(InputConnection inputConnection, boolean z) {
                super(inputConnection, z);
            }

            @Override // android.view.inputmethod.InputConnectionWrapper, android.view.inputmethod.InputConnection
            public boolean deleteSurroundingText(int r4, int r5) {
                int r0 = r4 + r5;
                if (r0 > 0) {
                    GameEditText.this.markDeleteFromInputConnection(r0);
                    if (GameEditText.this.deleteListener != null) {
                        for (int r1 = 0; r1 < r0; r1++) {
                            GameEditText.this.deleteListener.onDeleteKey();
                        }
                    }
                }
                return super.deleteSurroundingText(r4, r5);
            }

            @Override // android.view.inputmethod.InputConnectionWrapper, android.view.inputmethod.InputConnection
            public boolean sendKeyEvent(KeyEvent keyEvent) {
                if (keyEvent.getAction() == 0 && keyEvent.getKeyCode() == 67) {
                    GameEditText.this.markDeleteFromInputConnection(1);
                    if (GameEditText.this.deleteListener != null) {
                        GameEditText.this.deleteListener.onDeleteKey();
                    }
                }
                return super.sendKeyEvent(keyEvent);
            }
        }

        /* loaded from: classes4.dex */
        public interface OnDeleteListener {
            void onDeleteKey();
        }

        public GameEditText(Context context) {
            super(context);
            this.pendingDeleteCount = 0;
            this.ignoreTextChanges = false;
        }

        public GameEditText(Context context, AttributeSet attributeSet) {
            super(context, attributeSet);
            this.pendingDeleteCount = 0;
            this.ignoreTextChanges = false;
        }

        public GameEditText(Context context, AttributeSet attributeSet, int r3) {
            super(context, attributeSet, r3);
            this.pendingDeleteCount = 0;
            this.ignoreTextChanges = false;
        }

        public void clearTextWithoutNotify() {
            this.ignoreTextChanges = true;
            setText("");
            this.pendingDeleteCount = 0;
            this.ignoreTextChanges = false;
        }

        public int getDeleteCountForTextWatcher(int r4) {
            int r0 = this.pendingDeleteCount;
            int r1 = this.pendingDeleteCount;
            if (r0 >= r4) {
                this.pendingDeleteCount = r1 - r4;
                return 0;
            }
            int r42 = r4 - r1;
            this.pendingDeleteCount = 0;
            return r42;
        }

        public void markDeleteFromInputConnection(int r2) {
            this.pendingDeleteCount += r2;
        }

        @Override // androidx.appcompat.widget.AppCompatEditText, android.widget.TextView, android.view.View
        public InputConnection onCreateInputConnection(EditorInfo editorInfo) {
            InputConnection onCreateInputConnection = super.onCreateInputConnection(editorInfo);
            if (onCreateInputConnection == null) {
                return null;
            }
            return new GameInputConnectionWrapper(onCreateInputConnection, true);
        }

        public void setOnDeleteListener(OnDeleteListener onDeleteListener) {
            this.deleteListener = onDeleteListener;
        }

        public boolean shouldSendDeleteFromTextWatcher(int r4) {
            int r0 = this.pendingDeleteCount;
            int r1 = this.pendingDeleteCount;
            if (r0 >= r4) {
                this.pendingDeleteCount = r1 - r4;
                return false;
            }
            int r42 = r4 - r1;
            this.pendingDeleteCount = 0;
            return r42 > 0;
        }
    }

    private boolean anyRealKeyboardConnected() {
        for (int r0 : InputDevice.getDeviceIds()) {
            if (isPhysicalKeyboardDevice(InputDevice.getDevice(r0))) {
                return true;
            }
        }
        return false;
    }

    public static void beginExternalUi(String str) {
        String normalizeExternalUiSource = normalizeExternalUiSource(str);
        synchronized (EXTERNAL_UI_LOCK) {
            Map<String, Integer> map = externalUiSources;
            map.put(normalizeExternalUiSource, Integer.valueOf((map.containsKey(normalizeExternalUiSource) ? map.get(normalizeExternalUiSource).intValue() : 0) + 1));
            externalUiInProgress = true;
        }
    }

    /* JADX INFO: Access modifiers changed from: private */
    public void cancelPendingNetworkChange() {
        synchronized (this.networkChangeLock) {
            this.networkChangeGeneration++;
        }
    }

    public static void changeScreenXY(String str, String str2) {
        if (LAST_SCREEN_X.equals(str) && LAST_SCREEN_Y.equals(str2)) {
            return;
        }
        GNatives.setenv("SCREEN_X", str);
        GNatives.setenv("SCREEN_Y", str2);
        LAST_SCREEN_X = str;
        LAST_SCREEN_Y = str2;
    }

    /* JADX INFO: Access modifiers changed from: private */
    public static void checkAndForcePerformanceModeForLowEndGpu() {
        try {
            if (GNatives.isGpuInfoCached()) {
                String cachedGpuRenderer = GNatives.getCachedGpuRenderer();
                if (cachedGpuRenderer != null) {
                    Log.i("GPUCheck", "GPU (native cache): " + cachedGpuRenderer);
                    String lowerCase = cachedGpuRenderer.toLowerCase(Locale.US);
                    for (String str : LOW_END_GPU_NAMES) {
                        if (lowerCase.contains(str)) {
                            GNatives.setenv("FORCE_PERFORMANCE_MODE", "1");
                            Log.w("GPUCheck", "Low-end GPU detected (cached). Performance mode forced.");
                            return;
                        }
                    }
                    return;
                }
                return;
            }
        } catch (UnsatisfiedLinkError unused) {
            Log.w("GPUCheck", "Native not ready, using EGL fallback for GPU check");
        } catch (Throwable th) {
            Log.e("GPUCheck", "Error checking cached GPU info", th);
        }
        checkGpuViaEglFallback();
    }

    /* JADX WARN: Code restructure failed: missing block: B:68:0x00f1, code lost:
    
        net.blocklegends.natives.GNatives.setenv("FORCE_PERFORMANCE_MODE", "1");
        android.util.Log.w("GPUCheck", "Low-end GPU detected (fallback). Performance mode forced.");
     */
    /*
        Code decompiled incorrectly, please refer to instructions dump.
        To view partially-correct add '--show-bad-code' argument
    */
    private static void checkGpuViaEglFallback() {
        /*
            Method dump skipped, instructions count: 407
            To view this dump add '--comments-level debug' option
        */
        throw new UnsupportedOperationException("Method not decompiled: net.blocklegends.MainActivity.checkGpuViaEglFallback():void");
    }

    private static boolean checkNetworkSilently(Context context) {
        Network activeNetwork;
        NetworkCapabilities networkCapabilities;
        if (context == null) {
            return false;
        }
        try {
            ConnectivityManager connectivityManager = (ConnectivityManager) context.getSystemService("connectivity");
            if (connectivityManager != null && (activeNetwork = connectivityManager.getActiveNetwork()) != null && (networkCapabilities = connectivityManager.getNetworkCapabilities(activeNetwork)) != null && networkCapabilities.hasCapability(12)) {
                if (networkCapabilities.hasCapability(16)) {
                    return true;
                }
            }
            return false;
        } catch (Throwable th) {
            Log.e("MainActivity", "Error in checkNetworkSilently: " + th.getMessage());
        }
        return false;
    }

    private static void dismissNetworkDialog() {
        MainActivity mainActivity;
        try {
            AlertDialog alertDialog = networkDialog;
            if (alertDialog == null || !alertDialog.isShowing() || (mainActivity = activity) == null) {
                return;
            }
            mainActivity.runOnUiThread(new Runnable() { // from class: net.blocklegends.MainActivity$$ExternalSyntheticLambda42
                @Override // java.lang.Runnable
                public final void run() {
                    MainActivity.lambda$dismissNetworkDialog$31();
                }
            });
        } catch (Throwable th) {
            Log.e("MainActivity", "Error in dismissNetworkDialog: " + th.getMessage());
        }
    }

    private void dismissPhysicalKeyboardDialog() {
        try {
            AlertDialog alertDialog = this.physicalKeyboardDialog;
            if (alertDialog == null || !alertDialog.isShowing()) {
                return;
            }
            runOnUiThread(new Runnable() { // from class: net.blocklegends.MainActivity$$ExternalSyntheticLambda2
                @Override // java.lang.Runnable
                public final void run() {
                    MainActivity.this.m2074x119ae13f();
                }
            });
        } catch (Throwable th) {
            Log.e("MainActivity", "Error in dismissPhysicalKeyboardDialog: " + th.getMessage());
        }
    }

    public static void endExternalUi(String str) {
        boolean z;
        MainActivity mainActivity;
        String normalizeExternalUiSource = normalizeExternalUiSource(str);
        synchronized (EXTERNAL_UI_LOCK) {
            Map<String, Integer> map = externalUiSources;
            Integer num = map.get(normalizeExternalUiSource);
            if (num != null && num.intValue() > 1) {
                map.put(normalizeExternalUiSource, Integer.valueOf(num.intValue() - 1));
                externalUiInProgress = !map.isEmpty();
                z = externalUiInProgress;
            }
            map.remove(normalizeExternalUiSource);
            externalUiInProgress = !map.isEmpty();
            z = externalUiInProgress;
        }
        if (z || (mainActivity = activity) == null) {
            return;
        }
        mainActivity.forceLandscapeOrientation("externalUi:" + normalizeExternalUiSource);
    }

    public static MainActivity getActivity() {
        return activity;
    }

    public static int getKeyCodeFromChar(char c) {
        if (keyCharacterMap == null) {
            try {
                keyCharacterMap = KeyCharacterMap.load(-1);
            } catch (Throwable th) {
                Log.e("MainActivity", "Failed to load KeyCharacterMap", th);
            }
        }
        if (keyCharacterMap == null) {
            return 0;
        }
        KeyCharacterMap.KeyData keyData = new KeyCharacterMap.KeyData();
        if (keyCharacterMap.getKeyData(c, keyData)) {
            return keyData.meta[0];
        }
        return 0;
    }

    private String getLanguageKeyWithFallback(String str, String str2) {
        try {
            String languageKey = GNatives.getLanguageKey(str);
            if (languageKey != null) {
                if (!languageKey.isEmpty()) {
                    return languageKey;
                }
            }
            return str2;
        } catch (Throwable unused) {
            Log.w("MainActivity", "getLanguageKey failed for key: " + str + ", using fallback");
            return str2;
        }
    }

    private void handleAppleSignInIntent(Intent intent) {
        try {
            if (AppleSign.handleCallbackFromIntent(intent)) {
                Log.i("MainActivity", "Apple Sign-In callback received via deep link");
            }
        } catch (Throwable th) {
            Log.e("MainActivity", "Error handling Apple Sign-In intent: " + th.getMessage());
        }
    }

    private static void initANGLE() throws UnsatisfiedLinkError {
        try {
            System.loadLibrary("GLESv2_angle");
            System.loadLibrary("EGL_angle");
        } catch (Throwable th) {
            Log.e("MainActivity", "Failed to load ANGLE libraries", th);
            throw new UnsatisfiedLinkError("ANGLE libraries could not be loaded: " + th.getMessage());
        }
    }

    /* JADX INFO: Access modifiers changed from: private */
    /* JADX WARN: Removed duplicated region for block: B:24:0x00a1 A[Catch: all -> 0x00ad, TRY_ENTER, TryCatch #6 {all -> 0x00ad, blocks: (B:24:0x00a1, B:56:0x00af), top: B:22:0x009f }] */
    /* JADX WARN: Removed duplicated region for block: B:30:0x00c9 A[Catch: all -> 0x00fe, TryCatch #7 {all -> 0x00fe, blocks: (B:28:0x00bf, B:30:0x00c9, B:32:0x00dc), top: B:27:0x00bf }] */
    /* JADX WARN: Removed duplicated region for block: B:38:0x0137  */
    /* JADX WARN: Removed duplicated region for block: B:40:0x0140 A[EXC_TOP_SPLITTER, SYNTHETIC] */
    /* JADX WARN: Removed duplicated region for block: B:48:0x0108 A[EXC_TOP_SPLITTER, SYNTHETIC] */
    /* JADX WARN: Removed duplicated region for block: B:56:0x00af A[Catch: all -> 0x00ad, TRY_LEAVE, TryCatch #6 {all -> 0x00ad, blocks: (B:24:0x00a1, B:56:0x00af), top: B:22:0x009f }] */
    /*
        Code decompiled incorrectly, please refer to instructions dump.
        To view partially-correct add '--show-bad-code' argument
    */
    public void initGame() {
        /*
            Method dump skipped, instructions count: 387
            To view this dump add '--comments-level debug' option
        */
        throw new UnsupportedOperationException("Method not decompiled: net.blocklegends.MainActivity.initGame():void");
    }

    /* JADX INFO: Access modifiers changed from: private */
    public void initNonCritical() {
        InputManager inputManager = (InputManager) getSystemService("input");
        this.inputManager = inputManager;
        if (inputManager != null) {
            inputManager.registerInputDeviceListener(new InputManager.InputDeviceListener() { // from class: net.blocklegends.MainActivity.3
                @Override // android.hardware.input.InputManager.InputDeviceListener
                public void onInputDeviceAdded(int r1) {
                    MainActivity.this.updateKbBlockFlag();
                }

                @Override // android.hardware.input.InputManager.InputDeviceListener
                public void onInputDeviceChanged(int r1) {
                    MainActivity.this.updateKbBlockFlag();
                }

                @Override // android.hardware.input.InputManager.InputDeviceListener
                public void onInputDeviceRemoved(int r1) {
                    MainActivity.this.updateKbBlockFlag();
                }
            }, null);
        }
        updateKbBlockFlag();
        try {
            this.windowManager = new WindowManager(this);
        } catch (Throwable th) {
            Log.e("MainActivity", "Failed to create WindowManager: " + th.getMessage());
        }
        try {
            if (GNatives.isGpuInfoCached()) {
                checkAndForcePerformanceModeForLowEndGpu();
            } else {
                new Thread(new Runnable() { // from class: net.blocklegends.MainActivity$$ExternalSyntheticLambda29
                    @Override // java.lang.Runnable
                    public final void run() {
                        MainActivity.checkAndForcePerformanceModeForLowEndGpu();
                    }
                }, "GPU-Check-Thread").start();
            }
        } catch (UnsatisfiedLinkError unused) {
            new Thread(new Runnable() { // from class: net.blocklegends.MainActivity$$ExternalSyntheticLambda29
                @Override // java.lang.Runnable
                public final void run() {
                    MainActivity.checkAndForcePerformanceModeForLowEndGpu();
                }
            }, "GPU-Check-Thread").start();
        }
        new Handler(Looper.getMainLooper()).postDelayed(new Runnable() { // from class: net.blocklegends.MainActivity$$ExternalSyntheticLambda30
            @Override // java.lang.Runnable
            public final void run() {
                MainActivity.this.m2076lambda$initNonCritical$6$netblocklegendsMainActivity();
            }
        }, 6000L);
        new Thread(new Runnable() { // from class: net.blocklegends.MainActivity$$ExternalSyntheticLambda31
            @Override // java.lang.Runnable
            public final void run() {
                MainActivity.this.m2077lambda$initNonCritical$7$netblocklegendsMainActivity();
            }
        }, "Security-Check-Thread").start();
        try {
            VibrationManager.init(this);
        } catch (Throwable unused2) {
        }
        try {
            EditText editText = (EditText) findViewById(R.id.editText);
            if (editText != null) {
                this.frameLayout.removeView(editText);
            }
            GameEditText gameEditText = new GameEditText(this);
            this.editText = gameEditText;
            gameEditText.setId(R.id.editText);
            boolean z = true;
            this.editText.setFilters(new InputFilter[]{new InputFilter() { // from class: net.blocklegends.MainActivity$$ExternalSyntheticLambda32
                @Override // android.text.InputFilter
                public final CharSequence filter(CharSequence charSequence, int r2, int r3, Spanned spanned, int r5, int r6) {
                    return MainActivity.lambda$initNonCritical$8(charSequence, r2, r3, spanned, r5, r6);
                }
            }});
            this.editText.setImeOptions(268435456);
            this.editText.setVisibility(0);
            this.editText.setLayoutParams(new FrameLayout.LayoutParams(1, 1));
            this.editText.setFocusable(true);
            this.editText.setFocusableInTouchMode(true);
            this.editText.setInputType(49153);
            this.frameLayout.addView(this.editText);
            try {
                this.editText.setOnEditorActionListener(new TextView.OnEditorActionListener() { // from class: net.blocklegends.MainActivity.4
                    @Override // android.widget.TextView.OnEditorActionListener
                    public boolean onEditorAction(TextView textView, int r2, KeyEvent keyEvent) {
                        if (r2 == 6) {
                            return true;
                        }
                        if (keyEvent == null) {
                            return false;
                        }
                        try {
                            if (keyEvent.getKeyCode() == 66) {
                                return keyEvent.getAction() == 0;
                            }
                            return false;
                        } catch (Throwable th2) {
                            Log.e("MainActivity", "Error in onEditorAction: " + th2.getMessage());
                            return false;
                        }
                    }
                });
            } catch (Throwable th2) {
                Log.e("MainActivity", "Failed to set editor action listener: " + th2.getMessage());
            }
            try {
                this.editText.setOnDeleteListener(new GameEditText.OnDeleteListener() { // from class: net.blocklegends.MainActivity$$ExternalSyntheticLambda34
                    @Override // net.blocklegends.MainActivity.GameEditText.OnDeleteListener
                    public final void onDeleteKey() {
                        Util.run(new Runnable() { // from class: net.blocklegends.MainActivity$$ExternalSyntheticLambda1
                            @Override // java.lang.Runnable
                            public final void run() {
                                GNatives.onKeyDown(14, null, '.');
                            }
                        });
                    }
                });
                this.editText.addTextChangedListener(new AnonymousClass5());
            } catch (Throwable th3) {
                Log.e("MainActivity", "Failed to set text watcher: " + th3.getMessage());
            }
            new Thread(new Runnable() { // from class: net.blocklegends.MainActivity$$ExternalSyntheticLambda35
                @Override // java.lang.Runnable
                public final void run() {
                    MainActivity.lambda$initNonCritical$11();
                }
            }, "Init-Services-Thread").start();
            try {
                Keyboard.init();
            } catch (Throwable th4) {
                Log.e("MainActivity", "Failed to initialize Keyboard: " + th4.getMessage());
            }
            try {
                getOnBackPressedDispatcher().addCallback(this, new OnBackPressedCallback(z) { // from class: net.blocklegends.MainActivity.6
                    @Override // androidx.activity.OnBackPressedCallback
                    public void handleOnBackPressed() {
                        try {
                            InputMethodManager inputMethodManager = (InputMethodManager) MainActivity.this.getSystemService("input_method");
                            View currentFocus = MainActivity.this.getCurrentFocus();
                            if (inputMethodManager == null || currentFocus == null || !inputMethodManager.isActive(currentFocus)) {
                                return;
                            }
                            inputMethodManager.hideSoftInputFromWindow(currentFocus.getWindowToken(), 0);
                            currentFocus.clearFocus();
                        } catch (Throwable th5) {
                            Log.e("MainActivity", "Error in back pressed handler: " + th5.getMessage());
                        }
                    }
                });
            } catch (Throwable th5) {
                Log.e("MainActivity", "Failed to set back pressed callback: " + th5.getMessage());
            }
            registerNetworkCallback();
        } catch (Throwable th6) {
            Log.e("MainActivity", "Failed to initialize EditText: " + th6.getMessage());
            finish();
        }
    }

    private boolean isAutomationLikely() {
        try {
            if (CredentialEntry.TRUE_STRING.equals(Settings.System.getString(getContentResolver(), "firebase.test.lab"))) {
                Log.i("MainActivity", "Firebase Test Lab detected");
                return true;
            }
        } catch (Throwable th) {
            Log.e("MainActivity", "Error checking Firebase Test Lab: " + th.getMessage());
        }
        try {
            if (ActivityManager.isUserAMonkey()) {
                Log.i("MainActivity", "Monkey runner detected");
                return true;
            }
        } catch (Throwable unused) {
        }
        try {
            if (!ActivityManager.isRunningInTestHarness()) {
                return false;
            }
            Log.i("MainActivity", "Test harness detected");
            return true;
        } catch (Throwable unused2) {
            return false;
        }
    }

    private boolean isChromeOS() {
        try {
            PackageManager packageManager = getPackageManager();
            if (packageManager == null) {
                return false;
            }
            if (packageManager.hasSystemFeature("org.chromium.arc")) {
                return true;
            }
            return packageManager.hasSystemFeature("org.chromium.arc.device_management");
        } catch (Throwable th) {
            Log.e("MainActivity", "Error checking ChromeOS: " + th.getMessage());
            return false;
        }
    }

    public static boolean isExternalAuthInProgress() {
        return isExternalUiInProgress();
    }

    public static boolean isExternalUiInProgress() {
        return externalUiInProgress;
    }

    private boolean isHardwareKbActive() {
        boolean anyRealKeyboardConnected = anyRealKeyboardConnected();
        if (anyRealKeyboardConnected) {
            return anyRealKeyboardConnected && (getResources().getConfiguration().hardKeyboardHidden == 1);
        }
        return false;
    }

    public static boolean isNetworkAvailable(Context context) {
        MainActivity mainActivity;
        NetworkCapabilities networkCapabilities;
        try {
            if (context == null) {
                Log.e("MainActivity", "Context is null in isNetworkAvailable");
                return false;
            }
            ConnectivityManager connectivityManager = (ConnectivityManager) context.getSystemService("connectivity");
            if (connectivityManager == null) {
                Log.e("MainActivity", "ConnectivityManager is null");
                return false;
            }
            Network activeNetwork = connectivityManager.getActiveNetwork();
            boolean z = activeNetwork != null && (networkCapabilities = connectivityManager.getNetworkCapabilities(activeNetwork)) != null && networkCapabilities.hasCapability(12) && networkCapabilities.hasCapability(16);
            if (z || (mainActivity = activity) == null || mainActivity.isFinishing()) {
                if (z) {
                    dismissNetworkDialog();
                }
                return z;
            }
            if (activity.isAutomationLikely() || activity.isChromeOS()) {
                Log.i("MainActivity", "Skipping network dialog in test/automation environment");
                return z;
            }
            showNetworkErrorDialog();
            return z;
        } catch (Throwable th) {
            Log.e("MainActivity", "Error checking network availability: " + th.getMessage());
            return false;
        }
    }

    private boolean isPhysicalKeyboardDevice(InputDevice inputDevice) {
        boolean z;
        if (inputDevice == null || inputDevice.isVirtual() || (inputDevice.getSources() & 257) == 0) {
            return false;
        }
        boolean z2 = inputDevice.getKeyboardType() == 2;
        if (inputDevice.getVendorId() == 0) {
            if (inputDevice.getProductId() == 0) {
                z = false;
                return z2 && z;
            }
        }
        z = true;
        if (z2) {
            return false;
        }
    }

    /* JADX INFO: Access modifiers changed from: private */
    public boolean isTestEnvironment() {
        try {
            if (isAutomationLikely()) {
                return true;
            }
            if (isChromeOS()) {
                Log.i("MainActivity", "ChromeOS detected, skipping blocking dialogs");
                return true;
            }
            if (!Build.FINGERPRINT.startsWith("generic") && !Build.FINGERPRINT.startsWith(EnvironmentCompat.MEDIA_UNKNOWN) && !Build.MODEL.contains("google_sdk") && !Build.MODEL.contains("Emulator") && !Build.MODEL.contains("Android SDK built for x86") && !Build.MANUFACTURER.contains("Genymotion") && !Build.BRAND.startsWith("generic") && !Build.DEVICE.startsWith("generic") && !"google_sdk".equals(Build.PRODUCT) && !Build.HARDWARE.contains("goldfish") && !Build.HARDWARE.contains("ranchu")) {
                return false;
            }
            Log.i("MainActivity", "Emulator detected, skipping blocking dialogs");
            return true;
        } catch (Throwable th) {
            Log.e("MainActivity", "Error checking test environment: " + th.getMessage());
            return false;
        }
    }

    private boolean isTurkishOrAzeriUser() {
        try {
            TelephonyManager telephonyManager = (TelephonyManager) getSystemService("phone");
            if (telephonyManager != null) {
                String simCountryIso = telephonyManager.getSimCountryIso();
                if (simCountryIso != null && !simCountryIso.isEmpty()) {
                    String upperCase = simCountryIso.toUpperCase(Locale.US);
                    if (!"TR".equals(upperCase) && !"AZ".equals(upperCase)) {
                        Log.i("MainActivity", "Non-TR/AZ SIM detected: " + upperCase);
                        return false;
                    }
                    Log.i("MainActivity", "TR/AZ detected via SIM country: " + upperCase);
                    return true;
                }
                String networkCountryIso = telephonyManager.getNetworkCountryIso();
                if (networkCountryIso != null && !networkCountryIso.isEmpty()) {
                    String upperCase2 = networkCountryIso.toUpperCase(Locale.US);
                    if (!"TR".equals(upperCase2) && !"AZ".equals(upperCase2)) {
                        Log.i("MainActivity", "Non-TR/AZ network detected: " + upperCase2);
                        return false;
                    }
                    Log.i("MainActivity", "TR/AZ detected via network country: " + upperCase2);
                    return true;
                }
            }
            String country = Locale.getDefault().getCountry();
            if (country != null && !country.isEmpty()) {
                country = country.toUpperCase(Locale.US);
                if ("TR".equals(country) || "AZ".equals(country)) {
                    Log.i("MainActivity", "TR/AZ detected via locale country: " + country);
                    return true;
                }
            }
            String language = Locale.getDefault().getLanguage();
            if (!"tr".equals(language) && !"az".equals(language)) {
                Log.i("MainActivity", "No TR/AZ indicators found (country: " + country + ", lang: " + language + ")");
                return false;
            }
            Log.i("MainActivity", "TR/AZ detected via language: " + language);
            return true;
        } catch (Throwable th) {
            Log.e("MainActivity", "Error detecting user country: " + th.getMessage());
            return false;
        }
    }

    /* JADX INFO: Access modifiers changed from: package-private */
    public static /* synthetic */ void lambda$dismissNetworkDialog$31() {
        try {
            networkDialog.dismiss();
            networkDialog = null;
            Log.i("MainActivity", "Network dialog dismissed - connection restored");
        } catch (Throwable th) {
            Log.e("MainActivity", "Error dismissing network dialog: " + th.getMessage());
        }
    }

    /* JADX INFO: Access modifiers changed from: package-private */
    public static /* synthetic */ void lambda$initNonCritical$11() {
        try {
            GameSound.init();
        } catch (Throwable th) {
            Log.e("MainActivity", "Failed to initialize GameSound: " + th.getMessage());
        }
    }

    /* JADX INFO: Access modifiers changed from: package-private */
    public static /* synthetic */ CharSequence lambda$initNonCritical$8(CharSequence charSequence, int r1, int r2, Spanned spanned, int r4, int r5) {
        if (charSequence == null) {
            return null;
        }
        while (r1 < r2) {
            if (Character.isSurrogate(charSequence.charAt(r1))) {
                return "";
            }
            r1++;
        }
        return null;
    }

    /* JADX INFO: Access modifiers changed from: package-private */
    public static /* synthetic */ void lambda$onDestroy$16() {
        try {
            Thread.sleep(300L);
        } catch (InterruptedException unused) {
        }
        Process.killProcess(Process.myPid());
    }

    /* JADX INFO: Access modifiers changed from: package-private */
    public static /* synthetic */ void lambda$restartApplication$15(MainActivity mainActivity) {
        try {
            Intent launchIntentForPackage = mainActivity.getBaseContext().getPackageManager().getLaunchIntentForPackage(mainActivity.getBaseContext().getPackageName());
            if (launchIntentForPackage == null) {
                Log.e("MainActivity", "Launch intent is null, cannot restart");
                return;
            }
            launchIntentForPackage.addFlags(268468224);
            mainActivity.startActivity(launchIntentForPackage);
            mainActivity.finishAffinity();
        } catch (Throwable th) {
            Log.e("MainActivity", "Error in restart runOnUiThread: " + th.getMessage());
        }
    }

    /* JADX INFO: Access modifiers changed from: package-private */
    public static /* synthetic */ void lambda$showNetworkErrorDialog$27() {
        try {
            Thread.sleep(1000L);
            if (checkNetworkSilently(activity)) {
                Log.i("MainActivity", "Network reconnected on retry");
            } else {
                Log.w("MainActivity", "Network still unavailable, showing dialog again");
                showNetworkErrorDialog();
            }
        } catch (Throwable th) {
            Log.e("MainActivity", "Error retrying network: " + th.getMessage());
            showNetworkErrorDialog();
        }
    }

    /* JADX INFO: Access modifiers changed from: package-private */
    public static /* synthetic */ void lambda$showNetworkErrorDialog$28(DialogInterface dialogInterface, int r2) {
        dialogInterface.dismiss();
        networkDialog = null;
        new Thread(new Runnable() { // from class: net.blocklegends.MainActivity$$ExternalSyntheticLambda39
            @Override // java.lang.Runnable
            public final void run() {
                MainActivity.lambda$showNetworkErrorDialog$27();
            }
        }, "Network-Retry-Thread").start();
    }

    /* JADX INFO: Access modifiers changed from: package-private */
    public static /* synthetic */ void lambda$showNetworkErrorDialog$29(DialogInterface dialogInterface, int r1) {
        dialogInterface.dismiss();
        networkDialog = null;
        activity.finishAffinity();
    }

    /* JADX INFO: Access modifiers changed from: package-private */
    public static /* synthetic */ void lambda$showNetworkErrorDialog$30() {
        try {
            boolean equals = "tr".equals(Locale.getDefault().getLanguage());
            String str = equals ? "Bağlantı Hatası" : "Connection Error";
            String str2 = equals ? "İnternet bağlantısı gereklidir. Lütfen bağlantınızı kontrol edin ve tekrar deneyin." : "Internet connection is required. Please check your connection and try again.";
            String str3 = equals ? "Yeniden Dene" : "Retry";
            String str4 = equals ? "Çıkış" : "Exit";
            AlertDialog.Builder builder = new AlertDialog.Builder(activity);
            builder.setCancelable(true);
            builder.setTitle(str);
            builder.setMessage(str2);
            builder.setIcon(android.R.drawable.ic_dialog_alert);
            builder.setPositiveButton(str3, new DialogInterface.OnClickListener() { // from class: net.blocklegends.MainActivity$$ExternalSyntheticLambda3
                @Override // android.content.DialogInterface.OnClickListener
                public final void onClick(DialogInterface dialogInterface, int r2) {
                    MainActivity.lambda$showNetworkErrorDialog$28(dialogInterface, r2);
                }
            });
            builder.setNegativeButton(str4, new DialogInterface.OnClickListener() { // from class: net.blocklegends.MainActivity$$ExternalSyntheticLambda4
                @Override // android.content.DialogInterface.OnClickListener
                public final void onClick(DialogInterface dialogInterface, int r2) {
                    MainActivity.lambda$showNetworkErrorDialog$29(dialogInterface, r2);
                }
            });
            AlertDialog create = builder.create();
            networkDialog = create;
            create.show();
            Log.w("MainActivity", "Network error dialog shown with retry option");
        } catch (Throwable th) {
            Log.e("MainActivity", "Error showing network dialog: " + th.getMessage());
        }
    }

    /* JADX INFO: Access modifiers changed from: package-private */
    public static /* synthetic */ void lambda$showNotificationPermissionRationale$41(DialogInterface dialogInterface, int r1) {
        dialogInterface.dismiss();
        Log.d("MainActivity", "Kullanici bildirim iznini erteledi");
    }

    private void loadNativeLibraryFromAssetPack() {
        NativeLibLoader.loadAsync(this, new AnonymousClass1());
    }

    private static String normalizeExternalUiSource(String str) {
        return (str == null || str.isEmpty()) ? EnvironmentCompat.MEDIA_UNKNOWN : str;
    }

    private void registerNetworkCallback() {
        try {
            final ConnectivityManager connectivityManager = (ConnectivityManager) getSystemService("connectivity");
            if (connectivityManager == null) {
                return;
            }
            Network activeNetwork = connectivityManager.getActiveNetwork();
            this.lastKnownNetwork = activeNetwork;
            this.lastKnownNetworkTransportMask = transportMask(connectivityManager, activeNetwork);
            this.initialNetworkCallbackReceived = activeNetwork != null;
            ConnectivityManager.NetworkCallback networkCallback = new ConnectivityManager.NetworkCallback() { // from class: net.blocklegends.MainActivity.7
                @Override // android.net.ConnectivityManager.NetworkCallback
                public void onAvailable(Network network) {
                    boolean z = MainActivity.this.initialNetworkCallbackReceived;
                    MainActivity mainActivity = MainActivity.this;
                    if (!z) {
                        mainActivity.initialNetworkCallbackReceived = true;
                        MainActivity.this.lastKnownNetwork = network;
                        MainActivity.this.lastKnownNetworkTransportMask = MainActivity.transportMask(connectivityManager, network);
                        return;
                    }
                    Network network2 = mainActivity.lastKnownNetwork;
                    int r1 = MainActivity.this.lastKnownNetworkTransportMask;
                    int transportMask = MainActivity.transportMask(connectivityManager, network);
                    MainActivity.this.lastKnownNetwork = network;
                    MainActivity.this.lastKnownNetworkTransportMask = transportMask;
                    if (network2 != null && network2.equals(network)) {
                        MainActivity.this.cancelPendingNetworkChange();
                    } else if (r1 == 0 || r1 != transportMask) {
                        MainActivity.this.scheduleNetworkChanged("onAvailable", r1, transportMask);
                    } else {
                        MainActivity.this.cancelPendingNetworkChange();
                        Log.i("MainActivity", "NetworkCallback.onAvailable: aynı transport (" + MainActivity.transportMaskName(transportMask) + "), reconnect iptal");
                    }
                }

                @Override // android.net.ConnectivityManager.NetworkCallback
                public void onLost(Network network) {
                    if (network.equals(MainActivity.this.lastKnownNetwork)) {
                        int r3 = MainActivity.this.lastKnownNetworkTransportMask;
                        MainActivity.this.lastKnownNetwork = null;
                        MainActivity.this.scheduleNetworkChanged("onLost", r3, 0);
                    }
                }
            };
            this.networkCallback = networkCallback;
            connectivityManager.registerDefaultNetworkCallback(networkCallback);
            Log.i("MainActivity", "NetworkCallback kaydedildi");
        } catch (Exception e) {
            Log.e("MainActivity", "registerNetworkCallback hatası: " + e.getMessage());
        }
    }

    public static void restartApplication() {
        try {
            Log.i("MainActivity", "Restarting application...");
            final MainActivity activity2 = getActivity();
            if (activity2 == null) {
                Log.e("MainActivity", "Activity is null, cannot restart");
            } else {
                activity2.runOnUiThread(new Runnable() { // from class: net.blocklegends.MainActivity$$ExternalSyntheticLambda37
                    @Override // java.lang.Runnable
                    public final void run() {
                        MainActivity.lambda$restartApplication$15(MainActivity.this);
                    }
                });
            }
        } catch (Throwable th) {
            Log.e("MainActivity", "Error restarting application: " + th.getMessage());
        }
    }

    /* JADX INFO: Access modifiers changed from: private */
    public void scheduleNetworkChanged(final String str, final int r6, int r7) {
        final int r1;
        synchronized (this.networkChangeLock) {
            r1 = this.networkChangeGeneration + 1;
            this.networkChangeGeneration = r1;
        }
        Log.i("MainActivity", "NetworkCallback." + str + ": ağ değişimi doğrulanacak prev=" + transportMaskName(r6) + " next=" + transportMaskName(r7));
        this.networkChangeHandler.postDelayed(new Runnable() { // from class: net.blocklegends.MainActivity$$ExternalSyntheticLambda8
            @Override // java.lang.Runnable
            public final void run() {
                MainActivity.this.m2084lambda$scheduleNetworkChanged$17$netblocklegendsMainActivity(r1, r6, str);
            }
        }, NETWORK_CHANGE_CONFIRM_DELAY_MS);
    }

    public static void setExternalAuthInProgress(boolean z) {
        if (z) {
            beginExternalUi(EXTERNAL_UI_AUTH_LEGACY);
        } else {
            endExternalUi(EXTERNAL_UI_AUTH_LEGACY);
        }
    }

    /* JADX INFO: Access modifiers changed from: private */
    public void showIncompatibleDeviceDialog() {
        try {
            runOnUiThread(new Runnable() { // from class: net.blocklegends.MainActivity$$ExternalSyntheticLambda44
                @Override // java.lang.Runnable
                public final void run() {
                    MainActivity.this.m2086xbee6605d();
                }
            });
        } catch (Throwable th) {
            Log.e("MainActivity", "Failed to show incompatible device dialog: " + th.getMessage());
            runOnUiThread(new Runnable() { // from class: net.blocklegends.MainActivity$$ExternalSyntheticLambda45
                @Override // java.lang.Runnable
                public final void run() {
                    MainActivity.this.m2087xd3cf289e();
                }
            });
        }
    }

    private static void showNetworkErrorDialog() {
        try {
            AlertDialog alertDialog = networkDialog;
            if (alertDialog == null || !alertDialog.isShowing()) {
                getActivity().runOnUiThread(new Runnable() { // from class: net.blocklegends.MainActivity$$ExternalSyntheticLambda27
                    @Override // java.lang.Runnable
                    public final void run() {
                        MainActivity.lambda$showNetworkErrorDialog$30();
                    }
                });
            }
        } catch (Throwable th) {
            Log.e("MainActivity", "Failed to show network dialog: " + th.getMessage());
        }
    }

    private void showNotificationPermissionRationale() {
        try {
            String languageKeyWithFallback = getLanguageKeyWithFallback("notification.permission.title", "Notification Permission");
            String languageKeyWithFallback2 = getLanguageKeyWithFallback("notification.permission.message", "We need notification permission to send you important updates, events, and announcements about the game.");
            new AlertDialog.Builder(this).setTitle(languageKeyWithFallback).setMessage(languageKeyWithFallback2).setPositiveButton(getLanguageKeyWithFallback("notification.permission.allow", HttpHeaders.ALLOW), new DialogInterface.OnClickListener() { // from class: net.blocklegends.MainActivity$$ExternalSyntheticLambda0
                @Override // android.content.DialogInterface.OnClickListener
                public final void onClick(DialogInterface dialogInterface, int r2) {
                    MainActivity.this.m2088x51aac0c5(dialogInterface, r2);
                }
            }).setNegativeButton(getLanguageKeyWithFallback("notification.permission.later", "Later"), new DialogInterface.OnClickListener() { // from class: net.blocklegends.MainActivity$$ExternalSyntheticLambda11
                @Override // android.content.DialogInterface.OnClickListener
                public final void onClick(DialogInterface dialogInterface, int r2) {
                    MainActivity.lambda$showNotificationPermissionRationale$41(dialogInterface, r2);
                }
            }).setCancelable(true).show();
        } catch (Throwable th) {
            Log.e("MainActivity", "Bildirim izni dialog hatasi: " + th.getMessage());
        }
    }

    private void showPhysicalKeyboardWarning() {
        try {
            AlertDialog alertDialog = this.physicalKeyboardDialog;
            if (alertDialog == null || !alertDialog.isShowing()) {
                runOnUiThread(new Runnable() { // from class: net.blocklegends.MainActivity$$ExternalSyntheticLambda22
                    @Override // java.lang.Runnable
                    public final void run() {
                        MainActivity.this.m2090x6a56862d();
                    }
                });
            }
        } catch (Throwable th) {
            Log.e("MainActivity", "Failed to show physical keyboard warning: " + th.getMessage());
        }
    }

    /* JADX INFO: Access modifiers changed from: private */
    public void showStorageErrorDialog(final String str) {
        if (isFinishing() || isDestroyed()) {
            Log.w("MainActivity", "Activity is finishing/destroyed, skipping storage error dialog");
            return;
        }
        try {
            AlertDialog.Builder builder = new AlertDialog.Builder(this);
            builder.setCancelable(false);
            builder.setTitle(getString(R.string.storage_error_title));
            String string = getString(R.string.storage_error_message);
            if (str != null && !str.isEmpty()) {
                string = string + "\n\n" + getString(R.string.storage_error_detail, new Object[]{str});
            }
            builder.setMessage(string);
            builder.setPositiveButton(getString(R.string.storage_error_retry), new DialogInterface.OnClickListener() { // from class: net.blocklegends.MainActivity$$ExternalSyntheticLambda14
                @Override // android.content.DialogInterface.OnClickListener
                public final void onClick(DialogInterface dialogInterface, int r2) {
                    MainActivity.this.m2091lambda$showStorageErrorDialog$3$netblocklegendsMainActivity(dialogInterface, r2);
                }
            });
            builder.setNeutralButton(getString(R.string.storage_error_settings), new DialogInterface.OnClickListener() { // from class: net.blocklegends.MainActivity$$ExternalSyntheticLambda15
                @Override // android.content.DialogInterface.OnClickListener
                public final void onClick(DialogInterface dialogInterface, int r3) {
                    MainActivity.this.m2092lambda$showStorageErrorDialog$4$netblocklegendsMainActivity(str, dialogInterface, r3);
                }
            });
            builder.setNegativeButton(getString(R.string.storage_error_exit), new DialogInterface.OnClickListener() { // from class: net.blocklegends.MainActivity$$ExternalSyntheticLambda16
                @Override // android.content.DialogInterface.OnClickListener
                public final void onClick(DialogInterface dialogInterface, int r2) {
                    MainActivity.this.m2093lambda$showStorageErrorDialog$5$netblocklegendsMainActivity(dialogInterface, r2);
                }
            });
            builder.setIcon(android.R.drawable.ic_dialog_alert);
            builder.show();
        } catch (Throwable th) {
            Log.e("MainActivity", "Failed to show storage error dialog: " + th.getMessage());
            libraryLoadError = "Insufficient storage: " + str;
            showIncompatibleDeviceDialog();
        }
    }

    private void startAnalyticsHeartbeat() {
        ScheduledExecutorService scheduledExecutorService = this.analyticsHeartbeat;
        if (scheduledExecutorService == null || scheduledExecutorService.isShutdown()) {
            ScheduledExecutorService newSingleThreadScheduledExecutor = Executors.newSingleThreadScheduledExecutor();
            this.analyticsHeartbeat = newSingleThreadScheduledExecutor;
            newSingleThreadScheduledExecutor.scheduleWithFixedDelay(new Runnable() { // from class: net.blocklegends.MainActivity$$ExternalSyntheticLambda33
                @Override // java.lang.Runnable
                public final void run() {
                    MainActivity.this.m2094lambda$startAnalyticsHeartbeat$42$netblocklegendsMainActivity();
                }
            }, 0L, 2L, TimeUnit.MINUTES);
        }
    }

    private void startAssetExtraction() {
        new Thread(new Runnable() { // from class: net.blocklegends.MainActivity$$ExternalSyntheticLambda36
            @Override // java.lang.Runnable
            public final void run() {
                MainActivity.this.m2095lambda$startAssetExtraction$2$netblocklegendsMainActivity();
            }
        }, "AssetExtractor").start();
    }

    private void stopAnalyticsHeartbeat() {
        ScheduledExecutorService scheduledExecutorService = this.analyticsHeartbeat;
        if (scheduledExecutorService != null) {
            scheduledExecutorService.shutdownNow();
            this.analyticsHeartbeat = null;
        }
    }

    /* JADX INFO: Access modifiers changed from: private */
    /* JADX WARN: Multi-variable type inference failed */
    /* JADX WARN: Type inference failed for: r2v11 */
    /* JADX WARN: Type inference failed for: r2v12 */
    /* JADX WARN: Type inference failed for: r2v4, types: [int] */
    /* JADX WARN: Type inference failed for: r2v6 */
    public static int transportMask(ConnectivityManager connectivityManager, Network network) {
        NetworkCapabilities networkCapabilities;
        if (connectivityManager == null || network == null || (networkCapabilities = connectivityManager.getNetworkCapabilities(network)) == null) {
            return 0;
        }
        boolean hasTransport = networkCapabilities.hasTransport(1);
        boolean z = hasTransport;
        if (networkCapabilities.hasTransport(0)) {
            z = (hasTransport ? 1 : 0) | 2;
        }
        ?? r2 = z;
        if (networkCapabilities.hasTransport(3)) {
            r2 = (z ? 1 : 0) | 4;
        }
        return networkCapabilities.hasTransport(4) ? r2 | 8 : r2;
    }

    /* JADX INFO: Access modifiers changed from: private */
    public static String transportMaskName(int r2) {
        if (r2 == 0) {
            return "NONE";
        }
        StringBuilder sb = new StringBuilder();
        if ((r2 & 1) != 0) {
            sb.append("WIFI|");
        }
        if ((r2 & 2) != 0) {
            sb.append("CELLULAR|");
        }
        if ((r2 & 4) != 0) {
            sb.append("ETHERNET|");
        }
        if ((r2 & 8) != 0) {
            sb.append("VPN|");
        }
        if (sb.length() == 0) {
            return "OTHER(" + r2 + ")";
        }
        sb.setLength(sb.length() - 1);
        return sb.toString();
    }

    private void unregisterNetworkCallback() {
        try {
            cancelPendingNetworkChange();
            this.networkChangeHandler.removeCallbacksAndMessages(null);
            if (this.networkCallback != null) {
                ConnectivityManager connectivityManager = (ConnectivityManager) getSystemService("connectivity");
                if (connectivityManager != null) {
                    connectivityManager.unregisterNetworkCallback(this.networkCallback);
                }
                this.networkCallback = null;
            }
        } catch (Exception e) {
            Log.e("MainActivity", "unregisterNetworkCallback hatası: " + e.getMessage());
        }
    }

    /* JADX INFO: Access modifiers changed from: private */
    public void updateKbBlockFlag() {
        this.blockHardwareKeyboard = isHardwareKbActive();
        if (!this.blockHardwareKeyboard || GyroscopeEC.isProbablyEByBuild() || isTestEnvironment()) {
            dismissPhysicalKeyboardDialog();
        } else {
            showPhysicalKeyboardWarning();
        }
    }

    @Override // android.app.Activity, android.view.Window.Callback
    public boolean dispatchGenericMotionEvent(MotionEvent motionEvent) {
        if (isAutomationLikely() || isChromeOS()) {
            return super.dispatchGenericMotionEvent(motionEvent);
        }
        if ((motionEvent.getSource() & 17834010) != 0) {
            return true;
        }
        return super.dispatchGenericMotionEvent(motionEvent);
    }

    @Override // androidx.appcompat.app.AppCompatActivity, androidx.core.app.ComponentActivity, android.app.Activity, android.view.Window.Callback
    public boolean dispatchKeyEvent(KeyEvent keyEvent) {
        InputDevice device = keyEvent.getDevice();
        if (isTestEnvironment() || !isPhysicalKeyboardDevice(device)) {
            return super.dispatchKeyEvent(keyEvent);
        }
        int keyCode = keyEvent.getKeyCode();
        if (keyCode == 24 || keyCode == 25 || keyCode == 164) {
            return super.dispatchKeyEvent(keyEvent);
        }
        return true;
    }

    public void forceLandscapeOrientation(final String str) {
        Runnable runnable = new Runnable() { // from class: net.blocklegends.MainActivity$$ExternalSyntheticLambda43
            @Override // java.lang.Runnable
            public final void run() {
                MainActivity.this.m2075xcfe9888a(str);
            }
        };
        if (Looper.myLooper() == Looper.getMainLooper()) {
            runnable.run();
        } else {
            runOnUiThread(runnable);
        }
    }

    public AdManager getAdManager() {
        return this.adManager;
    }

    public GLContexts getContexts() {
        return this.contexts;
    }

    /* JADX INFO: Access modifiers changed from: package-private */
    /* renamed from: lambda$dismissPhysicalKeyboardDialog$39$net-blocklegends-MainActivity, reason: not valid java name */
    public /* synthetic */ void m2074x119ae13f() {
        try {
            this.physicalKeyboardDialog.dismiss();
            this.physicalKeyboardDialog = null;
            Log.i("MainActivity", "Physical keyboard warning dismissed");
        } catch (Throwable th) {
            Log.e("MainActivity", "Error dismissing keyboard dialog: " + th.getMessage());
        }
    }

    /* JADX INFO: Access modifiers changed from: package-private */
    /* renamed from: lambda$forceLandscapeOrientation$12$net-blocklegends-MainActivity, reason: not valid java name */
    public /* synthetic */ void m2075xcfe9888a(String str) {
        try {
            if (!isFinishing() && !isDestroyed()) {
                setRequestedOrientation(6);
            }
        } catch (Throwable th) {
            Log.e("MainActivity", "Failed to force landscape (" + str + "): " + th.getMessage());
        }
    }

    /* JADX INFO: Access modifiers changed from: package-private */
    /* renamed from: lambda$initNonCritical$6$net-blocklegends-MainActivity, reason: not valid java name */
    public /* synthetic */ void m2076lambda$initNonCritical$6$netblocklegendsMainActivity() {
        try {
            if (!isFinishing() && !isDestroyed()) {
                FlexibleUpdateHelper flexibleUpdateHelper = new FlexibleUpdateHelper(this);
                this.flexibleUpdateHelper = flexibleUpdateHelper;
                flexibleUpdateHelper.checkForUpdate();
            }
        } catch (Throwable th) {
            Log.e("MainActivity", "Failed to initialize update helper: " + th.getMessage());
        }
    }

    /* JADX INFO: Access modifiers changed from: package-private */
    /* renamed from: lambda$initNonCritical$7$net-blocklegends-MainActivity, reason: not valid java name */
    public /* synthetic */ void m2077lambda$initNonCritical$7$netblocklegendsMainActivity() {
        String installerPackageName;
        try {
            if (isNetworkAvailable(this)) {
                PackageManager packageManager = activity.getPackageManager();
                if (packageManager == null) {
                    Log.w("MainActivity", "PackageManager is null");
                    return;
                }
                String packageName = activity.getPackageName();
                if (packageName == null || packageName.isEmpty() || (installerPackageName = packageManager.getInstallerPackageName(packageName)) == null || installerPackageName.isEmpty() || "com.android.vending".equals(installerPackageName)) {
                    return;
                }
                Log.w("MainActivity", "App not installed from Play Store");
            }
        } catch (Throwable th) {
            Log.e("MainActivity", "Failed to check installer: " + th.getMessage());
        }
    }

    /* JADX INFO: Access modifiers changed from: package-private */
    /* renamed from: lambda$onActionMove$21$net-blocklegends-MainActivity, reason: not valid java name */
    public /* synthetic */ void m2078lambda$onActionMove$21$netblocklegendsMainActivity() {
        for (int r0 = 0; r0 < this.runnableList.size(); r0++) {
            try {
                this.runnableList.get(r0).run();
            } catch (Throwable th) {
                Log.e("MainActivity", "Error processing pointer " + r0 + ": " + th.getMessage());
            }
        }
    }

    /* JADX INFO: Access modifiers changed from: package-private */
    /* renamed from: lambda$onConfigurationChanged$33$net-blocklegends-MainActivity, reason: not valid java name */
    public /* synthetic */ void m2079lambda$onConfigurationChanged$33$netblocklegendsMainActivity() {
        try {
            final int width = this.surfaceView.getWidth();
            final int height = this.surfaceView.getHeight();
            Log.d("MainActivity", "New surface dimensions: " + width + "x" + height);
            if (height > width) {
                Log.w("MainActivity", "Portrait surface ignored during configuration change: " + width + "x" + height);
                forceLandscapeOrientation("portraitConfigSurface");
            } else if (this.contexts != null) {
                changeScreenXY(String.valueOf(width), String.valueOf(height));
                Util.run(new Runnable() { // from class: net.blocklegends.MainActivity$$ExternalSyntheticLambda7
                    @Override // java.lang.Runnable
                    public final void run() {
                        GNatives.onSurfaceChanged(width, height);
                    }
                });
            }
        } catch (Throwable th) {
            Log.e("MainActivity", "Error updating surface dimensions: " + th.getMessage());
        }
    }

    /* JADX INFO: Access modifiers changed from: package-private */
    /* renamed from: lambda$onCreate$0$net-blocklegends-MainActivity, reason: not valid java name */
    public /* synthetic */ void m2080lambda$onCreate$0$netblocklegendsMainActivity(Context context) {
        try {
            this.fa = FirebaseAnalytics.getInstance(context);
        } catch (Throwable th) {
            Log.e("MainActivity", "FirebaseAnalytics init failed", th);
        }
    }

    /* JADX INFO: Access modifiers changed from: package-private */
    /* renamed from: lambda$onCreate$1$net-blocklegends-MainActivity, reason: not valid java name */
    public /* synthetic */ void m2081lambda$onCreate$1$netblocklegendsMainActivity() {
        try {
            RidevIntroView ridevIntroView = this.ridevIntroView;
            if (ridevIntroView != null) {
                this.frameLayout.removeView(ridevIntroView);
                this.ridevIntroView = null;
            }
        } catch (Throwable th) {
            Log.e("MainActivity", "Error removing RidevIntroView: " + th.getMessage());
        }
    }

    /* JADX INFO: Access modifiers changed from: package-private */
    /* renamed from: lambda$onResume$13$net-blocklegends-MainActivity, reason: not valid java name */
    public /* synthetic */ void m2082lambda$onResume$13$netblocklegendsMainActivity() {
        try {
            startAnalyticsHeartbeat();
            if (this.fa != null) {
                this.fa.logEvent("game_foreground", null);
            }
        } catch (Throwable th) {
            th.printStackTrace();
        }
        try {
            FlexibleUpdateHelper flexibleUpdateHelper = this.flexibleUpdateHelper;
            if (flexibleUpdateHelper != null) {
                flexibleUpdateHelper.checkPendingUpdate();
            }
        } catch (Throwable th2) {
            Log.e("MainActivity", "Error checking pending update: " + th2.getMessage());
        }
    }

    /* JADX INFO: Access modifiers changed from: package-private */
    /* renamed from: lambda$onStart$14$net-blocklegends-MainActivity, reason: not valid java name */
    public /* synthetic */ void m2083lambda$onStart$14$netblocklegendsMainActivity() {
        try {
            updateKbBlockFlag();
        } catch (Throwable th) {
            Log.e("MainActivity", "Error in keyboard check: " + th.getMessage());
        }
    }

    /* JADX INFO: Access modifiers changed from: package-private */
    /* renamed from: lambda$scheduleNetworkChanged$17$net-blocklegends-MainActivity, reason: not valid java name */
    public /* synthetic */ void m2084lambda$scheduleNetworkChanged$17$netblocklegendsMainActivity(int r3, int r4, String str) {
        synchronized (this.networkChangeLock) {
            if (r3 != this.networkChangeGeneration) {
                return;
            }
            ConnectivityManager connectivityManager = (ConnectivityManager) getSystemService("connectivity");
            if (connectivityManager == null) {
                return;
            }
            Network activeNetwork = connectivityManager.getActiveNetwork();
            int transportMask = transportMask(connectivityManager, activeNetwork);
            if (activeNetwork != null && transportMask != 0 && r4 != 0 && transportMask == r4) {
                this.lastKnownNetwork = activeNetwork;
                this.lastKnownNetworkTransportMask = transportMask;
                Log.i("MainActivity", "NetworkCallback." + str + ": aktif transport değişmedi (" + transportMaskName(transportMask) + "), reconnect iptal");
                return;
            }
            if (activeNetwork != null) {
                this.lastKnownNetwork = activeNetwork;
                this.lastKnownNetworkTransportMask = transportMask;
            }
            boolean z = (activeNetwork == null || r4 == 0 || transportMask == 0 || transportMask == r4) ? false : true;
            Log.i("MainActivity", "NetworkCallback." + str + ": ağ değişimi doğrulandı active=" + transportMaskName(transportMask) + ", nativeReconnect=" + z);
            if (z) {
                if (KEEP_GNS_GAME_CONNECTION_ON_NETWORK_CHANGE) {
                    Log.i("MainActivity", "NetworkCallback." + str + ": GNS game channel route recovery için canlı tutuldu");
                } else {
                    GNatives.onNetworkChanged();
                }
            }
        }
    }

    /* JADX INFO: Access modifiers changed from: package-private */
    /* renamed from: lambda$showIncompatibleDeviceDialog$34$net-blocklegends-MainActivity, reason: not valid java name */
    public /* synthetic */ void m2085xa9fd981c(DialogInterface dialogInterface, int r2) {
        dialogInterface.dismiss();
        finishAffinity();
    }

    /* JADX INFO: Access modifiers changed from: package-private */
    /* renamed from: lambda$showIncompatibleDeviceDialog$35$net-blocklegends-MainActivity, reason: not valid java name */
    public /* synthetic */ void m2086xbee6605d() {
        AlertDialog.Builder builder = new AlertDialog.Builder(this);
        builder.setCancelable(false);
        builder.setTitle("Incompatible Device");
        builder.setMessage(libraryLoadError != null ? "Your device is not compatible with this application.\n\nDetails: " + libraryLoadError : "Your device is not compatible with this application.");
        builder.setPositiveButton("OK", new DialogInterface.OnClickListener() { // from class: net.blocklegends.MainActivity$$ExternalSyntheticLambda38
            @Override // android.content.DialogInterface.OnClickListener
            public final void onClick(DialogInterface dialogInterface, int r2) {
                MainActivity.this.m2085xa9fd981c(dialogInterface, r2);
            }
        });
        builder.setIcon(android.R.drawable.ic_dialog_alert);
        builder.show();
    }

    /* JADX INFO: Access modifiers changed from: package-private */
    /* renamed from: lambda$showIncompatibleDeviceDialog$36$net-blocklegends-MainActivity, reason: not valid java name */
    public /* synthetic */ void m2087xd3cf289e() {
        finishAffinity();
    }

    /* JADX INFO: Access modifiers changed from: package-private */
    /* renamed from: lambda$showNotificationPermissionRationale$40$net-blocklegends-MainActivity, reason: not valid java name */
    public /* synthetic */ void m2088x51aac0c5(DialogInterface dialogInterface, int r3) {
        if (Build.VERSION.SDK_INT >= 33) {
            ActivityCompat.requestPermissions(this, new String[]{"android.permission.POST_NOTIFICATIONS"}, 1001);
        }
    }

    /* JADX INFO: Access modifiers changed from: package-private */
    /* renamed from: lambda$showPhysicalKeyboardWarning$37$net-blocklegends-MainActivity, reason: not valid java name */
    public /* synthetic */ void m2089x556dbdec(DialogInterface dialogInterface, int r2) {
        dialogInterface.dismiss();
        this.physicalKeyboardDialog = null;
        Log.i("MainActivity", "Physical keyboard warning dismissed by user");
    }

    /* JADX INFO: Access modifiers changed from: package-private */
    /* renamed from: lambda$showPhysicalKeyboardWarning$38$net-blocklegends-MainActivity, reason: not valid java name */
    public /* synthetic */ void m2090x6a56862d() {
        String str;
        String str2;
        String str3;
        boolean z = false;
        try {
            try {
                Locale locale = getResources().getConfiguration().locale;
                if (locale != null) {
                    if (locale.getLanguage().equals("tr")) {
                        z = true;
                    }
                }
            } catch (Throwable th) {
                Log.e("MainActivity", "Error detecting language: " + th.getMessage());
            }
            AlertDialog.Builder builder = new AlertDialog.Builder(this);
            builder.setCancelable(true);
            if (z) {
                str = "⚠️ Fiziksel Klavye Tespit Edildi";
                str2 = "Fiziksel klavye girdileri oyunda devre dışıdır. Dokunmatik ekran veya sanal klavye kullanarak devam edebilirsiniz.";
                str3 = "Devam";
            } else {
                str = "⚠️ Physical Keyboard Detected";
                str2 = "Physical keyboard input is disabled in-game. You can continue using touch screen or virtual keyboard.";
                str3 = "Continue";
            }
            builder.setTitle(str);
            builder.setMessage(str2);
            builder.setIcon(android.R.drawable.ic_dialog_alert);
            builder.setPositiveButton(str3, new DialogInterface.OnClickListener() { // from class: net.blocklegends.MainActivity$$ExternalSyntheticLambda17
                @Override // android.content.DialogInterface.OnClickListener
                public final void onClick(DialogInterface dialogInterface, int r2) {
                    MainActivity.this.m2089x556dbdec(dialogInterface, r2);
                }
            });
            AlertDialog create = builder.create();
            this.physicalKeyboardDialog = create;
            create.show();
            Log.w("MainActivity", "Physical keyboard warning shown (non-blocking with button)");
        } catch (Throwable th2) {
            Log.e("MainActivity", "Error showing keyboard warning: " + th2.getMessage());
        }
    }

    /* JADX INFO: Access modifiers changed from: package-private */
    /* renamed from: lambda$showStorageErrorDialog$3$net-blocklegends-MainActivity, reason: not valid java name */
    public /* synthetic */ void m2091lambda$showStorageErrorDialog$3$netblocklegendsMainActivity(DialogInterface dialogInterface, int r2) {
        dialogInterface.dismiss();
        startAssetExtraction();
    }

    /* JADX INFO: Access modifiers changed from: package-private */
    /* renamed from: lambda$showStorageErrorDialog$4$net-blocklegends-MainActivity, reason: not valid java name */
    public /* synthetic */ void m2092lambda$showStorageErrorDialog$4$netblocklegendsMainActivity(String str, DialogInterface dialogInterface, int r4) {
        dialogInterface.dismiss();
        try {
            try {
                startActivity(new Intent("android.settings.INTERNAL_STORAGE_SETTINGS"));
            } catch (Exception unused) {
                startActivity(new Intent("android.settings.SETTINGS"));
            }
        } catch (Exception e) {
            Log.e("MainActivity", "Failed to open settings", e);
        }
        showStorageErrorDialog(str);
    }

    /* JADX INFO: Access modifiers changed from: package-private */
    /* renamed from: lambda$showStorageErrorDialog$5$net-blocklegends-MainActivity, reason: not valid java name */
    public /* synthetic */ void m2093lambda$showStorageErrorDialog$5$netblocklegendsMainActivity(DialogInterface dialogInterface, int r2) {
        dialogInterface.dismiss();
        finishAffinity();
    }

    /* JADX INFO: Access modifiers changed from: package-private */
    /* renamed from: lambda$startAnalyticsHeartbeat$42$net-blocklegends-MainActivity, reason: not valid java name */
    public /* synthetic */ void m2094lambda$startAnalyticsHeartbeat$42$netblocklegendsMainActivity() {
        try {
            Bundle bundle = new Bundle();
            bundle.putString("state", "in_game");
            this.fa.logEvent("game_heartbeat", bundle);
        } catch (Throwable th) {
            th.printStackTrace();
        }
    }

    /* JADX INFO: Access modifiers changed from: package-private */
    /* renamed from: lambda$startAssetExtraction$2$net-blocklegends-MainActivity, reason: not valid java name */
    public /* synthetic */ void m2095lambda$startAssetExtraction$2$netblocklegendsMainActivity() {
        GameAssetsExtractor.extractIfNeeded(this, new AnonymousClass2());
    }

    public void onActionMove(MotionEvent motionEvent, int r9) {
        try {
            if (motionEvent == null) {
                Log.w("MainActivity", "Event is null in onActionMove");
                return;
            }
            this.runnableList.clear();
            for (int r1 = 0; r1 < r9; r1++) {
                try {
                    final int pointerId = motionEvent.getPointerId(r1);
                    final float x = motionEvent.getX(r1);
                    final float y = motionEvent.getY(r1);
                    this.runnableList.add(new Runnable() { // from class: net.blocklegends.MainActivity$$ExternalSyntheticLambda40
                        @Override // java.lang.Runnable
                        public final void run() {
                            GNatives.onActionMove(pointerId, x, y);
                        }
                    });
                } catch (Throwable th) {
                    Log.e("MainActivity", "Error processing pointer " + r1 + ": " + th.getMessage());
                }
            }
            if (this.runnableList.isEmpty()) {
                return;
            }
            Util.run(new Runnable() { // from class: net.blocklegends.MainActivity$$ExternalSyntheticLambda41
                @Override // java.lang.Runnable
                public final void run() {
                    MainActivity.this.m2078lambda$onActionMove$21$netblocklegendsMainActivity();
                }
            });
        } catch (Throwable th2) {
            Log.e("MainActivity", "Error in onActionMove: " + th2.getMessage());
        }
    }

    /* JADX INFO: Access modifiers changed from: protected */
    @Override // androidx.fragment.app.FragmentActivity, androidx.activity.ComponentActivity, android.app.Activity
    public void onActivityResult(int r4, int r5, Intent intent) {
        super.onActivityResult(r4, r5, intent);
        try {
            AppleSign.onActivityResult(r4, r5, intent);
        } catch (Throwable th) {
            Log.e("MainActivity", "Error handling onActivityResult: " + th.getMessage());
        }
        try {
            GoogleSign.onActivityResult(r4, r5, intent);
        } catch (Throwable th2) {
            Log.e("MainActivity", "Error handling Google onActivityResult: " + th2.getMessage());
        }
    }

    @Override // androidx.appcompat.app.AppCompatActivity, androidx.activity.ComponentActivity, android.app.Activity, android.content.ComponentCallbacks
    public void onConfigurationChanged(Configuration configuration) {
        super.onConfigurationChanged(configuration);
        if (configuration != null) {
            try {
                Log.d("MainActivity", "Configuration changed: " + configuration.toString());
            } catch (Throwable th) {
                Log.e("MainActivity", "Error in onConfigurationChanged: " + th.getMessage());
                return;
            }
        }
        forceLandscapeOrientation("onConfigurationChanged");
        GameSurfaceView gameSurfaceView = this.surfaceView;
        if (gameSurfaceView != null) {
            gameSurfaceView.post(new Runnable() { // from class: net.blocklegends.MainActivity$$ExternalSyntheticLambda5
                @Override // java.lang.Runnable
                public final void run() {
                    MainActivity.this.m2079lambda$onConfigurationChanged$33$netblocklegendsMainActivity();
                }
            });
        }
        WindowManager windowManager = this.windowManager;
        if (windowManager != null) {
            windowManager.onWindowFocusChanged(true);
        }
    }

    /* JADX INFO: Access modifiers changed from: protected */
    @Override // androidx.fragment.app.FragmentActivity, androidx.activity.ComponentActivity, androidx.core.app.ComponentActivity, android.app.Activity
    public void onCreate(Bundle bundle) {
        requestWindowFeature(1);
        getWindow().setFlags(1024, 1024);
        getWindow().addFlags(128);
        WindowCompat.setDecorFitsSystemWindows(getWindow(), false);
        super.onCreate(bundle);
        final Context applicationContext = getApplicationContext();
        new Thread(new Runnable() { // from class: net.blocklegends.MainActivity$$ExternalSyntheticLambda25
            @Override // java.lang.Runnable
            public final void run() {
                MainActivity.this.m2080lambda$onCreate$0$netblocklegendsMainActivity(applicationContext);
            }
        }, "FA-Init").start();
        activity = this;
        handleAppleSignInIntent(getIntent());
        forceLandscapeOrientation("onCreate");
        try {
            setContentView(R.layout.activity_main);
            FrameLayout frameLayout = (FrameLayout) findViewById(R.id.activity_main);
            this.frameLayout = frameLayout;
            if (frameLayout != null) {
                frameLayout.setBackgroundColor(ViewCompat.MEASURED_STATE_MASK);
            }
            if (this.frameLayout != null) {
                try {
                    RidevIntroView ridevIntroView = new RidevIntroView(this, R.drawable.ridev_logo, new RidevIntroView.OnIntroCompleteListener() { // from class: net.blocklegends.MainActivity$$ExternalSyntheticLambda26
                        @Override // net.blocklegends.ui.RidevIntroView.OnIntroCompleteListener
                        public final void onIntroComplete() {
                            MainActivity.this.m2081lambda$onCreate$1$netblocklegendsMainActivity();
                        }
                    });
                    this.ridevIntroView = ridevIntroView;
                    this.frameLayout.addView(ridevIntroView);
                } catch (Throwable th) {
                    Log.e("MainActivity", "Failed to create RidevIntroView: " + th.getMessage());
                }
            }
            if (nativeLibrariesLoaded) {
                initGame();
            } else {
                loadNativeLibraryFromAssetPack();
            }
        } catch (Throwable th2) {
            Log.e("MainActivity", "Failed to set content view: " + th2.getMessage());
            finish();
        }
    }

    @Override // androidx.appcompat.app.AppCompatActivity, androidx.fragment.app.FragmentActivity, android.app.Activity
    protected void onDestroy() {
        unregisterNetworkCallback();
        try {
            InputManager inputManager = this.inputManager;
            if (inputManager != null) {
                inputManager.unregisterInputDeviceListener(null);
            }
        } catch (Throwable th) {
            th.printStackTrace();
        }
        try {
            AntiAutoClickerScheduler antiAutoClickerScheduler = this.anti;
            if (antiAutoClickerScheduler != null) {
                antiAutoClickerScheduler.shutdown();
            }
        } catch (Throwable th2) {
            th2.printStackTrace();
        }
        ScheduledExecutorService scheduledExecutorService = this.keyboardCheckScheduler;
        if (scheduledExecutorService != null) {
            try {
                scheduledExecutorService.shutdownNow();
            } catch (Throwable th3) {
                Log.e("MainActivity", "Error shutting down keyboard check scheduler: " + th3.getMessage());
            }
        }
        try {
            GameSurfaceView gameSurfaceView = this.surfaceView;
            if (gameSurfaceView != null) {
                gameSurfaceView.onDestroy();
            }
        } catch (Throwable th4) {
            Log.e("MainActivity", "Error destroying surface view: " + th4.getMessage());
        }
        try {
            RidevIntroView ridevIntroView = this.ridevIntroView;
            if (ridevIntroView != null) {
                this.frameLayout.removeView(ridevIntroView);
                this.ridevIntroView = null;
            }
        } catch (Throwable th5) {
            Log.e("MainActivity", "Error destroying ridev intro: " + th5.getMessage());
        }
        try {
            FlexibleUpdateHelper flexibleUpdateHelper = this.flexibleUpdateHelper;
            if (flexibleUpdateHelper != null) {
                flexibleUpdateHelper.cleanup();
            }
        } catch (Throwable th6) {
            Log.e("MainActivity", "Error cleaning up update helper: " + th6.getMessage());
        }
        try {
            GyroscopeEC gyroscopeEC = this.gyroscopeDetector;
            if (gyroscopeEC != null) {
                gyroscopeEC.stopDetection();
            }
        } catch (Throwable th7) {
            Log.e("MainActivity", "Error stopping gyroscope detector: " + th7.getMessage());
        }
        try {
            Util.shutdown();
        } catch (Throwable th8) {
            Log.e("MainActivity", "Error shutting down executor: " + th8.getMessage());
        }
        try {
            GameAssetsExtractor.requestCancellation();
        } catch (Throwable th9) {
            Log.e("MainActivity", "Error cancelling extraction: " + th9.getMessage());
        }
        super.onDestroy();
        if (isFinishing()) {
            new Thread(new Runnable() { // from class: net.blocklegends.MainActivity$$ExternalSyntheticLambda28
                @Override // java.lang.Runnable
                public final void run() {
                    MainActivity.lambda$onDestroy$16();
                }
            }, "Process-Kill").start();
        }
    }

    @Override // android.app.Activity
    public boolean onGenericMotionEvent(MotionEvent motionEvent) {
        if (isAutomationLikely() || isChromeOS()) {
            return super.onGenericMotionEvent(motionEvent);
        }
        if ((motionEvent.getSource() & 8194) == 8194) {
            return true;
        }
        return super.onGenericMotionEvent(motionEvent);
    }

    @Override // androidx.appcompat.app.AppCompatActivity, android.app.Activity, android.view.KeyEvent.Callback
    public boolean onKeyDown(final int r4, KeyEvent keyEvent) {
        if (r4 == 24 || r4 == 25 || r4 == 164) {
            return super.onKeyDown(r4, keyEvent);
        }
        if (!isTestEnvironment() && keyEvent.getDevice() != null && (keyEvent.getDevice().getSources() & 257) == 257) {
            return true;
        }
        final String characters = keyEvent.getCharacters();
        final char unicodeChar = (characters == null || characters.isEmpty()) ? (char) keyEvent.getUnicodeChar() : characters.charAt(0);
        Util.run(new Runnable() { // from class: net.blocklegends.MainActivity$$ExternalSyntheticLambda6
            @Override // java.lang.Runnable
            public final void run() {
                GNatives.onKeyDown(r4, characters, unicodeChar);
            }
        });
        return super.onKeyDown(r4, keyEvent);
    }

    @Override // android.app.Activity, android.view.KeyEvent.Callback
    public boolean onKeyUp(final int r3, KeyEvent keyEvent) {
        if (r3 == 24 || r3 == 25 || r3 == 164) {
            return super.onKeyUp(r3, keyEvent);
        }
        if (!isTestEnvironment() && keyEvent.getDevice() != null && (keyEvent.getDevice().getSources() & 257) == 257) {
            return true;
        }
        Util.run(new Runnable() { // from class: net.blocklegends.MainActivity$$ExternalSyntheticLambda9
            @Override // java.lang.Runnable
            public final void run() {
                GNatives.onKeyUp(r3);
            }
        });
        return super.onKeyUp(r3, keyEvent);
    }

    @Override // androidx.activity.ComponentActivity, android.app.Activity
    protected void onNewIntent(Intent intent) {
        super.onNewIntent(intent);
        setIntent(intent);
        handleAppleSignInIntent(intent);
    }

    /* JADX INFO: Access modifiers changed from: protected */
    @Override // androidx.fragment.app.FragmentActivity, android.app.Activity
    public void onPause() {
        FCMService.isAppInForeground = false;
        try {
            stopAnalyticsHeartbeat();
            this.fa.logEvent("game_background", null);
        } catch (Throwable th) {
            th.printStackTrace();
        }
        super.onPause();
        try {
            GoogleSign.onHostActivityPaused();
        } catch (Throwable th2) {
            Log.e("MainActivity", "Error in GoogleSign.onHostActivityPaused: " + th2.getMessage());
        }
        try {
            GameSurfaceView gameSurfaceView = this.surfaceView;
            if (gameSurfaceView != null) {
                gameSurfaceView.onPause();
            }
        } catch (Throwable th3) {
            Log.e("MainActivity", "Error pausing surface view: " + th3.getMessage());
        }
        try {
            RidevIntroView ridevIntroView = this.ridevIntroView;
            if (ridevIntroView != null) {
                ridevIntroView.onPause();
            }
        } catch (Throwable th4) {
            Log.e("MainActivity", "Error pausing ridev intro: " + th4.getMessage());
        }
        try {
            GyroscopeEC gyroscopeEC = this.gyroscopeDetector;
            if (gyroscopeEC != null) {
                gyroscopeEC.stopDetection();
            }
        } catch (Throwable th5) {
            Log.e("MainActivity", "Error stopping gyroscope detector in onPause: " + th5.getMessage());
        }
    }

    @Override // androidx.fragment.app.FragmentActivity, androidx.activity.ComponentActivity, android.app.Activity
    public void onRequestPermissionsResult(int r1, String[] strArr, int[] r3) {
        super.onRequestPermissionsResult(r1, strArr, r3);
        if (r1 == 1001) {
            if (r3.length <= 0 || r3[0] != 0) {
                Log.d("MainActivity", "Bildirim izni reddedildi");
            } else {
                Log.d("MainActivity", "Bildirim izni verildi");
            }
        }
    }

    @Override // android.app.Activity
    protected void onRestart() {
        super.onRestart();
        try {
            GNatives.onActivityRestart();
        } catch (Throwable th) {
            Log.e("MainActivity", "Error in onActivityRestart: " + th.getMessage());
        }
    }

    @Override // android.app.Activity
    protected void onRestoreInstanceState(Bundle bundle) {
        super.onRestoreInstanceState(bundle);
        try {
            Log.d("MainActivity", "Restoring instance state");
            GNatives.onRestoreInstanceState();
        } catch (Throwable th) {
            Log.e("MainActivity", "Error restoring instance state: " + th.getMessage());
        }
    }

    /* JADX INFO: Access modifiers changed from: protected */
    @Override // androidx.fragment.app.FragmentActivity, android.app.Activity
    public void onResume() {
        super.onResume();
        try {
            AppleSign.onHostActivityResumed();
        } catch (Throwable th) {
            Log.e("MainActivity", "Error handling Apple Sign-In resume: " + th.getMessage());
        }
        try {
            GoogleSign.onHostActivityResumed();
        } catch (Throwable th2) {
            Log.e("MainActivity", "Error handling Google Sign-In resume: " + th2.getMessage());
        }
        FCMService.isAppInForeground = true;
        forceLandscapeOrientation("onResume");
        try {
            WindowManager windowManager = this.windowManager;
            if (windowManager != null) {
                windowManager.onWindowFocusChanged(true);
            }
        } catch (Throwable th3) {
            Log.e("MainActivity", "Error updating window manager: " + th3.getMessage());
        }
        try {
            GameSurfaceView gameSurfaceView = this.surfaceView;
            if (gameSurfaceView != null) {
                gameSurfaceView.onResume();
            }
        } catch (Throwable th4) {
            Log.e("MainActivity", "Error resuming surface view: " + th4.getMessage());
        }
        try {
            RidevIntroView ridevIntroView = this.ridevIntroView;
            if (ridevIntroView != null) {
                ridevIntroView.onResume();
            }
        } catch (Throwable th5) {
            Log.e("MainActivity", "Error resuming ridev intro: " + th5.getMessage());
        }
        getWindow().getDecorView().post(new Runnable() { // from class: net.blocklegends.MainActivity$$ExternalSyntheticLambda24
            @Override // java.lang.Runnable
            public final void run() {
                MainActivity.this.m2082lambda$onResume$13$netblocklegendsMainActivity();
            }
        });
    }

    @Override // androidx.activity.ComponentActivity, androidx.core.app.ComponentActivity, android.app.Activity
    protected void onSaveInstanceState(Bundle bundle) {
        super.onSaveInstanceState(bundle);
        try {
            Log.d("MainActivity", "Saving instance state");
            GNatives.onSaveInstanceState();
        } catch (Throwable th) {
            Log.e("MainActivity", "Error saving instance state: " + th.getMessage());
        }
    }

    @Override // androidx.appcompat.app.AppCompatActivity, androidx.fragment.app.FragmentActivity, android.app.Activity
    protected void onStart() {
        super.onStart();
        try {
            if (this.cachedIsTurkishOrAzeri == null) {
                this.cachedIsTurkishOrAzeri = Boolean.valueOf(isTurkishOrAzeriUser());
            }
            if (this.cachedIsTurkishOrAzeri.booleanValue()) {
                if (this.anti == null) {
                    this.anti = new AntiAutoClickerScheduler(getApplicationContext(), new AntiAutoClickerScheduler.OnDetectionListener() { // from class: net.blocklegends.MainActivity$$ExternalSyntheticLambda10
                        @Override // net.blocklegends.utils.AntiAutoClickerScheduler.OnDetectionListener
                        public final void onDetected(AntiAutoClickerUtil.DetectionResult detectionResult) {
                            GNatives.showAutoClickerPopup(detectionResult);
                        }
                    });
                }
                this.anti.start(0L, 30L, TimeUnit.SECONDS);
            }
        } catch (Throwable th) {
            Log.e("MainActivity", "Error initalize: " + th.getMessage());
        }
        try {
            ScheduledExecutorService scheduledExecutorService = this.keyboardCheckScheduler;
            if (scheduledExecutorService == null || scheduledExecutorService.isShutdown()) {
                ScheduledExecutorService newSingleThreadScheduledExecutor = Executors.newSingleThreadScheduledExecutor();
                this.keyboardCheckScheduler = newSingleThreadScheduledExecutor;
                newSingleThreadScheduledExecutor.scheduleWithFixedDelay(new Runnable() { // from class: net.blocklegends.MainActivity$$ExternalSyntheticLambda12
                    @Override // java.lang.Runnable
                    public final void run() {
                        MainActivity.this.m2083lambda$onStart$14$netblocklegendsMainActivity();
                    }
                }, 0L, 30L, TimeUnit.SECONDS);
            }
        } catch (Throwable th2) {
            Log.e("MainActivity", "Error initializing keyboard check scheduler: " + th2.getMessage());
        }
    }

    @Override // androidx.appcompat.app.AppCompatActivity, androidx.fragment.app.FragmentActivity, android.app.Activity
    protected void onStop() {
        super.onStop();
        try {
            GNatives.onActivityStop();
        } catch (Throwable th) {
            Log.e("MainActivity", "Error in onActivityStop: " + th.getMessage());
        }
        AntiAutoClickerScheduler antiAutoClickerScheduler = this.anti;
        if (antiAutoClickerScheduler != null) {
            antiAutoClickerScheduler.stop();
        }
        ScheduledExecutorService scheduledExecutorService = this.keyboardCheckScheduler;
        if (scheduledExecutorService == null || scheduledExecutorService.isShutdown()) {
            return;
        }
        this.keyboardCheckScheduler.shutdownNow();
    }

    @Override // android.app.Activity
    public boolean onTouchEvent(MotionEvent motionEvent) {
        try {
        } catch (Throwable th) {
            Log.e("MainActivity", "Error in onTouchEvent: " + th.getMessage(), th);
        }
        if (motionEvent == null) {
            Log.w("MainActivity", "Touch event is null");
            return super.onTouchEvent(motionEvent);
        }
        if ((motionEvent.getSource() & InputDeviceCompat.SOURCE_TOUCHSCREEN) != 4098) {
            return true;
        }
        int actionMasked = motionEvent.getActionMasked();
        int actionIndex = motionEvent.getActionIndex();
        int pointerCount = motionEvent.getPointerCount();
        final int x = (int) motionEvent.getX();
        final int y = (int) motionEvent.getY();
        final float x2 = motionEvent.getX(actionIndex);
        final float y2 = motionEvent.getY(actionIndex);
        final int pointerId = motionEvent.getPointerId(actionIndex);
        if (actionMasked == 0) {
            Util.run(new Runnable() { // from class: net.blocklegends.MainActivity$$ExternalSyntheticLambda21
                @Override // java.lang.Runnable
                public final void run() {
                    GNatives.onActionDown(x, y, pointerId);
                }
            });
        } else if (actionMasked == 1) {
            Util.run(new Runnable() { // from class: net.blocklegends.MainActivity$$ExternalSyntheticLambda23
                @Override // java.lang.Runnable
                public final void run() {
                    GNatives.onActionUp(x, y, pointerId);
                }
            });
        } else if (actionMasked == 2) {
            onActionMove(motionEvent, pointerCount);
        } else if (actionMasked == 3) {
            Util.run(new Runnable() { // from class: net.blocklegends.MainActivity$$ExternalSyntheticLambda18
                @Override // java.lang.Runnable
                public final void run() {
                    GNatives.onActionCancel((int) x2, (int) y2, pointerId);
                }
            });
        } else if (actionMasked == 5) {
            Util.run(new Runnable() { // from class: net.blocklegends.MainActivity$$ExternalSyntheticLambda20
                @Override // java.lang.Runnable
                public final void run() {
                    GNatives.onActionPointerDown(x2, y2, pointerId);
                }
            });
        } else if (actionMasked == 6) {
            Util.run(new Runnable() { // from class: net.blocklegends.MainActivity$$ExternalSyntheticLambda19
                @Override // java.lang.Runnable
                public final void run() {
                    GNatives.onActionPointerUp((int) x2, (int) y2, pointerId);
                }
            });
        }
        return super.onTouchEvent(motionEvent);
    }

    public void requestNotificationPermission() {
        try {
            if (Build.VERSION.SDK_INT >= 33) {
                if (ContextCompat.checkSelfPermission(this, "android.permission.POST_NOTIFICATIONS") == 0) {
                    Log.d("MainActivity", "Bildirim izni zaten verilmis");
                } else if (ActivityCompat.shouldShowRequestPermissionRationale(this, "android.permission.POST_NOTIFICATIONS")) {
                    showNotificationPermissionRationale();
                } else {
                    ActivityCompat.requestPermissions(this, new String[]{"android.permission.POST_NOTIFICATIONS"}, 1001);
                }
            }
        } catch (Throwable th) {
            Log.e("MainActivity", "Bildirim izni isteme hatasi: " + th.getMessage());
        }
    }
}
