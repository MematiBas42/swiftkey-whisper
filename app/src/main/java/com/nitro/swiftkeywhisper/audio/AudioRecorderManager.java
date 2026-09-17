package com.nitro.swiftkeywhisper.audio;

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

public class AudioRecorderManager {
    private static final String TAG = "SwiftKeyWhisperAudio";
    private static final int SAMPLE_RATE = 16000;
    private static final int CHANNEL_CONFIG = AudioFormat.CHANNEL_IN_MONO;
    private static final int AUDIO_FORMAT = AudioFormat.ENCODING_PCM_16BIT;

    public interface ResultCallback {
        void onResult(String text);
        void onError(String error);
    }

    private static AudioRecorderManager instance;
    private AudioRecord audioRecord;
    private volatile boolean isRecording = false;
    private final AtomicBoolean isSpeechActive = new AtomicBoolean(false);

    private final Object pcmLock = new Object();
    private ByteArrayOutputStream currentSentencePcm;

    private Thread recordingThread;
    private final Handler mainHandler = new Handler(Looper.getMainLooper());

    private final ExecutorService segmentExecutor = Executors.newSingleThreadExecutor();
    private final ScheduledExecutorService partialScheduler = Executors.newSingleThreadScheduledExecutor();
    private ScheduledFuture<?> partialTaskFuture;

    private RecognitionListener currentListener;
    private ConfigManager currentConfig;
    private String currentLanguage;
    private ResultCallback currentCallback;
    private VoiceActivityDetector vad;

    public static synchronized AudioRecorderManager getInstance() {
        if (instance == null) {
            instance = new AudioRecorderManager();
        }
        return instance;
    }

