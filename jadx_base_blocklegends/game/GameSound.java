package net.blocklegends.game;

import android.media.AudioAttributes;
import android.media.MediaPlayer;
import android.media.SoundPool;
import java.util.ArrayList;
import java.util.Iterator;
import java.util.Map;
import java.util.concurrent.Callable;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentLinkedDeque;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.BiFunction;
import java.util.function.Function;
import net.blocklegends.natives.GNatives;
import net.blocklegends.utils.Util;

/* loaded from: classes4.dex */
public class GameSound {
    private static volatile Thread AUDIO_THREAD = null;
    private static final long COOLDOWN_MS_PER_SAMPLE = 35;
    private static volatile boolean IS_SOUND_POOL_CREATED = false;
    private static final int MAX_MEDIA_PLAYERS = 2;
    private static final int MAX_POLYPHONY_PER_SAMPLE = 2;
    private static final int MAX_STARTS_PER_16MS = 5;
    private static final int MAX_STREAMS = 10;
    private static final long WINDOW_NS = 16000000;
    private static volatile SoundPool soundPool;
    public static AtomicInteger mediaPlayerID = new AtomicInteger(100000000);
    private static final ConcurrentHashMap<Integer, MediaPlayer> MEDIA_MAP = new ConcurrentHashMap<>();
    private static final ConcurrentHashMap<Integer, ConcurrentLinkedDeque<Integer>> POLY = new ConcurrentHashMap<>();
    private static final Object POLY_LOCK = new Object();
    private static final ConcurrentHashMap<Integer, Integer> STREAM_TO_SAMPLE = new ConcurrentHashMap<>();
    private static final ConcurrentHashMap<Integer, float[]> LAST_VOLUME = new ConcurrentHashMap<>();
    private static final ConcurrentHashMap<Integer, Long> LAST_PLAY_MS = new ConcurrentHashMap<>();
    private static volatile long windowStartNs = System.nanoTime();
    private static final AtomicInteger startsThisWindow = new AtomicInteger(0);
    private static final Object WINDOW_LOCK = new Object();
    private static final ConcurrentHashMap<Integer, String> SOUND_ID_TO_PATH = new ConcurrentHashMap<>();
    private static final AudioAttributes POOL_ATTRS = new AudioAttributes.Builder().setUsage(14).setContentType(4).build();
    private static final AudioAttributes MP_ATTRS = new AudioAttributes.Builder().setUsage(1).setContentType(2).build();
    private static final ExecutorService AUDIO_EXECUTOR = Executors.newSingleThreadExecutor(new ThreadFactory() { // from class: net.blocklegends.game.GameSound$$ExternalSyntheticLambda8
        @Override // java.util.concurrent.ThreadFactory
        public final Thread newThread(Runnable runnable) {
            return GameSound.lambda$static$1(runnable);
        }
    });

    private static boolean allowStartForSample(int r4) {
        final long currentTimeMillis = System.currentTimeMillis();
        Long compute = LAST_PLAY_MS.compute(Integer.valueOf(r4), new BiFunction() { // from class: net.blocklegends.game.GameSound$$ExternalSyntheticLambda12
            @Override // java.util.function.BiFunction
            public final Object apply(Object obj, Object obj2) {
                return GameSound.lambda$allowStartForSample$5(currentTimeMillis, (Integer) obj, (Long) obj2);
            }
        });
        return compute == null || compute.longValue() == currentTimeMillis;
    }

    private static boolean allowStartGlobal() {
        long nanoTime = System.nanoTime();
        synchronized (WINDOW_LOCK) {
            if (nanoTime - windowStartNs > WINDOW_NS) {
                windowStartNs = nanoTime;
                startsThisWindow.set(0);
            }
            AtomicInteger atomicInteger = startsThisWindow;
            if (atomicInteger.incrementAndGet() <= 5) {
                return true;
            }
            atomicInteger.decrementAndGet();
            return false;
        }
    }

