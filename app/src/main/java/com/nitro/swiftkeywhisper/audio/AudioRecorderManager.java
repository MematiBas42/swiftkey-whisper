package com.nitro.swiftkeywhisper.audio;

import android.content.Context;
import android.media.AudioFormat;
import android.media.AudioRecord;
import android.media.MediaRecorder;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.speech.RecognitionListener;
import android.speech.SpeechRecognizer;
import android.util.Log;

import com.nitro.swiftkeywhisper.config.ConfigManager;
import com.nitro.swiftkeywhisper.network.WhisperClient;

import java.io.ByteArrayOutputStream;
import java.util.ArrayList;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;

public class AudioRecorderManager {
    private static final String TAG = "SwiftKeyWhisperAudio";
    private static final int SAMPLE_RATE = 16000;
    private static final int CHANNEL_CONFIG = AudioFormat.CHANNEL_IN_MONO;
    private static final int AUDIO_FORMAT = AudioFormat.ENCODING_PCM_16BIT;

    // Safety timeout: 10 minutes matching SwiftKey native SPEECH_INPUT_COMPLETE_SILENCE_LENGTH_MILLIS
    private static final long MAX_SESSION_TIMEOUT_MS = 600000L;

    private static AudioRecorderManager instance;
    private AudioRecord audioRecord;
    private volatile boolean isRecording = false;
    private final AtomicBoolean isSpeechActive = new AtomicBoolean(false);
    private final AtomicInteger sessionEpoch = new AtomicInteger(0);
    private final AtomicInteger utteranceEpoch = new AtomicInteger(0);

    private final Object pcmLock = new Object();
    private ByteArrayOutputStream currentSentencePcm;

    private Thread recordingThread;
    private final Handler mainHandler = new Handler(Looper.getMainLooper());
    private Runnable sessionTimeoutRunnable;
    private Runnable autoStopRunnable;

    private final ExecutorService segmentExecutor = Executors.newSingleThreadExecutor();
    private final ScheduledExecutorService partialScheduler = Executors.newSingleThreadScheduledExecutor();
    private ScheduledFuture<?> partialTaskFuture;

    // VAD-Guided Streaming Parameters
    private static final int MIN_PARTIAL_AUDIO_BYTES = 16000; // 500ms at 16kHz 16-bit mono
    private static final int MIN_SPEECH_DELTA_BYTES = 3200;   // 100ms new audio since last partial
    private static final long PARTIAL_IN_FLIGHT_TIMEOUT_MS = 3000L;

    private final AtomicBoolean isPartialInFlight = new AtomicBoolean(false);
    private final AtomicBoolean hasPendingBoundary = new AtomicBoolean(false);
    private final AtomicInteger partialSequenceGenerator = new AtomicInteger(0);
    private volatile int lastDeliveredSequenceId = 0;
    private volatile int lastPartialAudioBytes = 0;
    private volatile long lastPartialDispatchTimeMs = 0L;
    private final Object sequenceFenceLock = new Object();

    private RecognitionListener currentListener;
    private ConfigManager currentConfig;
    private String currentLanguage;
    private VoiceActivityDetector vad;
    private Context appContext;
    private final AudioEffectsHelper effectsHelper = new AudioEffectsHelper();

    public static synchronized AudioRecorderManager getInstance() {
        if (instance == null) {
            instance = new AudioRecorderManager();
        }
        return instance;
    }

    public boolean isRecording() {
        return isRecording;
    }

    public boolean isRecordingActive() {
        return isRecording;
    }

    public boolean isSpeaking() {
        return isSpeechActive.get();
    }

    public boolean hasPendingUtterance() {
        if (isSpeechActive.get()) return true;
        synchronized (pcmLock) {
            return currentSentencePcm != null && currentSentencePcm.size() > 0;
        }
    }

    public synchronized void startRecording(RecognitionListener listener, ConfigManager config, String dynamicLang) {
        startRecording(this.appContext, listener, config, dynamicLang);
    }

