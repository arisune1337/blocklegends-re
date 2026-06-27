package net.blocklegends.firebase;

import com.google.android.gms.tasks.OnFailureListener;
import com.google.android.gms.tasks.OnSuccessListener;
import com.google.android.gms.tasks.Task;
import com.google.android.play.core.integrity.IntegrityManagerFactory;
import com.google.android.play.core.integrity.IntegrityServiceException;
import com.google.android.play.core.integrity.IntegrityTokenRequest;
import com.google.android.play.core.integrity.IntegrityTokenResponse;
import java.util.concurrent.Executor;
import java.util.concurrent.Executors;
import java.util.concurrent.ThreadFactory;
import net.blocklegends.MainActivity;
import net.blocklegends.R;
import net.blocklegends.natives.GNatives;

/* loaded from: classes3.dex */
public class Integrity {
    private static final int FALLBACK_INTEGRITY_ERROR_CODE = -1001;
    public static final long GOOGLE_CLOUD_PROJECT_NUMBER = Long.parseLong(MainActivity.getActivity().getString(R.string.game_services_project_id));
    private static final Executor INTEGRITY_CALLBACK_EXECUTOR = Executors.newSingleThreadExecutor(new ThreadFactory() { // from class: net.blocklegends.firebase.Integrity$$ExternalSyntheticLambda0
        @Override // java.util.concurrent.ThreadFactory
        public final Thread newThread(Runnable runnable) {
            return Integrity.lambda$static$0(runnable);
        }
    });
    private static final int MAX_ERROR_MESSAGE_LENGTH = 160;

    private static String formatIntegrityFailure(Throwable th) {
        int errorCode = th instanceof IntegrityServiceException ? ((IntegrityServiceException) th).getErrorCode() : FALLBACK_INTEGRITY_ERROR_CODE;
        String simpleName = th == null ? "Unknown" : th.getClass().getSimpleName();
        String sanitizeMessage = sanitizeMessage(th == null ? "" : th.getMessage());
        return sanitizeMessage.isEmpty() ? errorCode + ":" + simpleName : errorCode + ":" + simpleName + ":" + sanitizeMessage;
    }

    /* JADX INFO: Access modifiers changed from: package-private */
    public static /* synthetic */ void lambda$run$1(long j, IntegrityTokenResponse integrityTokenResponse) {
        String str = integrityTokenResponse.token();
        if (str == null) {
            str = "";
        }
        GNatives.onIntegritySuccess(str, j);
    }

    /* JADX INFO: Access modifiers changed from: package-private */
    public static /* synthetic */ Thread lambda$static$0(Runnable runnable) {
        Thread thread = new Thread(runnable, "IntegrityCallback");
        thread.setDaemon(true);
        return thread;
    }

    public static void run(String str, final long j) {
        try {
            Task<IntegrityTokenResponse> requestIntegrityToken = IntegrityManagerFactory.create(MainActivity.getActivity()).requestIntegrityToken(IntegrityTokenRequest.builder().setNonce(str).setCloudProjectNumber(GOOGLE_CLOUD_PROJECT_NUMBER).build());
            Executor executor = INTEGRITY_CALLBACK_EXECUTOR;
            requestIntegrityToken.addOnSuccessListener(executor, new OnSuccessListener() { // from class: net.blocklegends.firebase.Integrity$$ExternalSyntheticLambda1
                @Override // com.google.android.gms.tasks.OnSuccessListener
                public final void onSuccess(Object obj) {
                    Integrity.lambda$run$1(j, (IntegrityTokenResponse) obj);
                }
            }).addOnFailureListener(executor, new OnFailureListener() { // from class: net.blocklegends.firebase.Integrity$$ExternalSyntheticLambda2
                @Override // com.google.android.gms.tasks.OnFailureListener
                public final void onFailure(Exception exc) {
                    GNatives.onIntegrityFailure(Integrity.formatIntegrityFailure(exc), j);
                }
            });
        } catch (Throwable th) {
            try {
                GNatives.onIntegrityThrow(formatIntegrityFailure(th), j);
            } catch (Throwable unused) {
            }
        }
    }

    private static String sanitizeMessage(String str) {
        if (str == null) {
            return "";
        }
        String trim = str.replace('\n', ' ').replace('\r', ' ').replace('\t', ' ').trim();
        return trim.length() > MAX_ERROR_MESSAGE_LENGTH ? trim.substring(0, MAX_ERROR_MESSAGE_LENGTH) + "..." : trim;
    }
}
