package net.blocklegends.game;

import android.content.Intent;
import android.content.SharedPreferences;
import android.net.Uri;
import android.os.Build;
import android.os.Handler;
import android.os.Looper;
import android.util.Base64;
import android.util.Log;
import androidx.browser.customtabs.CustomTabsClient;
import androidx.browser.customtabs.CustomTabsIntent;
import androidx.browser.trusted.sharing.ShareTarget;
import com.google.common.net.HttpHeaders;
import com.google.firebase.messaging.Constants;
import com.tiktok.appevents.edp.TTEDPEventConstants;
import java.io.BufferedReader;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.security.SecureRandom;
import java.util.concurrent.atomic.AtomicInteger;
import kotlin.text.Typography;
import kotlinx.serialization.json.internal.AbstractJsonLexerKt;
import net.blocklegends.MainActivity;
import net.blocklegends.natives.GNatives;
import org.json.JSONObject;

/* loaded from: classes4.dex */
public class AppleSign {
    private static final String APPLE_AUTH_URL = "https://appleid.apple.com/auth/authorize";
    private static final long AUTH_TIMEOUT_MS = 120000;
    private static final long BROWSER_RETURN_GRACE_MS = 1500;
    private static final String CALLBACK_HOST = "apple-callback";
    private static final String CALLBACK_HTTPS_HOST = "blocklegends.net";
    private static final String CALLBACK_HTTPS_SCHEME = "https";
    private static final String CALLBACK_PATH = "/apple-callback";
    private static final String CALLBACK_SCHEME = "blocklegends";
    private static final String CLIENT_ID = "net.blocklegends.applesignin";
    private static final int HTTP_TIMEOUT_MS = 10000;
    private static final String PREFS_NAME = "apple_sign_state";
    private static final String PREF_NONCE = "nonce";
    private static final String PREF_STATE = "state";
    private static final String REDIRECT_URI = "https://blocklegends.net/apple";
    public static final int REQUEST_CODE_APPLE_SIGN_IN = 9001;
    private static final String STATE_PREFIX = "bl2_";
    private static final String TAG = "AppleSign";
    private static final Handler MAIN = new Handler(Looper.getMainLooper());
    private static final SecureRandom SECURE_RANDOM = new SecureRandom();
    private static final AtomicInteger signInAttemptCounter = new AtomicInteger();
    private static volatile String currentState = null;
    private static volatile String currentNonce = null;
    private static volatile boolean isLoginInProgress = false;
    private static volatile boolean browserFlowActive = false;
    private static volatile boolean callbackDelivered = false;
    private static volatile int activeSignInAttempt = 0;
    private static volatile long activeSignInStartedAt = 0;
    private static volatile Runnable activeTimeoutRunnable = null;

    /* JADX INFO: Access modifiers changed from: private */
    /* loaded from: classes4.dex */
    public static class ConsumeResult {
        final String authCode;
        final String detail;
        final String errorType;
        final String idToken;
        final boolean ok;

        private ConsumeResult(boolean z, String str, String str2, String str3, String str4) {
            this.ok = z;
            this.idToken = str;
            this.authCode = str2;
            this.errorType = str3;
            this.detail = str4;
        }

        static ConsumeResult error(String str, String str2) {
            if (str == null || str.isEmpty()) {
                str = "consume_error";
            }
            String str3 = str;
            if (str2 == null) {
                str2 = "";
            }
            return new ConsumeResult(false, null, null, str3, str2);
        }

        static ConsumeResult success(String str, String str2) {
            return new ConsumeResult(true, str, str2, null, null);
        }
    }

    private static int beginAttempt(MainActivity mainActivity) {
        currentState = STATE_PREFIX + createRandomToken(24);
        currentNonce = createRandomToken(32);
        isLoginInProgress = true;
        browserFlowActive = false;
        callbackDelivered = false;
        activeSignInStartedAt = System.currentTimeMillis();
        activeSignInAttempt = signInAttemptCounter.incrementAndGet();
        MainActivity.beginExternalUi(MainActivity.EXTERNAL_UI_APPLE_SIGN);
        saveState(mainActivity);
        scheduleTimeout(activeSignInAttempt);
        return activeSignInAttempt;
    }