    public static void autoPause() {
        runOnAudioThread(new Runnable() { // from class: net.blocklegends.game.GameSound$$ExternalSyntheticLambda7
            @Override // java.lang.Runnable
            public final void run() {
                GameSound.lambda$autoPause$11();
            }
        });
    }

    public static void autoResume() {
        runOnAudioThread(new Runnable() { // from class: net.blocklegends.game.GameSound$$ExternalSyntheticLambda0
            @Override // java.lang.Runnable
            public final void run() {
                GameSound.lambda$autoResume$12();
            }
        });
    }

    private static <T> T callOnAudioThread(Callable<T> callable, T t) {
        if (isAudioThread()) {
            try {
                return callable.call();
            } catch (Exception unused) {
                return t;
            }
        }
        try {
            return AUDIO_EXECUTOR.submit(callable).get();
        } catch (Exception unused2) {
            return t;
        }
    }

    private static void cleanupPlayer(int r0, MediaPlayer mediaPlayer) {
        try {
            mediaPlayer.reset();
        } catch (Throwable unused) {
        }
        try {
            mediaPlayer.release();
        } catch (Throwable unused2) {
        }
        MEDIA_MAP.remove(Integer.valueOf(r0));
    }

    private static void enforcePolyphonyCap(int r4) {
        ConcurrentLinkedDeque<Integer> computeIfAbsent = POLY.computeIfAbsent(Integer.valueOf(r4), new Function() { // from class: net.blocklegends.game.GameSound$$ExternalSyntheticLambda10
            @Override // java.util.function.Function
            public final Object apply(Object obj) {
                return GameSound.lambda$enforcePolyphonyCap$6((Integer) obj);
            }
        });
        ArrayList<Integer> arrayList = new ArrayList();
        synchronized (POLY_LOCK) {
            while (computeIfAbsent.size() >= 2) {
                Integer pollFirst = computeIfAbsent.pollFirst();
                if (pollFirst != null) {
                    arrayList.add(pollFirst);
                }
            }
        }
        for (Integer num : arrayList) {
            STREAM_TO_SAMPLE.remove(num);
            SoundPool soundPool2 = soundPool;
            if (soundPool2 != null) {
                try {
                    soundPool2.stop(num.intValue());
                } catch (Throwable unused) {
                }
            }
        }
    }

    public static int getCurrentPosition(int r1) {
        MediaPlayer mediaPlayer = MEDIA_MAP.get(Integer.valueOf(r1));
        if (mediaPlayer == null) {
            return -1;
        }
        try {
            return mediaPlayer.getCurrentPosition();
        } catch (Throwable unused) {
            return -1;
        }
    }

    public static synchronized void init() {
        synchronized (GameSound.class) {
            callOnAudioThread(new Callable() { // from class: net.blocklegends.game.GameSound$$ExternalSyntheticLambda9
                @Override // java.util.concurrent.Callable
                public final Object call() {
                    return GameSound.lambda$init$4();
                }
            }, null);
        }
    }

    private static boolean isAudioThread() {
        return Thread.currentThread() == AUDIO_THREAD;
    }

    /* JADX INFO: Access modifiers changed from: package-private */
    public static /* synthetic */ Long lambda$allowStartForSample$5(long j, Integer num, Long l) {
        return (l == null || j - l.longValue() >= COOLDOWN_MS_PER_SAMPLE) ? Long.valueOf(j) : l;
    }

    /* JADX INFO: Access modifiers changed from: package-private */
    public static /* synthetic */ void lambda$autoPause$11() {
        if (soundPool != null) {
            soundPool.autoPause();
        }
    }

    /* JADX INFO: Access modifiers changed from: package-private */
    public static /* synthetic */ void lambda$autoResume$12() {
        if (soundPool != null) {
            soundPool.autoResume();
        }
    }

    /* JADX INFO: Access modifiers changed from: package-private */
    public static /* synthetic */ ConcurrentLinkedDeque lambda$enforcePolyphonyCap$6(Integer num) {
        return new ConcurrentLinkedDeque();
    }

