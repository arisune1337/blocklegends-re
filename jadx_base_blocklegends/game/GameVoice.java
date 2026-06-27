package net.blocklegends.game;

import android.content.Context;
import android.media.AudioAttributes;
import android.media.AudioDeviceInfo;
import android.media.AudioFormat;
import android.media.AudioManager;
import android.media.AudioRecord;
import android.media.AudioRecordingConfiguration;
import android.media.AudioTrack;
import android.media.audiofx.AcousticEchoCanceler;
import android.media.audiofx.AudioEffect;
import android.media.audiofx.NoiseSuppressor;
import android.os.Build;
import android.util.Log;
import androidx.core.app.ActivityCompat;
import androidx.core.content.ContextCompat;
import androidx.core.os.EnvironmentCompat;
import androidx.credentials.exceptions.publickeycredential.DomExceptionUtils;
import androidx.credentials.provider.CredentialEntry;
import java.util.Iterator;
import java.util.List;
import kotlinx.serialization.json.internal.AbstractJsonLexerKt;
import net.blocklegends.MainActivity;
import net.blocklegends.MainApp;

/* loaded from: classes2.dex */
public final class GameVoice {
    private static final int CAPTURE_ALL_SOURCES_MASK;
    private static final int CAPTURE_DEFAULT_SOURCE_INDEX = 0;
    private static final int CAPTURE_MIC_SOURCE_INDEX = 1;
    private static final int[] CAPTURE_SOURCES;
    private static final String PERMISSION_PREFS = "cr_voice_permissions";
    private static final int PLAYBACK_HARDWARE_DELAY_MS = 20;
    private static final int PLAYBACK_HEAD_STALL_TIMEOUT_MS = 200;
    private static final int PLAYBACK_MAX_DELAY_MS = 240;
    private static final int PLAYBACK_MIN_DELAY_MS = 20;
    private static final int PLAYOUT_BUFFER_MS = 240;
    private static final int PLAYOUT_PREBUFFER_MS = 60;
    private static final int PLAYOUT_TARGET_QUEUE_MS = 80;
    private static final String RECORD_PERMISSION_REQUESTED_KEY = "record_permission_requested";
    public static final int REQUEST_RECORD_AUDIO = 3817;
    private static final boolean REQUIRE_AEC_FOR_SPEAKER = true;
    private static final int SILENT_FALLBACK_READS = 100;
    private static boolean audioModeActive;
    private static boolean captureAecSilenceLogged;
    private static boolean captureAllSourcesSilentLogged;
    private static int captureFrameSamples;
    private static int captureReadFailures;
    private static boolean captureRuntimeBlockedLogged;
    private static int captureSampleRate;
    private static int captureSilentReads;
    private static int captureSourceIndex;
    private static int captureTriedSourceMask;
    private static int captureUnhealthySourceMask;
    private static boolean communicationDeviceSet;
    private static AcousticEchoCanceler echoCanceler;
    private static boolean echoCancelerActive;
    private static volatile boolean nativeWebRtcAecActive;
    private static NoiseSuppressor noiseSuppressor;
    private static boolean noiseSuppressorActive;
    private static int playbackBufferedSamples;
    private static int playbackDelayMs;
    private static int playbackFrameSamples;
    private static long playbackFramesWritten;
    private static long playbackHeadStallSinceMs;
    private static boolean playbackHeadUnreliable;
    private static long playbackHeadWraps;
    private static int playbackLastHeadRaw;
    private static long playbackLastPlayedFrames;
    private static int playbackOverruns;
    private static int playbackPrebufferSamples;
    private static boolean playbackPrimed;
    private static int playbackReadIndex;
    private static short[] playbackRing;
    private static int playbackRingCapacity;
    private static boolean playbackRunning;
    private static int playbackSampleRate;
    private static short[] playbackScratch;
    private static int playbackTargetQueueSamples;
    private static Thread playbackThread;
    private static int playbackUnderruns;
    private static int playbackWriteIndex;
    private static AudioTrack player;
    private static boolean previousMicrophoneMute;
    private static boolean previousSpeakerphoneOn;
    private static AudioRecord recorder;
    private static final Object CAPTURE_LOCK = new Object();
    private static final Object PLAYBACK_LOCK = new Object();
    private static int playbackSmoothedDelayMs = -1;
    private static int previousAudioMode = 0;

    static {
        int[] r0 = {7, 1, 6, 0};
        CAPTURE_SOURCES = r0;
        CAPTURE_ALL_SOURCES_MASK = (1 << r0.length) - 1;
    }

    private GameVoice() {
    }

    private static int clampPlaybackDelayMs(int r1) {
        if (r1 < 20) {
            return 20;
        }
        if (r1 > 240) {
            return 240;
        }
        return r1;
    }

    private static void clearCommunicationDeviceLocked(AudioManager audioManager) {
        if (!communicationDeviceSet || audioManager == null || Build.VERSION.SDK_INT < 31) {
            return;
        }
        try {
            Log.i("CRVoice", "AudioManager communication device clearing current=" + deviceName(audioManager.getCommunicationDevice()));
            audioManager.clearCommunicationDevice();
        } catch (Throwable th) {
            try {
                Log.w("CRVoice", "AudioManager communication device clear failed", th);
            } finally {
                communicationDeviceSet = false;
            }
        }
    }

    private static void configureAudioModeLocked() {
        if (audioModeActive) {
            return;
        }
        Context app = MainApp.getApp();
        if (app == null) {
            app = MainActivity.getActivity();
        }
        if (app == null) {
            return;
        }
        try {
            AudioManager audioManager = (AudioManager) app.getSystemService("audio");
            if (audioManager == null) {
                return;
            }
            previousAudioMode = audioManager.getMode();
            previousMicrophoneMute = audioManager.isMicrophoneMute();
            previousSpeakerphoneOn = audioManager.isSpeakerphoneOn();
            audioManager.setMode(3);
            if (!selectCommunicationSpeakerLocked(audioManager)) {
                audioManager.setSpeakerphoneOn(true);
            }
            audioManager.setMicrophoneMute(false);
            audioModeActive = true;
            Log.i("CRVoice", "AudioManager voice mode enabled previousMode=" + previousAudioMode + " modeNow=" + audioManager.getMode() + " speakerphoneNow=" + audioManager.isSpeakerphoneOn() + " communicationDevice=" + currentCommunicationDeviceName(audioManager) + " microphoneMuteNow=" + audioManager.isMicrophoneMute());
        } catch (Throwable th) {
            Log.w("CRVoice", "AudioManager voice mode failed", th);
        }
    }