    private static String buildAuthUrl() {
        return Uri.parse(APPLE_AUTH_URL).buildUpon().appendQueryParameter("response_type", "code id_token").appendQueryParameter("response_mode", "form_post").appendQueryParameter("client_id", CLIENT_ID).appendQueryParameter(AppleSignActivity.EXTRA_REDIRECT_URI, REDIRECT_URI).appendQueryParameter("scope", "name email").appendQueryParameter(PREF_STATE, currentState).appendQueryParameter(PREF_NONCE, currentNonce).build().toString();
    }

    private static String buildForm(String... strArr) throws Exception {
        StringBuilder sb = new StringBuilder();
        for (int r1 = 0; r1 < strArr.length; r1 += 2) {
            if (r1 > 0) {
                sb.append(Typography.amp);
            }
            sb.append(URLEncoder.encode(strArr[r1], "UTF-8"));
            sb.append('=');
            String str = strArr[r1 + 1];
            if (str == null) {
                str = "";
            }
            sb.append(URLEncoder.encode(str, "UTF-8"));
        }
        return sb.toString();
    }

    private static String buildTelemetry(String str, String str2) {
        StringBuilder sb = new StringBuilder("type=");
        sb.append(sanitize(str));
        sb.append(";elapsed_ms=").append(activeSignInStartedAt == 0 ? -1L : System.currentTimeMillis() - activeSignInStartedAt);
        sb.append(";sdk=").append(Build.VERSION.SDK_INT);
        sb.append(";manufacturer=").append(sanitize(Build.MANUFACTURER));
        sb.append(";model=").append(sanitize(Build.MODEL));
        MainActivity activity = MainActivity.getActivity();
        if (activity != null) {
            try {
                sb.append(";custom_tabs_provider=").append(sanitize(CustomTabsClient.getPackageName(activity, null)));
            } catch (Throwable unused) {
                sb.append(";custom_tabs_provider=");
            }
        }
        if (str2 != null && !str2.isEmpty()) {
            sb.append(";detail=").append(sanitize(str2));
        }
        return sb.toString();
    }

    private static void cancelTimeout() {
        if (activeTimeoutRunnable != null) {
            MAIN.removeCallbacks(activeTimeoutRunnable);
            activeTimeoutRunnable = null;
        }
    }

    private static void clearAttempt() {
        cancelTimeout();
        isLoginInProgress = false;
        browserFlowActive = false;
        callbackDelivered = false;
        activeSignInAttempt = 0;
        activeSignInStartedAt = 0L;
        currentState = null;
        currentNonce = null;
        MainActivity.endExternalUi(MainActivity.EXTERNAL_UI_APPLE_SIGN);
        MainActivity activity = MainActivity.getActivity();
        if (activity != null) {
            getPrefs(activity).edit().clear().apply();
        }
    }

    private static void consumeCallback(final int r4, final String str) {
        final String str2 = currentState;
        final String str3 = currentNonce;
        if (str2 == null || str2.isEmpty() || str3 == null || str3.isEmpty()) {
            finishWithTechnicalFailure(r4, "missing_state_or_nonce", buildTelemetry("missing_state_or_nonce", ""));
        } else {
            new Thread(new Runnable() { // from class: net.blocklegends.game.AppleSign$$ExternalSyntheticLambda2
                @Override // java.lang.Runnable
                public final void run() {
                    AppleSign.lambda$consumeCallback$5(str, str2, str3, r4);
                }
            }, "AppleSign-Consume").start();
        }
    }

