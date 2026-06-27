package net.blocklegends.game;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.DialogInterface;
import android.content.Intent;
import android.content.pm.PackageInfo;
import android.os.Build;
import android.os.Bundle;
import android.os.CancellationSignal;
import android.os.Handler;
import android.os.Looper;
import android.util.Base64;
import android.util.Log;
import androidx.core.content.ContextCompat;
import androidx.core.os.EnvironmentCompat;
import androidx.credentials.ClearCredentialStateRequest;
import androidx.credentials.Credential;
import androidx.credentials.CredentialManager;
import androidx.credentials.CredentialManagerCallback;
import androidx.credentials.CustomCredential;
import androidx.credentials.GetCredentialRequest;
import androidx.credentials.GetCredentialResponse;
import androidx.credentials.exceptions.ClearCredentialException;
import androidx.credentials.exceptions.GetCredentialCancellationException;
import androidx.credentials.exceptions.GetCredentialException;
import androidx.credentials.exceptions.GetCredentialInterruptedException;
import androidx.credentials.exceptions.GetCredentialProviderConfigurationException;
import androidx.credentials.exceptions.GetCredentialUnsupportedException;
import androidx.credentials.exceptions.NoCredentialException;
import com.google.android.gms.auth.api.signin.GoogleSignIn;
import com.google.android.gms.auth.api.signin.GoogleSignInAccount;
import com.google.android.gms.auth.api.signin.GoogleSignInClient;
import com.google.android.gms.auth.api.signin.GoogleSignInOptions;
import com.google.android.gms.common.api.ApiException;
import com.google.android.libraries.identity.googleid.GetSignInWithGoogleOption;
import com.google.android.libraries.identity.googleid.GoogleIdTokenCredential;
import com.google.firebase.analytics.FirebaseAnalytics;
import com.google.firebase.crashlytics.FirebaseCrashlytics;
import java.security.SecureRandom;
import java.util.Locale;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import kotlinx.serialization.json.internal.AbstractJsonLexerKt;
import net.blocklegends.MainActivity;
import net.blocklegends.R;
import net.blocklegends.natives.GNatives;
import net.blocklegends.utils.Util;

/* loaded from: classes4.dex */
public class GoogleSign {
    private static final long CM_RESUME_GRACE_MS = 1500;
    private static final long CM_STUCK_TIMEOUT_MS = 30000;
    private static final long CM_TIMEOUT_MS = 12000;
    private static final long LEGACY_TIMEOUT_MS = 120000;
    private static final int NONCE_BYTES = 32;
    private static final int REQUEST_CODE_GOOGLE_LEGACY = 9012;
    private static final long STALE_LOCK_MS = 147000;
    private static final String TAG = "GoogleSign";
    private static volatile CancellationSignal activeCancellationSignal;
    private static volatile String activeNonce;
    private static volatile long activeSignInStartedAt;
    private static volatile Runnable activeTimeoutRunnable;
    private static volatile String cachedGmsVersion;
    private static volatile String cachedPlayStoreVersion;
    private static volatile boolean cmUiShown;
    private static final Handler MAIN_HANDLER = new Handler(Looper.getMainLooper());
    private static final SecureRandom SECURE_RANDOM = new SecureRandom();
    private static final AtomicBoolean signInInProgress = new AtomicBoolean(false);
    private static final AtomicInteger signInAttemptCounter = new AtomicInteger();
    private static volatile int activeSignInAttempt = -1;
    private static volatile int legacyTakenOverAttempt = -1;
    private static final ExecutorService TELEMETRY_EXEC = Util.newExecutorService("GSI-Telemetry", 1);

    private static int beginAttempt(String str) {
        int incrementAndGet = signInAttemptCounter.incrementAndGet();
        activeSignInAttempt = incrementAndGet;
        activeSignInStartedAt = System.currentTimeMillis();
        activeNonce = str;
        cmUiShown = false;
        legacyTakenOverAttempt = -1;
        scheduleCmTimeout(incrementAndGet);
        MainActivity.beginExternalUi(MainActivity.EXTERNAL_UI_GOOGLE_SIGN);
        return incrementAndGet;
    }

    private static String buildFailureTelemetry(MainActivity mainActivity, String str, String str2, long j) {
        String sanitizeMessage = sanitizeMessage(str2);
        StringBuilder sb = new StringBuilder(256);
        StringBuilder append = sb.append("type=");
        if (str == null) {
            str = "other";
        }
        append.append(str);
        sb.append("|elapsed_ms=").append(j);
        sb.append("|sdk=").append(Build.VERSION.SDK_INT);
        sb.append("|manufacturer=").append(sanitizeMessage(Build.MANUFACTURER));
        sb.append("|model=").append(sanitizeMessage(Build.MODEL));
        sb.append("|gms=").append(gmsVersion(mainActivity));
        sb.append("|play_store=").append(playStoreVersion(mainActivity));
        if (!sanitizeMessage.isEmpty()) {
            sb.append("|message_hash=").append(Integer.toHexString(sanitizeMessage.hashCode()));
            sb.append("|message=").append(sanitizeMessage);
        }
        return sb.toString();
    }