    /* JADX INFO: Access modifiers changed from: package-private */
    public static /* synthetic */ void lambda$init$3(SoundPool soundPool2, final int r1, final int r2) {
        if (r2 == 0) {
            Util.run(new Runnable() { // from class: net.blocklegends.game.GameSound$$ExternalSyntheticLambda3
                @Override // java.lang.Runnable
                public final void run() {
                    GNatives.setOnLoadCompleteListener(r1, r2);
                }
            });
        }
    }

    /* JADX INFO: Access modifiers changed from: package-private */
    public static /* synthetic */ Object lambda$init$4() throws Exception {
        if (IS_SOUND_POOL_CREATED) {
            return null;
        }
        IS_SOUND_POOL_CREATED = true;
        soundPool = new SoundPool.Builder().setMaxStreams(10).setAudioAttributes(POOL_ATTRS).build();
        soundPool.setOnLoadCompleteListener(new SoundPool.OnLoadCompleteListener() { // from class: net.blocklegends.game.GameSound$$ExternalSyntheticLambda23
            @Override // android.media.SoundPool.OnLoadCompleteListener
            public final void onLoadComplete(SoundPool soundPool2, int r2, int r3) {
                GameSound.lambda$init$3(soundPool2, r2, r3);
            }
        });
        windowStartNs = System.nanoTime();
        startsThisWindow.set(0);
        return null;
    }

    /* JADX INFO: Access modifiers changed from: package-private */
    public static /* synthetic */ Integer lambda$load$26(String str) throws Exception {
        try {
            if (soundPool == null) {
                return -1;
            }
            int load = soundPool.load(str, 1);
            if (load > 0) {
                SOUND_ID_TO_PATH.put(Integer.valueOf(load), str);
            }
            return Integer.valueOf(load);
        } catch (Throwable th) {
            th.printStackTrace();
            return -1;
        }
    }

    /* JADX INFO: Access modifiers changed from: package-private */
    public static /* synthetic */ Integer lambda$pause$9(int r2) throws Exception {
        MediaPlayer mediaPlayer = MEDIA_MAP.get(Integer.valueOf(r2));
        if (mediaPlayer == null) {
            return -1;
        }
        try {
            int currentPosition = mediaPlayer.getCurrentPosition();
            mediaPlayer.pause();
            return Integer.valueOf(currentPosition);
        } catch (Throwable unused) {
            return -1;
        }
    }

    /* JADX INFO: Access modifiers changed from: package-private */
    public static /* synthetic */ Integer lambda$play$14(float f, float f2, int r9, float f3, int r11, int r12) throws Exception {
        SoundPool soundPool2 = soundPool;
        if (soundPool2 == null) {
            return 0;
        }
        if ((f <= 0.001f && f2 <= 0.001f) || !allowStartForSample(r9) || !allowStartGlobal()) {
            return 0;
        }
        enforcePolyphonyCap(r9);
        int play = soundPool2.play(r9, f, f2, r11, r12, Math.max(0.5f, Math.min(2.0f, f3)));
        if (play > 0) {
            trackStart(r9, play);
        } else {
            startsThisWindow.decrementAndGet();
        }
        return Integer.valueOf(play);
    }

    /* JADX INFO: Access modifiers changed from: package-private */
    public static /* synthetic */ void lambda$playMedia$15(int r0, MediaPlayer mediaPlayer, int r2) {
        cleanupPlayer(r0, mediaPlayer);
        if (r2 == 100) {
            resetSoundPool();
        }
    }

    /* JADX INFO: Access modifiers changed from: package-private */
    public static /* synthetic */ boolean lambda$playMedia$16(final int r0, final MediaPlayer mediaPlayer, final int r2, int r3) {
        runOnAudioThread(new Runnable() { // from class: net.blocklegends.game.GameSound$$ExternalSyntheticLambda22
            @Override // java.lang.Runnable
            public final void run() {
                GameSound.lambda$playMedia$15(r0, mediaPlayer, r2);
            }
        });
        return true;
    }