    private static ConsumeResult consumeCallbackBlocking(String str, String str2, String str3) {
        HttpURLConnection httpURLConnection = null;
        JSONObject jSONObject = null;
        try {
            HttpURLConnection httpURLConnection2 = (HttpURLConnection) new URL(REDIRECT_URI).openConnection();
            try {
                httpURLConnection2.setRequestMethod(ShareTarget.METHOD_POST);
                httpURLConnection2.setConnectTimeout(HTTP_TIMEOUT_MS);
                httpURLConnection2.setReadTimeout(HTTP_TIMEOUT_MS);
                httpURLConnection2.setDoOutput(true);
                httpURLConnection2.setRequestProperty(HttpHeaders.CONTENT_TYPE, "application/x-www-form-urlencoded; charset=UTF-8");
                httpURLConnection2.setRequestProperty(HttpHeaders.ACCEPT, "application/json");
                byte[] bytes = buildForm("action", "consume", "callback_id", str, PREF_STATE, str2, PREF_NONCE, str3).getBytes(StandardCharsets.UTF_8);
                httpURLConnection2.setFixedLengthStreamingMode(bytes.length);
                OutputStream outputStream = httpURLConnection2.getOutputStream();
                try {
                    outputStream.write(bytes);
                    if (outputStream != null) {
                        outputStream.close();
                    }
                    int responseCode = httpURLConnection2.getResponseCode();
                    String readStream = readStream((responseCode < 200 || responseCode >= 300) ? httpURLConnection2.getErrorStream() : httpURLConnection2.getInputStream());
                    if (!readStream.isEmpty()) {
                        jSONObject = new JSONObject(readStream);
                    }
                    if (responseCode < 200 || responseCode >= 300) {
                        ConsumeResult error = ConsumeResult.error(jSONObject != null ? jSONObject.optString(Constants.IPC_BUNDLE_KEY_SEND_ERROR, "consume_http_error") : "consume_http_error", "http=" + responseCode);
                        if (httpURLConnection2 != null) {
                            httpURLConnection2.disconnect();
                        }
                        return error;
                    }
                    if (jSONObject == null) {
                        ConsumeResult error2 = ConsumeResult.error("consume_empty_response", "");
                        if (httpURLConnection2 != null) {
                            httpURLConnection2.disconnect();
                        }
                        return error2;
                    }
                    if (!jSONObject.optBoolean("ok", false)) {
                        ConsumeResult error3 = ConsumeResult.error(jSONObject.optString(Constants.IPC_BUNDLE_KEY_SEND_ERROR, "consume_rejected"), "");
                        if (httpURLConnection2 != null) {
                            httpURLConnection2.disconnect();
                        }
                        return error3;
                    }
                    String optString = jSONObject.optString(TTEDPEventConstants.EDP_EVENT_PROPERTY_PAY_CODE, "");
                    String optString2 = jSONObject.optString("id_token", "");
                    if (!optString.isEmpty() && !optString2.isEmpty()) {
                        ConsumeResult success = ConsumeResult.success(optString2, optString);
                        if (httpURLConnection2 != null) {
                            httpURLConnection2.disconnect();
                        }
                        return success;
                    }
                    ConsumeResult error4 = ConsumeResult.error("consume_missing_token", "");
                    if (httpURLConnection2 != null) {
                        httpURLConnection2.disconnect();
                    }
                    return error4;
                } finally {
                }
            } catch (Throwable th) {
                th = th;
                httpURLConnection = httpURLConnection2;
                try {
                    return ConsumeResult.error("consume_exception", th.getClass().getSimpleName());
                } finally {
                    if (httpURLConnection != null) {
                        httpURLConnection.disconnect();
                    }
                }
            }
        } catch (Throwable th2) {
            th = th2;
        }
    }

    private static String createRandomToken(int r1) {
        byte[] bArr = new byte[r1];
        SECURE_RANDOM.nextBytes(bArr);
        return Base64.encodeToString(bArr, 11);
    }

    private static int ensureAttemptForCallback(MainActivity mainActivity) {
        if (activeSignInAttempt != 0) {
            return activeSignInAttempt;
        }
        if (mainActivity == null) {
            return 0;
        }
        SharedPreferences prefs = getPrefs(mainActivity);
        currentState = prefs.getString(PREF_STATE, null);
        currentNonce = prefs.getString(PREF_NONCE, null);
        if (currentState == null || currentState.isEmpty() || currentNonce == null || currentNonce.isEmpty()) {
            return 0;
        }
        isLoginInProgress = true;
        browserFlowActive = false;
        callbackDelivered = false;
        activeSignInStartedAt = System.currentTimeMillis();
        activeSignInAttempt = signInAttemptCounter.incrementAndGet();
        MainActivity.beginExternalUi(MainActivity.EXTERNAL_UI_APPLE_SIGN);
        scheduleTimeout(activeSignInAttempt);
        return activeSignInAttempt;
    }

    private static void finishSuccess(int r0) {
        if (isActiveAttempt(r0)) {
            clearAttempt();
        }
    }

    private static void finishWithCancel(int r1, String str) {
        if (isActiveAttempt(r1)) {
            Log.i(TAG, "Apple Sign-In cancelled: " + str);
            clearAttempt();
            notifyEmptyTokenReceived();
        }
    }

    private static void finishWithTechnicalFailure(int r1, String str, String str2) {
        if (r1 == 0 || isActiveAttempt(r1)) {
            Log.e(TAG, "Apple Sign-In failed: " + str + " " + str2);
            clearAttempt();
            notifyAppleSignFailed(str, str2);
        }
    }

