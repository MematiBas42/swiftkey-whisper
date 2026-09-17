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

    public interface ResultCallback {
        void onResult(String text);
        void onError(String error);
    }

    private static AudioRecorderManager instance;
    private AudioRecord audioRecord;
    private volatile boolean isRecording = false;
    private final AtomicBoolean isSpeechActive = new AtomicBoolean(false);
    private final AtomicInteger sessionEpoch = new AtomicInteger(0);

    private final Object pcmLock = new Object();
    private ByteArrayOutputStream currentSentencePcm;

    private Thread recordingThread;
    private final Handler mainHandler = new Handler(Looper.getMainLooper());
    private Runnable sessionTimeoutRunnable;
    private Runnable autoStopRunnable;

    private final ExecutorService segmentExecutor = Executors.newSingleThreadExecutor();
    private final ScheduledExecutorService partialScheduler = Executors.newSingleThreadScheduledExecutor();
    private ScheduledFuture<?> partialTaskFuture;

    private RecognitionListener currentListener;
    private ConfigManager currentConfig;
    private String currentLanguage;
    private ResultCallback currentCallback;
    private VoiceActivityDetector vad;
    private Context appContext;
    private final AudioEffectsHelper effectsHelper = new AudioEffectsHelper();

    public static synchronized AudioRecorderManager getInstance() {
        if (instance == null) {
            instance = new AudioRecorderManager();
        }
        return instance;
    }

    public synchronized void startRecording(RecognitionListener listener, ConfigManager config, String dynamicLang, ResultCallback callback) {
        startRecording(this.appContext, listener, config, dynamicLang, callback);
    }

    public synchronized void startRecording(Context context, RecognitionListener listener, ConfigManager config, String dynamicLang, ResultCallback callback) {
        if (isRecording) {
            cancel();
        }

        this.appContext = context;
        if (context != null) {
            EarconPlayer.getInstance(context);
        }

        final int sessionId = sessionEpoch.incrementAndGet();
        this.currentListener = listener;
        this.currentConfig = config;
        this.currentLanguage = dynamicLang;
        this.currentCallback = callback;
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
            vad = new VoiceActivityDetector(silenceTimeout, new VoiceActivityDetector.VadListener() {
                @Override
                public void onSpeechStart() {
                    handleSpeechOnset(sessionId);
                }

                @Override
                public void onSpeechEnd() {
                    handleSpeechOffset(sessionId);
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
        isSpeechActive.set(true);
        Log.i(TAG, "Speech onset: activating SwiftKey speaking state & Lottie animation [session " + sessionId + "]");

        final RecognitionListener listener = currentListener;
        if (listener != null) {
            mainHandler.post(() -> {
                if (sessionId == sessionEpoch.get() && isRecording && currentListener == listener) {
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

        if (currentConfig != null && currentConfig.isStreamingEnabled()) {
            startPartialScheduler(sessionId);
        }
    }

    private void handleSpeechOffset(int sessionId) {
        if (sessionId != sessionEpoch.get() || !isRecording) {
            return;
        }
        if (!isSpeechActive.compareAndSet(true, false)) {
            return;
        }
        Log.i(TAG, "Speech offset: pausing speaking state & finalizing sentence segment [session " + sessionId + "]");

        final RecognitionListener listener = currentListener;
        if (listener != null) {
            mainHandler.post(() -> {
                if (sessionId == sessionEpoch.get() && isRecording && currentListener == listener) {
                    listener.onEndOfSpeech();
                }
            });
        }

        stopPartialScheduler();
        WhisperClient.cancelPartialRequests();

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

        segmentExecutor.execute(() -> processSentenceSegment(sessionId, pcmData));
    }

    private void processSentenceSegment(int sessionId, byte[] pcmData) {
        if (sessionId != sessionEpoch.get() || !isRecording) {
            return;
        }
        byte[] wavBytes = WavWriter.pcmToWav(pcmData, SAMPLE_RATE, 1, 16);
        Log.i(TAG, "Submitting sentence segment to Whisper (" + wavBytes.length + " bytes WAV) [session " + sessionId + "]");

        WhisperClient.transcribeSegment(wavBytes, currentConfig, currentLanguage, new WhisperClient.TranscriptionCallback() {
            @Override
            public void onSuccess(String text) {
                if (sessionId != sessionEpoch.get() || !isRecording) {
                    Log.d(TAG, "Segment transcription dropped because session changed/stopped [session " + sessionId + "]");
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

                final ResultCallback callback = currentCallback;
                if (callback != null) {
                    callback.onResult(text);
                }

                final RecognitionListener listener = currentListener;
                if (listener != null) {
                    mainHandler.post(() -> {
                        if (sessionId == sessionEpoch.get() && currentListener == listener && isRecording) {
                            Bundle bundle = createResultsBundle(text, true);
                            listener.onPartialResults(bundle);
                        }
                    });
                }
            }

            @Override
            public void onError(String errorMessage) {
                Log.e(TAG, "Segment transcription error: " + errorMessage);
            }
        });
    }

    private void startPartialScheduler(int sessionId) {
        stopPartialScheduler();
        int interval = currentConfig != null ? currentConfig.getPartialIntervalMs() : 1000;

        partialTaskFuture = partialScheduler.scheduleWithFixedDelay(() -> {
            if (sessionId != sessionEpoch.get() || !isRecording || !isSpeechActive.get()) {
                return;
            }

            byte[] snapshot;
            synchronized (pcmLock) {
                snapshot = currentSentencePcm != null ? currentSentencePcm.toByteArray() : new byte[0];
            }

            if (snapshot.length < 16000) {
                return;
            }

            byte[] wavBytes = WavWriter.pcmToWav(snapshot, SAMPLE_RATE, 1, 16);
            WhisperClient.transcribePartial(wavBytes, currentConfig, currentLanguage, new WhisperClient.TranscriptionCallback() {
                @Override
                public void onSuccess(String text) {
                    if (sessionId != sessionEpoch.get() || text == null || text.trim().isEmpty() || !isSpeechActive.get() || !isRecording) {
                        return;
                    }

                    final RecognitionListener listener = currentListener;
                    if (listener != null) {
                        mainHandler.post(() -> {
                            if (sessionId == sessionEpoch.get() && currentListener == listener && isRecording && isSpeechActive.get()) {
                                Bundle bundle = createResultsBundle(text, false);
                                listener.onPartialResults(bundle);
                            }
                        });
                    }
                }

                @Override
                public void onError(String errorMessage) {
                    // Suppress partial errors; final segment will retry
                }
            });
        }, interval, interval, TimeUnit.MILLISECONDS);
    }

    private void stopPartialScheduler() {
        if (partialTaskFuture != null) {
            partialTaskFuture.cancel(false);
            partialTaskFuture = null;
        }
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
        isRecording = false;
        isSpeechActive.set(false);
        Log.i(TAG, "stopListening requested: finalizing session " + sessionId);

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
                    deliverFinalResults(sessionId, text);
                }

                @Override
                public void onError(String errorMessage) {
                    deliverFinalResults(sessionId, "");
                }
            });
        } else {
            deliverFinalResults(sessionId, "");
        }
    }

    private void deliverFinalResults(int sessionId, String text) {
        if (sessionId != sessionEpoch.get()) {
            Log.d(TAG, "deliverFinalResults dropped for stale session " + sessionId);
            return;
        }
        final RecognitionListener listener = currentListener;
        if (listener != null) {
            mainHandler.post(() -> {
                if (sessionId == sessionEpoch.get() && currentListener == listener) {
                    Bundle finalBundle = createResultsBundle(text != null ? text : "", true);
                    // onResults unconditionally terminates SwiftKey session (o0)
                    listener.onResults(finalBundle);
                }
            });
        }
    }

    public synchronized void cancel() {
        boolean wasRecording = isRecording;
        sessionEpoch.incrementAndGet();
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

        synchronized (pcmLock) {
            currentSentencePcm = null;
        }

        currentListener = null;
        currentCallback = null;
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