    private static void configureEffectsLocked(int r16, int r17) {
        boolean z;
        releaseEffectsLocked();
        if (r16 <= 0) {
            return;
        }
        if (isNativeWebRtcAecActive()) {
            System.setProperty("cr.voice.platformAec.active", CredentialEntry.FALSE_STRING);
            Log.i("CRVoice", "Platform voice effects skipped: WebRTC AEC3 native processor active session=" + r16 + " source=" + sourceName(r17));
            return;
        }
        boolean z2 = false;
        if (AcousticEchoCanceler.isAvailable()) {
            try {
                AcousticEchoCanceler create = AcousticEchoCanceler.create(r16);
                if (create != null) {
                    int enabled = create.setEnabled(true);
                    try {
                        z = create.getEnabled();
                    } catch (Throwable unused) {
                        z = false;
                    }
                    String effectDescriptor = effectDescriptor(create);
                    if (z) {
                        echoCanceler = create;
                        echoCancelerActive = true;
                        System.setProperty("cr.voice.platformAec.active", CredentialEntry.TRUE_STRING);
                    } else {
                        create.release();
                    }
                    Log.i("CRVoice", "AcousticEchoCanceler enabled result=" + enabled + " active=" + z + " descriptor=" + effectDescriptor + " session=" + r16 + " source=" + sourceName(r17));
                } else {
                    Log.w("CRVoice", "AcousticEchoCanceler create returned null session=" + r16 + " source=" + sourceName(r17));
                }
            } catch (Throwable th) {
                Log.w("CRVoice", "AcousticEchoCanceler enable failed", th);
            }
        } else {
            Log.i("CRVoice", "AcousticEchoCanceler unavailable");
        }
        if (!NoiseSuppressor.isAvailable()) {
            Log.i("CRVoice", "NoiseSuppressor unavailable");
            return;
        }
        try {
            NoiseSuppressor create2 = NoiseSuppressor.create(r16);
            if (create2 == null) {
                Log.w("CRVoice", "NoiseSuppressor create returned null session=" + r16 + " source=" + sourceName(r17));
                return;
            }
            int enabled2 = create2.setEnabled(true);
            try {
                z2 = create2.getEnabled();
            } catch (Throwable unused2) {
            }
            String effectDescriptor2 = effectDescriptor(create2);
            if (z2) {
                noiseSuppressor = create2;
                noiseSuppressorActive = true;
            } else {
                create2.release();
            }
            Log.i("CRVoice", "NoiseSuppressor enabled result=" + enabled2 + " active=" + z2 + " descriptor=" + effectDescriptor2 + " session=" + r16 + " source=" + sourceName(r17));
        } catch (Throwable th2) {
            Log.w("CRVoice", "NoiseSuppressor enable failed", th2);
        }
    }

    private static int copyPlaybackFromRingLocked(short[] sArr, int r5) {
        int min = Math.min(r5, playbackBufferedSamples);
        if (min <= 0) {
            return 0;
        }
        int min2 = Math.min(min, playbackRingCapacity - playbackReadIndex);
        System.arraycopy(playbackRing, playbackReadIndex, sArr, 0, min2);
        int r2 = min - min2;
        if (r2 > 0) {
            System.arraycopy(playbackRing, 0, sArr, min2, r2);
        }
        playbackReadIndex = (playbackReadIndex + min) % playbackRingCapacity;
        playbackBufferedSamples -= min;
        return min;
    }

    private static void copyPlaybackToRingLocked(short[] sArr, int r4, int r5) {
        int min = Math.min(r5, playbackRingCapacity - playbackWriteIndex);
        System.arraycopy(sArr, r4, playbackRing, playbackWriteIndex, min);
        int r1 = r5 - min;
        if (r1 > 0) {
            System.arraycopy(sArr, r4 + min, playbackRing, 0, r1);
        }
        playbackWriteIndex = (playbackWriteIndex + r5) % playbackRingCapacity;
        playbackBufferedSamples += r5;
    }

    private static String currentCommunicationDeviceName(AudioManager audioManager) {
        if (audioManager == null || Build.VERSION.SDK_INT < 31) {
            return "legacy-speakerphone";
        }
        try {
            return deviceName(audioManager.getCommunicationDevice());
        } catch (Throwable unused) {
            return EnvironmentCompat.MEDIA_UNKNOWN;
        }
    }

    private static String deviceListName(List<AudioDeviceInfo> list) {
        if (list == null || list.isEmpty()) {
            return "[]";
        }
        StringBuilder sb = new StringBuilder("[");
        for (int r1 = 0; r1 < list.size(); r1++) {
            if (r1 > 0) {
                sb.append(", ");
            }
            sb.append(deviceName(list.get(r1)));
        }
        sb.append(AbstractJsonLexerKt.END_LIST);
        return sb.toString();
    }

    /* JADX WARN: Removed duplicated region for block: B:12:0x003a  */
    /*
        Code decompiled incorrectly, please refer to instructions dump.
        To view partially-correct add '--show-bad-code' argument
    */
    private static java.lang.String deviceName(android.media.AudioDeviceInfo r4) {
        /*
            java.lang.String r0 = ""
            if (r4 != 0) goto L7
            java.lang.String r4 = "null"
            return r4
        L7:
            java.lang.CharSequence r1 = r4.getProductName()     // Catch: java.lang.Throwable -> L13
            if (r1 != 0) goto Le
            goto L13
        Le:
            java.lang.String r1 = r1.toString()     // Catch: java.lang.Throwable -> L13
            goto L14
        L13:
            r1 = r0
        L14:
            java.lang.StringBuilder r2 = new java.lang.StringBuilder
            r2.<init>()
            int r3 = r4.getType()
            java.lang.String r3 = deviceTypeName(r3)
            java.lang.StringBuilder r2 = r2.append(r3)
            java.lang.String r3 = "#"
            java.lang.StringBuilder r2 = r2.append(r3)
            int r4 = r4.getId()
            java.lang.StringBuilder r4 = r2.append(r4)
            int r2 = r1.length()
            if (r2 != 0) goto L3a
            goto L4f
        L3a:
            java.lang.StringBuilder r0 = new java.lang.StringBuilder
            java.lang.String r2 = "("
            r0.<init>(r2)
            java.lang.StringBuilder r0 = r0.append(r1)
            java.lang.String r1 = ")"
            java.lang.StringBuilder r0 = r0.append(r1)
            java.lang.String r0 = r0.toString()
        L4f:
            java.lang.StringBuilder r4 = r4.append(r0)
            java.lang.String r4 = r4.toString()
            return r4
        */
        throw new UnsupportedOperationException("Method not decompiled: net.blocklegends.game.GameVoice.deviceName(android.media.AudioDeviceInfo):java.lang.String");
    }

