package net.blocklegends.gameservices;

import android.app.Activity;
import android.content.Intent;
import android.util.Log;
import com.google.android.gms.games.AuthenticationResult;
import com.google.android.gms.games.PlayGames;
import com.google.android.gms.games.PlayGamesSdk;
import com.google.android.gms.games.Player;
import com.google.android.gms.tasks.OnCompleteListener;
import com.google.android.gms.tasks.OnFailureListener;
import com.google.android.gms.tasks.OnSuccessListener;
import com.google.android.gms.tasks.Task;
import net.blocklegends.MainActivity;
import net.blocklegends.MainApp;
import net.blocklegends.R;
import net.blocklegends.game.AppleSign;
import net.blocklegends.natives.GNatives;

/* loaded from: classes4.dex */
public class GameServicesNatives {
    private static final String TAG = "PGS";
    private static volatile String displayName = null;
    private static volatile boolean initialized = false;
    private static volatile String playerId;

    /* JADX INFO: Access modifiers changed from: package-private */
    public static /* synthetic */ void lambda$pgs_init$0(Player player) {
        playerId = player.getPlayerId();
        displayName = player.getDisplayName();
        Log.i(TAG, "playerId=" + playerId + " name=" + displayName);
        try {
            GNatives.onGcAuthSuccess(playerId, displayName);
        } catch (Throwable th) {
            Log.w(TAG, "dispatch onGcAuthSuccess fail", th);
        }
    }

    /* JADX INFO: Access modifiers changed from: package-private */
    public static /* synthetic */ void lambda$pgs_init$2(Activity activity, Task task) {
        boolean z = task.isSuccessful() && ((AuthenticationResult) task.getResult()).isAuthenticated();
        Log.i(TAG, "init authed=" + z);
        if (z) {
            PlayGames.getPlayersClient(activity).getCurrentPlayer().addOnSuccessListener(new OnSuccessListener() { // from class: net.blocklegends.gameservices.GameServicesNatives$$ExternalSyntheticLambda2
                @Override // com.google.android.gms.tasks.OnSuccessListener
                public final void onSuccess(Object obj) {
                    GameServicesNatives.lambda$pgs_init$0((Player) obj);
                }
            }).addOnFailureListener(new OnFailureListener() { // from class: net.blocklegends.gameservices.GameServicesNatives$$ExternalSyntheticLambda3
                @Override // com.google.android.gms.tasks.OnFailureListener
                public final void onFailure(Exception exc) {
                    Log.w(GameServicesNatives.TAG, "getCurrentPlayer fail: " + exc);
                }
            });
        }
    }

    /* JADX INFO: Access modifiers changed from: package-private */
    public static /* synthetic */ void lambda$pgs_serverAuthCode$7(String str) {
        Log.i(TAG, "serverAuthCode len=" + (str == null ? 0 : str.length()));
        if (str == null || str.isEmpty()) {
            return;
        }
        try {
            GNatives.onGcServerAuthCode(str);
        } catch (Throwable th) {
            Log.w(TAG, "dispatch onGcServerAuthCode fail", th);
        }
    }

    public static void pgs_incrementAchievement(String str, int r4) {
        MainActivity activity;
        if (!initialized || str == null || str.isEmpty() || r4 <= 0 || (activity = MainActivity.getActivity()) == null) {
            return;
        }
        try {
            PlayGames.getAchievementsClient(activity).setSteps(str, r4);
            Log.i(TAG, "setSteps " + str + "=" + r4);
        } catch (Throwable th) {
            Log.w(TAG, "setSteps fail", th);
        }
    }

    public static void pgs_init() {
        MainApp app;
        if (initialized || (app = MainApp.getApp()) == null) {
            return;
        }
        try {
            PlayGamesSdk.initialize(app);
            initialized = true;
            final MainActivity activity = MainActivity.getActivity();
            if (activity == null) {
                Log.i(TAG, "init queued — activity not ready");
            } else {
                PlayGames.getGamesSignInClient(activity).isAuthenticated().addOnCompleteListener(new OnCompleteListener() { // from class: net.blocklegends.gameservices.GameServicesNatives$$ExternalSyntheticLambda4
                    @Override // com.google.android.gms.tasks.OnCompleteListener
                    public final void onComplete(Task task) {
                        GameServicesNatives.lambda$pgs_init$2(activity, task);
                    }
                });
            }
        } catch (Throwable th) {
            Log.w(TAG, "init fail", th);
        }
    }

    public static boolean pgs_isAuthenticated() {
        return playerId != null;
    }

