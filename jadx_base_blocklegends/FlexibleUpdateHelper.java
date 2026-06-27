package net.blocklegends;

import android.app.Activity;
import android.content.IntentSender;
import android.content.pm.PackageManager;
import android.util.Log;
import android.view.View;
import com.google.android.gms.tasks.OnFailureListener;
import com.google.android.gms.tasks.OnSuccessListener;
import com.google.android.gms.tasks.Task;
import com.google.android.material.snackbar.Snackbar;
import com.google.android.play.core.appupdate.AppUpdateInfo;
import com.google.android.play.core.appupdate.AppUpdateManager;
import com.google.android.play.core.appupdate.AppUpdateManagerFactory;
import com.google.android.play.core.install.InstallState;
import com.google.android.play.core.install.InstallStateUpdatedListener;

/* loaded from: classes3.dex */
public class FlexibleUpdateHelper {
    private static final int REQUEST_CODE_UPDATE = 2001;
    private static final String TAG = "FlexibleUpdateHelper";
    private final Activity activity;
    private final AppUpdateManager appUpdateManager;
    private InstallStateUpdatedListener updateListener;

    public FlexibleUpdateHelper(Activity activity) {
        if (activity == null) {
            throw new IllegalArgumentException("Activity cannot be null");
        }
        this.activity = activity;
        try {
            AppUpdateManager create = AppUpdateManagerFactory.create(activity);
            this.appUpdateManager = create;
            if (create == null) {
                Log.e(TAG, "AppUpdateManager is null");
            }
        } catch (Exception e) {
            Log.e(TAG, "Failed to create AppUpdateManager: " + e.getMessage(), e);
            throw new RuntimeException("Failed to initialize update manager", e);
        }
    }

    private long getCurrentVersionCode() {
        try {
            return this.activity.getPackageManager().getPackageInfo(this.activity.getPackageName(), 0).getLongVersionCode();
        } catch (PackageManager.NameNotFoundException e) {
            Log.e(TAG, "Failed to get current version code: " + e.getMessage(), e);
            return -1L;
        }
    }

    private String getInstallStatusString(int r2) {
        if (r2 == 11) {
            return "DOWNLOADED";
        }
        switch (r2) {
            case 0:
                return "UNKNOWN";
            case 1:
                return "PENDING";
            case 2:
                return "DOWNLOADING";
            case 3:
                return "INSTALLING";
            case 4:
                return "INSTALLED";
            case 5:
                return "FAILED";
            case 6:
                return "CANCELED";
            default:
                return "UNKNOWN(" + r2 + ")";
        }
    }

    private String getUpdateAvailabilityString(int r2) {
        return r2 != 0 ? r2 != 1 ? r2 != 2 ? r2 != 3 ? "UNKNOWN(" + r2 + ")" : "DEVELOPER_TRIGGERED_UPDATE_IN_PROGRESS" : "UPDATE_AVAILABLE" : "UPDATE_NOT_AVAILABLE" : "UNKNOWN";
    }

    private void showCompleteUpdateSnackbar() {
        try {
            Activity activity = this.activity;
            if (activity != null && !activity.isFinishing() && !this.activity.isDestroyed()) {
                View findViewById = this.activity.findViewById(android.R.id.content);
                if (findViewById == null) {
                    Log.e(TAG, "Content view is null, cannot show snackbar");
                    return;
                } else if (this.appUpdateManager == null) {
                    Log.e(TAG, "AppUpdateManager is null, cannot complete update");
                    return;
                } else {
                    Snackbar.make(findViewById, "BLOCK LEGENDS!", -2).setAction("GO!", new View.OnClickListener() { // from class: net.blocklegends.FlexibleUpdateHelper$$ExternalSyntheticLambda3
                        @Override // android.view.View.OnClickListener
                        public final void onClick(View view) {
                            FlexibleUpdateHelper.this.m2053xd7e54532(view);
                        }
                    }).show();
                    Log.i(TAG, "Update snackbar shown");
                    return;
                }
            }
            Log.w(TAG, "Activity is not valid, cannot show snackbar");
        } catch (Exception e) {
            Log.e(TAG, "Error showing snackbar: " + e.getMessage(), e);
        }
    }