    private static GetSignInWithGoogleOption buildGoogleOption(MainActivity mainActivity, String str) {
        return new GetSignInWithGoogleOption.Builder(mainActivity.getString(R.string.default_web_client_id)).setNonce(str).build();
    }

    private static void cancelActiveCmSignal() {
        CancellationSignal cancellationSignal = activeCancellationSignal;
        if (cancellationSignal != null && !cancellationSignal.isCanceled()) {
            cancellationSignal.cancel();
        }
        activeCancellationSignal = null;
    }

    private static void cancelActiveTimeout() {
        Runnable runnable = activeTimeoutRunnable;
        if (runnable != null) {
            MAIN_HANDLER.removeCallbacks(runnable);
        }
        activeTimeoutRunnable = null;
    }

    /* JADX INFO: Access modifiers changed from: private */
    public static String classifyCredentialError(GetCredentialException getCredentialException) {
        String message = getCredentialException.getMessage();
        if (message != null) {
            String lowerCase = message.toLowerCase(Locale.US);
            if (lowerCase.contains("unable to get sync account")) {
                return "sync_account_unavailable";
            }
            if (lowerCase.contains("temporarily blocked")) {
                return "temporarily_blocked";
            }
            if (lowerCase.contains("no provider dependencies")) {
                return "provider_configuration";
            }
        }
        return getCredentialException instanceof GetCredentialCancellationException ? "cancelled" : getCredentialException instanceof NoCredentialException ? "no_credential" : getCredentialException instanceof GetCredentialProviderConfigurationException ? "provider_configuration" : getCredentialException instanceof GetCredentialUnsupportedException ? "unsupported" : getCredentialException instanceof GetCredentialInterruptedException ? "interrupted" : "other";
    }

    private static void completeAttempt(int r3) {
        if (activeSignInAttempt == r3) {
            cancelActiveTimeout();
            activeCancellationSignal = null;
            activeNonce = null;
            legacyTakenOverAttempt = -1;
            cmUiShown = false;
            activeSignInAttempt = -1;
            activeSignInStartedAt = 0L;
            signInInProgress.set(false);
            MainActivity.endExternalUi(MainActivity.EXTERNAL_UI_GOOGLE_SIGN);
        }
    }

    private static String createNonce() {
        byte[] bArr = new byte[32];
        SECURE_RANDOM.nextBytes(bArr);
        return Base64.encodeToString(bArr, 11);
    }

    private static long elapsedMsForAttempt(int r4) {
        if (!isActiveAttempt(r4) || activeSignInStartedAt <= 0) {
            return -1L;
        }
        return System.currentTimeMillis() - activeSignInStartedAt;
    }

    /* JADX INFO: Access modifiers changed from: private */
    public static void finishWithCancel(int r1) {
        if (isActiveAttempt(r1)) {
            completeAttempt(r1);
            notifyEmptyToken();
        }
    }

    /* JADX INFO: Access modifiers changed from: private */
    public static void finishWithTechnicalFailure(MainActivity mainActivity, int r5, String str, String str2, Throwable th) {
        if (!isActiveAttempt(r5)) {
            Log.w(TAG, "[G-A] stale technical failure ignored, attempt=" + r5 + ", active=" + activeSignInAttempt);
            return;
        }
        long elapsedMsForAttempt = elapsedMsForAttempt(r5);
        completeAttempt(r5);
        String buildFailureTelemetry = buildFailureTelemetry(mainActivity, str, str2, elapsedMsForAttempt);
        Log.e(TAG, "[G-A] technical failure: " + buildFailureTelemetry, th);
        notifyGoogleSignFailed(str, buildFailureTelemetry);
    }

    /* JADX INFO: Access modifiers changed from: private */
    public static void finishWithToken(int r6, String str, boolean z) {
        if (!isActiveAttempt(r6)) {
            Log.w(TAG, "[G-A] stale token ignored, attempt=" + r6 + ", active=" + activeSignInAttempt);
            return;
        }
        if (str == null || str.isEmpty()) {
            Log.w(TAG, "[G-A] finishWithToken received empty token");
            finishWithTechnicalFailure(MainActivity.getActivity(), r6, "empty_token", "Google ID token is empty", null);
            return;
        }
        String str2 = z ? "" : activeNonce;
        if (!z && (str2 == null || str2.isEmpty())) {
            Log.w(TAG, "[G-A] finishWithToken received empty nonce");
            finishWithTechnicalFailure(MainActivity.getActivity(), r6, "missing_nonce", "Google auth nonce is empty", null);
            return;
        }
        try {
            GNatives.onTokenReceived(str, str2 != null ? str2 : "");
        } catch (Throwable th) {
            try {
                Log.e(TAG, "[G-A] token delivery failed: " + th.getMessage(), th);
                notifyGoogleSignFailed("native_token_delivery_failed", "native_callback_exception=" + sanitizeMessage(th.getMessage()));
            } finally {
                completeAttempt(r6);
            }
        }
    }

