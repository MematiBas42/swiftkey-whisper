package com.nitro.swiftkeywhisper.audio;

import android.util.Log;

public class VoiceActivityDetector {
    private static final String TAG = "SwiftKeyWhisperVAD";

    public interface VadListener {
        void onSpeechStart();
        void onSpeechEnd();
    }

    private final VadListener listener;
    private int silenceTimeoutMs;

    // Audio parameters (16kHz 16-bit Mono)
    private static final int SAMPLE_RATE = 16000;
    private static final int BYTES_PER_SAMPLE = 2; // 16-bit
    private static final int FRAME_DURATION_MS = 30; // 30ms per frame
    private static final int FRAME_SAMPLES = (SAMPLE_RATE * FRAME_DURATION_MS) / 1000; // 480 samples
    private static final int FRAME_BYTES = FRAME_SAMPLES * BYTES_PER_SAMPLE; // 960 bytes

    // Pre-roll buffer (250ms to preserve leading consonants)
    private static final int PRE_ROLL_MS = 250;
    private static final int PRE_ROLL_BYTES = (SAMPLE_RATE * PRE_ROLL_MS / 1000) * BYTES_PER_SAMPLE;
    private final byte[] preRollRingBuffer = new byte[PRE_ROLL_BYTES];
    private int preRollWriteIndex = 0;
    private boolean preRollFull = false;

    // Calibration & Debounce parameters
    private static final int INITIAL_CALIBRATION_FRAMES = 8; // ~240ms initial noise floor calibration
    private static final int MIN_SPEECH_FRAMES = 3; // ~90ms debounce to prevent clicks/pops from triggering wave
    private static final int MIN_SPEECH_ZCR = 8; // ~260Hz boundary: below this is pure monotonous mechanical hum
    private static final float PRE_EMPHASIS_COEFF = 0.95f; // High-pass filter attenuates <150Hz rumble by >22dB
    private static final float RELATIVE_FALLOFF_DB = 12.0f; // Drop from peak speech energy in noisy environments
    private static final float PEAK_DECAY_PER_FRAME_DB = 0.04f; // ~1.3 dB/s slow peak decay
    private static final int MAX_UTTERANCE_DURATION_MS = 15000; // 15s max continuous segment safety clamp
    private int processedFrames = 0;

    // VAD State
    private boolean isSpeaking = false;
    private int consecutiveSpeechFrames = 0;
    private int silenceDurationMs = 0;
    private float noiseFloorDb = 35.0f; // Initial estimate
    private short prevRawSample = 0;
    private float speechPeakDb = 0.0f;
    private int utteranceDurationMs = 0;

    public VoiceActivityDetector(int silenceTimeoutMs, VadListener listener) {
        this.silenceTimeoutMs = Math.max(300, silenceTimeoutMs);
        this.listener = listener;
    }

    public void setSilenceTimeoutMs(int silenceTimeoutMs) {
        this.silenceTimeoutMs = Math.max(300, silenceTimeoutMs);
    }

    public synchronized void processBuffer(byte[] buffer, int offset, int length) {
        // Feed into circular pre-roll buffer
        appendPreRoll(buffer, offset, length);

        // Process audio in 30ms chunks
        int remaining = length;
        int currentOffset = offset;

        while (remaining >= FRAME_BYTES) {
            processFrame(buffer, currentOffset, FRAME_BYTES);
            currentOffset += FRAME_BYTES;
            remaining -= FRAME_BYTES;
        }
    }

