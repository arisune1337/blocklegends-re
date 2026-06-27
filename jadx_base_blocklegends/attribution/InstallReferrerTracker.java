package net.blocklegends.attribution;

import android.content.Context;
import android.content.SharedPreferences;
import android.net.Uri;
import android.os.Bundle;
import android.util.Log;
import com.android.installreferrer.api.InstallReferrerClient;
import com.android.installreferrer.api.InstallReferrerStateListener;
import com.android.installreferrer.api.ReferrerDetails;
import com.google.firebase.analytics.FirebaseAnalytics;
import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.atomic.AtomicBoolean;
import net.blocklegends.utils.Util;

/* loaded from: classes3.dex */
public final class InstallReferrerTracker {
    private static final String KEY_CAPTURED = "captured";
    private static final String PREFS = "install_referrer";
    private static final AtomicBoolean started = new AtomicBoolean(false);
    private static final String TAG = "InstallReferrer";
    private static final ExecutorService EXECUTOR = Util.newExecutorService(TAG, 1);

    private InstallReferrerTracker() {
    }

    public static void capture(Context context) {
        if (context != null && started.compareAndSet(false, true)) {
            final Context applicationContext = context.getApplicationContext();
            EXECUTOR.submit(new Runnable() { // from class: net.blocklegends.attribution.InstallReferrerTracker$$ExternalSyntheticLambda0
                @Override // java.lang.Runnable
                public final void run() {
                    InstallReferrerTracker.lambda$capture$0(applicationContext);
                }
            });
        }
    }

    private static String clip(String str) {
        return clip(str, 36);
    }

    private static String clip(String str, int r2) {
        if (str == null) {
            return null;
        }
        return str.length() <= r2 ? str : str.substring(0, r2);
    }

    private static void connect(final Context context, final SharedPreferences sharedPreferences) {
        try {
            final InstallReferrerClient build = InstallReferrerClient.newBuilder(context).build();
            try {
                build.startConnection(new InstallReferrerStateListener() { // from class: net.blocklegends.attribution.InstallReferrerTracker.1
                    @Override // com.android.installreferrer.api.InstallReferrerStateListener
                    public void onInstallReferrerServiceDisconnected() {
                    }

                    @Override // com.android.installreferrer.api.InstallReferrerStateListener
                    public void onInstallReferrerSetupFinished(int r5) {
                        try {
                            InstallReferrerTracker.handleResponse(context, sharedPreferences, build, r5);
                        } finally {
                            try {
                            } finally {
                            }
                        }
                    }
                });
            } catch (Throwable th) {
                Log.e(TAG, "startConnection failed: " + th.getMessage());
                safeEnd(build);
            }
        } catch (Throwable th2) {
            Log.e(TAG, "client build failed: " + th2.getMessage());
        }
    }

    private static String first(Map<String, String> map, String... strArr) {
        for (String str : strArr) {
            String str2 = map.get(str);
            if (str2 != null && !str2.isEmpty()) {
                return str2;
            }
        }
        return null;
    }

    /* JADX INFO: Access modifiers changed from: private */
    public static void handleResponse(Context context, SharedPreferences sharedPreferences, InstallReferrerClient installReferrerClient, int r4) {
        if (r4 == 0) {
            try {
                persist(context, sharedPreferences, installReferrerClient.getInstallReferrer());
                return;
            } catch (Throwable th) {
                Log.e(TAG, "getInstallReferrer failed: " + th.getMessage());
                return;
            }
        }
        if (r4 == 1) {
            Log.w(TAG, "service unavailable, will retry next launch");
        } else if (r4 != 2) {
            Log.w(TAG, "unexpected response: " + r4);
        } else {
            sharedPreferences.edit().putBoolean(KEY_CAPTURED, true).apply();
            Log.w(TAG, "feature not supported");
        }
    }

    /* JADX INFO: Access modifiers changed from: package-private */
    public static /* synthetic */ void lambda$capture$0(Context context) {
        try {
            SharedPreferences sharedPreferences = context.getSharedPreferences(PREFS, 0);
            if (sharedPreferences.getBoolean(KEY_CAPTURED, false)) {
                return;
            }
            connect(context, sharedPreferences);
        } catch (Throwable th) {
            Log.e(TAG, "capture failed: " + th.getMessage());
        }
    }

    private static Map<String, String> parse(String str) {
        HashMap hashMap = new HashMap();
        if (str != null && !str.isEmpty()) {
            for (String str2 : str.split("&")) {
                int indexOf = str2.indexOf(61);
                if (indexOf > 0) {
                    try {
                        hashMap.put(Uri.decode(str2.substring(0, indexOf)), Uri.decode(str2.substring(indexOf + 1)));
                    } catch (Throwable unused) {
                    }
                }
            }
        }
        return hashMap;
    }