    private static void forceResetActiveAttempt() {
        cancelActiveTimeout();
        cancelActiveCmSignal();
        activeNonce = null;
        legacyTakenOverAttempt = -1;
        cmUiShown = false;
        int r2 = activeSignInAttempt;
        activeSignInAttempt = -1;
        activeSignInStartedAt = 0L;
        signInInProgress.set(false);
        if (r2 != -1) {
            try {
                MainActivity.endExternalUi(MainActivity.EXTERNAL_UI_GOOGLE_SIGN);
            } catch (Throwable unused) {
            }
        }
    }

    private static String gmsVersion(MainActivity mainActivity) {
        String str = cachedGmsVersion;
        if (str != null) {
            return str;
        }
        String packageVersion = packageVersion(mainActivity, "com.google.android.gms");
        cachedGmsVersion = packageVersion;
        return packageVersion;
    }

    public static void init() {
        if (MainActivity.getActivity() == null) {
            throw new IllegalStateException("MainActivity is null");
        }
    }

    /* JADX INFO: Access modifiers changed from: private */
    public static boolean isActiveAttempt(int r1) {
        return activeSignInAttempt == r1;
    }

    private static boolean isActivityUnavailable(MainActivity mainActivity) {
        return mainActivity == null || mainActivity.isFinishing() || mainActivity.isDestroyed();
    }

    /* JADX INFO: Access modifiers changed from: private */
    public static boolean isUserDismissal(String str) {
        return "cancelled".equals(str);
    }

    /* JADX INFO: Access modifiers changed from: package-private */
    public static /* synthetic */ void lambda$logFunnel$7(String str, String str2, int r8) {
        try {
            MainActivity activity = MainActivity.getActivity();
            if (activity != null) {
                Bundle bundle = new Bundle();
                bundle.putString("stage", str);
                if (str2 != null) {
                    bundle.putString("detail", sanitizeMessage(str2));
                }
                bundle.putInt("sdk", r8);
                bundle.putString("gms", gmsVersion(activity));
                FirebaseAnalytics.getInstance(activity).logEvent("google_signin_funnel", bundle);
            }
            FirebaseCrashlytics firebaseCrashlytics = FirebaseCrashlytics.getInstance();
            firebaseCrashlytics.setCustomKey("gsi_stage", str);
            if (str2 != null) {
                firebaseCrashlytics.setCustomKey("gsi_detail", sanitizeMessage(str2));
            }
            firebaseCrashlytics.setCustomKey("gsi_sdk", r8);
            firebaseCrashlytics.setCustomKey("gsi_manufacturer", sanitizeMessage(Build.MANUFACTURER));
            firebaseCrashlytics.setCustomKey("gsi_model", sanitizeMessage(Build.MODEL));
            firebaseCrashlytics.setCustomKey("gsi_brand", sanitizeMessage(Build.BRAND));
            firebaseCrashlytics.setCustomKey("gsi_device", sanitizeMessage(Build.DEVICE));
            firebaseCrashlytics.setCustomKey("gsi_product", sanitizeMessage(Build.PRODUCT));
            firebaseCrashlytics.setCustomKey("gsi_fingerprint", sanitizeMessage(Build.FINGERPRINT));
            firebaseCrashlytics.setCustomKey("gsi_build_display", sanitizeMessage(Build.DISPLAY));
            if (activity != null) {
                firebaseCrashlytics.setCustomKey("gsi_gms", gmsVersion(activity));
                firebaseCrashlytics.setCustomKey("gsi_play_store", playStoreVersion(activity));
            }
            firebaseCrashlytics.log("[G-A] funnel=" + str + (str2 != null ? " (" + str2 + ")" : ""));
        } catch (Throwable th) {
            Log.w(TAG, "[G-A] logFunnel(bg) failed: " + th.getClass().getSimpleName());
        }
    }

    /* JADX INFO: Access modifiers changed from: package-private */
    public static /* synthetic */ void lambda$onHostActivityResumed$3(int r2) {
        if (!isActiveAttempt(r2) || legacyTakenOverAttempt == r2) {
            return;
        }
        Log.w(TAG, "[G-A] CM UI shown but resumed without callback — switching to legacy fallback");
        logFunnel("cm_no_callback_after_resume", null);
        startLegacyFallback(r2, "cm_no_callback_after_resume");
    }