    private void processFrame(byte[] frame, int offset, int length) {
        // Calculate RMS and Zero-Crossing Rate (ZCR) with pre-emphasis filtering
        long sum = 0;
        int zcrCount = 0;
        short prevSample = 0;
        int sampleCount = length / 2;

        for (int i = 0; i < length - 1; i += 2) {
            short rawSample = (short) ((frame[offset + i] & 0xFF) | (frame[offset + i + 1] << 8));
            short filteredSample = (short) (rawSample - PRE_EMPHASIS_COEFF * prevRawSample);
            prevRawSample = rawSample;

            sum += (long) filteredSample * filteredSample;

            if (i > 0) {
                if ((prevSample >= 0 && filteredSample < 0) || (prevSample < 0 && filteredSample >= 0)) {
                    zcrCount++;
                }
            }
            prevSample = filteredSample;
        }

        double mean = (double) sum / (sampleCount > 0 ? sampleCount : 1);
        double rms = Math.sqrt(mean);
        float frameDb = (float) (20 * Math.log10(rms > 0 ? rms : 1));

        // Initial calibration window (first ~240ms after mic opening)
        if (processedFrames < INITIAL_CALIBRATION_FRAMES) {
            processedFrames++;
            if (processedFrames == 1) {
                noiseFloorDb = Math.max(20.0f, Math.min(50.0f, frameDb));
            } else {
                noiseFloorDb = noiseFloorDb * 0.7f + frameDb * 0.3f;
            }
            return;
        }

        // Monotonous low-frequency mechanical rumble (AC, fan, engine hum < ~130-260Hz)
        boolean isMechanicalHum = (zcrCount < MIN_SPEECH_ZCR);

        // Adaptive noise floor tracking:
        // Allows steady background noise to adapt smoothly even if noise levels rise moderately
        if (!isSpeaking) {
            if (frameDb < noiseFloorDb + 12.0f || (isMechanicalHum && frameDb <= 55.0f)) {
                float alpha = (frameDb < noiseFloorDb) ? 0.05f : 0.01f;
                noiseFloorDb = Math.max(20.0f, Math.min(55.0f, noiseFloorDb * (1.0f - alpha) + frameDb * alpha));
            }
        }

        float speechThresholdDb = Math.max(46.0f, noiseFloorDb + 10.0f);
        float silenceThresholdDb = Math.max(40.0f, noiseFloorDb + 5.0f);

        boolean meetsSpeechEnergy = (frameDb >= speechThresholdDb);
        boolean validSpeechFrame = meetsSpeechEnergy && (isSpeaking || !isMechanicalHum);

        if (validSpeechFrame) {
            consecutiveSpeechFrames++;
            silenceDurationMs = 0;

            if (isSpeaking) {
                speechPeakDb = Math.max(speechPeakDb, frameDb);
                utteranceDurationMs += FRAME_DURATION_MS;

                // Max utterance safety clamp: split segment if speech/noise exceeds max duration
                if (utteranceDurationMs >= MAX_UTTERANCE_DURATION_MS) {
                    isSpeaking = false;
                    silenceDurationMs = 0;
                    utteranceDurationMs = 0;
                    speechPeakDb = 0.0f;
                    Log.i(TAG, "Max utterance duration reached (" + MAX_UTTERANCE_DURATION_MS + "ms), forcing speech end");
                    if (listener != null) {
                        listener.onSpeechEnd();
                    }
                    return;
                }
            }

            if (consecutiveSpeechFrames >= MIN_SPEECH_FRAMES && !isSpeaking) {
                isSpeaking = true;
                speechPeakDb = frameDb;
                utteranceDurationMs = 0;
                Log.d(TAG, "Speech onset detected (" + frameDb + " dB, noise: " + noiseFloorDb + " dB, zcr: " + zcrCount + ")");
                if (listener != null) {
                    listener.onSpeechStart();
                }
            }
        } else {
            // Relative falloff condition: detects speech end in high ambient noise
            boolean relativeFalloff = isSpeaking && (speechPeakDb >= speechThresholdDb) && (speechPeakDb - frameDb >= RELATIVE_FALLOFF_DB);
            boolean isSilence = (frameDb < silenceThresholdDb) || relativeFalloff || (!isSpeaking && isMechanicalHum);

            if (isSilence) {
                consecutiveSpeechFrames = 0;

                if (isSpeaking) {
                    speechPeakDb = Math.max(speechThresholdDb, speechPeakDb - PEAK_DECAY_PER_FRAME_DB);
                    utteranceDurationMs += FRAME_DURATION_MS;
                    silenceDurationMs += FRAME_DURATION_MS;

                    if (silenceDurationMs >= silenceTimeoutMs) {
                        isSpeaking = false;
                        silenceDurationMs = 0;
                        utteranceDurationMs = 0;
                        speechPeakDb = 0.0f;
                        Log.d(TAG, "Speech end detected (silence " + silenceTimeoutMs + "ms, frame: " + frameDb + " dB, peak: " + speechPeakDb + " dB)");
                        if (listener != null) {
                            listener.onSpeechEnd();
                        }
                    }
                }
            } else {
                // In-between hysteresis zone
                if (isSpeaking) {
                    speechPeakDb = Math.max(speechPeakDb, frameDb);
                    utteranceDurationMs += FRAME_DURATION_MS;
                    silenceDurationMs += (FRAME_DURATION_MS / 2);
                } else {
                    consecutiveSpeechFrames = 0;
                }
            }
        }
    }

    private synchronized void appendPreRoll(byte[] buffer, int offset, int length) {
        for (int i = 0; i < length; i++) {
            preRollRingBuffer[preRollWriteIndex] = buffer[offset + i];
            preRollWriteIndex = (preRollWriteIndex + 1) % PRE_ROLL_BYTES;
            if (preRollWriteIndex == 0) {
                preRollFull = true;
            }
        }
    }

    public synchronized byte[] getPreRollBytes() {
        int available = preRollFull ? PRE_ROLL_BYTES : preRollWriteIndex;
        byte[] out = new byte[available];
        if (preRollFull) {
            int part1 = PRE_ROLL_BYTES - preRollWriteIndex;
            System.arraycopy(preRollRingBuffer, preRollWriteIndex, out, 0, part1);
            System.arraycopy(preRollRingBuffer, 0, out, part1, preRollWriteIndex);
        } else {
            System.arraycopy(preRollRingBuffer, 0, out, 0, preRollWriteIndex);
        }
        return out;
    }

    public synchronized void reset() {
        isSpeaking = false;
        consecutiveSpeechFrames = 0;
        silenceDurationMs = 0;
        processedFrames = 0;
        preRollWriteIndex = 0;
        preRollFull = false;
        prevRawSample = 0;
        speechPeakDb = 0.0f;
        utteranceDurationMs = 0;
    }

    public boolean isSpeaking() {
        return isSpeaking;
    }
}