    private static String deviceTypeName(int r1) {
        return r1 != 1 ? r1 != 2 ? r1 != 3 ? r1 != 4 ? r1 != 7 ? r1 != 8 ? r1 != 22 ? String.valueOf(r1) : "USB_HEADSET" : "BLUETOOTH_A2DP" : "BLUETOOTH_SCO" : "WIRED_HEADPHONES" : "WIRED_HEADSET" : "SPEAKER" : "EARPIECE";
    }

    private static void dropPlaybackSamplesLocked(int r2) {
        int min = Math.min(r2, playbackBufferedSamples);
        if (min <= 0) {
            return;
        }
        playbackReadIndex = (playbackReadIndex + min) % playbackRingCapacity;
        playbackBufferedSamples -= min;
    }

    private static String effectDescriptor(AudioEffect audioEffect) {
        if (audioEffect == null) {
            return AbstractJsonLexerKt.NULL;
        }
        try {
            AudioEffect.Descriptor descriptor = audioEffect.getDescriptor();
            return descriptor == null ? AbstractJsonLexerKt.NULL : descriptor.name + DomExceptionUtils.SEPARATOR + descriptor.implementor;
        } catch (Throwable unused) {
            return EnvironmentCompat.MEDIA_UNKNOWN;
        }
    }

    private static void ensurePlaybackBufferLocked(int r3, int r4) {
        int r0 = (r3 * 240) / 1000;
        int r1 = (r3 * 60) / 1000;
        int playbackTargetQueueSamplesFor = playbackTargetQueueSamplesFor(r3, r4);
        int r2 = r4 * 8;
        if (r0 < r2) {
            r0 = r2;
        }
        if (r1 < r4) {
            r1 = r4;
        }
        if (r1 < playbackTargetQueueSamplesFor) {
            r1 = playbackTargetQueueSamplesFor;
        }
        int r22 = (r4 * 2) + r1;
        if (r0 < r22) {
            r0 = r22;
        }
        short[] sArr = playbackRing;
        if (sArr == null || sArr.length < r0) {
            playbackRing = new short[r0];
        }
        short[] sArr2 = playbackScratch;
        if (sArr2 == null || sArr2.length < r4) {
            playbackScratch = new short[r4];
        }
        playbackRingCapacity = r0;
        playbackPrebufferSamples = r1;
        playbackTargetQueueSamples = playbackTargetQueueSamplesFor;
    }

    private static int estimatePlaybackDelayMs(int r4, int r5) {
        if (r4 <= 0 || r5 <= 0) {
            return 0;
        }
        int r42 = (int) (((r4 / 2) * 1000) / r5);
        if (r42 < 40) {
            return 40;
        }
        if (r42 > 240) {
            return 240;
        }
        return r42;
    }

    private static int firstCaptureSourceIndex() {
        return isNativeWebRtcAecActive() ? 1 : 0;
    }

    public static int getPlaybackDelayMs() {
        synchronized (PLAYBACK_LOCK) {
            AudioTrack audioTrack = player;
            if (audioTrack != null) {
                return updatePlaybackDelayLocked(audioTrack);
            }
            return playbackDelayMs;
        }
    }

    private static long getPlaybackTrackPendingFramesLocked(AudioTrack audioTrack) {
        long updatePlaybackPlayedFramesLocked = updatePlaybackPlayedFramesLocked(audioTrack);
        if (updatePlaybackPlayedFramesLocked < 0) {
            return -1L;
        }
        long j = playbackFramesWritten - updatePlaybackPlayedFramesLocked;
        if (j < 0) {
            return 0L;
        }
        return j;
    }

    private static void handleCaptureDigitalSilenceLocked(int r4) {
        String sourceName = sourceName(CAPTURE_SOURCES[captureSourceIndex]);
        if (!captureAecSilenceLogged) {
            captureAecSilenceLogged = true;
            Log.w("CRVoice", "AudioRecord produced digital silence on " + sourceName + " for " + r4 + " reads platformAec=" + echoCancelerActive + " noiseSuppressor=" + noiseSuppressorActive + " nativeWebRtcAec=" + isNativeWebRtcAecActive() + (isNativeWebRtcAecActive() ? ", probing alternate WebRTC capture source" : ", probing alternate capture source"));
            logActiveRecordingConfigsLocked("digital-silence-" + sourceName);
        }
        if (isNativeWebRtcAecActive() && captureSourceIndex != 1 && !isCaptureSourceUnhealthyLocked(1)) {
            markCurrentCaptureSourceUnhealthyLocked();
            if (restartCaptureAfterReadFailureLocked("digital-silence-webrtc-mic", 1)) {
                return;
            }
        }
        if (!hasHealthyAlternateCaptureSourceLocked()) {
            logAllCaptureSourcesSilentLocked();
            return;
        }
        markCurrentCaptureSourceUnhealthyLocked();
        if (restartCaptureAfterReadFailureLocked(isNativeWebRtcAecActive() ? "digital-silence-webrtc" : "digital-silence")) {
            return;
        }
        logAllCaptureSourcesSilentLocked();
    }

    private static boolean hasHealthyAlternateCaptureSourceLocked() {
        return ((~(1 << captureSourceIndex)) & (CAPTURE_ALL_SOURCES_MASK & (~captureUnhealthySourceMask))) != 0;
    }

    public static boolean hasRecordPermission() {
        Context app = MainApp.getApp();
        if (app == null) {
            app = MainActivity.getActivity();
        }
        return app != null && ContextCompat.checkSelfPermission(app, "android.permission.RECORD_AUDIO") == 0;
    }

    private static boolean isCaptureSourceUnhealthyLocked(int r2) {
        if (r2 < 0 || r2 >= CAPTURE_SOURCES.length) {
            return false;
        }
        return ((1 << r2) & captureUnhealthySourceMask) != 0;
    }

    private static boolean isNativeWebRtcAecActive() {
        return nativeWebRtcAecActive || CredentialEntry.TRUE_STRING.equalsIgnoreCase(System.getProperty("cr.voice.webrtcAec.active", CredentialEntry.FALSE_STRING));
    }

    public static boolean isPlatformAecActive() {
        boolean z;
        synchronized (CAPTURE_LOCK) {
            z = echoCancelerActive;
        }
        return z;
    }