    /* JADX INFO: Access modifiers changed from: package-private */
    public static /* synthetic */ void lambda$openLogin$0(int r4, MainActivity mainActivity) {
        try {
            Log.i(TAG, "[G-A] openLoginInternal starting on UI thread, attempt=" + r4);
            openLoginInternal(mainActivity, r4);
        } catch (Throwable th) {
            Log.e(TAG, "[G-A] openLogin runOnUiThread exception: " + th.getMessage(), th);
            finishWithTechnicalFailure(mainActivity, r4, "internal_exception", th.getMessage(), th);
        }
    }

    /* JADX INFO: Access modifiers changed from: package-private */
    public static /* synthetic */ void lambda$scheduleCmStuckTimeout$5(int r2) {
        if (!isActiveAttempt(r2) || legacyTakenOverAttempt == r2) {
            return;
        }
        cancelActiveCmSignal();
        Log.w(TAG, "[G-A] Credential Manager stuck after UI shown — switching to legacy fallback");
        logFunnel("cm_stuck_after_ui", null);
        startLegacyFallback(r2, "cm_stuck_after_ui");
    }

    /* JADX INFO: Access modifiers changed from: package-private */
    public static /* synthetic */ void lambda$scheduleCmTimeout$4(int r3) {
        if (!isActiveAttempt(r3) || legacyTakenOverAttempt == r3) {
            return;
        }
        if (cmUiShown) {
            Log.w(TAG, "[G-A] Credential Manager timeout but UI was shown — waiting longer");
            logFunnel("cm_timeout_ui_shown", null);
            scheduleCmStuckTimeout(r3);
        } else {
            cancelActiveCmSignal();
            Log.w(TAG, "[G-A] Credential Manager timeout, no UI shown — switching to legacy fallback");
            logFunnel("cm_timeout_no_ui", null);
            startLegacyFallback(r3, "cm_timeout_no_ui");
        }
    }

    /* JADX INFO: Access modifiers changed from: package-private */
    public static /* synthetic */ void lambda$scheduleLegacyTimeout$6(int r4) {
        if (isActiveAttempt(r4)) {
            finishWithTechnicalFailure(MainActivity.getActivity(), r4, "legacy_timeout", "Legacy sign-in did not return before timeout", null);
        }
    }

    /* JADX INFO: Access modifiers changed from: package-private */
    public static /* synthetic */ void lambda$showCredentialErrorDialog$8(MainActivity mainActivity, String str, String str2) {
        try {
            new AlertDialog.Builder(mainActivity).setCancelable(true).setTitle(str).setMessage(str2).setPositiveButton("Tamam", new DialogInterface.OnClickListener() { // from class: net.blocklegends.game.GoogleSign.3
                @Override // android.content.DialogInterface.OnClickListener
                public void onClick(DialogInterface dialogInterface, int r2) {
                }
            }).setIcon(android.R.drawable.ic_dialog_alert).show();
        } catch (Throwable th) {
            Log.e(TAG, "[G-A] showCredentialErrorDialog failed: " + th.getMessage(), th);
        }
    }

    /* JADX INFO: Access modifiers changed from: package-private */
    public static /* synthetic */ void lambda$signOut$2(MainActivity mainActivity) {
        try {
            CredentialManager.create(mainActivity).clearCredentialStateAsync(new ClearCredentialStateRequest(), new CancellationSignal(), ContextCompat.getMainExecutor(mainActivity), new CredentialManagerCallback<Void, ClearCredentialException>() { // from class: net.blocklegends.game.GoogleSign.2
                @Override // androidx.credentials.CredentialManagerCallback
                public void onError(ClearCredentialException clearCredentialException) {
                    Log.w(GoogleSign.TAG, "[G-A] clear credential state failed: " + clearCredentialException.getClass().getSimpleName());
                }

                @Override // androidx.credentials.CredentialManagerCallback
                public void onResult(Void r1) {
                    Log.i(GoogleSign.TAG, "[G-A] credential state cleared");
                }
            });
        } catch (Throwable th) {
            Log.w(TAG, "[G-A] signOut failed: " + th.getClass().getSimpleName());
        }
        try {
            GoogleSignIn.getClient((Activity) mainActivity, new GoogleSignInOptions.Builder(GoogleSignInOptions.DEFAULT_SIGN_IN).build()).signOut();
        } catch (Throwable th2) {
            Log.w(TAG, "[G-A] legacy signOut failed: " + th2.getClass().getSimpleName());
        }
    }