    public synchronized void startRecording(Context context, RecognitionListener listener, ConfigManager config, String dynamicLang) {
        if (isRecording) {
            cancel();
        }

        this.appContext = context;
        if (context != null) {
            EarconPlayer.getInstance(context);
        }

        final int sessionId = sessionEpoch.incrementAndGet();
        utteranceEpoch.incrementAndGet();
        this.currentListener = listener;
        this.currentConfig = config;
        this.currentLanguage = dynamicLang;
        this.currentConfig.clearContext(); // Reset session context

        int minBufferSize = AudioRecord.getMinBufferSize(SAMPLE_RATE, CHANNEL_CONFIG, AUDIO_FORMAT);
        int bufferSize = Math.max(minBufferSize, 4096);

        try {
            audioRecord = new AudioRecord(
                    MediaRecorder.AudioSource.VOICE_RECOGNITION,
                    SAMPLE_RATE,
                    CHANNEL_CONFIG,
                    AUDIO_FORMAT,
                    bufferSize
            );

            if (audioRecord.getState() != AudioRecord.STATE_INITIALIZED) {
                Log.e(TAG, "AudioRecord initialization failed");
                if (listener != null) {
                    mainHandler.post(() -> listener.onError(SpeechRecognizer.ERROR_AUDIO));
                }
                return;
            }

            effectsHelper.attach(audioRecord.getAudioSessionId());

            synchronized (pcmLock) {
                currentSentencePcm = new ByteArrayOutputStream();
            }

            // Initialize VAD with configured silence threshold (e.g. 750ms)
            int silenceTimeout = config != null ? config.getSilenceTimeoutMs() : ConfigManager.DEFAULT_SILENCE_TIMEOUT_MS;
            vad = new VoiceActivityDetector(appContext, silenceTimeout, new VoiceActivityDetector.VadListener() {
                @Override
                public void onSpeechStart() {
                    handleSpeechOnset(sessionId);
                }

                @Override
                public void onSpeechEnd() {
                    handleSpeechOffset(sessionId);
                }

                @Override
                public void onAcousticBoundary() {
                    handleAcousticBoundary(sessionId);
                }
            });

            audioRecord.startRecording();
            isRecording = true;
            isSpeechActive.set(false);

            Log.i(TAG, "Audio recording pipeline started (VOICE_RECOGNITION, 16kHz Mono) [session " + sessionId + "]");

            // Arm 10-minute maximum session safety timeout matching SwiftKey native
            sessionTimeoutRunnable = () -> {
                Log.w(TAG, "Max session safety timeout reached (10 minutes) for session " + sessionId);
                stopListening();
            };
            mainHandler.postDelayed(sessionTimeoutRunnable, MAX_SESSION_TIMEOUT_MS);

            // Arm initial auto-stop timer (allowing generous time before first word)
            int autoStopMs = config != null ? config.getAutoStopTimeoutMs() : ConfigManager.DEFAULT_AUTO_STOP_TIMEOUT_MS;
            if (autoStopMs > 0) {
                scheduleAutoStop(sessionId, Math.max(4000L, autoStopMs * 2L));
            }

            // SwiftKey contract: signal readiness immediately (0ms delay, instant native response)
            if (listener != null) {
                final RecognitionListener targetListener = listener;
                mainHandler.post(() -> {
                    if (sessionId == sessionEpoch.get() && isRecording && targetListener == currentListener) {
                        targetListener.onReadyForSpeech(new Bundle());
                        if (appContext != null) {
                            EarconPlayer.getInstance(appContext).playOpen();
                        }
                    }
                });
            }

            recordingThread = new Thread(new RecordingRunnable(bufferSize, sessionId), "WhisperAudioCapture-" + sessionId);
            recordingThread.start();

        } catch (SecurityException se) {
            Log.e(TAG, "RECORD_AUDIO permission missing", se);
            if (listener != null) {
                mainHandler.post(() -> listener.onError(SpeechRecognizer.ERROR_INSUFFICIENT_PERMISSIONS));
            }
        } catch (Throwable t) {
            Log.e(TAG, "Failed to start AudioRecord", t);
            if (listener != null) {
                mainHandler.post(() -> listener.onError(SpeechRecognizer.ERROR_AUDIO));
            }
        }
    }