    public void checkForUpdate() {
        try {
            if (this.appUpdateManager == null) {
                Log.e(TAG, "AppUpdateManager is null, cannot check for updates");
                return;
            }
            Activity activity = this.activity;
            if (activity != null && !activity.isFinishing() && !this.activity.isDestroyed()) {
                final long currentVersionCode = getCurrentVersionCode();
                Log.i(TAG, "Current app version code: " + currentVersionCode);
                Task<AppUpdateInfo> appUpdateInfo = this.appUpdateManager.getAppUpdateInfo();
                appUpdateInfo.addOnSuccessListener(new OnSuccessListener() { // from class: net.blocklegends.FlexibleUpdateHelper$$ExternalSyntheticLambda0
                    @Override // com.google.android.gms.tasks.OnSuccessListener
                    public final void onSuccess(Object obj) {
                        FlexibleUpdateHelper.this.m2050lambda$checkForUpdate$0$netblocklegendsFlexibleUpdateHelper(currentVersionCode, (AppUpdateInfo) obj);
                    }
                });
                appUpdateInfo.addOnFailureListener(new OnFailureListener() { // from class: net.blocklegends.FlexibleUpdateHelper$$ExternalSyntheticLambda1
                    @Override // com.google.android.gms.tasks.OnFailureListener
                    public final void onFailure(Exception exc) {
                        Log.e(FlexibleUpdateHelper.TAG, "Failed to get update info: " + exc.getMessage(), exc);
                    }
                });
                InstallStateUpdatedListener installStateUpdatedListener = new InstallStateUpdatedListener() { // from class: net.blocklegends.FlexibleUpdateHelper$$ExternalSyntheticLambda2
                    @Override // com.google.android.play.core.listener.StateUpdatedListener
                    public final void onStateUpdate(InstallState installState) {
                        FlexibleUpdateHelper.this.m2051lambda$checkForUpdate$2$netblocklegendsFlexibleUpdateHelper(installState);
                    }
                };
                this.updateListener = installStateUpdatedListener;
                this.appUpdateManager.registerListener(installStateUpdatedListener);
                return;
            }
            Log.w(TAG, "Activity is not valid, skipping update check");
        } catch (Exception e) {
            Log.e(TAG, "Error checking for update: " + e.getMessage(), e);
        }
    }

    public void checkPendingUpdate() {
        try {
            if (this.appUpdateManager == null) {
                Log.w(TAG, "AppUpdateManager is null, cannot check pending update");
                return;
            }
            Activity activity = this.activity;
            if (activity != null && !activity.isFinishing() && !this.activity.isDestroyed()) {
                this.appUpdateManager.getAppUpdateInfo().addOnSuccessListener(new OnSuccessListener() { // from class: net.blocklegends.FlexibleUpdateHelper$$ExternalSyntheticLambda4
                    @Override // com.google.android.gms.tasks.OnSuccessListener
                    public final void onSuccess(Object obj) {
                        FlexibleUpdateHelper.this.m2052x89b3d0a7((AppUpdateInfo) obj);
                    }
                }).addOnFailureListener(new OnFailureListener() { // from class: net.blocklegends.FlexibleUpdateHelper$$ExternalSyntheticLambda5
                    @Override // com.google.android.gms.tasks.OnFailureListener
                    public final void onFailure(Exception exc) {
                        Log.e(FlexibleUpdateHelper.TAG, "Failed to get update info for pending check: " + exc.getMessage(), exc);
                    }
                });
                return;
            }
            Log.w(TAG, "Activity is not valid, skipping pending update check");
        } catch (Exception e) {
            Log.e(TAG, "Error in checkPendingUpdate: " + e.getMessage(), e);
        }
    }

    public void cleanup() {
        InstallStateUpdatedListener installStateUpdatedListener;
        try {
            AppUpdateManager appUpdateManager = this.appUpdateManager;
            if (appUpdateManager == null || (installStateUpdatedListener = this.updateListener) == null) {
                return;
            }
            appUpdateManager.unregisterListener(installStateUpdatedListener);
            this.updateListener = null;
            Log.i(TAG, "Update listener unregistered");
        } catch (Exception e) {
            Log.e(TAG, "Error during cleanup: " + e.getMessage(), e);
        }
    }