    private static void finishWithToken(int r1, String str, String str2) {
        if (isActiveAttempt(r1)) {
            try {
                GNatives.onTokenReceived(str, str2);
                finishSuccess(r1);
            } catch (Throwable th) {
                Log.e(TAG, "native token delivery failed: " + th.getMessage(), th);
                finishWithTechnicalFailure(r1, "native_token_delivery_failed", buildTelemetry("native_token_delivery_failed", th.getMessage()));
            }
        }
    }

    public static String getCurrentState() {
        return currentState;
    }

    private static SharedPreferences getPrefs(MainActivity mainActivity) {
        return mainActivity.getSharedPreferences(PREFS_NAME, 0);
    }

    public static void handleCallback(final Uri uri) {
        if (!isMainThread()) {
            MAIN.post(new Runnable() { // from class: net.blocklegends.game.AppleSign$$ExternalSyntheticLambda3
                @Override // java.lang.Runnable
                public final void run() {
                    AppleSign.handleCallback(uri);
                }
            });
            return;
        }
        MainActivity activity = MainActivity.getActivity();
        int ensureAttemptForCallback = ensureAttemptForCallback(activity);
        try {
            if (!isActiveAttempt(ensureAttemptForCallback)) {
                Log.w(TAG, "Ignoring stale Apple callback");
                return;
            }
            if (uri == null) {
                finishWithTechnicalFailure(ensureAttemptForCallback, "null_callback", buildTelemetry("null_callback", ""));
                return;
            }
            String queryParameter = uri.getQueryParameter("callback_id");
            String queryParameter2 = uri.getQueryParameter(PREF_STATE);
            String queryParameter3 = uri.getQueryParameter(Constants.IPC_BUNDLE_KEY_SEND_ERROR);
            String str = currentState;
            if ((str == null || str.isEmpty()) && activity != null) {
                str = getPrefs(activity).getString(PREF_STATE, null);
            }
            boolean z = true;
            if (str != null && !str.isEmpty()) {
                if (!str.equals(queryParameter2)) {
                    Log.w(TAG, "Ignoring Apple callback with mismatched state");
                    return;
                }
                callbackDelivered = true;
                if (queryParameter3 != null && !queryParameter3.isEmpty()) {
                    if ("user_cancelled_authorize".equals(queryParameter3)) {
                        finishWithCancel(ensureAttemptForCallback, "user_cancelled_authorize");
                        return;
                    } else {
                        finishWithTechnicalFailure(ensureAttemptForCallback, "apple_callback_error", buildTelemetry("apple_callback_error", queryParameter3));
                        return;
                    }
                }
                if (queryParameter == null || queryParameter.isEmpty()) {
                    finishWithTechnicalFailure(ensureAttemptForCallback, "missing_callback_id", buildTelemetry("missing_callback_id", "callback_id_present=false"));
                    return;
                } else {
                    consumeCallback(ensureAttemptForCallback, queryParameter);
                    return;
                }
            }
            StringBuilder sb = new StringBuilder("expected_present=false;state_present=");
            if (queryParameter2 == null) {
                z = false;
            }
            finishWithTechnicalFailure(ensureAttemptForCallback, "state_mismatch", buildTelemetry("state_mismatch", sb.append(z).toString()));
        } catch (Throwable th) {
            Log.e(TAG, "Exception in handleCallback: " + th.getMessage(), th);
            finishWithTechnicalFailure(ensureAttemptForCallback, "callback_exception", buildTelemetry("callback_exception", th.getMessage()));
        }
    }

    public static boolean handleCallbackFromIntent(Intent intent) {
        if (intent == null || intent.getData() == null) {
            return false;
        }
        final Uri data = intent.getData();
        if (!isCallbackUrl(data)) {
            return false;
        }
        if (isMainThread()) {
            handleCallback(data);
            return true;
        }
        MAIN.post(new Runnable() { // from class: net.blocklegends.game.AppleSign$$ExternalSyntheticLambda5
            @Override // java.lang.Runnable
            public final void run() {
                AppleSign.handleCallback(data);
            }
        });
        return true;
    }

    private static boolean isActiveAttempt(int r1) {
        return r1 != 0 && r1 == activeSignInAttempt;
    }

    private static boolean isCallbackUrl(Uri uri) {
        if (uri == null) {
            return false;
        }
        String scheme = uri.getScheme();
        String host = uri.getHost();
        if (CALLBACK_SCHEME.equals(scheme) && CALLBACK_HOST.equals(host)) {
            return true;
        }
        return "https".equals(scheme) && CALLBACK_HTTPS_HOST.equals(host) && CALLBACK_PATH.equals(uri.getPath());
    }