    private void handleSpeechOnset(int sessionId) {
        if (sessionId != sessionEpoch.get() || !isRecording) {
            return;
        }
        cancelAutoStop();
        final int utteranceId = utteranceEpoch.incrementAndGet();
        isSpeechActive.set(true);
        Log.i(TAG, "Speech onset: activating SwiftKey speaking state & Lottie animation [session " + sessionId + ", utterance " + utteranceId + "]");

        final RecognitionListener listener = currentListener;
        if (listener != null) {
            mainHandler.post(() -> {
                if (sessionId == sessionEpoch.get() && utteranceId == utteranceEpoch.get() && isRecording && currentListener == listener) {
                    listener.onBeginningOfSpeech();
                }
            });
        }

        synchronized (pcmLock) {
            if (currentSentencePcm != null) {
                currentSentencePcm.reset();
                byte[] preRoll = vad.getPreRollBytes();
                if (preRoll != null && preRoll.length > 0) {
                    currentSentencePcm.write(preRoll, 0, preRoll.length);
                }
            }
        }

        partialSequenceGenerator.set(0);
        lastDeliveredSequenceId = 0;
        lastPartialAudioBytes = 0;
        isPartialInFlight.set(false);
        hasPendingBoundary.set(false);

        if (vad != null) {
            vad.notifyPartialDispatched();
        }
    }

    private void handleSpeechOffset(int sessionId) {
        if (sessionId != sessionEpoch.get() || !isRecording) {
            return;
        }
        if (!isSpeechActive.compareAndSet(true, false)) {
            return;
        }
        final int utteranceId = utteranceEpoch.get();
        Log.i(TAG, "Speech offset: pausing speaking state & finalizing sentence segment [session " + sessionId + ", utterance " + utteranceId + "]");

        final RecognitionListener listener = currentListener;
        if (listener != null) {
            mainHandler.post(() -> {
                if (sessionId == sessionEpoch.get() && utteranceId == utteranceEpoch.get() && isRecording && currentListener == listener) {
                    listener.onEndOfSpeech();
                }
            });
        }

        stopPartialScheduler();
        WhisperClient.cancelPartialRequests();
        isPartialInFlight.set(false);
        hasPendingBoundary.set(false);

        // Arm auto-stop timer after sentence ends
        int autoStopMs = currentConfig != null ? currentConfig.getAutoStopTimeoutMs() : ConfigManager.DEFAULT_AUTO_STOP_TIMEOUT_MS;
        if (autoStopMs > 0) {
            scheduleAutoStop(sessionId, autoStopMs);
        }

        byte[] pcmData;
        synchronized (pcmLock) {
            pcmData = currentSentencePcm != null ? currentSentencePcm.toByteArray() : new byte[0];
            if (currentSentencePcm != null) {
                currentSentencePcm.reset();
            }
        }

        if (pcmData.length < 9600) {
            Log.d(TAG, "Sentence audio too short (" + pcmData.length + " bytes), ignoring.");
            return;
        }

        segmentExecutor.execute(() -> processSentenceSegment(sessionId, utteranceId, pcmData));
    }