    private static boolean isPlaybackHeadStalledLocked(long j) {
        long j2 = playbackFramesWritten - j;
        if (j2 > playbackLastPlayedFrames) {
            playbackLastPlayedFrames = j2;
            playbackHeadStallSinceMs = 0L;
            return false;
        }
        long currentTimeMillis = System.currentTimeMillis();
        long j3 = playbackHeadStallSinceMs;
        if (j3 > 0) {
            return currentTimeMillis - j3 >= 200;
        }
        playbackHeadStallSinceMs = currentTimeMillis;
        return false;
    }

    public static boolean isRecordPermissionDenied() {
        Context app = MainApp.getApp();
        if (app == null) {
            app = MainActivity.getActivity();
        }
        if (app == null || hasRecordPermission()) {
            return false;
        }
        return app.getSharedPreferences(PERMISSION_PREFS, 0).getBoolean(RECORD_PERMISSION_REQUESTED_KEY, false);
    }

    public static boolean isSupported() {
        return true;
    }

    private static void logActiveRecordingConfigsLocked(String str) {
        Context app = MainApp.getApp();
        if (app == null) {
            app = MainActivity.getActivity();
        }
        if (app == null) {
            return;
        }
        try {
            AudioManager audioManager = (AudioManager) app.getSystemService("audio");
            if (audioManager == null) {
                return;
            }
            List<AudioRecordingConfiguration> activeRecordingConfigurations = audioManager.getActiveRecordingConfigurations();
            if (activeRecordingConfigurations != null && !activeRecordingConfigurations.isEmpty()) {
                StringBuilder sb = new StringBuilder();
                sb.append("recording configs ").append(str).append(": count=").append(activeRecordingConfigurations.size());
                for (int r2 = 0; r2 < activeRecordingConfigurations.size(); r2++) {
                    AudioRecordingConfiguration audioRecordingConfiguration = activeRecordingConfigurations.get(r2);
                    sb.append(" [source=").append(sourceName(audioRecordingConfiguration.getClientAudioSource())).append(" session=").append(audioRecordingConfiguration.getClientAudioSessionId()).append(" rate=").append(audioRecordingConfiguration.getClientFormat().getSampleRate());
                    if (Build.VERSION.SDK_INT >= 29) {
                        sb.append(" silenced=").append(audioRecordingConfiguration.isClientSilenced());
                    }
                    sb.append(AbstractJsonLexerKt.END_LIST);
                }
                Log.i("CRVoice", sb.toString());
                return;
            }
            Log.w("CRVoice", "recording configs " + str + ": none");
        } catch (Throwable th) {
            Log.w("CRVoice", "recording config log failed " + str, th);
        }
    }

    private static void logAllCaptureSourcesSilentLocked() {
        if (captureAllSourcesSilentLogged) {
            return;
        }
        captureAllSourcesSilentLogged = true;
        Log.w("CRVoice", "AudioRecord has no healthy alternate capture source; continuing with the best available source and waiting for non-zero input");
        logActiveRecordingConfigsLocked("all-silent");
    }

    private static void markCurrentCaptureSourceUnhealthyLocked() {
        int r0 = captureSourceIndex;
        if (r0 < 0 || r0 >= CAPTURE_SOURCES.length) {
            return;
        }
        captureUnhealthySourceMask = (1 << r0) | captureUnhealthySourceMask;
    }

    private static int pcmPeak(short[] sArr, int r3, int r4) {
        int r42 = r4 + r3;
        int r0 = 0;
        while (r3 < r42) {
            short s = sArr[r3];
            int r1 = s;
            if (s < 0) {
                r1 = -s;
            }
            if (r1 > r0) {
                r0 = r1;
            }
            r3++;
        }
        return r0;
    }

    private static long playbackFrameDurationNs(int r6, int r7) {
        if (r6 <= 0 || r7 <= 0) {
            return 20000000L;
        }
        long j = (r7 * 1000000000) / r6;
        if (j <= 0) {
            return 20000000L;
        }
        return j;
    }

    private static long playbackQueueWaitNs(long j, int r4, long j2, int r7, int r8) {
        if (r8 > 0) {
            long j3 = r7;
            if (j > j3) {
                long j4 = (((j - j3) + r4) * 1000000000) / r8;
                if (j4 <= 0) {
                    return 1000000L;
                }
                return Math.min(j4, j2);
            }
        }
        return j2;
    }

    private static int playbackTargetQueueSamplesFor(int r0, int r1) {
        int r02 = (r0 * 80) / 1000;
        int r12 = r1 * 3;
        return r02 < r12 ? r12 : r02;
    }

    public static int readCapture(short[] sArr, int r7, int r8) {
        int r2 = -1;
        if (sArr == null || r7 < 0 || r8 <= 0 || r7 > sArr.length - r8) {
            return -1;
        }
        synchronized (CAPTURE_LOCK) {
            AudioRecord audioRecord = recorder;
            if (audioRecord == null) {
                return -1;
            }
            try {
                r2 = audioRecord.read(sArr, r7, r8, 1);
            } catch (Throwable unused) {
            }
            int pcmPeak = r2 > 0 ? pcmPeak(sArr, r7, r2) : 0;
            if (r2 < 0) {
                int r6 = captureReadFailures + 1;
                captureReadFailures = r6;
                if (r6 >= 3) {
                    Log.w("CRVoice", "AudioRecord read failed (" + r2 + ") on " + sourceName(CAPTURE_SOURCES[captureSourceIndex]) + ", trying fallback");
                    markCurrentCaptureSourceUnhealthyLocked();
                    restartCaptureAfterReadFailureLocked("read-failure");
                }
            } else if (r2 > 0) {
                captureReadFailures = 0;
                if (pcmPeak == 0) {
                    int r62 = captureSilentReads + 1;
                    captureSilentReads = r62;
                    if (r62 >= 100) {
                        handleCaptureDigitalSilenceLocked(r62);
                    }
                } else {
                    if (captureSilentReads >= 100) {
                        Log.i("CRVoice", "AudioRecord recovered non-zero input on " + sourceName(CAPTURE_SOURCES[captureSourceIndex]) + " peak=" + pcmPeak);
                    }
                    captureSilentReads = 0;
                    captureAllSourcesSilentLogged = false;
                    captureAecSilenceLogged = false;
                    captureUnhealthySourceMask = 0;
                }
            }
            return r2;
        }
    }