    public static boolean isLoginInProgress() {
        return isLoginInProgress;
    }

    private static boolean isMainThread() {
        return Looper.myLooper() == Looper.getMainLooper();
    }

    /* JADX INFO: Access modifiers changed from: package-private */
    public static /* synthetic */ void lambda$consumeCallback$4(int r2, ConsumeResult consumeResult) {
        if (isActiveAttempt(r2)) {
            if (consumeResult.ok) {
                finishWithToken(r2, consumeResult.idToken, consumeResult.authCode);
            } else {
                finishWithTechnicalFailure(r2, consumeResult.errorType, buildTelemetry(consumeResult.errorType, consumeResult.detail));
            }
        }
    }

    /* JADX INFO: Access modifiers changed from: package-private */
    public static /* synthetic */ void lambda$consumeCallback$5(String str, String str2, String str3, final int r3) {
        final ConsumeResult consumeCallbackBlocking = consumeCallbackBlocking(str, str2, str3);
        MAIN.post(new Runnable() { // from class: net.blocklegends.game.AppleSign$$ExternalSyntheticLambda6
            @Override // java.lang.Runnable
            public final void run() {
                AppleSign.lambda$consumeCallback$4(r3, consumeCallbackBlocking);
            }
        });
    }

    /* JADX INFO: Access modifiers changed from: package-private */
    public static /* synthetic */ void lambda$onHostActivityResumed$1(int r1) {
        if (isActiveAttempt(r1) && browserFlowActive && !callbackDelivered) {
            finishWithCancel(r1, "browser_returned_without_callback");
        }
    }

    /* JADX INFO: Access modifiers changed from: package-private */
    public static /* synthetic */ void lambda$scheduleTimeout$6(int r2) {
        if (!isActiveAttempt(r2) || callbackDelivered) {
            return;
        }
        finishWithTechnicalFailure(r2, "timeout", buildTelemetry("timeout", ""));
    }

    private static void notifyAppleSignFailed(String str, String str2) {
        if (str == null) {
            str = "other";
        }
        if (str2 == null) {
            str2 = "";
        }
        try {
            GNatives.onAppleSignFailed(str, str2);
        } catch (Throwable th) {
            Log.e(TAG, "apple failure callback failed: " + th.getMessage(), th);
            notifyEmptyTokenReceived();
        }
    }

    private static void notifyEmptyTokenReceived() {
        try {
            GNatives.onTokenReceived("", "");
        } catch (Throwable th) {
            Log.e(TAG, "empty token callback failed: " + th.getMessage(), th);
        }
    }

    public static void onActivityResult(final int r2, final int r3, final Intent intent) {
        if (!isMainThread()) {
            MAIN.post(new Runnable() { // from class: net.blocklegends.game.AppleSign$$ExternalSyntheticLambda7
                @Override // java.lang.Runnable
                public final void run() {
                    AppleSign.onActivityResult(r2, r3, intent);
                }
            });
            return;
        }
        if (r2 != 9001) {
            return;
        }
        int r22 = activeSignInAttempt;
        if (isActiveAttempt(r22)) {
            if (r3 != -1 || intent == null) {
                String stringExtra = intent != null ? intent.getStringExtra(Constants.IPC_BUNDLE_KEY_SEND_ERROR) : null;
                if ("User cancelled".equals(stringExtra)) {
                    finishWithCancel(r22, "user_cancelled");
                    return;
                } else {
                    finishWithTechnicalFailure(r22, "webview_error", buildTelemetry("webview_error", stringExtra));
                    return;
                }
            }
            Uri data = intent.getData();
            if (data != null) {
                handleCallback(data);
            } else {
                finishWithTechnicalFailure(r22, "missing_activity_result_uri", buildTelemetry("missing_activity_result_uri", ""));
            }
        }
    }

    public static void onHostActivityResumed() {
        if (!isMainThread()) {
            MAIN.post(new Runnable() { // from class: net.blocklegends.game.AppleSign$$ExternalSyntheticLambda0
                @Override // java.lang.Runnable
                public final void run() {
                    AppleSign.onHostActivityResumed();
                }
            });
            return;
        }
        final int r0 = activeSignInAttempt;
        if (isActiveAttempt(r0) && browserFlowActive && !callbackDelivered) {
            MAIN.postDelayed(new Runnable() { // from class: net.blocklegends.game.AppleSign$$ExternalSyntheticLambda1
                @Override // java.lang.Runnable
                public final void run() {
                    AppleSign.lambda$onHostActivityResumed$1(r0);
                }
            }, BROWSER_RETURN_GRACE_MS);
        }
    }