    /* JADX INFO: Access modifiers changed from: package-private */
    public static /* synthetic */ void lambda$startLegacyFallback$1(int r3, MainActivity mainActivity) {
        if (isActiveAttempt(r3)) {
            try {
                GoogleSignInClient client = GoogleSignIn.getClient((Activity) mainActivity, new GoogleSignInOptions.Builder(GoogleSignInOptions.DEFAULT_SIGN_IN).requestIdToken(mainActivity.getString(R.string.default_web_client_id)).requestEmail().build());
                scheduleLegacyTimeout(r3);
                mainActivity.startActivityForResult(client.getSignInIntent(), REQUEST_CODE_GOOGLE_LEGACY);
            } catch (Throwable th) {
                Log.e(TAG, "[G-A] legacy fallback start failed: " + th.getMessage(), th);
                finishWithTechnicalFailure(mainActivity, r3, "legacy_start_exception", th.getMessage(), th);
                showCredentialErrorDialog(mainActivity, "other");
            }
        }
    }

    /* JADX INFO: Access modifiers changed from: private */
    public static void logFunnel(final String str, final String str2) {
        final int r0 = Build.VERSION.SDK_INT;
        try {
            TELEMETRY_EXEC.execute(new Runnable() { // from class: net.blocklegends.game.GoogleSign$$ExternalSyntheticLambda4
                @Override // java.lang.Runnable
                public final void run() {
                    GoogleSign.lambda$logFunnel$7(str, str2, r0);
                }
            });
        } catch (Throwable th) {
            Log.w(TAG, "[G-A] logFunnel dispatch failed: " + th.getClass().getSimpleName());
        }
    }

    private static void notifyEmptyToken() {
        try {
            GNatives.onTokenReceived("", "");
        } catch (Throwable th) {
            Log.e(TAG, "[G-A] empty token callback failed: " + th.getMessage(), th);
        }
    }

    private static void notifyGoogleSignFailed(String str, String str2) {
        if (str == null) {
            str = "other";
        }
        if (str2 == null) {
            str2 = "";
        }
        try {
            GNatives.onGoogleSignFailed(str, str2);
        } catch (Throwable th) {
            Log.e(TAG, "[G-A] google failure callback failed: " + th.getMessage(), th);
            notifyEmptyToken();
        }
    }

    public static void onActivityResult(int r7, int r8, Intent intent) {
        if (r7 != REQUEST_CODE_GOOGLE_LEGACY) {
            return;
        }
        int r72 = activeSignInAttempt;
        if (!isActiveAttempt(r72)) {
            Log.w(TAG, "[G-A] legacy onActivityResult stale, attempt=" + r72 + ", active=" + activeSignInAttempt);
            return;
        }
        MainActivity activity = MainActivity.getActivity();
        try {
            GoogleSignInAccount result = GoogleSignIn.getSignedInAccountFromIntent(intent).getResult(ApiException.class);
            String idToken = result != null ? result.getIdToken() : null;
            if (idToken != null && !idToken.isEmpty()) {
                Log.i(TAG, "[G-A] legacy id token received - length=" + idToken.length());
                logFunnel("legacy_result", FirebaseAnalytics.Param.SUCCESS);
                finishWithToken(r72, idToken, true);
                return;
            }
            Log.w(TAG, "[G-A] legacy id token empty");
            logFunnel("legacy_error", "empty_id_token");
            finishWithTechnicalFailure(activity, r72, "legacy_empty_token", "Legacy id token is empty", null);
            showCredentialErrorDialog(activity, "other");
        } catch (ApiException e) {
            int statusCode = e.getStatusCode();
            if (statusCode == 12501 || statusCode == 16) {
                Log.w(TAG, "[G-A] legacy sign-in cancelled, code=" + statusCode);
                logFunnel("legacy_cancelled", "code=" + statusCode);
                finishWithCancel(r72);
            } else {
                Log.e(TAG, "[G-A] legacy ApiException code=" + statusCode + " -> " + e.getMessage(), e);
                logFunnel("legacy_error", "api_code=" + statusCode);
                finishWithTechnicalFailure(activity, r72, "legacy_api_error", "code=" + statusCode, e);
                showCredentialErrorDialog(activity, "other");
            }
        } catch (Throwable th) {
            Log.e(TAG, "[G-A] legacy onActivityResult exception: " + th.getMessage(), th);
            logFunnel("legacy_error", th.getClass().getSimpleName());
            finishWithTechnicalFailure(activity, r72, "legacy_exception", th.getMessage(), th);
            showCredentialErrorDialog(activity, "other");
        }
    }

    public static void onHostActivityPaused() {
        int r0 = activeSignInAttempt;
        if (r0 == -1 || legacyTakenOverAttempt == r0) {
            return;
        }
        cmUiShown = true;
    }

    public static void onHostActivityResumed() {
        final int r0 = activeSignInAttempt;
        if (r0 == -1 || legacyTakenOverAttempt == r0 || !cmUiShown) {
            return;
        }
        MAIN_HANDLER.postDelayed(new Runnable() { // from class: net.blocklegends.game.GoogleSign$$ExternalSyntheticLambda0
            @Override // java.lang.Runnable
            public final void run() {
                GoogleSign.lambda$onHostActivityResumed$3(r0);
            }
        }, CM_RESUME_GRACE_MS);
    }