    private static int readPlaybackFrame(AudioTrack audioTrack, int r13, long j) {
        synchronized (PLAYBACK_LOCK) {
            while (playbackRunning && audioTrack == player) {
                short[] sArr = playbackScratch;
                if (sArr == null || sArr.length < r13) {
                    playbackScratch = new short[r13];
                }
                long playbackTrackPendingFramesLocked = getPlaybackTrackPendingFramesLocked(audioTrack);
                if (playbackTrackPendingFramesLocked < 0) {
                    playbackTrackPendingFramesLocked = 0;
                }
                if (!playbackHeadUnreliable && playbackTrackPendingFramesLocked >= playbackTargetQueueSamples) {
                    if (isPlaybackHeadStalledLocked(playbackTrackPendingFramesLocked)) {
                        playbackHeadUnreliable = true;
                        Log.w("CRVoice", "AudioTrack playback head not advancing for 200ms; disabling head-based back-pressure (write-paced playback)");
                    } else if (!waitPlaybackLocked(playbackQueueWaitNs(playbackTrackPendingFramesLocked, r13, j, playbackTargetQueueSamples, playbackSampleRate))) {
                        return -1;
                    }
                }
                if (!playbackPrimed) {
                    if (playbackBufferedSamples >= playbackPrebufferSamples) {
                        playbackPrimed = true;
                    } else {
                        try {
                            try {
                                PLAYBACK_LOCK.wait(20L);
                            } catch (Throwable unused) {
                                return -1;
                            }
                        } catch (InterruptedException unused2) {
                            Thread.currentThread().interrupt();
                            return -1;
                        }
                    }
                }
                long playbackTrackPendingFramesLocked2 = getPlaybackTrackPendingFramesLocked(audioTrack);
                if (playbackTrackPendingFramesLocked2 < 0) {
                    playbackTrackPendingFramesLocked2 = 0;
                }
                while (!playbackHeadUnreliable && playbackBufferedSamples < r13 && playbackTrackPendingFramesLocked2 >= r13) {
                    if (!waitPlaybackLocked(Math.min(j, 10000000L))) {
                        return -1;
                    }
                    if (playbackRunning && audioTrack == player) {
                        playbackTrackPendingFramesLocked2 = getPlaybackTrackPendingFramesLocked(audioTrack);
                        if (playbackTrackPendingFramesLocked2 < 0) {
                            playbackTrackPendingFramesLocked2 = 0;
                        }
                    }
                    return -1;
                }
                int copyPlaybackFromRingLocked = copyPlaybackFromRingLocked(playbackScratch, r13);
                if (copyPlaybackFromRingLocked < r13) {
                    zeroPlaybackScratchLocked(copyPlaybackFromRingLocked, r13);
                    playbackUnderruns++;
                }
                updatePlaybackDelayLocked(audioTrack);
                return r13;
            }
            return -1;
        }
    }

    private static void releaseEffectsLocked() {
        AcousticEchoCanceler acousticEchoCanceler = echoCanceler;
        echoCanceler = null;
        echoCancelerActive = false;
        System.setProperty("cr.voice.platformAec.active", CredentialEntry.FALSE_STRING);
        if (acousticEchoCanceler != null) {
            try {
                acousticEchoCanceler.setEnabled(false);
            } catch (Throwable unused) {
            }
            try {
                acousticEchoCanceler.release();
            } catch (Throwable unused2) {
            }
        }
        NoiseSuppressor noiseSuppressor2 = noiseSuppressor;
        noiseSuppressor = null;
        noiseSuppressorActive = false;
        if (noiseSuppressor2 != null) {
            try {
                noiseSuppressor2.setEnabled(false);
            } catch (Throwable unused3) {
            }
            try {
                noiseSuppressor2.release();
            } catch (Throwable unused4) {
            }
        }
    }

    private static void releaseRecorderLocked(boolean z) {
        AudioRecord audioRecord = recorder;
        recorder = null;
        captureReadFailures = 0;
        captureSilentReads = 0;
        captureAecSilenceLogged = false;
        releaseEffectsLocked();
        if (audioRecord != null) {
            try {
                audioRecord.stop();
            } catch (Throwable unused) {
            }
            audioRecord.release();
        }
        if (z) {
            restoreAudioModeLocked();
        }
    }

    public static void requestRecordPermission() {
        final MainActivity activity = MainActivity.getActivity();
        if (activity == null) {
            return;
        }
        activity.getSharedPreferences(PERMISSION_PREFS, 0).edit().putBoolean(RECORD_PERMISSION_REQUESTED_KEY, true).apply();
        activity.runOnUiThread(new Runnable() { // from class: net.blocklegends.game.GameVoice$$ExternalSyntheticLambda0
            @Override // java.lang.Runnable
            public final void run() {
                ActivityCompat.requestPermissions(MainActivity.this, new String[]{"android.permission.RECORD_AUDIO"}, GameVoice.REQUEST_RECORD_AUDIO);
            }
        });
    }

    private static boolean restartCaptureAfterReadFailureLocked(String str) {
        return restartCaptureAfterReadFailureLocked(str, captureSourceIndex + 1);
    }

    private static boolean restartCaptureAfterReadFailureLocked(String str, int r9) {
        int r0 = captureSampleRate;
        int r1 = captureFrameSamples;
        int r2 = captureSourceIndex;
        String sourceName = sourceName(CAPTURE_SOURCES[r2]);
        releaseRecorderLocked(false);
        int minBufferSize = AudioRecord.getMinBufferSize(r0, 16, 2);
        if (minBufferSize <= 0) {
            return false;
        }
        int max = Math.max(r1 * 4, minBufferSize);
        Log.i("CRVoice", "Restarting AudioRecord after " + str + " from " + sourceName);
        boolean startCaptureLocked = startCaptureLocked(r0, max, r9, true);
        if (!startCaptureLocked) {
            Log.w("CRVoice", "AudioRecord healthy source probe failed after " + str + ", retrying all capture sources");
            startCaptureLocked = startCaptureLocked(r0, max, r9, false);
        }
        if (startCaptureLocked) {
            return captureSourceIndex != r2;
        }
        Log.w("CRVoice", "AudioRecord restart failed after " + str);
        logActiveRecordingConfigsLocked("restart-failed-" + str);
        return false;
    }