    private void processSentenceSegment(int sessionId, int utteranceId, byte[] pcmData) {
        if (sessionId != sessionEpoch.get() || utteranceId != utteranceEpoch.get() || !isRecording) {
            return;
        }

        // Trim the trailing silence timeout that accumulated while waiting for speech to end
        int silenceTimeoutMs = currentConfig != null ? currentConfig.getSilenceTimeoutMs() : ConfigManager.DEFAULT_SILENCE_TIMEOUT_MS;
        int silenceBytes = silenceTimeoutMs * (SAMPLE_RATE * 2 / 1000); // 32 bytes per ms (16kHz 16-bit mono)
        byte[] cleanPcm = pcmData;
        if (pcmData.length > silenceBytes + 9600) {
            int trimmedLength = pcmData.length - silenceBytes;
            cleanPcm = new byte[trimmedLength];
            System.arraycopy(pcmData, 0, cleanPcm, 0, trimmedLength);
        }

        byte[] wavBytes = WavWriter.pcmToWav(cleanPcm, SAMPLE_RATE, 1, 16);
        Log.i(TAG, "Submitting sentence segment to Whisper (" + wavBytes.length + " bytes WAV, trimmed " + (pcmData.length - cleanPcm.length) + " silence bytes) [session " + sessionId + ", utterance " + utteranceId + "]");

        final int[] retryAttempts = new int[]{0};
        final int MAX_SEGMENT_RETRIES = 3;
        final int RETRY_DELAY_MS = 1100;

        WhisperClient.transcribeSegment(wavBytes, currentConfig, currentLanguage, new WhisperClient.TranscriptionCallback() {
            @Override
            public void onSuccess(String text) {
                if (sessionId != sessionEpoch.get() || utteranceId != utteranceEpoch.get()) {
                    Log.d(TAG, "Segment transcription dropped because session/utterance changed [session " + sessionId + ", utterance " + utteranceId + "]");
                    return;
                }
                if (text == null || text.trim().isEmpty()) {
                    Log.d(TAG, "Whisper returned empty segment text");
                    return;
                }

                Log.i(TAG, "Segment transcription committed: " + text);

                if (currentConfig != null) {
                    currentConfig.appendContextText(text);
                }

                final RecognitionListener listener = currentListener;
                if (listener != null) {
                    mainHandler.post(() -> {
                        if (sessionId == sessionEpoch.get() && utteranceId == utteranceEpoch.get() && currentListener == listener) {
                            Bundle bundle = createResultsBundle(text, true);
                            listener.onPartialResults(bundle);
                        }
                    });
                }
            }

            @Override
            public void onError(String errorMessage) {
                Log.e(TAG, "Segment transcription error: " + errorMessage);
                if (errorMessage != null && errorMessage.contains("Rate limit") && retryAttempts[0] < MAX_SEGMENT_RETRIES) {
                    retryAttempts[0]++;
                    Log.w(TAG, "Segment transcription hit rate limit, retry attempt " + retryAttempts[0] + "/" + MAX_SEGMENT_RETRIES + " in " + RETRY_DELAY_MS + "ms [session " + sessionId + ", utterance " + utteranceId + "]");
                    mainHandler.postDelayed(() -> {
                        if (sessionId == sessionEpoch.get() && utteranceId == utteranceEpoch.get()) {
                            WhisperClient.transcribeSegment(wavBytes, currentConfig, currentLanguage, this);
                        }
                    }, RETRY_DELAY_MS);
                }
            }
        });
    }

    public void handleAcousticBoundary(int sessionId) {
        final int utteranceId = utteranceEpoch.get();
        if (sessionId != sessionEpoch.get() || !isRecording || !isSpeechActive.get()) {
            return;
        }
        if (currentConfig == null || !currentConfig.isStreamingEnabled()) {
            return;
        }

        byte[] snapshot;
        synchronized (pcmLock) {
            snapshot = currentSentencePcm != null ? currentSentencePcm.toByteArray() : null;
        }

        if (snapshot == null || snapshot.length < MIN_PARTIAL_AUDIO_BYTES) {
            return;
        }

        if ((snapshot.length - lastPartialAudioBytes) < MIN_SPEECH_DELTA_BYTES) {
            return;
        }

        // Concurrency gating: allow only 1 in-flight partial request
        if (isPartialInFlight.get()) {
            long inFlightDuration = System.currentTimeMillis() - lastPartialDispatchTimeMs;
            if (inFlightDuration > PARTIAL_IN_FLIGHT_TIMEOUT_MS) {
                Log.w(TAG, "In-flight partial timed out (" + inFlightDuration + "ms), cancelling and resetting gate");
                WhisperClient.cancelPartialRequests();
                isPartialInFlight.set(false);
            } else {
                // Coalesce: mark pending boundary while partial in-flight
                hasPendingBoundary.set(true);
                Log.d(TAG, "Coalescing acoustic boundary: marked pending while partial in-flight (" + inFlightDuration + "ms)");
                return;
            }
        }

        dispatchPartialSnapshot(sessionId, utteranceId, snapshot);
    }

