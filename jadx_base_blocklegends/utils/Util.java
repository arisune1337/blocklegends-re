package net.blocklegends.utils;

import android.util.Log;
import java.lang.Thread;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import net.blocklegends.utils.Util;

/* loaded from: classes4.dex */
public class Util {
    private static final String TAG = "Util";
    private static final long TASK_TIMEOUT_MS = 4000;
    private static final Object LOCK = new Object();
    private static volatile ScheduledExecutorService sExecutor = newScheduledExecutorService("SINGLE-SYNC", 5);
    private static final AtomicInteger taskCounter = new AtomicInteger(0);

    /* JADX INFO: Access modifiers changed from: package-private */
    /* renamed from: net.blocklegends.utils.Util$1, reason: invalid class name */
    /* loaded from: classes4.dex */
    public class AnonymousClass1 implements ThreadFactory {
        final /* synthetic */ String val$name;
        final /* synthetic */ int val$priority;

        AnonymousClass1(String str, int r2) {
            this.val$name = str;
            this.val$priority = r2;
        }

        /* JADX INFO: Access modifiers changed from: package-private */
        public static /* synthetic */ void lambda$newThread$0(Runnable runnable, String str) {
            try {
                runnable.run();
            } catch (Exception e) {
                Log.e(Util.TAG, "Exception in scheduled executor: " + str, e);
            }
        }

        @Override // java.util.concurrent.ThreadFactory
        public Thread newThread(final Runnable runnable) {
            final String str = this.val$name;
            Thread thread = new Thread(new Runnable() { // from class: net.blocklegends.utils.Util$1$$ExternalSyntheticLambda0
                @Override // java.lang.Runnable
                public final void run() {
                    Util.AnonymousClass1.lambda$newThread$0(runnable, str);
                }
            });
            String str2 = this.val$name;
            if (str2 != null) {
                thread.setName(str2);
            }
            thread.setPriority(this.val$priority);
            thread.setUncaughtExceptionHandler(new Thread.UncaughtExceptionHandler() { // from class: net.blocklegends.utils.Util$1$$ExternalSyntheticLambda1
                @Override // java.lang.Thread.UncaughtExceptionHandler
                public final void uncaughtException(Thread thread2, Throwable th) {
                    Log.e(Util.TAG, "Uncaught exception in thread: " + thread2.getName(), th);
                }
            });
            return thread;
        }
    }

    /* renamed from: net.blocklegends.utils.Util$2, reason: invalid class name */
    /* loaded from: classes4.dex */
    class AnonymousClass2 implements ThreadFactory {
        final /* synthetic */ String val$name;
        final /* synthetic */ int val$priority;

        AnonymousClass2(String str, int r2) {
            this.val$name = str;
            this.val$priority = r2;
        }

        /* JADX INFO: Access modifiers changed from: package-private */
        public static /* synthetic */ void lambda$newThread$0(Runnable runnable, String str) {
            try {
                runnable.run();
            } catch (Exception e) {
                Log.e(Util.TAG, "Exception in executor: " + str, e);
            }
        }

        @Override // java.util.concurrent.ThreadFactory
        public Thread newThread(final Runnable runnable) {
            final String str = this.val$name;
            Thread thread = new Thread(new Runnable() { // from class: net.blocklegends.utils.Util$2$$ExternalSyntheticLambda0
                @Override // java.lang.Runnable
                public final void run() {
                    Util.AnonymousClass2.lambda$newThread$0(runnable, str);
                }
            });
            String str2 = this.val$name;
            if (str2 != null) {
                thread.setName(str2);
            }
            thread.setPriority(this.val$priority);
            thread.setUncaughtExceptionHandler(new Thread.UncaughtExceptionHandler() { // from class: net.blocklegends.utils.Util$2$$ExternalSyntheticLambda1
                @Override // java.lang.Thread.UncaughtExceptionHandler
                public final void uncaughtException(Thread thread2, Throwable th) {
                    Log.e(Util.TAG, "Uncaught exception in thread: " + thread2.getName(), th);
                }
            });
            return thread;
        }
    }

    private static ScheduledExecutorService getExecutor() {
        ScheduledExecutorService scheduledExecutorService;
        ScheduledExecutorService scheduledExecutorService2 = sExecutor;
        if (scheduledExecutorService2 != null && !scheduledExecutorService2.isShutdown()) {
            return scheduledExecutorService2;
        }
        synchronized (LOCK) {
            scheduledExecutorService = sExecutor;
            if (scheduledExecutorService == null || scheduledExecutorService.isShutdown()) {
                scheduledExecutorService = newScheduledExecutorService("SINGLE-SYNC", 5);
                sExecutor = scheduledExecutorService;
                Log.i(TAG, "Executor re-created after shutdown");
            }
        }
        return scheduledExecutorService;
    }

    /* JADX INFO: Access modifiers changed from: package-private */
    public static /* synthetic */ void lambda$run$0(Runnable runnable, int r4) {
        try {
            runnable.run();
        } catch (Exception e) {
            Log.e(TAG, "Exception in task #" + r4, e);
        } catch (Throwable th) {
            Log.e(TAG, "Fatal error in task #" + r4, th);
        }
    }

    public static ExecutorService newExecutorService(String str, int r2) {
        return Executors.newSingleThreadExecutor(new AnonymousClass2(str, r2));
    }

    public static ScheduledExecutorService newScheduledExecutorService(String str, int r2) {
        return Executors.newSingleThreadScheduledExecutor(new AnonymousClass1(str, r2));
    }

    public static Future<?> run(final Runnable runnable) {
        if (runnable == null) {
            Log.w(TAG, "Null runnable passed to run()");
            return null;
        }
        try {
            final int incrementAndGet = taskCounter.incrementAndGet();
            return getExecutor().submit(new Runnable() { // from class: net.blocklegends.utils.Util$$ExternalSyntheticLambda0
                @Override // java.lang.Runnable
                public final void run() {
                    Util.lambda$run$0(runnable, incrementAndGet);
                }
            });
        } catch (RejectedExecutionException unused) {
            Log.w(TAG, "Task rejected (executor shutdown)");
            return null;
        } catch (Exception e) {
            Log.w(TAG, "Failed to submit task: " + e.getMessage());
            return null;
        }
    }

    public static void shutdown() {
        synchronized (LOCK) {
            ScheduledExecutorService scheduledExecutorService = sExecutor;
            if (scheduledExecutorService == null || scheduledExecutorService.isShutdown()) {
                return;
            }
            try {
                Log.i(TAG, "Shutting down executor...");
                scheduledExecutorService.shutdown();
                if (!scheduledExecutorService.awaitTermination(2L, TimeUnit.SECONDS)) {
                    Log.w(TAG, "Executor did not terminate in time, forcing shutdown");
                    scheduledExecutorService.shutdownNow();
                }
            } catch (InterruptedException e) {
                Log.e(TAG, "Interrupted while shutting down executor", e);
                scheduledExecutorService.shutdownNow();
                Thread.currentThread().interrupt();
            }
        }
    }
}
