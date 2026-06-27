package net.blocklegends.ad;

import android.app.Activity;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.util.Log;
import com.google.ads.mediation.admob.AdMobAdapter;
import com.google.android.gms.ads.AdError;
import com.google.android.gms.ads.AdRequest;
import com.google.android.gms.ads.FullScreenContentCallback;
import com.google.android.gms.ads.LoadAdError;
import com.google.android.gms.ads.MobileAds;
import com.google.android.gms.ads.OnUserEarnedRewardListener;
import com.google.android.gms.ads.RequestConfiguration;
import com.google.android.gms.ads.initialization.InitializationStatus;
import com.google.android.gms.ads.initialization.OnInitializationCompleteListener;
import com.google.android.gms.ads.rewarded.RewardItem;
import com.google.android.gms.ads.rewarded.RewardedAd;
import com.google.android.gms.ads.rewarded.RewardedAdLoadCallback;
import com.google.android.ump.ConsentForm;
import com.google.android.ump.FormError;
import com.google.firebase.analytics.FirebaseAnalytics;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import net.blocklegends.MainActivity;
import net.blocklegends.MainApp;
import net.blocklegends.R;
import net.blocklegends.ad.AdManager;
import net.blocklegends.ad.GoogleMobileAdsConsentManager;
import net.blocklegends.natives.GNatives;
import net.blocklegends.utils.Util;

/* loaded from: classes4.dex */
public class AdManager {
    private static GoogleMobileAdsConsentManager googleMobileAdsConsentManager;
    private static volatile boolean isLoading;
    private static RewardedAd rewardedAd;
    private static final ScheduledExecutorService EXECUTOR_SERVICE = Util.newScheduledExecutorService("AD-0", 5);
    private static boolean CHILD_MODE = false;
    private static final String AD_UNIT_ID = MainActivity.getActivity().getString(R.string.ad_unit_id_0);
    private static final AtomicBoolean isMobileAdsInitializeCalled = new AtomicBoolean(false);

    /* JADX INFO: Access modifiers changed from: package-private */
    /* renamed from: net.blocklegends.ad.AdManager$2, reason: invalid class name */
    /* loaded from: classes4.dex */
    public class AnonymousClass2 extends FullScreenContentCallback {
        final /* synthetic */ AtomicBoolean val$externalUiStarted;
        final /* synthetic */ long val$id;
        final /* synthetic */ AtomicBoolean val$rewardGiven;

        AnonymousClass2(AtomicBoolean atomicBoolean, long j, AtomicBoolean atomicBoolean2) {
            this.val$externalUiStarted = atomicBoolean;
            this.val$id = j;
            this.val$rewardGiven = atomicBoolean2;
        }

        /* JADX INFO: Access modifiers changed from: package-private */
        public static /* synthetic */ void lambda$onAdDismissedFullScreenContent$0(AtomicBoolean atomicBoolean, long j) {
            if (atomicBoolean.get()) {
                return;
            }
            AdManager.notifyReward(j, false, "", -1);
        }

        @Override // com.google.android.gms.ads.FullScreenContentCallback
        public void onAdDismissedFullScreenContent() {
            AdManager.rewardedAd = null;
            if (this.val$externalUiStarted.compareAndSet(true, false)) {
                MainActivity.endExternalUi(MainActivity.EXTERNAL_UI_ADMOB_REWARDED);
            }
            Handler handler = new Handler(Looper.getMainLooper());
            final AtomicBoolean atomicBoolean = this.val$rewardGiven;
            final long j = this.val$id;
            handler.postDelayed(new Runnable() { // from class: net.blocklegends.ad.AdManager$2$$ExternalSyntheticLambda0
                @Override // java.lang.Runnable
                public final void run() {
                    AdManager.AnonymousClass2.lambda$onAdDismissedFullScreenContent$0(atomicBoolean, j);
                }
            }, 3000L);
        }

        @Override // com.google.android.gms.ads.FullScreenContentCallback
        public void onAdFailedToShowFullScreenContent(AdError adError) {
            AdManager.rewardedAd = null;
            if (this.val$externalUiStarted.compareAndSet(true, false)) {
                MainActivity.endExternalUi(MainActivity.EXTERNAL_UI_ADMOB_REWARDED);
            }
            AdManager.notifyReward(this.val$id, false, "", -1);
        }

        @Override // com.google.android.gms.ads.FullScreenContentCallback
        public void onAdShowedFullScreenContent() {
        }
    }