    private void checkAndDispatchPending(int sessionId) {
        if (hasPendingBoundary.compareAndSet(true, false)) {
            if (sessionId == sessionEpoch.get() && isRecording && isSpeechActive.get()) {
                segmentExecutor.execute(() -> handleAcousticBoundary(sessionId));
            }
        }
    }

    private void dispatchPartialSnapshot(int sessionId, int utteranceId, byte[] snapshot) {
        if (!isPartialInFlight.compareAndSet(false, true)) {
            return;
        }

        lastPartialDispatchTimeMs = System.currentTimeMillis();
        lastPartialAudioBytes = snapshot.length;
        final int seqId = partialSequenceGenerator.incrementAndGet();

        // Reset dynamic acoustic boundary decay on VAD
        if (vad != null) {
            vad.notifyPartialDispatched();
        }

        byte[] wavBytes = WavWriter.pcmToWav(snapshot, SAMPLE_RATE, 1, 16);
        Log.d(TAG, "Dispatching VAD-guided partial [seq " + seqId + ", " + wavBytes.length + " bytes WAV, session " + sessionId + ", utterance " + utteranceId + "]");

        WhisperClient.transcribePartial(wavBytes, currentConfig, currentLanguage, new WhisperClient.TranscriptionCallback() {
            @Override
            public void onSuccess(String text) {
                isPartialInFlight.set(false);
                checkAndDispatchPending(sessionId);

                if (sessionId != sessionEpoch.get() || utteranceId != utteranceEpoch.get() || !isRecording || !isSpeechActive.get()) {
                    Log.d(TAG, "Partial dropped: session/utterance expired or speech inactive [seq " + seqId + "]");
                    return;
                }

                if (text == null || text.trim().isEmpty()) {
                    return;
                }

                synchronized (sequenceFenceLock) {
                    if (seqId <= lastDeliveredSequenceId) {
                        Log.d(TAG, "Dropping out-of-order partial: seq " + seqId + " <= lastDelivered " + lastDeliveredSequenceId);
                        return;
                    }
                    lastDeliveredSequenceId = seqId;
                }

                final RecognitionListener listener = currentListener;
                if (listener != null) {
                    mainHandler.post(() -> {
                        if (sessionId == sessionEpoch.get() && utteranceId == utteranceEpoch.get() && currentListener == listener && isRecording && isSpeechActive.get()) {
                            Bundle bundle = createResultsBundle(text, false);
                            listener.onPartialResults(bundle);
                        }
                    });
                }
            }

            @Override
            public void onError(String errorMessage) {
                isPartialInFlight.set(false);
                checkAndDispatchPending(sessionId);
                Log.d(TAG, "Partial error suppressed [seq " + seqId + "]: " + errorMessage);
            }
        });
    }

    private void startPartialScheduler(int sessionId) {
        if (vad != null) {
            vad.notifyPartialDispatched();
        }
    }

    private void stopPartialScheduler() {
        if (partialTaskFuture != null) {
            partialTaskFuture.cancel(false);
            partialTaskFuture = null;
        }
        isPartialInFlight.set(false);
        hasPendingBoundary.set(false);
    }