    public static void openLogin() {
        Log.i(TAG, "[G-A] openLogin called, thread=" + Thread.currentThread().getName());
        final MainActivity activity = MainActivity.getActivity();
        if (activity == null) {
            Log.e(TAG, "[G-A] openLogin aborted: MainActivity is null");
            notifyEmptyToken();
            return;
        }
        AtomicBoolean atomicBoolean = signInInProgress;
        if (!atomicBoolean.compareAndSet(false, true)) {
            long j = activeSignInStartedAt;
            long currentTimeMillis = System.currentTimeMillis();
            if (j > 0) {
                long j2 = currentTimeMillis - j;
                if (j2 > STALE_LOCK_MS) {
                    Log.w(TAG, "[G-A] stale sign-in lock detected (age=" + j2 + "ms), forcing reset");
                    logFunnel("debounce_skipped", "stale_reset");
                    forceResetActiveAttempt();
                    if (!atomicBoolean.compareAndSet(false, true)) {
                        logFunnel("debounce_skipped", "stale_reset_failed");
                        return;
                    }
                }
            }
            Log.w(TAG, "[G-A] openLogin ignored: sign-in already in progress");
            logFunnel("debounce_skipped", "in_progress");
            return;
        }
        logFunnel("open_login", null);
        final int beginAttempt = beginAttempt(createNonce());
        activity.runOnUiThread(new Runnable() { // from class: net.blocklegends.game.GoogleSign$$ExternalSyntheticLambda8
            @Override // java.lang.Runnable
            public final void run() {
                GoogleSign.lambda$openLogin$0(beginAttempt, activity);
            }
        });
    }