    private static void restoreAudioModeLocked() {
        AudioManager audioManager;
        if (audioModeActive) {
            Context app = MainApp.getApp();
            if (app == null) {
                app = MainActivity.getActivity();
            }
            if (app == null) {
                audioManager = null;
            } else {
                try {
                    audioManager = (AudioManager) app.getSystemService("audio");
                } finally {
                    try {
                    } finally {
                    }
                }
            }
            if (audioManager != null) {
                clearCommunicationDeviceLocked(audioManager);
                audioManager.setMode(previousAudioMode);
                audioManager.setSpeakerphoneOn(previousSpeakerphoneOn);
                audioManager.setMicrophoneMute(previousMicrophoneMute);
            }
        }
    }

    /* JADX INFO: Access modifiers changed from: private */
    /* JADX WARN: Code restructure failed: missing block: B:45:0x0041, code lost:
    
        return;
     */
    /*
        Code decompiled incorrectly, please refer to instructions dump.
        To view partially-correct add '--show-bad-code' argument
    */
    public static void runPlaybackLoop(android.media.AudioTrack r10, int r11, int r12) {
        /*
            long r0 = playbackFrameDurationNs(r11, r12)
        L4:
            int r11 = readPlaybackFrame(r10, r12, r0)
            if (r11 >= 0) goto Lb
            goto L26
        Lb:
            if (r11 != 0) goto Le
            goto L4
        Le:
            java.lang.Object r2 = net.blocklegends.game.GameVoice.PLAYBACK_LOCK
            monitor-enter(r2)
            android.media.AudioTrack r3 = net.blocklegends.game.GameVoice.player     // Catch: java.lang.Throwable -> L42
            if (r10 != r3) goto L40
            short[] r3 = net.blocklegends.game.GameVoice.playbackScratch     // Catch: java.lang.Throwable -> L42
            if (r3 != 0) goto L1a
            goto L40
        L1a:
            monitor-exit(r2)     // Catch: java.lang.Throwable -> L42
            r2 = 0
        L1c:
            if (r2 >= r11) goto L4
            int r4 = r11 - r2
            int r4 = r10.write(r3, r2, r4)     // Catch: java.lang.Throwable -> L3f
            if (r4 > 0) goto L27
        L26:
            return
        L27:
            int r2 = r2 + r4
            java.lang.Object r5 = net.blocklegends.game.GameVoice.PLAYBACK_LOCK
            monitor-enter(r5)
            android.media.AudioTrack r6 = net.blocklegends.game.GameVoice.player     // Catch: java.lang.Throwable -> L3c
            if (r10 == r6) goto L31
            monitor-exit(r5)     // Catch: java.lang.Throwable -> L3c
            return
        L31:
            long r6 = net.blocklegends.game.GameVoice.playbackFramesWritten     // Catch: java.lang.Throwable -> L3c
            long r8 = (long) r4     // Catch: java.lang.Throwable -> L3c
            long r6 = r6 + r8
            net.blocklegends.game.GameVoice.playbackFramesWritten = r6     // Catch: java.lang.Throwable -> L3c
            updatePlaybackDelayLocked(r10)     // Catch: java.lang.Throwable -> L3c
            monitor-exit(r5)     // Catch: java.lang.Throwable -> L3c
            goto L1c
        L3c:
            r10 = move-exception
            monitor-exit(r5)     // Catch: java.lang.Throwable -> L3c
            throw r10
        L3f:
            return
        L40:
            monitor-exit(r2)     // Catch: java.lang.Throwable -> L42
            return
        L42:
            r10 = move-exception
            monitor-exit(r2)     // Catch: java.lang.Throwable -> L42
            throw r10
        */
        throw new UnsupportedOperationException("Method not decompiled: net.blocklegends.game.GameVoice.runPlaybackLoop(android.media.AudioTrack, int, int):void");
    }

    private static boolean selectCommunicationSpeakerLocked(AudioManager audioManager) {
        AudioDeviceInfo audioDeviceInfo;
        communicationDeviceSet = false;
        if (audioManager != null && Build.VERSION.SDK_INT >= 31) {
            try {
                List<AudioDeviceInfo> availableCommunicationDevices = audioManager.getAvailableCommunicationDevices();
                if (availableCommunicationDevices != null && !availableCommunicationDevices.isEmpty()) {
                    Iterator<AudioDeviceInfo> it = availableCommunicationDevices.iterator();
                    while (true) {
                        if (!it.hasNext()) {
                            audioDeviceInfo = null;
                            break;
                        }
                        audioDeviceInfo = it.next();
                        if (audioDeviceInfo != null && audioDeviceInfo.getType() == 2) {
                            break;
                        }
                    }
                    if (audioDeviceInfo == null) {
                        Log.w("CRVoice", "AudioManager builtin speaker communication route missing devices=" + deviceListName(availableCommunicationDevices));
                        return false;
                    }
                    boolean communicationDevice = audioManager.setCommunicationDevice(audioDeviceInfo);
                    communicationDeviceSet = communicationDevice;
                    Log.i("CRVoice", "AudioManager communication speaker selected=" + communicationDevice + " speaker=" + deviceName(audioDeviceInfo) + " devices=" + deviceListName(availableCommunicationDevices));
                    return communicationDevice;
                }
                Log.w("CRVoice", "AudioManager communication devices unavailable");
                return false;
            } catch (Throwable th) {
                Log.w("CRVoice", "AudioManager communication speaker select failed", th);
            }
        }
        return false;
    }

    public static void setNativeAecActive(boolean z) {
        nativeWebRtcAecActive = z;
        System.setProperty("cr.voice.webrtcAec.active", z ? CredentialEntry.TRUE_STRING : CredentialEntry.FALSE_STRING);
    }

    /* JADX WARN: Code restructure failed: missing block: B:24:0x00c6, code lost:
    
        if (r1.contains("android sdk built for x86") != false) goto L25;
     */
    /* JADX WARN: Removed duplicated region for block: B:39:0x0103 A[Catch: all -> 0x011a, TryCatch #1 {all -> 0x011a, blocks: (B:37:0x00fd, B:39:0x0103, B:41:0x010d), top: B:36:0x00fd }] */
    /*
        Code decompiled incorrectly, please refer to instructions dump.
        To view partially-correct add '--show-bad-code' argument
    */
    private static boolean shouldBlockCaptureOnThisRuntime() {
        /*
            Method dump skipped, instructions count: 283
            To view this dump add '--comments-level debug' option
        */
        throw new UnsupportedOperationException("Method not decompiled: net.blocklegends.game.GameVoice.shouldBlockCaptureOnThisRuntime():boolean");
    }

