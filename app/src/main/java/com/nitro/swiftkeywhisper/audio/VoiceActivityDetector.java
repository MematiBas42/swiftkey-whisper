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
    private int processedFrames = 0;

    // VAD State
    private boolean isSpeaking = false;
    private int consecutiveSpeechFrames = 0;
    private int silenceDurationMs = 0;
    private float noiseFloorDb = 35.0f; // Initial estimate

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
        // Calculate RMS and Zero-Crossing Rate (ZCR) of frame
        long sum = 0;
        int zcrCount = 0;
        short prevSample = 0;
        int sampleCount = length / 2;

        for (int i = 0; i < length - 1; i += 2) {
            short sample = (short) ((frame[offset + i] & 0xFF) | (frame[offset + i + 1] << 8));
            sum += (long) sample * sample;

            if (i > 0) {
                if ((prevSample >= 0 && sample < 0) || (prevSample < 0 && sample >= 0)) {
                    zcrCount++;
                }
            }
            prevSample = sample;
        }

        double mean = (double) sum / (sampleCount > 0 ? sampleCount : 1);
        double rms = Math.sqrt(mean);
        float frameDb = (float) (20 * Math.log10(rms > 0 ? rms : 1));

        // Initial calibration window (first ~240ms after mic opening)
        // Suppresses hardware turn-on pops and rapidly learns the real ambient noise floor
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
        // Allows steady mechanical hums up to 55 dB to be absorbed into the noise floor
        if (!isSpeaking) {
            if (frameDb < noiseFloorDb + 8.0f || (isMechanicalHum && frameDb <= 55.0f)) {
                noiseFloorDb = Math.max(20.0f, Math.min(55.0f, noiseFloorDb * 0.98f + frameDb * 0.02f));
            }
        }

        float speechThresholdDb = Math.max(46.0f, noiseFloorDb + 10.0f);
        float silenceThresholdDb = Math.max(40.0f, noiseFloorDb + 5.0f);

        // Soft ZCR guard:
        // Pure monotonous mechanical hum cannot trigger speech onset from silence.
        // Once speech is active (isSpeaking == true), ZCR is never used to cut speech,
        // strictly preserving deep male resonant vowels and trailing phonemes.
        boolean meetsSpeechEnergy = (frameDb >= speechThresholdDb);
        boolean validSpeechFrame = meetsSpeechEnergy && (isSpeaking || !isMechanicalHum);

        if (validSpeechFrame) {
            consecutiveSpeechFrames++;
            silenceDurationMs = 0;

            if (consecutiveSpeechFrames >= MIN_SPEECH_FRAMES && !isSpeaking) {
                isSpeaking = true;
                Log.d(TAG, "Speech onset detected (" + frameDb + " dB, noise: " + noiseFloorDb + " dB, zcr: " + zcrCount + ")");
                if (listener != null) {
                    listener.onSpeechStart();
                }
            }
        } else if (frameDb < silenceThresholdDb || (!isSpeaking && isMechanicalHum)) {
            consecutiveSpeechFrames = 0;

            if (isSpeaking) {
                silenceDurationMs += FRAME_DURATION_MS;
                if (silenceDurationMs >= silenceTimeoutMs) {
                    isSpeaking = false;
                    silenceDurationMs = 0;
                    Log.d(TAG, "Speech end detected (silence " + silenceTimeoutMs + "ms, frame: " + frameDb + " dB)");
                    if (listener != null) {
                        listener.onSpeechEnd();
                    }
                }
            }
        } else {
            // In-between hysteresis zone
            if (isSpeaking) {
                silenceDurationMs += (FRAME_DURATION_MS / 2);
            } else {
                consecutiveSpeechFrames = 0;
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
    }

    public boolean isSpeaking() {
        return isSpeaking;
    }
}