    private static void openLoginInternal(final MainActivity mainActivity, final int r10) {
        if (isActivityUnavailable(mainActivity)) {
            finishWithTechnicalFailure(mainActivity, r10, "activity_unavailable", "Activity is finishing or destroyed", null);
            return;
        }
        String str = activeNonce;
        if (str == null || str.isEmpty()) {
            finishWithTechnicalFailure(mainActivity, r10, "missing_nonce", "Google auth nonce is missing", null);
            return;
        }
        CredentialManager create = CredentialManager.create(mainActivity);
        GetSignInWithGoogleOption buildGoogleOption = buildGoogleOption(mainActivity, str);
        CredentialManagerCallback<GetCredentialResponse, GetCredentialException> credentialManagerCallback = new CredentialManagerCallback<GetCredentialResponse, GetCredentialException>() { // from class: net.blocklegends.game.GoogleSign.1
            @Override // androidx.credentials.CredentialManagerCallback
            public void onError(GetCredentialException getCredentialException) {
                if (GoogleSign.legacyTakenOverAttempt == r10) {
                    Log.w(GoogleSign.TAG, "[G-A] onError ignored, legacy already took over attempt=" + r10);
                    return;
                }
                String classifyCredentialError = GoogleSign.classifyCredentialError(getCredentialException);
                if (!GoogleSign.isActiveAttempt(r10)) {
                    Log.w(GoogleSign.TAG, "[G-A] stale onError ignored, attempt=" + r10 + ", active=" + GoogleSign.activeSignInAttempt);
                    return;
                }
                Log.e(GoogleSign.TAG, "[G-A] onError: " + classifyCredentialError + " / " + getCredentialException.getClass().getSimpleName() + " -> " + getCredentialException.getMessage(), getCredentialException);
                GoogleSign.logFunnel("cm_error", classifyCredentialError);
                try {
                    if (!GoogleSign.isUserDismissal(classifyCredentialError)) {
                        GoogleSign.startLegacyFallback(r10, "cm_error:" + classifyCredentialError);
                    } else if (GoogleSign.cmUiShown) {
                        GoogleSign.finishWithCancel(r10);
                    } else {
                        Log.w(GoogleSign.TAG, "[G-A] phantom cancellation (no UI shown) — switching to legacy fallback");
                        GoogleSign.startLegacyFallback(r10, "cm_phantom_cancel");
                    }
                } catch (Throwable th) {
                    Log.e(GoogleSign.TAG, "[G-A] onError handler exception: " + th.getMessage(), th);
                    GoogleSign.finishWithTechnicalFailure(mainActivity, r10, "error_handler_exception", th.getMessage(), th);
                }
            }

            @Override // androidx.credentials.CredentialManagerCallback
            public void onResult(GetCredentialResponse getCredentialResponse) {
                int r5 = GoogleSign.legacyTakenOverAttempt;
                int r6 = r10;
                if (r5 == r6) {
                    Log.w(GoogleSign.TAG, "[G-A] onResult ignored, legacy already took over attempt=" + r10);
                    return;
                }
                boolean isActiveAttempt = GoogleSign.isActiveAttempt(r6);
                int r62 = r10;
                if (!isActiveAttempt) {
                    Log.w(GoogleSign.TAG, "[G-A] stale onResult ignored, attempt=" + r62 + ", active=" + GoogleSign.activeSignInAttempt);
                    return;
                }
                Log.i(GoogleSign.TAG, "[G-A] onResult called, attempt=" + r62 + ", thread=" + Thread.currentThread().getName());
                try {
                    if (getCredentialResponse == null) {
                        Log.w(GoogleSign.TAG, "[G-A] result is null");
                        GoogleSign.finishWithTechnicalFailure(mainActivity, r10, "empty_result", "Credential result is null", null);
                        return;
                    }
                    Credential credential = getCredentialResponse.getCredential();
                    Log.i(GoogleSign.TAG, "[G-A] credential class: " + credential.getClass().getName() + ", type: " + credential.getType());
                    boolean z = credential instanceof GoogleIdTokenCredential;
                    Object obj = AbstractJsonLexerKt.NULL;
                    if (z) {
                        String zzb = ((GoogleIdTokenCredential) credential).getZzb();
                        StringBuilder sb = new StringBuilder("[G-A] GoogleIdTokenCredential direct - token length=");
                        if (zzb != null) {
                            obj = Integer.valueOf(zzb.length());
                        }
                        Log.i(GoogleSign.TAG, sb.append(obj).toString());
                        GoogleSign.logFunnel("cm_result", FirebaseAnalytics.Param.SUCCESS);
                        GoogleSign.finishWithToken(r10, zzb, false);
                        return;
                    }
                    if (!(credential instanceof CustomCredential)) {
                        Log.w(GoogleSign.TAG, "[G-A] Unexpected credential class: " + credential.getClass().getName());
                        GoogleSign.finishWithTechnicalFailure(mainActivity, r10, "unexpected_credential_class", credential.getClass().getName(), null);
                        return;
                    }
                    if (!GoogleIdTokenCredential.TYPE_GOOGLE_ID_TOKEN_CREDENTIAL.equals(credential.getType())) {
                        Log.w(GoogleSign.TAG, "[G-A] Unexpected credential type: " + credential.getType());
                        GoogleSign.finishWithTechnicalFailure(mainActivity, r10, "unexpected_credential_type", credential.getType(), null);
                        return;
                    }
                    String zzb2 = GoogleIdTokenCredential.createFrom(credential.getData()).getZzb();
                    StringBuilder sb2 = new StringBuilder("[G-A] CustomCredential -> GoogleIdToken - token length=");
                    if (zzb2 != null) {
                        obj = Integer.valueOf(zzb2.length());
                    }
                    Log.i(GoogleSign.TAG, sb2.append(obj).toString());
                    GoogleSign.logFunnel("cm_result", FirebaseAnalytics.Param.SUCCESS);
                    GoogleSign.finishWithToken(r10, zzb2, false);
                } catch (Throwable th) {
                    Log.e(GoogleSign.TAG, "[G-A] onResult exception: " + th.getMessage(), th);
                    GoogleSign.finishWithTechnicalFailure(mainActivity, r10, "result_exception", th.getMessage(), th);
                }
            }
        };
        CancellationSignal cancellationSignal = new CancellationSignal();
        activeCancellationSignal = cancellationSignal;
        logFunnel("cm_dispatched", null);
        create.getCredentialAsync(mainActivity, new GetCredentialRequest.Builder().addCredentialOption(buildGoogleOption).build(), cancellationSignal, ContextCompat.getMainExecutor(mainActivity), credentialManagerCallback);
    }

    private static String packageVersion(MainActivity mainActivity, String str) {
        if (mainActivity == null || str == null) {
            return EnvironmentCompat.MEDIA_UNKNOWN;
        }
        try {
            PackageInfo packageInfo = mainActivity.getPackageManager().getPackageInfo(str, 0);
            return sanitizeMessage(packageInfo.versionName) + "(" + packageInfo.getLongVersionCode() + ")";
        } catch (Throwable unused) {
            return "missing";
        }
    }

    private static String playStoreVersion(MainActivity mainActivity) {
        String str = cachedPlayStoreVersion;
        if (str != null) {
            return str;
        }
        String packageVersion = packageVersion(mainActivity, "com.android.vending");
        cachedPlayStoreVersion = packageVersion;
        return packageVersion;
    }

    private static String sanitizeMessage(String str) {
        if (str == null) {
            return "";
        }
        String trim = str.replace('\n', ' ').replace('\r', ' ').replace('|', '/').trim();
        return trim.length() > 180 ? trim.substring(0, 180) : trim;
    }