    /* JADX INFO: Access modifiers changed from: package-private */
    /* renamed from: lambda$checkForUpdate$0$net-blocklegends-FlexibleUpdateHelper, reason: not valid java name */
    public /* synthetic */ void m2050lambda$checkForUpdate$0$netblocklegendsFlexibleUpdateHelper(long j, AppUpdateInfo appUpdateInfo) {
        try {
            if (appUpdateInfo == null) {
                Log.w(TAG, "AppUpdateInfo is null");
                return;
            }
            if (!this.activity.isFinishing() && !this.activity.isDestroyed()) {
                int updateAvailability = appUpdateInfo.updateAvailability();
                int availableVersionCode = appUpdateInfo.availableVersionCode();
                Log.i(TAG, "Update check result: updateAvailability=" + getUpdateAvailabilityString(updateAvailability) + ", availableVersionCode=" + availableVersionCode + ", currentVersionCode=" + j + ", installStatus=" + getInstallStatusString(appUpdateInfo.installStatus()));
                if (updateAvailability != 2 || !appUpdateInfo.isUpdateTypeAllowed(0)) {
                    Log.d(TAG, "No update available or not allowed");
                    return;
                }
                if (availableVersionCode <= j) {
                    Log.w(TAG, "FALSE POSITIVE: Play Store says update available but availableVersionCode(" + availableVersionCode + ") <= currentVersionCode(" + j + "). Skipping update.");
                    return;
                }
                try {
                    this.appUpdateManager.startUpdateFlowForResult(appUpdateInfo, 0, this.activity, REQUEST_CODE_UPDATE);
                    Log.i(TAG, "Update flow started for version " + availableVersionCode);
                    return;
                } catch (IntentSender.SendIntentException e) {
                    Log.e(TAG, "Failed to start update flow: " + e.getMessage(), e);
                    return;
                }
            }
            Log.w(TAG, "Activity is finishing, skipping update");
        } catch (Exception e2) {
            Log.e(TAG, "Error in update success listener: " + e2.getMessage(), e2);
        }
    }

    /* JADX INFO: Access modifiers changed from: package-private */
    /* renamed from: lambda$checkForUpdate$2$net-blocklegends-FlexibleUpdateHelper, reason: not valid java name */
    public /* synthetic */ void m2051lambda$checkForUpdate$2$netblocklegendsFlexibleUpdateHelper(InstallState installState) {
        try {
            if (installState == null) {
                Log.w(TAG, "Install state is null");
                return;
            }
            if (installState.installStatus() == 11) {
                Activity activity = this.activity;
                if (activity == null || activity.isFinishing() || this.activity.isDestroyed()) {
                    Log.w(TAG, "Activity not valid, cannot show snackbar");
                } else {
                    showCompleteUpdateSnackbar();
                }
            }
        } catch (Exception e) {
            Log.e(TAG, "Error in install state listener: " + e.getMessage(), e);
        }
    }

    /* JADX INFO: Access modifiers changed from: package-private */
    /* renamed from: lambda$checkPendingUpdate$4$net-blocklegends-FlexibleUpdateHelper, reason: not valid java name */
    public /* synthetic */ void m2052x89b3d0a7(AppUpdateInfo appUpdateInfo) {
        try {
            if (appUpdateInfo == null) {
                Log.w(TAG, "AppUpdateInfo is null in checkPendingUpdate");
                return;
            }
            if (!this.activity.isFinishing() && !this.activity.isDestroyed()) {
                if (appUpdateInfo.installStatus() == 11) {
                    Log.i(TAG, "Found downloaded update, showing install prompt");
                    showCompleteUpdateSnackbar();
                    return;
                }
                return;
            }
            Log.w(TAG, "Activity is finishing, skipping pending update");
        } catch (Exception e) {
            Log.e(TAG, "Error checking pending update: " + e.getMessage(), e);
        }
    }

    /* JADX INFO: Access modifiers changed from: package-private */
    /* renamed from: lambda$showCompleteUpdateSnackbar$3$net-blocklegends-FlexibleUpdateHelper, reason: not valid java name */
    public /* synthetic */ void m2053xd7e54532(View view) {
        try {
            AppUpdateManager appUpdateManager = this.appUpdateManager;
            if (appUpdateManager != null) {
                appUpdateManager.completeUpdate();
            } else {
                Log.e(TAG, "AppUpdateManager is null in action");
            }
        } catch (Exception e) {
            Log.e(TAG, "Error completing update: " + e.getMessage(), e);
        }
    }
}