    private synchronized void scheduleAutoStop(int sessionId, long delayMs) {
        cancelAutoStop();
        if (currentConfig == null || delayMs <= 0) {
            return;
        }

        autoStopRunnable = () -> {
            if (sessionId == sessionEpoch.get() && isRecording && !isSpeechActive.get()) {
                Log.i(TAG, "Auto-stop threshold reached (" + delayMs + " ms silence). Stopping session " + sessionId);
                stopListening();
            }
        };
        mainHandler.postDelayed(autoStopRunnable, delayMs);
    }

    private synchronized void cancelAutoStop() {
        if (autoStopRunnable != null) {
            mainHandler.removeCallbacks(autoStopRunnable);
            autoStopRunnable = null;
        }
    }

    public synchronized void stopListening() {
        if (!isRecording) {
            return;
        }
        final int sessionId = sessionEpoch.get();
        final int utteranceId = utteranceEpoch.get();
        isRecording = false;
        isSpeechActive.set(false);
        Log.i(TAG, "stopListening requested: finalizing session " + sessionId + ", utterance " + utteranceId);

        if (appContext != null) {
            EarconPlayer.getInstance(appContext).playSuccess();
        }

        cancelAutoStop();

        if (sessionTimeoutRunnable != null) {
            mainHandler.removeCallbacks(sessionTimeoutRunnable);
            sessionTimeoutRunnable = null;
        }

        stopPartialScheduler();
        WhisperClient.cancelPartialRequests();

        try {
            effectsHelper.release();
            if (audioRecord != null) {
                audioRecord.stop();
                audioRecord.release();
                audioRecord = null;
            }
        } catch (Throwable t) {
            Log.w(TAG, "Error stopping AudioRecord: " + t.getMessage());
        }

        if (vad != null) {
            vad.close();
            vad = null;
        }

        byte[] remainingPcm;
        synchronized (pcmLock) {
            remainingPcm = currentSentencePcm != null ? currentSentencePcm.toByteArray() : new byte[0];
            currentSentencePcm = null;
        }

        // Final closure: if audio was pending, transcribe it before closing
        if (remainingPcm.length > 9600) {
            byte[] wavBytes = WavWriter.pcmToWav(remainingPcm, SAMPLE_RATE, 1, 16);
            WhisperClient.transcribeSegment(wavBytes, currentConfig, currentLanguage, new WhisperClient.TranscriptionCallback() {
                @Override
                public void onSuccess(String text) {
                    deliverFinalResults(sessionId, utteranceId, text);
                }

                @Override
                public void onError(String errorMessage) {
                    deliverFinalResults(sessionId, utteranceId, "");
                }
            });
        } else {
            deliverFinalResults(sessionId, utteranceId, "");
        }
    }

    private void deliverFinalResults(int sessionId, int utteranceId, String text) {
        if (sessionId != sessionEpoch.get() || utteranceId != utteranceEpoch.get()) {
            Log.d(TAG, "deliverFinalResults dropped for stale session/utterance " + sessionId + "/" + utteranceId);
            return;
        }
        final RecognitionListener listener = currentListener;
        if (listener != null) {
            mainHandler.post(() -> {
                if (sessionId == sessionEpoch.get() && utteranceId == utteranceEpoch.get() && currentListener == listener) {
                    Bundle finalBundle = createResultsBundle(text != null ? text : "", true);
                    // onResults unconditionally terminates SwiftKey session (o0)
                    listener.onResults(finalBundle);
                }
            });
        }
    }