    private static void scheduleCmStuckTimeout(final int r3) {
        Runnable runnable = new Runnable() { // from class: net.blocklegends.game.GoogleSign$$ExternalSyntheticLambda3
            @Override // java.lang.Runnable
            public final void run() {
                GoogleSign.lambda$scheduleCmStuckTimeout$5(r3);
            }
        };
        activeTimeoutRunnable = runnable;
        MAIN_HANDLER.postDelayed(runnable, 30000L);
    }

    private static void scheduleCmTimeout(final int r3) {
        Runnable runnable = new Runnable() { // from class: net.blocklegends.game.GoogleSign$$ExternalSyntheticLambda7
            @Override // java.lang.Runnable
            public final void run() {
                GoogleSign.lambda$scheduleCmTimeout$4(r3);
            }
        };
        activeTimeoutRunnable = runnable;
        MAIN_HANDLER.postDelayed(runnable, CM_TIMEOUT_MS);
    }

    private static void scheduleLegacyTimeout(final int r3) {
        Runnable runnable = new Runnable() { // from class: net.blocklegends.game.GoogleSign$$ExternalSyntheticLambda1
            @Override // java.lang.Runnable
            public final void run() {
                GoogleSign.lambda$scheduleLegacyTimeout$6(r3);
            }
        };
        activeTimeoutRunnable = runnable;
        MAIN_HANDLER.postDelayed(runnable, LEGACY_TIMEOUT_MS);
    }

    private static void showCredentialErrorDialog(final MainActivity mainActivity, String str) {
        final String str2;
        final String str3;
        if (mainActivity == null || mainActivity.isFinishing() || isUserDismissal(str)) {
            return;
        }
        if ("temporarily_blocked".equals(str)) {
            str2 = "Çok fazla giriş ekranı kapatıldı!";
            str3 = "Giriş ekranını çok fazla açıp kapattığınız için geçici süreliğine erişiminiz engellendi.";
        } else if ("sync_account_unavailable".equals(str)) {
            str2 = "Google hesabı doğrulanamadı.";
            str3 = "Google hesabınız cihazda yeniden doğrulama istiyor olabilir. Google Play, Google Play Services ve Google hesabınızı kontrol edip tekrar deneyin.";
        } else if ("no_credential".equals(str)) {
            str2 = "Google hesabı bulunamadı.";
            str3 = "Bu cihazda Google ile giriş için uygun bir hesap bulunamadı. Google Play hesabınızı kontrol edip tekrar deneyin.";
        } else if ("provider_configuration".equals(str) || "unsupported".equals(str)) {
            str2 = "Google Play güncellemesi gerekiyor.";
            str3 = "Google Play ve Google Play Services uygulamalarını güncelleyin, ardından Google hesabınızla tekrar giriş yapmayı deneyin.";
        } else {
            str2 = "Google girişi başlatılamadı.";
            str3 = "Google Play bağlantısı şu anda giriş ekranını açamadı. Google Play Services durumunu kontrol edip tekrar deneyin.";
        }
        mainActivity.runOnUiThread(new Runnable() { // from class: net.blocklegends.game.GoogleSign$$ExternalSyntheticLambda2
            @Override // java.lang.Runnable
            public final void run() {
                GoogleSign.lambda$showCredentialErrorDialog$8(MainActivity.this, str2, str3);
            }
        });
    }

    public static void signOut() {
        final MainActivity activity = MainActivity.getActivity();
        if (isActivityUnavailable(activity)) {
            return;
        }
        activity.runOnUiThread(new Runnable() { // from class: net.blocklegends.game.GoogleSign$$ExternalSyntheticLambda5
            @Override // java.lang.Runnable
            public final void run() {
                GoogleSign.lambda$signOut$2(MainActivity.this);
            }
        });
    }

    /* JADX INFO: Access modifiers changed from: private */
    public static void startLegacyFallback(final int r3, String str) {
        if (!isActiveAttempt(r3)) {
            Log.w(TAG, "[G-A] legacy fallback stale, attempt=" + r3);
            return;
        }
        final MainActivity activity = MainActivity.getActivity();
        if (isActivityUnavailable(activity)) {
            finishWithTechnicalFailure(activity, r3, "activity_unavailable", "Activity unavailable for legacy fallback", null);
            return;
        }
        legacyTakenOverAttempt = r3;
        cancelActiveTimeout();
        cancelActiveCmSignal();
        logFunnel("legacy_dispatched", str);
        activity.runOnUiThread(new Runnable() { // from class: net.blocklegends.game.GoogleSign$$ExternalSyntheticLambda6
            @Override // java.lang.Runnable
            public final void run() {
                GoogleSign.lambda$startLegacyFallback$1(r3, activity);
            }
        });
    }
}