    private static Activity getActivity() {
        return MainActivity.getActivity();
    }

    public static void init() {
        final Activity activity;
        if (CHILD_MODE || (activity = getActivity()) == null) {
            return;
        }
        googleMobileAdsConsentManager = GoogleMobileAdsConsentManager.getInstance(activity.getApplicationContext());
        activity.runOnUiThread(new Runnable() { // from class: net.blocklegends.ad.AdManager$$ExternalSyntheticLambda0
            @Override // java.lang.Runnable
            public final void run() {
                AdManager.lambda$init$7(activity);
            }
        });
        if (googleMobileAdsConsentManager.canRequestAds()) {
            initializeMobileAdsSdk();
        }
    }

    private static void initializeMobileAdsSdk() {
        if (CHILD_MODE) {
            return;
        }
        isMobileAdsInitializeCalled.getAndSet(true);
    }

    public static boolean isLoadedGoogleAd() {
        return (CHILD_MODE || rewardedAd == null) ? false : true;
    }

    /* JADX INFO: Access modifiers changed from: package-private */
    public static /* synthetic */ void lambda$init$6(Activity activity, FormError formError) {
        if (formError != null) {
            Log.w("CONSENTERROR", String.format("%s: %s", Integer.valueOf(formError.getErrorCode()), formError.getMessage()));
        }
        if (googleMobileAdsConsentManager.canRequestAds()) {
            initializeMobileAdsSdk();
        }
        if (googleMobileAdsConsentManager.isPrivacyOptionsRequired()) {
            activity.invalidateOptionsMenu();
        }
    }

    /* JADX INFO: Access modifiers changed from: package-private */
    public static /* synthetic */ void lambda$init$7(final Activity activity) {
        try {
            googleMobileAdsConsentManager.gatherConsent(activity, new GoogleMobileAdsConsentManager.OnConsentGatheringCompleteListener() { // from class: net.blocklegends.ad.AdManager$$ExternalSyntheticLambda12
                @Override // net.blocklegends.ad.GoogleMobileAdsConsentManager.OnConsentGatheringCompleteListener
                public final void consentGatheringComplete(FormError formError) {
                    AdManager.lambda$init$6(activity, formError);
                }
            });
        } catch (Throwable th) {
            Log.e("AdManager", "Consent gather error: " + th.getMessage());
        }
    }

    /* JADX INFO: Access modifiers changed from: package-private */
    public static /* synthetic */ void lambda$loadRewardedAd$10() {
        final AdRequest build;
        if (isLoading) {
            return;
        }
        isLoading = true;
        try {
            final Activity activity = getActivity();
            if (activity == null) {
                isLoading = false;
                notifyAdLoaded(false);
                return;
            }
            if (CHILD_MODE) {
                Bundle bundle = new Bundle();
                bundle.putString("npa", "1");
                build = new AdRequest.Builder().addNetworkExtrasBundle(AdMobAdapter.class, bundle).build();
            } else {
                build = new AdRequest.Builder().build();
            }
            activity.runOnUiThread(new Runnable() { // from class: net.blocklegends.ad.AdManager$$ExternalSyntheticLambda13
                @Override // java.lang.Runnable
                public final void run() {
                    RewardedAd.load(activity, AdManager.AD_UNIT_ID, build, new RewardedAdLoadCallback() { // from class: net.blocklegends.ad.AdManager.1
                        @Override // com.google.android.gms.ads.AdLoadCallback
                        public void onAdFailedToLoad(LoadAdError loadAdError) {
                            AdManager.rewardedAd = null;
                            AdManager.isLoading = false;
                            AdManager.notifyAdLoaded(false);
                        }

                        @Override // com.google.android.gms.ads.AdLoadCallback
                        public void onAdLoaded(RewardedAd rewardedAd2) {
                            AdManager.rewardedAd = rewardedAd2;
                            AdManager.isLoading = false;
                            AdManager.notifyAdLoaded(true);
                        }
                    });
                }
            });
        } catch (Throwable th) {
            isLoading = false;
            th.printStackTrace();
            notifyAdLoaded(false);
        }
    }

    /* JADX INFO: Access modifiers changed from: package-private */
    public static /* synthetic */ void lambda$openAdPrivacyOptions$0(FormError formError) {
        if (formError != null) {
            Log.w("AdManager", String.format("%s: %s", Integer.valueOf(formError.getErrorCode()), formError.getMessage()));
        }
    }