    private static String sourceName(int r1) {
        return r1 != 0 ? r1 != 1 ? r1 != 6 ? r1 != 7 ? String.valueOf(r1) : "VOICE_COMMUNICATION" : "VOICE_RECOGNITION" : "MIC" : "DEFAULT";
    }

    public static boolean startCapture(int r4, int r5) {
        Context app = MainApp.getApp();
        if (app == null) {
            app = MainActivity.getActivity();
        }
        if (shouldBlockCaptureOnThisRuntime()) {
            if (!captureRuntimeBlockedLogged) {
                captureRuntimeBlockedLogged = true;
                Log.w("CRVoice", "AudioRecord capture disabled on emulator/runtime to avoid HAL pcm_read stalls: " + Build.MANUFACTURER + DomExceptionUtils.SEPARATOR + Build.BRAND + DomExceptionUtils.SEPARATOR + Build.MODEL + DomExceptionUtils.SEPARATOR + Build.DEVICE + DomExceptionUtils.SEPARATOR + Build.PRODUCT + DomExceptionUtils.SEPARATOR + Build.HARDWARE);
            }
            return false;
        }
        if (app == null || ContextCompat.checkSelfPermission(app, "android.permission.RECORD_AUDIO") != 0) {
            return false;
        }
        synchronized (CAPTURE_LOCK) {
            releaseRecorderLocked(false);
            configureAudioModeLocked();
            int minBufferSize = AudioRecord.getMinBufferSize(r4, 16, 2);
            if (minBufferSize <= 0) {
                restoreAudioModeLocked();
                return false;
            }
            int max = Math.max(r5 * 4, minBufferSize);
            captureSampleRate = r4;
            captureFrameSamples = r5;
            captureSourceIndex = 0;
            captureReadFailures = 0;
            captureSilentReads = 0;
            captureTriedSourceMask = 0;
            captureUnhealthySourceMask = 0;
            captureAllSourcesSilentLogged = false;
            captureAecSilenceLogged = false;
            System.setProperty("cr.voice.platformAec.active", CredentialEntry.FALSE_STRING);
            int firstCaptureSourceIndex = firstCaptureSourceIndex();
            captureSourceIndex = firstCaptureSourceIndex;
            boolean startCaptureLocked = startCaptureLocked(r4, max, firstCaptureSourceIndex);
            if (!startCaptureLocked) {
                restoreAudioModeLocked();
            }
            return startCaptureLocked;
        }
    }

    private static boolean startCaptureLocked(int r1, int r2, int r3) {
        return startCaptureLocked(r1, r2, r3, false);
    }

    private static boolean startCaptureLocked(int r12, int r13, int r14, boolean z) {
        int r8;
        int r11;
        if (!hasRecordPermission()) {
            return false;
        }
        int r3 = 0;
        while (true) {
            int[] r0 = CAPTURE_SOURCES;
            if (r3 >= r0.length) {
                break;
            }
            int length = (r14 + r3) % r0.length;
            if (z && isCaptureSourceUnhealthyLocked(length)) {
                r8 = r12;
                r11 = r13;
            } else {
                int r7 = r0[length];
                try {
                    try {
                        r8 = r12;
                        r11 = r13;
                        try {
                            AudioRecord audioRecord = new AudioRecord(r7, r8, 16, 2, r11);
                            if (audioRecord.getState() != 1) {
                                audioRecord.release();
                            } else {
                                configureEffectsLocked(audioRecord.getAudioSessionId(), r7);
                                if (isNativeWebRtcAecActive() || echoCancelerActive) {
                                    try {
                                        audioRecord.startRecording();
                                        recorder = audioRecord;
                                        captureSourceIndex = length;
                                        captureReadFailures = 0;
                                        captureSilentReads = 0;
                                        captureTriedSourceMask |= 1 << length;
                                        captureAllSourcesSilentLogged = false;
                                        captureAecSilenceLogged = false;
                                        Log.i("CRVoice", "AudioRecord started with " + sourceName(r7) + " platformAec=" + echoCancelerActive + " noiseSuppressor=" + noiseSuppressorActive + " nativeWebRtcAec=" + isNativeWebRtcAecActive());
                                        logActiveRecordingConfigsLocked("started-" + sourceName(r7));
                                        return true;
                                    } catch (Throwable th) {
                                        Log.w("CRVoice", "AudioRecord start failed for " + sourceName(r7), th);
                                        releaseEffectsLocked();
                                        audioRecord.release();
                                    }
                                } else {
                                    Log.w("CRVoice", "AudioRecord rejected for " + sourceName(r7) + ": platform AEC is not active, speaker full-duplex would echo");
                                    releaseEffectsLocked();
                                    audioRecord.release();
                                }
                            }
                        } catch (Throwable th2) {
                            th = th2;
                            Log.w("CRVoice", "AudioRecord create failed for " + sourceName(r7), th);
                            r3++;
                            r12 = r8;
                            r13 = r11;
                        }
                    } catch (SecurityException unused) {
                        return false;
                    }
                } catch (Throwable th3) {
                    th = th3;
                    r8 = r12;
                    r11 = r13;
                }
            }
            r3++;
            r12 = r8;
            r13 = r11;
        }
    }

    public static boolean startPlayback(final int r17, final int r18) {
        synchronized (PLAYBACK_LOCK) {
            if (player != null) {
                return true;
            }
            int minBufferSize = AudioTrack.getMinBufferSize(r17, 4, 2);
            if (minBufferSize <= 0) {
                return false;
            }
            int playbackTargetQueueSamplesFor = playbackTargetQueueSamplesFor(r17, r18);
            int max = Math.max(Math.max(r18 * 4, minBufferSize), playbackTargetQueueSamplesFor * 2);
            int clampPlaybackDelayMs = clampPlaybackDelayMs(((int) ((playbackTargetQueueSamplesFor * 1000) / r17)) + 20);
            final AudioTrack audioTrack = new AudioTrack(new AudioAttributes.Builder().setUsage(2).setContentType(1).build(), new AudioFormat.Builder().setSampleRate(r17).setEncoding(2).setChannelMask(4).build(), max, 1, 0);
            if (audioTrack.getState() != 1) {
                audioTrack.release();
                return false;
            }
            try {
                audioTrack.play();
                ensurePlaybackBufferLocked(r17, r18);
                playbackRunning = true;
                playbackPrimed = false;
                player = audioTrack;
                playbackDelayMs = clampPlaybackDelayMs;
                playbackSmoothedDelayMs = clampPlaybackDelayMs;
                playbackSampleRate = r17;
                playbackFrameSamples = r18;
                playbackFramesWritten = 0L;
                playbackHeadWraps = 0L;
                playbackLastHeadRaw = 0;
                playbackLastPlayedFrames = -1L;
                playbackHeadStallSinceMs = 0L;
                playbackHeadUnreliable = false;
                playbackReadIndex = 0;
                playbackWriteIndex = 0;
                playbackBufferedSamples = 0;
                playbackUnderruns = 0;
                playbackOverruns = 0;
                Thread thread = new Thread(new Runnable() { // from class: net.blocklegends.game.GameVoice.1
                    @Override // java.lang.Runnable
                    public void run() {
                        GameVoice.runPlaybackLoop(audioTrack, r17, r18);
                    }
                }, "CRVoicePlayout");
                thread.setDaemon(true);
                playbackThread = thread;
                thread.start();
                System.setProperty("cr.voice.playbackDelayMs", String.valueOf(clampPlaybackDelayMs));
                Log.i("CRVoice", "AudioTrack voice playback started bufferBytes=" + max + " ringSamples=" + playbackRingCapacity + " prebufferSamples=" + playbackPrebufferSamples + " targetQueueSamples=" + playbackTargetQueueSamples + " delayMs=" + clampPlaybackDelayMs);
                return true;
            } catch (Throwable unused) {
                playbackRunning = false;
                player = null;
                audioTrack.release();
                return false;
            }
        }
    }