    /* JADX INFO: Access modifiers changed from: package-private */
    public static /* synthetic */ void lambda$playMedia$17(int r0, MediaPlayer mediaPlayer, int r2) {
        cleanupPlayer(r0, mediaPlayer);
        try {
            GNatives.setOnCompleteListener(r2);
        } catch (Throwable unused) {
        }
    }

    /* JADX INFO: Access modifiers changed from: package-private */
    public static /* synthetic */ void lambda$playMedia$19(int r1, MediaPlayer mediaPlayer) {
        try {
            if (MEDIA_MAP.containsKey(Integer.valueOf(r1))) {
                mediaPlayer.start();
            }
        } catch (Throwable th) {
            th.printStackTrace();
        }
    }

    /* JADX INFO: Access modifiers changed from: package-private */
    public static /* synthetic */ Integer lambda$playMedia$21(float f, float f2, int r7, String str, final int r9) throws Exception {
        ConcurrentHashMap<Integer, MediaPlayer> concurrentHashMap = MEDIA_MAP;
        if (concurrentHashMap.size() >= 2) {
            Integer nextElement = concurrentHashMap.keys().hasMoreElements() ? concurrentHashMap.keys().nextElement() : null;
            if (nextElement != null) {
                stop(nextElement.intValue());
            }
        }
        final int incrementAndGet = mediaPlayerID.incrementAndGet();
        try {
            MediaPlayer mediaPlayer = new MediaPlayer();
            mediaPlayer.setAudioAttributes(MP_ATTRS);
            mediaPlayer.setVolume(f, f2);
            mediaPlayer.setLooping(r7 == -1);
            mediaPlayer.setDataSource(str);
            mediaPlayer.setOnErrorListener(new MediaPlayer.OnErrorListener() { // from class: net.blocklegends.game.GameSound$$ExternalSyntheticLambda4
                @Override // android.media.MediaPlayer.OnErrorListener
                public final boolean onError(MediaPlayer mediaPlayer2, int r2, int r3) {
                    return GameSound.lambda$playMedia$16(incrementAndGet, mediaPlayer2, r2, r3);
                }
            });
            mediaPlayer.setOnCompletionListener(new MediaPlayer.OnCompletionListener() { // from class: net.blocklegends.game.GameSound$$ExternalSyntheticLambda5
                @Override // android.media.MediaPlayer.OnCompletionListener
                public final void onCompletion(MediaPlayer mediaPlayer2) {
                    GameSound.runOnAudioThread(new Runnable() { // from class: net.blocklegends.game.GameSound$$ExternalSyntheticLambda2
                        @Override // java.lang.Runnable
                        public final void run() {
                            GameSound.lambda$playMedia$17(r1, mediaPlayer2, r3);
                        }
                    });
                }
            });
            concurrentHashMap.put(Integer.valueOf(incrementAndGet), mediaPlayer);
            mediaPlayer.setOnPreparedListener(new MediaPlayer.OnPreparedListener() { // from class: net.blocklegends.game.GameSound$$ExternalSyntheticLambda6
                @Override // android.media.MediaPlayer.OnPreparedListener
                public final void onPrepared(MediaPlayer mediaPlayer2) {
                    GameSound.runOnAudioThread(new Runnable() { // from class: net.blocklegends.game.GameSound$$ExternalSyntheticLambda16
                        @Override // java.lang.Runnable
                        public final void run() {
                            GameSound.lambda$playMedia$19(r1, mediaPlayer2);
                        }
                    });
                }
            });
            mediaPlayer.prepareAsync();
            return Integer.valueOf(incrementAndGet);
        } catch (Throwable th) {
            th.printStackTrace();
            MEDIA_MAP.remove(Integer.valueOf(incrementAndGet));
            return -1;
        }
    }

    /* JADX INFO: Access modifiers changed from: package-private */
    public static /* synthetic */ void lambda$resetSoundPool$8(SoundPool soundPool2, int r1, int r2) {
        if (r2 == 0) {
            try {
                GNatives.setOnLoadCompleteListener(r1, r2);
            } catch (Throwable unused) {
            }
        }
    }