    /* JADX INFO: Access modifiers changed from: package-private */
    public static /* synthetic */ void lambda$openAdPrivacyOptions$1() {
        GoogleMobileAdsConsentManager googleMobileAdsConsentManager2;
        try {
            Activity activity = getActivity();
            if (activity != null && (googleMobileAdsConsentManager2 = googleMobileAdsConsentManager) != null) {
                googleMobileAdsConsentManager2.showPrivacyOptionsForm(activity, new ConsentForm.OnConsentFormDismissedListener() { // from class: net.blocklegends.ad.AdManager$$ExternalSyntheticLambda11
                    @Override // com.google.android.ump.ConsentForm.OnConsentFormDismissedListener
                    public final void onConsentFormDismissed(FormError formError) {
                        AdManager.lambda$openAdPrivacyOptions$0(formError);
                    }
                });
            }
        } catch (Exception e) {
            Log.e("AdManager", "Privacy options form hatasi: " + e.getMessage());
        }
    }

    /* JADX INFO: Access modifiers changed from: package-private */
    public static /* synthetic */ void lambda$showRewardedVideo$12(AtomicBoolean atomicBoolean, final long j, final RewardItem rewardItem) {
        atomicBoolean.set(true);
        new Handler(Looper.getMainLooper()).postDelayed(new Runnable() { // from class: net.blocklegends.ad.AdManager$$ExternalSyntheticLambda7
            @Override // java.lang.Runnable
            public final void run() {
                AdManager.notifyReward(j, true, r2.getType(), rewardItem.getAmount());
            }
        }, 3000L);
    }

    /* JADX INFO: Access modifiers changed from: package-private */
    public static /* synthetic */ void lambda$showRewardedVideo$13(AtomicBoolean atomicBoolean, final AtomicBoolean atomicBoolean2, final long j) {
        try {
            Activity activity = getActivity();
            if (rewardedAd == null || activity == null) {
                notifyReward(j, false, "", -1);
                return;
            }
            MainActivity.beginExternalUi(MainActivity.EXTERNAL_UI_ADMOB_REWARDED);
            atomicBoolean.set(true);
            rewardedAd.show(activity, new OnUserEarnedRewardListener() { // from class: net.blocklegends.ad.AdManager$$ExternalSyntheticLambda14
                @Override // com.google.android.gms.ads.OnUserEarnedRewardListener
                public final void onUserEarnedReward(RewardItem rewardItem) {
                    AdManager.lambda$showRewardedVideo$12(atomicBoolean2, j, rewardItem);
                }
            });
        } catch (Throwable unused) {
            if (atomicBoolean.compareAndSet(true, false)) {
                MainActivity.endExternalUi(MainActivity.EXTERNAL_UI_ADMOB_REWARDED);
            }
            notifyReward(j, false, "", -1);
        }
    }

    /* JADX INFO: Access modifiers changed from: package-private */
    public static /* synthetic */ void lambda$startAdManager$2(boolean z) {
        try {
            FirebaseAnalytics firebaseAnalytics = FirebaseAnalytics.getInstance(MainApp.getApp());
            if (z) {
                firebaseAnalytics.setAnalyticsCollectionEnabled(false);
            } else {
                firebaseAnalytics.setAnalyticsCollectionEnabled(true);
            }
        } catch (Throwable th) {
            Log.e("AdManager", "Analytics init hatası: " + th.getMessage());
        }
    }

    /* JADX INFO: Access modifiers changed from: package-private */
    public static /* synthetic */ void lambda$startAdManager$3() {
        try {
            init();
        } catch (Throwable th) {
            th.printStackTrace();
        }
    }

    /* JADX INFO: Access modifiers changed from: package-private */
    public static /* synthetic */ void lambda$startAdManager$5() {
        try {
            if (CHILD_MODE) {
                Log.d("AdManager", "Çocuk modu aktif. AdMob SDK başlatılmıyor.");
            } else {
                MobileAds.setRequestConfiguration(new RequestConfiguration.Builder().setTagForChildDirectedTreatment(0).build());
                MobileAds.initialize(MainApp.getApp(), new OnInitializationCompleteListener() { // from class: net.blocklegends.ad.AdManager$$ExternalSyntheticLambda10
                    @Override // com.google.android.gms.ads.initialization.OnInitializationCompleteListener
                    public final void onInitializationComplete(InitializationStatus initializationStatus) {
                        AdManager.EXECUTOR_SERVICE.submit(new Runnable() { // from class: net.blocklegends.ad.AdManager$$ExternalSyntheticLambda8
                            @Override // java.lang.Runnable
                            public final void run() {
                                AdManager.lambda$startAdManager$3();
                            }
                        });
                    }
                });
            }
        } catch (Throwable th) {
            th.printStackTrace();
        }
    }

