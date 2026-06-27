package net.blocklegends.ad;

import android.app.Activity;
import android.content.Context;
import com.google.android.gms.ads.AdRequest;
import com.google.android.ump.ConsentDebugSettings;
import com.google.android.ump.ConsentForm;
import com.google.android.ump.ConsentInformation;
import com.google.android.ump.ConsentRequestParameters;
import com.google.android.ump.FormError;
import com.google.android.ump.UserMessagingPlatform;
import net.blocklegends.ad.GoogleMobileAdsConsentManager;

/* loaded from: classes2.dex */
public class GoogleMobileAdsConsentManager {
    private static GoogleMobileAdsConsentManager instance;
    private final ConsentInformation consentInformation;

    /* loaded from: classes2.dex */
    public interface OnConsentGatheringCompleteListener {
        void consentGatheringComplete(FormError formError);
    }

    private GoogleMobileAdsConsentManager(Context context) {
        this.consentInformation = UserMessagingPlatform.getConsentInformation(context);
    }

    public static GoogleMobileAdsConsentManager getInstance(Context context) {
        if (instance == null) {
            instance = new GoogleMobileAdsConsentManager(context);
        }
        return instance;
    }

    public boolean canRequestAds() {
        return this.consentInformation.canRequestAds();
    }

    public void gatherConsent(final Activity activity, final OnConsentGatheringCompleteListener onConsentGatheringCompleteListener) {
        ConsentDebugSettings.Builder builder = new ConsentDebugSettings.Builder(activity);
        if ((activity.getApplicationInfo().flags & 2) != 0) {
            builder.addTestDeviceHashedId(AdRequest.DEVICE_ID_EMULATOR).addTestDeviceHashedId("1ACCBD996D1872C03F2DBD85D2157E30").addTestDeviceHashedId("D6F8B28C9B4B3616FA61F38CB87AC7E2");
        }
        this.consentInformation.requestConsentInfoUpdate(activity, new ConsentRequestParameters.Builder().setConsentDebugSettings(builder.build()).build(), new ConsentInformation.OnConsentInfoUpdateSuccessListener() { // from class: net.blocklegends.ad.GoogleMobileAdsConsentManager$$ExternalSyntheticLambda1
            @Override // com.google.android.ump.ConsentInformation.OnConsentInfoUpdateSuccessListener
            public final void onConsentInfoUpdateSuccess() {
                UserMessagingPlatform.loadAndShowConsentFormIfRequired(activity, new ConsentForm.OnConsentFormDismissedListener() { // from class: net.blocklegends.ad.GoogleMobileAdsConsentManager$$ExternalSyntheticLambda0
                    @Override // com.google.android.ump.ConsentForm.OnConsentFormDismissedListener
                    public final void onConsentFormDismissed(FormError formError) {
                        GoogleMobileAdsConsentManager.OnConsentGatheringCompleteListener.this.consentGatheringComplete(formError);
                    }
                });
            }
        }, new ConsentInformation.OnConsentInfoUpdateFailureListener() { // from class: net.blocklegends.ad.GoogleMobileAdsConsentManager$$ExternalSyntheticLambda2
            @Override // com.google.android.ump.ConsentInformation.OnConsentInfoUpdateFailureListener
            public final void onConsentInfoUpdateFailure(FormError formError) {
                GoogleMobileAdsConsentManager.OnConsentGatheringCompleteListener.this.consentGatheringComplete(formError);
            }
        });
    }

    public boolean isPrivacyOptionsRequired() {
        return this.consentInformation.getPrivacyOptionsRequirementStatus() == ConsentInformation.PrivacyOptionsRequirementStatus.REQUIRED;
    }

    public void showPrivacyOptionsForm(Activity activity, ConsentForm.OnConsentFormDismissedListener onConsentFormDismissedListener) {
        UserMessagingPlatform.showPrivacyOptionsForm(activity, onConsentFormDismissedListener);
    }
}