    /* JADX INFO: Access modifiers changed from: package-private */
    public static /* synthetic */ void lambda$resume$10(int r2, int r3) {
        MediaPlayer mediaPlayer = MEDIA_MAP.get(Integer.valueOf(r2));
        if (mediaPlayer == null) {
            return;
        }
        try {
            mediaPlayer.seekTo(r3, 0);
            mediaPlayer.start();
        } catch (Throwable unused) {
        }
    }

    /* JADX INFO: Access modifiers changed from: package-private */
    public static /* synthetic */ void lambda$setLoop$24(int r1, int r2) {
        if (soundPool == null || r1 <= 0) {
            return;
        }
        soundPool.setLoop(r1, r2);
    }

    /* JADX INFO: Access modifiers changed from: package-private */
    public static /* synthetic */ void lambda$setPriority$23(int r1, int r2) {
        if (soundPool == null || r1 <= 0) {
            return;
        }
        soundPool.setPriority(r1, r2);
    }

    /* JADX INFO: Access modifiers changed from: package-private */
    public static /* synthetic */ void lambda$setRate$25(int r2, float f) {
        if (soundPool == null || r2 <= 0) {
            return;
        }
        soundPool.setRate(r2, Math.max(0.5f, Math.min(2.0f, f)));
    }

    /* JADX INFO: Access modifiers changed from: package-private */
    public static /* synthetic */ void lambda$setVolume$22(int r6, float f, float f2) {
        ConcurrentHashMap<Integer, float[]> concurrentHashMap = LAST_VOLUME;
        float[] fArr = concurrentHashMap.get(Integer.valueOf(r6));
        if (fArr != null) {
            float abs = Math.abs(fArr[0] - f);
            float abs2 = Math.abs(fArr[1] - f2);
            if (abs < 0.001f && abs2 < 0.001f) {
                return;
            }
        }
        concurrentHashMap.put(Integer.valueOf(r6), new float[]{f, f2});
        MediaPlayer mediaPlayer = MEDIA_MAP.get(Integer.valueOf(r6));
        if (mediaPlayer != null) {
            try {
                mediaPlayer.setVolume(f, f2);
            } catch (Throwable unused) {
            }
        } else {
            if (soundPool == null || r6 <= 0) {
                return;
            }
            try {
                soundPool.setVolume(r6, f, f2);
            } catch (Throwable unused2) {
            }
        }
    }

    /* JADX INFO: Access modifiers changed from: package-private */
    public static /* synthetic */ Object lambda$shutdown$27() throws Exception {
        for (Map.Entry<Integer, MediaPlayer> entry : MEDIA_MAP.entrySet()) {
            try {
                entry.getValue().stop();
            } catch (Throwable unused) {
            }
            try {
                entry.getValue().release();
            } catch (Throwable unused2) {
            }
        }
        MEDIA_MAP.clear();
        SoundPool soundPool2 = soundPool;
        soundPool = null;
        if (soundPool2 != null) {
            try {
                soundPool2.release();
            } catch (Throwable unused3) {
            }
        }
        IS_SOUND_POOL_CREATED = false;
        POLY.clear();
        STREAM_TO_SAMPLE.clear();
        LAST_PLAY_MS.clear();
        LAST_VOLUME.clear();
        startsThisWindow.set(0);
        return null;
    }

    /* JADX INFO: Access modifiers changed from: package-private */
    public static /* synthetic */ void lambda$static$0(Runnable runnable) {
        AUDIO_THREAD = Thread.currentThread();
        runnable.run();
    }

    /* JADX INFO: Access modifiers changed from: package-private */
    public static /* synthetic */ Thread lambda$static$1(final Runnable runnable) {
        Thread thread = new Thread(new Runnable() { // from class: net.blocklegends.game.GameSound$$ExternalSyntheticLambda19
            @Override // java.lang.Runnable
            public final void run() {
                GameSound.lambda$static$0(runnable);
            }
        }, "GameSound-Audio");
        thread.setPriority(Math.min(6, 10));
        return thread;
    }