    public synchronized void startRecording(RecognitionListener listener, ConfigManager config, String dynamicLang, ResultCallback callback) {
        if (isRecording) {
            stopListening();
            return;
        }

        this.currentListener = listener;
        this.currentConfig = config;
        this.currentLanguage = dynamicLang;
        this.currentCallback = callback;
        this.currentConfig.clearContext(); // Reset session context

        int minBufferSize = AudioRecord.getMinBufferSize(SAMPLE_RATE, CHANNEL_CONFIG, AUDIO_FORMAT);
        int bufferSize = Math.max(minBufferSize, 4096);

        try {
            audioRecord = new AudioRecord(
                    MediaRecorder.AudioSource.MIC,
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

            synchronized (pcmLock) {
                currentSentencePcm = new ByteArrayOutputStream();
            }

            // Initialize VAD with configured silence threshold (e.g. 750ms)
            int silenceTimeout = config != null ? config.getSilenceTimeoutMs() : ConfigManager.DEFAULT_SILENCE_TIMEOUT_MS;
            vad = new VoiceActivityDetector(silenceTimeout, new VoiceActivityDetector.VadListener() {
                @Override
                public void onSpeechStart() {
                    handleSpeechOnset();
                }

                @Override
                public void onSpeechEnd() {
                    handleSpeechOffset();
                }
            });

            audioRecord.startRecording();
            isRecording = true;
            isSpeechActive.set(false);

            Log.i(TAG, "Audio recording pipeline started (16kHz Mono)");

            // SwiftKey contract: signal readiness immediately (0ms delay, instant native response)
            if (listener != null) {
                final RecognitionListener targetListener = listener;
                mainHandler.post(() -> {
                    if (isRecording && targetListener == currentListener) {
                        targetListener.onReadyForSpeech(new Bundle());
                    }
                });
            }

            recordingThread = new Thread(new RecordingRunnable(bufferSize), "WhisperAudioCapture");
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

    private void handleSpeechOnset() {
        isSpeechActive.set(true);
        Log.i(TAG, "Speech onset: activating SwiftKey speaking state & Lottie animation");

        // Notify SwiftKey to start Lottie waveform wave animation (VOICE_TALK)
        if (currentListener != null) {
            mainHandler.post(() -> {
                if (currentListener != null && isRecording) {
                    currentListener.onBeginningOfSpeech();
                }
            });
        }

        // Initialize sentence buffer with pre-roll audio (to prevent clipping start consonants)
        synchronized (pcmLock) {
            currentSentencePcm.reset();
            byte[] preRoll = vad.getPreRollBytes();
            if (preRoll != null && preRoll.length > 0) {
                currentSentencePcm.write(preRoll, 0, preRoll.length);
            }
        }

        // Start periodic partial transcription if streaming is enabled
        if (currentConfig != null && currentConfig.isStreamingEnabled()) {
            startPartialScheduler();
        }
    }

    private void handleSpeechOffset() {
        if (!isSpeechActive.compareAndSet(true, false)) {
            return;
        }
        Log.i(TAG, "Speech offset: pausing speaking state & finalizing sentence segment");

        // Notify SwiftKey to transition Lottie animation back to idle (VOICE_TALK_OUT -> Speak now)
        if (currentListener != null) {
            mainHandler.post(() -> {
                if (currentListener != null && isRecording) {
                    currentListener.onEndOfSpeech();
                }
            });
        }

        stopPartialScheduler();
        WhisperClient.cancelPartialRequests();

        // Extract captured sentence PCM
        byte[] pcmData;
        synchronized (pcmLock) {
            pcmData = currentSentencePcm != null ? currentSentencePcm.toByteArray() : new byte[0];
            if (currentSentencePcm != null) {
                currentSentencePcm.reset();
            }
        }

        // Minimum audio length guard: ~0.3s (4800 samples = 9600 bytes)
        if (pcmData.length < 9600) {
            Log.d(TAG, "Sentence audio too short (" + pcmData.length + " bytes), ignoring.");
            return;
        }

        // Dispatch finalized sentence to Groq Whisper
        segmentExecutor.execute(() -> processSentenceSegment(pcmData));
    }

    private void processSentenceSegment(byte[] pcmData) {
        byte[] wavBytes = WavWriter.pcmToWav(pcmData, SAMPLE_RATE, 1, 16);
        Log.i(TAG, "Submitting sentence segment to Whisper (" + wavBytes.length + " bytes WAV)");

        WhisperClient.transcribeSegment(wavBytes, currentConfig, currentLanguage, new WhisperClient.TranscriptionCallback() {
            @Override
            public void onSuccess(String text) {
                if (text == null || text.trim().isEmpty()) {
                    Log.d(TAG, "Whisper returned empty segment text");
                    return;
                }

                Log.i(TAG, "Segment transcription committed: " + text);

                // Update rolling context history for next sentences
                if (currentConfig != null) {
                    currentConfig.appendContextText(text);
                }

                if (currentCallback != null) {
                    currentCallback.onResult(text);
                }

                // Deliver as finalized segment to SwiftKey
                // final_result = true triggers y0 (VoiceTypingSegmentCompleted)
                // SwiftKey commits text permanently with commitText() and KEEPS SESSION ALIVE!
                if (currentListener != null) {
                    mainHandler.post(() -> {
                        if (currentListener != null && isRecording) {
                            Bundle bundle = createResultsBundle(text, true);
                            currentListener.onPartialResults(bundle);
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

    private void startPartialScheduler() {
        stopPartialScheduler();
        int interval = currentConfig != null ? currentConfig.getPartialIntervalMs() : 1000;

        partialTaskFuture = partialScheduler.scheduleWithFixedDelay(() -> {
            if (!isRecording || !isSpeechActive.get()) {
                return;
            }

            byte[] snapshot;
            synchronized (pcmLock) {
                snapshot = currentSentencePcm != null ? currentSentencePcm.toByteArray() : new byte[0];
            }

            // Only transcribe partial if we have at least 0.5s of speech
            if (snapshot.length < 16000) {
                return;
            }

            byte[] wavBytes = WavWriter.pcmToWav(snapshot, SAMPLE_RATE, 1, 16);
            WhisperClient.transcribePartial(wavBytes, currentConfig, currentLanguage, new WhisperClient.TranscriptionCallback() {
                @Override
                public void onSuccess(String text) {
                    if (text == null || text.trim().isEmpty() || !isSpeechActive.get() || !isRecording) {
                        return;
                    }

                    // Deliver live partial text to SwiftKey
                    // final_result = false triggers p0 (VoiceTypingPartialFragment)
                    // SwiftKey writes text in real time with setComposingText()!
                    if (currentListener != null) {
                        mainHandler.post(() -> {
                            if (currentListener != null && isRecording && isSpeechActive.get()) {
                                Bundle bundle = createResultsBundle(text, false);
                                currentListener.onPartialResults(bundle);
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

    public synchronized void stopListening() {
        if (!isRecording) {
            return;
        }
        isRecording = false;
        Log.i(TAG, "stopListening requested by user/SwiftKey: finalizing session");

        stopPartialScheduler();
        WhisperClient.cancelPartialRequests();

        try {
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
                    deliverFinalResults(text);
                }

                @Override
                public void onError(String errorMessage) {
                    deliverFinalResults("");
                }
            });
        } else {
            deliverFinalResults("");
        }
    }

    private void deliverFinalResults(String text) {
        if (currentListener != null) {
            mainHandler.post(() -> {
                if (currentListener != null) {
                    Bundle finalBundle = createResultsBundle(text != null ? text : "", true);
                    // onResults unconditionally terminates SwiftKey session (o0)
                    currentListener.onResults(finalBundle);
                }
            });
        }
    }

    public synchronized void cancel() {
        isRecording = false;
        isSpeechActive.set(false);
        mainHandler.removeCallbacksAndMessages(null);
        stopPartialScheduler();
        WhisperClient.cancelPartialRequests();

        try {
            if (audioRecord != null) {
                audioRecord.stop();
                audioRecord.release();
                audioRecord = null;
            }
        } catch (Throwable ignored) {}

        synchronized (pcmLock) {
            currentSentencePcm = null;
        }
        Log.i(TAG, "Audio recording cancelled");
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

        RecordingRunnable(int bufferSize) {
            this.bufferSize = bufferSize;
        }

        @Override
        public void run() {
            byte[] buffer = new byte[bufferSize];

            while (isRecording) {
                int read = audioRecord != null ? audioRecord.read(buffer, 0, buffer.length) : -1;
                if (read > 0) {
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

                    // Forward buffer event to SwiftKey to mark receivedAudioData = true
                    if (currentListener != null) {
                        final byte[] copy = new byte[read];
                        System.arraycopy(buffer, 0, copy, 0, read);
                        mainHandler.post(() -> {
                            if (isRecording && currentListener != null) {
                                currentListener.onBufferReceived(copy);
                            }
                        });
                    }
                }
            }
        }
    }
}