    private static boolean openCustomTab(MainActivity mainActivity, String str) {
        String packageName = CustomTabsClient.getPackageName(mainActivity, null);
        if (packageName == null || packageName.isEmpty()) {
            Log.w(TAG, "No Custom Tabs provider found; using WebView fallback");
            return false;
        }
        CustomTabsIntent build = new CustomTabsIntent.Builder().setShowTitle(false).build();
        build.intent.setPackage(packageName);
        browserFlowActive = true;
        build.launchUrl(mainActivity, Uri.parse(str));
        return true;
    }

    public static void openLogin() {
        if (!isMainThread()) {
            MAIN.post(new Runnable() { // from class: net.blocklegends.game.AppleSign$$ExternalSyntheticLambda8
                @Override // java.lang.Runnable
                public final void run() {
                    AppleSign.openLogin();
                }
            });
            return;
        }
        try {
            MainActivity activity = MainActivity.getActivity();
            if (activity == null) {
                Log.w(TAG, "MainActivity is null");
                notifyEmptyTokenReceived();
                return;
            }
            if (!isLoginInProgress && activeSignInAttempt == 0) {
                int beginAttempt = beginAttempt(activity);
                String buildAuthUrl = buildAuthUrl();
                Log.i(TAG, "Opening Apple Sign-In");
                try {
                    if (openCustomTab(activity, buildAuthUrl)) {
                        return;
                    }
                    openWebViewFallback(activity, buildAuthUrl);
                    return;
                } catch (Throwable th) {
                    Log.e(TAG, "Failed to open Apple auth UI: " + th.getMessage(), th);
                    finishWithTechnicalFailure(beginAttempt, "open_auth_ui_failed", buildTelemetry("open_auth_ui_failed", th.getMessage()));
                    return;
                }
            }
            Log.i(TAG, "Login already in progress");
        } catch (Throwable th2) {
            Log.e(TAG, "Exception in openLogin: " + th2.getMessage(), th2);
            finishWithTechnicalFailure(activeSignInAttempt, "open_login_exception", buildTelemetry("open_login_exception", th2.getMessage()));
        }
    }

    private static void openWebViewFallback(MainActivity mainActivity, String str) {
        browserFlowActive = false;
        Intent intent = new Intent(mainActivity, (Class<?>) AppleSignActivity.class);
        intent.putExtra(AppleSignActivity.EXTRA_AUTH_URL, str);
        intent.putExtra(AppleSignActivity.EXTRA_REDIRECT_URI, REDIRECT_URI);
        mainActivity.startActivityForResult(intent, REQUEST_CODE_APPLE_SIGN_IN);
    }

    private static String readStream(InputStream inputStream) throws Exception {
        if (inputStream == null) {
            return "";
        }
        StringBuilder sb = new StringBuilder();
        BufferedReader bufferedReader = new BufferedReader(new InputStreamReader(inputStream, StandardCharsets.UTF_8));
        while (true) {
            try {
                String readLine = bufferedReader.readLine();
                if (readLine == null) {
                    bufferedReader.close();
                    return sb.toString();
                }
                sb.append(readLine);
            } catch (Throwable th) {
                try {
                    bufferedReader.close();
                } catch (Throwable th2) {
                    th.addSuppressed(th2);
                }
                throw th;
            }
        }
    }

    private static String sanitize(String str) {
        if (str == null) {
            return "";
        }
        String replace = str.replace('\n', ' ').replace('\r', ' ').replace(';', AbstractJsonLexerKt.COMMA);
        return replace.length() > 180 ? replace.substring(0, 180) : replace;
    }

    private static void saveState(MainActivity mainActivity) {
        getPrefs(mainActivity).edit().putString(PREF_STATE, currentState).putString(PREF_NONCE, currentNonce).apply();
    }

    private static void scheduleTimeout(final int r3) {
        cancelTimeout();
        activeTimeoutRunnable = new Runnable() { // from class: net.blocklegends.game.AppleSign$$ExternalSyntheticLambda4
            @Override // java.lang.Runnable
            public final void run() {
                AppleSign.lambda$scheduleTimeout$6(r3);
            }
        };
        MAIN.postDelayed(activeTimeoutRunnable, AUTH_TIMEOUT_MS);
    }
}