    /* JADX INFO: Access modifiers changed from: package-private */
    public static /* synthetic */ void lambda$stop$13(int r3) {
        MediaPlayer mediaPlayer = MEDIA_MAP.get(Integer.valueOf(r3));
        if (mediaPlayer != null) {
            try {
                mediaPlayer.stop();
            } catch (IllegalStateException unused) {
            }
            cleanupPlayer(r3, mediaPlayer);
            LAST_VOLUME.remove(Integer.valueOf(r3));
        } else {
            if (soundPool == null || r3 <= 0) {
                return;
            }
            Integer remove = STREAM_TO_SAMPLE.remove(Integer.valueOf(r3));
            if (remove != null) {
                synchronized (POLY_LOCK) {
                    ConcurrentLinkedDeque<Integer> concurrentLinkedDeque = POLY.get(remove);
                    if (concurrentLinkedDeque != null) {
                        concurrentLinkedDeque.remove(Integer.valueOf(r3));
                    }
                }
                try {
                    soundPool.stop(r3);
                } catch (Throwable unused2) {
                }
            }
            LAST_VOLUME.remove(Integer.valueOf(r3));
        }
    }

    /* JADX INFO: Access modifiers changed from: package-private */
    public static /* synthetic */ ConcurrentLinkedDeque lambda$trackStart$7(Integer num) {
        return new ConcurrentLinkedDeque();
    }

    public static int load(final String str) {
        return ((Integer) callOnAudioThread(new Callable() { // from class: net.blocklegends.game.GameSound$$ExternalSyntheticLambda1
            @Override // java.util.concurrent.Callable
            public final Object call() {
                return GameSound.lambda$load$26(str);
            }
        }, -1)).intValue();
    }

    public static int pause(final int r1) {
        return ((Integer) callOnAudioThread(new Callable() { // from class: net.blocklegends.game.GameSound$$ExternalSyntheticLambda14
            @Override // java.util.concurrent.Callable
            public final Object call() {
                return GameSound.lambda$pause$9(r1);
            }
        }, -1)).intValue();
    }

    public static int play(final int r7, final float f, final float f2, final int r10, final int r11, final float f3) {
        return ((Integer) callOnAudioThread(new Callable() { // from class: net.blocklegends.game.GameSound$$ExternalSyntheticLambda18
            @Override // java.util.concurrent.Callable
            public final Object call() {
                return GameSound.lambda$play$14(f, f2, r7, f3, r10, r11);
            }
        }, 0)).intValue();
    }

    public static int playMedia(final String str, final float f, final float f2, final int r9, final int r10) {
        return ((Integer) callOnAudioThread(new Callable() { // from class: net.blocklegends.game.GameSound$$ExternalSyntheticLambda15
            @Override // java.util.concurrent.Callable
            public final Object call() {
                return GameSound.lambda$playMedia$21(f, f2, r9, str, r10);
            }
        }, -1)).intValue();
    }

    private static synchronized void resetSoundPool() {
        synchronized (GameSound.class) {
            try {
                if (soundPool != null) {
                    soundPool.release();
                }
            } catch (Throwable unused) {
            }
            soundPool = new SoundPool.Builder().setMaxStreams(10).setAudioAttributes(POOL_ATTRS).build();
            soundPool.setOnLoadCompleteListener(new SoundPool.OnLoadCompleteListener() { // from class: net.blocklegends.game.GameSound$$ExternalSyntheticLambda11
                @Override // android.media.SoundPool.OnLoadCompleteListener
                public final void onLoadComplete(SoundPool soundPool2, int r2, int r3) {
                    GameSound.lambda$resetSoundPool$8(soundPool2, r2, r3);
                }
            });
            Iterator<Map.Entry<Integer, String>> it = SOUND_ID_TO_PATH.entrySet().iterator();
            while (it.hasNext()) {
                try {
                    soundPool.load(it.next().getValue(), 1);
                } catch (Throwable unused2) {
                }
            }
            POLY.clear();
            STREAM_TO_SAMPLE.clear();
            LAST_PLAY_MS.clear();
            windowStartNs = System.nanoTime();
            startsThisWindow.set(0);
        }
    }