    public static String pgs_serverAuthCode() {
        MainActivity activity;
        if (!initialized || (activity = MainActivity.getActivity()) == null) {
            return null;
        }
        try {
            PlayGames.getGamesSignInClient(activity).requestServerSideAccess(activity.getString(R.string.default_web_client_id), false).addOnSuccessListener(new OnSuccessListener() { // from class: net.blocklegends.gameservices.GameServicesNatives$$ExternalSyntheticLambda7
                @Override // com.google.android.gms.tasks.OnSuccessListener
                public final void onSuccess(Object obj) {
                    GameServicesNatives.lambda$pgs_serverAuthCode$7((String) obj);
                }
            }).addOnFailureListener(new OnFailureListener() { // from class: net.blocklegends.gameservices.GameServicesNatives$$ExternalSyntheticLambda8
                @Override // com.google.android.gms.tasks.OnFailureListener
                public final void onFailure(Exception exc) {
                    Log.w(GameServicesNatives.TAG, "serverAuthCode fail: " + exc);
                }
            });
        } catch (Throwable th) {
            Log.w(TAG, "serverAuthCode dispatch fail", th);
        }
        return null;
    }

    public static void pgs_showAchievementsUI() {
        final MainActivity activity;
        if (initialized && (activity = MainActivity.getActivity()) != null) {
            try {
                PlayGames.getAchievementsClient(activity).getAchievementsIntent().addOnSuccessListener(new OnSuccessListener() { // from class: net.blocklegends.gameservices.GameServicesNatives$$ExternalSyntheticLambda0
                    @Override // com.google.android.gms.tasks.OnSuccessListener
                    public final void onSuccess(Object obj) {
                        activity.startActivityForResult((Intent) obj, AppleSign.REQUEST_CODE_APPLE_SIGN_IN);
                    }
                }).addOnFailureListener(new OnFailureListener() { // from class: net.blocklegends.gameservices.GameServicesNatives$$ExternalSyntheticLambda1
                    @Override // com.google.android.gms.tasks.OnFailureListener
                    public final void onFailure(Exception exc) {
                        Log.w(GameServicesNatives.TAG, "showAchievementsUI fail: " + exc);
                    }
                });
            } catch (Throwable th) {
                Log.w(TAG, "showAchievementsUI fail", th);
            }
        }
    }

    public static void pgs_showLeaderboardUI(String str) {
        final MainActivity activity;
        if (!initialized || str == null || str.isEmpty() || (activity = MainActivity.getActivity()) == null) {
            return;
        }
        try {
            PlayGames.getLeaderboardsClient(activity).getLeaderboardIntent(str).addOnSuccessListener(new OnSuccessListener() { // from class: net.blocklegends.gameservices.GameServicesNatives$$ExternalSyntheticLambda5
                @Override // com.google.android.gms.tasks.OnSuccessListener
                public final void onSuccess(Object obj) {
                    activity.startActivityForResult((Intent) obj, 9002);
                }
            }).addOnFailureListener(new OnFailureListener() { // from class: net.blocklegends.gameservices.GameServicesNatives$$ExternalSyntheticLambda6
                @Override // com.google.android.gms.tasks.OnFailureListener
                public final void onFailure(Exception exc) {
                    Log.w(GameServicesNatives.TAG, "showLeaderboardUI fail: " + exc);
                }
            });
        } catch (Throwable th) {
            Log.w(TAG, "showLeaderboardUI fail", th);
        }
    }

    public static void pgs_signOut() {
        playerId = null;
        displayName = null;
        Log.i(TAG, "signOut (soft — PGS v2 has no SDK sign-out)");
    }

    public static void pgs_submitScore(String str, long j) {
        MainActivity activity;
        if (!initialized || str == null || str.isEmpty() || (activity = MainActivity.getActivity()) == null) {
            return;
        }
        try {
            PlayGames.getLeaderboardsClient(activity).submitScore(str, j);
            Log.i(TAG, "submitScore " + str + "=" + j);
        } catch (Throwable th) {
            Log.w(TAG, "submitScore fail", th);
        }
    }

    public static void pgs_unlockAchievement(String str, double d) {
        MainActivity activity;
        if (!initialized || str == null || str.isEmpty() || (activity = MainActivity.getActivity()) == null) {
            return;
        }
        try {
            PlayGames.getAchievementsClient(activity).unlock(str);
            Log.i(TAG, "unlock " + str + " pct=" + d);
        } catch (Throwable th) {
            Log.w(TAG, "unlock fail", th);
        }
    }
}