    public synchronized void stopSessionOnEditorCleared() {
        if (!isRecording) {
            return;
        }
        final int sessionId = sessionEpoch.get();
        final int utteranceId = utteranceEpoch.incrementAndGet();
        isRecording = false;
        isSpeechActive.set(false);
        Log.i(TAG, "stopSessionOnEditorCleared: host app sent message, stopping voice session [session " + sessionId + "]");

        if (appContext != null) {
            EarconPlayer.getInstance(appContext).playSuccess();
        }

        cancelAutoStop();
        stopPartialScheduler();
        WhisperClient.cancelAllRequests();

        try {
            effectsHelper.release();
            if (audioRecord != null) {
                audioRecord.stop();
                audioRecord.release();
                audioRecord = null;
            }
        } catch (Throwable ignored) {}

        synchronized (pcmLock) {
            currentSentencePcm = null;
        }

        if (vad != null) {
            vad.close();
            vad = null;
        }

        if (currentConfig != null) {
            currentConfig.clearContext();
        }

        deliverFinalResults(sessionId, utteranceId, "");
    }

    public synchronized void cancel() {
        boolean wasRecording = isRecording;
        sessionEpoch.incrementAndGet();
        utteranceEpoch.incrementAndGet();
        isRecording = false;
        isSpeechActive.set(false);

        if (wasRecording && appContext != null) {
            EarconPlayer.getInstance(appContext).playSuccess();
        }

        cancelAutoStop();

        if (sessionTimeoutRunnable != null) {
            mainHandler.removeCallbacks(sessionTimeoutRunnable);
            sessionTimeoutRunnable = null;
        }
        mainHandler.removeCallbacksAndMessages(null);

        stopPartialScheduler();
        WhisperClient.cancelAllRequests();

        try {
            effectsHelper.release();
            if (audioRecord != null) {
                audioRecord.stop();
                audioRecord.release();
                audioRecord = null;
            }
        } catch (Throwable ignored) {}

        if (vad != null) {
            vad.close();
            vad = null;
        }

        synchronized (pcmLock) {
            currentSentencePcm = null;
        }

        currentListener = null;
        Log.i(TAG, "Audio recording cancelled and cleaned up");
    }

    private Bundle createResultsBundle(String text, boolean isFinal) {
        Bundle bundle = new Bundle();
        ArrayList<String> matches = new ArrayList<>(1);
        if (text != null && !text.trim().isEmpty()) {
            matches.add(text.trim());
        }
        bundle.putStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION, matches);
        bundle.putFloatArray("confidence_scores", new float[]{ 1.0f });
        bundle.putBoolean("final_result", isFinal);
        return bundle;
    }

    private class RecordingRunnable implements Runnable {
        private final int bufferSize;
        private final int sessionId;
        private volatile boolean bufferReceivedSent = false;

        RecordingRunnable(int bufferSize, int sessionId) {
            this.bufferSize = bufferSize;
            this.sessionId = sessionId;
        }

        @Override
        public void run() {
            byte[] buffer = new byte[bufferSize];

            while (isRecording && sessionId == sessionEpoch.get()) {
                int read = audioRecord != null ? audioRecord.read(buffer, 0, buffer.length) : -1;
                if (read > 0) {
                    if (sessionId != sessionEpoch.get() || !isRecording) {
                        break;
                    }

                    // Send to VAD engine
                    if (vad != null) {
                        vad.processBuffer(buffer, 0, read);
                    }

                    // If user is speaking, accumulate audio into current sentence
                    if (isSpeechActive.get()) {
                        synchronized (pcmLock) {
                            if (currentSentencePcm != null) {
                                currentSentencePcm.write(buffer, 0, read);
                            }
                        }
                    }

                    // Forward buffer event to SwiftKey ONCE to mark receivedAudioData = true
                    // SwiftKey only uses this to set f43337c = true for telemetry.
                    // Emitting on every read was flooding the main looper with 20 byte[] allocations/sec.
                    if (!bufferReceivedSent && currentListener != null) {
                        bufferReceivedSent = true;
                        final RecognitionListener listener = currentListener;
                        final byte[] copy = new byte[Math.min(read, 320)];
                        System.arraycopy(buffer, 0, copy, 0, copy.length);
                        mainHandler.post(() -> {
                            if (sessionId == sessionEpoch.get() && currentListener == listener) {
                                listener.onBufferReceived(copy);
                            }
                        });
                    }
                }
            }
        }
    }
}