    public static void loadRewardedAd() {
        if (CHILD_MODE) {
            notifyAdLoaded(false);
        } else {
            if (rewardedAd != null || isLoading) {
                return;
            }
            EXECUTOR_SERVICE.submit(new Runnable() { // from class: net.blocklegends.ad.AdManager$$ExternalSyntheticLambda5
                @Override // java.lang.Runnable
                public final void run() {
                    AdManager.lambda$loadRewardedAd$10();
                }
            });
        }
    }

    /* JADX INFO: Access modifiers changed from: private */
    public static void notifyAdLoaded(final boolean z) {
        Util.run(new Runnable() { // from class: net.blocklegends.ad.AdManager$$ExternalSyntheticLambda6
            @Override // java.lang.Runnable
            public final void run() {
                GNatives.onGoogleAdLoaded(z);
            }
        });
    }

    /* JADX INFO: Access modifiers changed from: private */
    public static void notifyReward(final long j, final boolean z, final String str, final int r10) {
        Util.run(new Runnable() { // from class: net.blocklegends.ad.AdManager$$ExternalSyntheticLambda2
            @Override // java.lang.Runnable
            public final void run() {
                GNatives.onUserEarnedReward(j, z, str, r10);
            }
        });
    }

    public static void openAdPrivacyOptions() {
        if (CHILD_MODE) {
            return;
        }
        Activity activity = getActivity();
        if (activity == null || googleMobileAdsConsentManager == null) {
            Log.w("AdManager", "openAdPrivacyOptions: activity veya consentManager null");
        } else {
            activity.runOnUiThread(new Runnable() { // from class: net.blocklegends.ad.AdManager$$ExternalSyntheticLambda9
                @Override // java.lang.Runnable
                public final void run() {
                    AdManager.lambda$openAdPrivacyOptions$1();
                }
            });
        }
    }

    public static void rewardAdRequest(final long j) {
        if (CHILD_MODE) {
            notifyReward(j, false, "", -1);
        } else {
            EXECUTOR_SERVICE.submit(new Runnable() { // from class: net.blocklegends.ad.AdManager$$ExternalSyntheticLambda3
                @Override // java.lang.Runnable
                public final void run() {
                    AdManager.showRewardedVideo(j);
                }
            });
        }
    }

    /* JADX INFO: Access modifiers changed from: private */
    public static void showRewardedVideo(final long j) {
        if (CHILD_MODE) {
            notifyReward(j, false, "", -1);
            return;
        }
        if (rewardedAd == null) {
            notifyReward(j, false, "", -1);
            return;
        }
        try {
            final AtomicBoolean atomicBoolean = new AtomicBoolean(false);
            final AtomicBoolean atomicBoolean2 = new AtomicBoolean(false);
            rewardedAd.setFullScreenContentCallback(new AnonymousClass2(atomicBoolean2, j, atomicBoolean));
            getActivity().runOnUiThread(new Runnable() { // from class: net.blocklegends.ad.AdManager$$ExternalSyntheticLambda4
                @Override // java.lang.Runnable
                public final void run() {
                    AdManager.lambda$showRewardedVideo$13(atomicBoolean2, atomicBoolean, j);
                }
            });
        } catch (Throwable unused) {
            notifyReward(j, false, "", -1);
        }
    }

    public static void startAdManager(final boolean z) {
        CHILD_MODE = z;
        ScheduledExecutorService scheduledExecutorService = EXECUTOR_SERVICE;
        scheduledExecutorService.schedule(new Runnable() { // from class: net.blocklegends.ad.AdManager$$ExternalSyntheticLambda15
            @Override // java.lang.Runnable
            public final void run() {
                AdManager.lambda$startAdManager$2(z);
            }
        }, 15L, TimeUnit.SECONDS);
        scheduledExecutorService.schedule(new Runnable() { // from class: net.blocklegends.ad.AdManager$$ExternalSyntheticLambda1
            @Override // java.lang.Runnable
            public final void run() {
                AdManager.lambda$startAdManager$5();
            }
        }, 20L, TimeUnit.SECONDS);
    }
}
