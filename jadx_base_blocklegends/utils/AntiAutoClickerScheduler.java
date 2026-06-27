package net.blocklegends.utils;

import android.content.Context;
import android.os.Handler;
import android.os.Looper;
import java.lang.ref.WeakReference;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.TimeUnit;
import net.blocklegends.utils.AntiAutoClickerUtil;

/* loaded from: classes4.dex */
public final class AntiAutoClickerScheduler {
    private static final long UI_THROTTLE_MS = 60000;
    private final WeakReference<Context> appCtxRef;
    private ScheduledFuture<?> future;
    private final OnDetectionListener listener;
    private final Handler mainHandler = new Handler(Looper.getMainLooper());
    private long lastUiNotifyAt = 0;
    private final ScheduledExecutorService scheduler = Executors.newSingleThreadScheduledExecutor(new ThreadFactory() { // from class: net.blocklegends.utils.AntiAutoClickerScheduler.1
        @Override // java.util.concurrent.ThreadFactory
        public Thread newThread(Runnable runnable) {
            Thread thread = new Thread(runnable, "AntiAutoClickerScheduler");
            thread.setDaemon(true);
            return thread;
        }
    });

    /* loaded from: classes4.dex */
    public interface OnDetectionListener {
        void onDetected(AntiAutoClickerUtil.DetectionResult detectionResult);
    }

    public AntiAutoClickerScheduler(Context context, OnDetectionListener onDetectionListener) {
        this.appCtxRef = new WeakReference<>(context.getApplicationContext());
        this.listener = onDetectionListener;
    }

    /* JADX INFO: Access modifiers changed from: package-private */
    /* renamed from: lambda$start$0$net-blocklegends-utils-AntiAutoClickerScheduler, reason: not valid java name */
    public /* synthetic */ void m2146lambda$start$0$netblocklegendsutilsAntiAutoClickerScheduler(AntiAutoClickerUtil.DetectionResult detectionResult) {
        this.listener.onDetected(detectionResult);
    }

    /* JADX INFO: Access modifiers changed from: package-private */
    /* renamed from: lambda$start$1$net-blocklegends-utils-AntiAutoClickerScheduler, reason: not valid java name */
    public /* synthetic */ void m2147lambda$start$1$netblocklegendsutilsAntiAutoClickerScheduler() {
        Context context = this.appCtxRef.get();
        if (context == null) {
            return;
        }
        final AntiAutoClickerUtil.DetectionResult detect = AntiAutoClickerUtil.detect(context);
        if (detect.detected) {
            long currentTimeMillis = System.currentTimeMillis();
            if (currentTimeMillis - this.lastUiNotifyAt >= UI_THROTTLE_MS) {
                this.lastUiNotifyAt = currentTimeMillis;
                this.mainHandler.post(new Runnable() { // from class: net.blocklegends.utils.AntiAutoClickerScheduler$$ExternalSyntheticLambda0
                    @Override // java.lang.Runnable
                    public final void run() {
                        AntiAutoClickerScheduler.this.m2146lambda$start$0$netblocklegendsutilsAntiAutoClickerScheduler(detect);
                    }
                });
            }
        }
    }

    public void shutdown() {
        this.scheduler.shutdownNow();
    }

    public synchronized void start(long j, long j2, TimeUnit timeUnit) {
        ScheduledFuture<?> scheduledFuture = this.future;
        if (scheduledFuture == null || scheduledFuture.isCancelled()) {
            this.future = this.scheduler.scheduleWithFixedDelay(new Runnable() { // from class: net.blocklegends.utils.AntiAutoClickerScheduler$$ExternalSyntheticLambda1
                @Override // java.lang.Runnable
                public final void run() {
                    AntiAutoClickerScheduler.this.m2147lambda$start$1$netblocklegendsutilsAntiAutoClickerScheduler();
                }
            }, j, j2, timeUnit);
        }
    }

    public synchronized void stop() {
        ScheduledFuture<?> scheduledFuture = this.future;
        if (scheduledFuture != null) {
            scheduledFuture.cancel(true);
            this.future = null;
        }
    }
}
