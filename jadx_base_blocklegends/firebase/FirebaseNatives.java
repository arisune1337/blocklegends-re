package net.blocklegends.firebase;

import android.app.Activity;
import android.os.Bundle;
import com.google.android.gms.tasks.OnCompleteListener;
import com.google.android.gms.tasks.Task;
import com.google.android.play.core.review.ReviewInfo;
import com.google.android.play.core.review.ReviewManager;
import com.google.android.play.core.review.ReviewManagerFactory;
import com.google.firebase.analytics.FirebaseAnalytics;
import net.blocklegends.MainActivity;
import net.blocklegends.MainApp;

/* loaded from: classes4.dex */
public class FirebaseNatives {
    /* JADX INFO: Access modifiers changed from: package-private */
    public static /* synthetic */ void lambda$showAppReviewPopup$0(Task task) {
    }

    /* JADX INFO: Access modifiers changed from: package-private */
    public static /* synthetic */ void lambda$showAppReviewPopup$1(ReviewManager reviewManager, Activity activity, Task task) {
        if (task.isSuccessful()) {
            reviewManager.launchReviewFlow(activity, (ReviewInfo) task.getResult()).addOnCompleteListener(activity, new OnCompleteListener() { // from class: net.blocklegends.firebase.FirebaseNatives$$ExternalSyntheticLambda2
                @Override // com.google.android.gms.tasks.OnCompleteListener
                public final void onComplete(Task task2) {
                    FirebaseNatives.lambda$showAppReviewPopup$0(task2);
                }
            });
            return;
        }
        Exception exception = task.getException();
        if (exception != null) {
            exception.printStackTrace();
        }
    }

    /* JADX INFO: Access modifiers changed from: package-private */
    public static /* synthetic */ void lambda$showAppReviewPopup$2(final Activity activity) {
        final ReviewManager create = ReviewManagerFactory.create(activity);
        create.requestReviewFlow().addOnCompleteListener(activity, new OnCompleteListener() { // from class: net.blocklegends.firebase.FirebaseNatives$$ExternalSyntheticLambda0
            @Override // com.google.android.gms.tasks.OnCompleteListener
            public final void onComplete(Task task) {
                FirebaseNatives.lambda$showAppReviewPopup$1(ReviewManager.this, activity, task);
            }
        });
    }

    public static void logEarnVirtualCurrency(String str, double d) {
        try {
            MainApp app = MainApp.getApp();
            if (app != null && str != null && !str.isEmpty() && d > 0.0d) {
                Bundle bundle = new Bundle();
                bundle.putString(FirebaseAnalytics.Param.VIRTUAL_CURRENCY_NAME, str);
                bundle.putDouble("value", d);
                FirebaseAnalytics.getInstance(app).logEvent(FirebaseAnalytics.Event.EARN_VIRTUAL_CURRENCY, bundle);
            }
        } catch (Throwable unused) {
        }
    }

    public static void logEvent(String str, String[] strArr) {
        try {
            MainApp app = MainApp.getApp();
            if (app != null && str != null && !str.isEmpty()) {
                Bundle bundle = new Bundle();
                if (strArr != null) {
                    int r2 = 0;
                    while (true) {
                        int r3 = r2 + 1;
                        if (r3 >= strArr.length) {
                            break;
                        }
                        String str2 = strArr[r2];
                        String str3 = strArr[r3];
                        if (str2 != null && str3 != null) {
                            bundle.putString(str2, str3);
                        }
                        r2 += 2;
                    }
                }
                FirebaseAnalytics.getInstance(app).logEvent(str, bundle);
            }
        } catch (Throwable unused) {
        }
    }

    public static void logLevelUp(int r5, String str) {
        try {
            MainApp app = MainApp.getApp();
            if (app != null && r5 > 0) {
                Bundle bundle = new Bundle();
                bundle.putLong(FirebaseAnalytics.Param.LEVEL, r5);
                if (str != null && !str.isEmpty()) {
                    bundle.putString(FirebaseAnalytics.Param.CHARACTER, str);
                }
                FirebaseAnalytics.getInstance(app).logEvent(FirebaseAnalytics.Event.LEVEL_UP, bundle);
            }
        } catch (Throwable unused) {
        }
    }

    public static void logSpendVirtualCurrency(String str, double d, String str2) {
        try {
            MainApp app = MainApp.getApp();
            if (app != null && str != null && !str.isEmpty() && d > 0.0d && str2 != null && !str2.isEmpty()) {
                Bundle bundle = new Bundle();
                bundle.putString(FirebaseAnalytics.Param.VIRTUAL_CURRENCY_NAME, str);
                bundle.putDouble("value", d);
                bundle.putString(FirebaseAnalytics.Param.ITEM_NAME, str2);
                FirebaseAnalytics.getInstance(app).logEvent(FirebaseAnalytics.Event.SPEND_VIRTUAL_CURRENCY, bundle);
            }
        } catch (Throwable unused) {
        }
    }

    public static void setUserProperty(String str, String str2) {
        try {
            MainApp app = MainApp.getApp();
            if (app != null && str != null && !str.isEmpty()) {
                FirebaseAnalytics.getInstance(app).setUserProperty(str, str2);
            }
        } catch (Throwable unused) {
        }
    }

    public static void showAppReviewPopup() {
        final MainActivity activity = MainActivity.getActivity();
        if (activity == null || activity.isFinishing()) {
            return;
        }
        activity.runOnUiThread(new Runnable() { // from class: net.blocklegends.firebase.FirebaseNatives$$ExternalSyntheticLambda1
            @Override // java.lang.Runnable
            public final void run() {
                FirebaseNatives.lambda$showAppReviewPopup$2(activity);
            }
        });
    }

    public static void startPerformanceTrace(String str) {
    }

    public static void stopPerformanceTrace() {
    }
}