    public static void stopCapture() {
        synchronized (CAPTURE_LOCK) {
            releaseRecorderLocked(true);
        }
    }

    public static void stopPlayback() {
        AudioTrack audioTrack;
        Thread thread;
        Object obj = PLAYBACK_LOCK;
        synchronized (obj) {
            audioTrack = player;
            thread = playbackThread;
            player = null;
            playbackThread = null;
            playbackRunning = false;
            playbackPrimed = false;
            playbackDelayMs = 0;
            playbackSmoothedDelayMs = -1;
            playbackSampleRate = 0;
            playbackFrameSamples = 0;
            playbackFramesWritten = 0L;
            playbackHeadWraps = 0L;
            playbackLastHeadRaw = 0;
            playbackLastPlayedFrames = -1L;
            playbackHeadStallSinceMs = 0L;
            playbackHeadUnreliable = false;
            playbackReadIndex = 0;
            playbackWriteIndex = 0;
            playbackBufferedSamples = 0;
            playbackTargetQueueSamples = 0;
            System.setProperty("cr.voice.playbackDelayMs", "0");
            obj.notifyAll();
        }
        if (audioTrack != null) {
            try {
                audioTrack.pause();
            } catch (Throwable unused) {
            }
            try {
                audioTrack.flush();
            } catch (Throwable unused2) {
            }
            try {
                audioTrack.stop();
            } catch (Throwable unused3) {
            }
        }
        if (thread != null && thread != Thread.currentThread()) {
            try {
                thread.join(200L);
            } catch (InterruptedException unused4) {
                Thread.currentThread().interrupt();
            } catch (Throwable unused5) {
            }
        }
        if (audioTrack != null) {
            audioTrack.release();
        }
    }

    private static int updatePlaybackDelayLocked(AudioTrack audioTrack) {
        long j;
        if (audioTrack == null || playbackSampleRate <= 0) {
            return playbackDelayMs;
        }
        if (playbackHeadUnreliable) {
            j = playbackTargetQueueSamples + playbackBufferedSamples;
        } else {
            long playbackTrackPendingFramesLocked = getPlaybackTrackPendingFramesLocked(audioTrack);
            if (playbackTrackPendingFramesLocked < 0) {
                return playbackDelayMs;
            }
            j = playbackTrackPendingFramesLocked + playbackBufferedSamples;
        }
        int clampPlaybackDelayMs = clampPlaybackDelayMs(((int) ((j * 1000) / playbackSampleRate)) + 20);
        int r0 = playbackSmoothedDelayMs;
        if (r0 < 0) {
            playbackSmoothedDelayMs = clampPlaybackDelayMs;
        } else {
            playbackSmoothedDelayMs = (((r0 * 3) + clampPlaybackDelayMs) + 2) / 4;
        }
        int r4 = playbackSmoothedDelayMs;
        if (r4 != playbackDelayMs) {
            playbackDelayMs = r4;
            System.setProperty("cr.voice.playbackDelayMs", String.valueOf(r4));
        }
        return playbackDelayMs;
    }

    private static long updatePlaybackPlayedFramesLocked(AudioTrack audioTrack) {
        try {
            int playbackHeadPosition = audioTrack.getPlaybackHeadPosition();
            long j = playbackHeadPosition & 4294967295L;
            long j2 = 4294967295L & playbackLastHeadRaw;
            if (j < j2 && j2 - j > 1073741824) {
                playbackHeadWraps++;
            }
            playbackLastHeadRaw = playbackHeadPosition;
            return (playbackHeadWraps << 32) + j;
        } catch (Throwable unused) {
            return -1L;
        }
    }

    private static boolean waitPlaybackLocked(long j) {
        long j2 = j <= 0 ? 1L : (j + 999999) / 1000000;
        try {
            PLAYBACK_LOCK.wait(j2 > 0 ? j2 : 1L);
            return true;
        } catch (InterruptedException unused) {
            Thread.currentThread().interrupt();
            return false;
        } catch (Throwable unused2) {
            return false;
        }
    }

    public static void writePlayback(short[] sArr, int r4, int r5) {
        if (sArr == null || r4 < 0 || r5 <= 0 || r4 > sArr.length - r5) {
            return;
        }
        Object obj = PLAYBACK_LOCK;
        synchronized (obj) {
            if (player != null && playbackRunning && playbackRing != null) {
                int r1 = playbackRingCapacity;
                if (r5 > r1) {
                    r4 += r5 - r1;
                    r5 = r1;
                }
                int r12 = r5 - (r1 - playbackBufferedSamples);
                if (r12 > 0) {
                    dropPlaybackSamplesLocked(r12);
                    playbackOverruns++;
                }
                copyPlaybackToRingLocked(sArr, r4, r5);
                if (!playbackPrimed && playbackBufferedSamples >= playbackPrebufferSamples) {
                    playbackPrimed = true;
                }
                updatePlaybackDelayLocked(player);
                obj.notifyAll();
            }
        }
    }

    private static void zeroPlaybackScratchLocked(int r2, int r3) {
        int min = Math.min(r3, playbackScratch.length);
        for (int max = Math.max(0, r2); max < min; max++) {
            playbackScratch[max] = 0;
        }
    }
}