    private static void persist(Context context, SharedPreferences sharedPreferences, ReferrerDetails referrerDetails) {
        long j;
        long j2;
        String str;
        String str2;
        FirebaseAnalytics firebaseAnalytics;
        String installReferrer = referrerDetails.getInstallReferrer();
        long referrerClickTimestampSeconds = referrerDetails.getReferrerClickTimestampSeconds();
        long installBeginTimestampSeconds = referrerDetails.getInstallBeginTimestampSeconds();
        boolean googlePlayInstantParam = referrerDetails.getGooglePlayInstantParam();
        Map<String, String> parse = parse(installReferrer);
        String clip = clip(first(parse, "utm_source", "source"));
        String clip2 = clip(first(parse, "utm_medium", "medium"));
        String clip3 = clip(first(parse, "utm_campaign", "campaign"));
        String first = first(parse, "gclid");
        try {
            FirebaseAnalytics firebaseAnalytics2 = FirebaseAnalytics.getInstance(context);
            if (clip != null) {
                j2 = installBeginTimestampSeconds;
                try {
                    firebaseAnalytics2.setUserProperty("ir_source", clip);
                } catch (Throwable th) {
                    th = th;
                    j = referrerClickTimestampSeconds;
                    str = "install_ts";
                    String str3 = "analytics write failed: " + th.getMessage();
                    str2 = TAG;
                    Log.e(str2, str3);
                    sharedPreferences.edit().putBoolean(KEY_CAPTURED, true).putString("raw", installReferrer).putString("source", clip).putString("medium", clip2).putString("campaign", clip3).putString("gclid", first).putLong("click_ts", j).putLong(str, j2).apply();
                    Log.i(str2, "captured: source=" + clip + " medium=" + clip2 + " campaign=" + clip3);
                }
            } else {
                j2 = installBeginTimestampSeconds;
            }
            if (clip2 != null) {
                firebaseAnalytics2.setUserProperty("ir_medium", clip2);
            }
            if (clip3 != null) {
                firebaseAnalytics2.setUserProperty("ir_campaign", clip3);
            }
            Bundle bundle = new Bundle();
            if (installReferrer != null) {
                firebaseAnalytics = firebaseAnalytics2;
                bundle.putString("raw", clip(installReferrer, 100));
            } else {
                firebaseAnalytics = firebaseAnalytics2;
            }
            if (clip != null) {
                bundle.putString("source", clip);
            }
            if (clip2 != null) {
                bundle.putString("medium", clip2);
            }
            if (clip3 != null) {
                bundle.putString("campaign", clip3);
            }
            if (first != null) {
                bundle.putString("gclid", clip(first, 100));
            }
            bundle.putLong("click_ts", referrerClickTimestampSeconds);
            j = referrerClickTimestampSeconds;
            str = "install_ts";
            long j3 = j2;
            try {
                bundle.putLong(str, j3);
                j2 = j3;
                try {
                    bundle.putInt("instant", googlePlayInstantParam ? 1 : 0);
                    firebaseAnalytics.logEvent(PREFS, bundle);
                    str2 = TAG;
                } catch (Throwable th2) {
                    th = th2;
                    String str32 = "analytics write failed: " + th.getMessage();
                    str2 = TAG;
                    Log.e(str2, str32);
                    sharedPreferences.edit().putBoolean(KEY_CAPTURED, true).putString("raw", installReferrer).putString("source", clip).putString("medium", clip2).putString("campaign", clip3).putString("gclid", first).putLong("click_ts", j).putLong(str, j2).apply();
                    Log.i(str2, "captured: source=" + clip + " medium=" + clip2 + " campaign=" + clip3);
                }
            } catch (Throwable th3) {
                th = th3;
                j2 = j3;
            }
        } catch (Throwable th4) {
            th = th4;
            j = referrerClickTimestampSeconds;
            j2 = installBeginTimestampSeconds;
        }
        sharedPreferences.edit().putBoolean(KEY_CAPTURED, true).putString("raw", installReferrer).putString("source", clip).putString("medium", clip2).putString("campaign", clip3).putString("gclid", first).putLong("click_ts", j).putLong(str, j2).apply();
        Log.i(str2, "captured: source=" + clip + " medium=" + clip2 + " campaign=" + clip3);
    }

    /* JADX INFO: Access modifiers changed from: private */
    public static void safeEnd(InstallReferrerClient installReferrerClient) {
        try {
            installReferrerClient.endConnection();
        } catch (Throwable unused) {
        }
    }
}