    public static void resume(final int r1, final int r2) {
        runOnAudioThread(new Runnable() { // from class: net.blocklegends.game.GameSound$$ExternalSyntheticLambda17
            @Override // java.lang.Runnable
            public final void run() {
                GameSound.lambda$resume$10(r1, r2);
            }
        });
    }

    /* JADX INFO: Access modifiers changed from: private */
    public static void runOnAudioThread(Runnable runnable) {
        if (isAudioThread()) {
            runnable.run();
        } else {
            AUDIO_EXECUTOR.execute(runnable);
        }
    }

    public static void setLoop(final int r1, final int r2) {
        runOnAudioThread(new Runnable() { // from class: net.blocklegends.game.GameSound$$ExternalSyntheticLambda26
            @Override // java.lang.Runnable
            public final void run() {
                GameSound.lambda$setLoop$24(r1, r2);
            }
        });
    }

    public static void setPriority(final int r1, final int r2) {
        runOnAudioThread(new Runnable() { // from class: net.blocklegends.game.GameSound$$ExternalSyntheticLambda27
            @Override // java.lang.Runnable
            public final void run() {
                GameSound.lambda$setPriority$23(r1, r2);
            }
        });
    }

    public static void setRate(final int r1, final float f) {
        runOnAudioThread(new Runnable() { // from class: net.blocklegends.game.GameSound$$ExternalSyntheticLambda20
            @Override // java.lang.Runnable
            public final void run() {
                GameSound.lambda$setRate$25(r1, f);
            }
        });
    }

    public static void setVolume(final int r1, final float f, final float f2) {
        runOnAudioThread(new Runnable() { // from class: net.blocklegends.game.GameSound$$ExternalSyntheticLambda25
            @Override // java.lang.Runnable
            public final void run() {
                GameSound.lambda$setVolume$22(r1, f, f2);
            }
        });
    }

    public static synchronized void shutdown() {
        synchronized (GameSound.class) {
            callOnAudioThread(new Callable() { // from class: net.blocklegends.game.GameSound$$ExternalSyntheticLambda13
                @Override // java.util.concurrent.Callable
                public final Object call() {
                    return GameSound.lambda$shutdown$27();
                }
            }, null);
        }
    }

    public static void stop(final int r1) {
        runOnAudioThread(new Runnable() { // from class: net.blocklegends.game.GameSound$$ExternalSyntheticLambda24
            @Override // java.lang.Runnable
            public final void run() {
                GameSound.lambda$stop$13(r1);
            }
        });
    }

    private static void trackStart(int r3, int r4) {
        if (r4 <= 0) {
            return;
        }
        STREAM_TO_SAMPLE.put(Integer.valueOf(r4), Integer.valueOf(r3));
        synchronized (POLY_LOCK) {
            POLY.computeIfAbsent(Integer.valueOf(r3), new Function() { // from class: net.blocklegends.game.GameSound$$ExternalSyntheticLambda21
                @Override // java.util.function.Function
                public final Object apply(Object obj) {
                    return GameSound.lambda$trackStart$7((Integer) obj);
                }
            }).addLast(Integer.valueOf(r4));
        }
    }

    private static void trackStop(int r3) {
        Integer remove = STREAM_TO_SAMPLE.remove(Integer.valueOf(r3));
        if (remove == null) {
            return;
        }
        synchronized (POLY_LOCK) {
            ConcurrentLinkedDeque<Integer> concurrentLinkedDeque = POLY.get(remove);
            if (concurrentLinkedDeque != null) {
                concurrentLinkedDeque.remove(Integer.valueOf(r3));
            }
        }
    }
}
