package com.nitro.swiftkeywhisper.audio;

import android.content.Context;
import android.util.Log;

import com.nitro.swiftkeywhisper.MainHook;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.nio.FloatBuffer;
import java.nio.LongBuffer;
import java.nio.file.Files;
import java.util.HashMap;
import java.util.Map;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;

import ai.onnxruntime.OnnxTensor;
import ai.onnxruntime.OrtEnvironment;
import ai.onnxruntime.OrtSession;

public class VoiceActivityDetector {
    private static final String TAG = "SwiftKeyWhisperVAD";

    public interface VadListener {
        void onSpeechStart();
        void onSpeechEnd();
    }

    private final VadListener listener;
    private final Context hostContext;
    private int silenceTimeoutMs;

    // Audio parameters (16kHz 16-bit Mono)
    private static final int SAMPLE_RATE = 16000;
    private static final int BYTES_PER_SAMPLE = 2; // 16-bit
    // Silero VAD requires 512 samples per frame at 16kHz (32ms)
    private static final int FRAME_SAMPLES = 512;
    private static final int FRAME_BYTES = FRAME_SAMPLES * BYTES_PER_SAMPLE; // 1024 bytes

    // Pre-roll buffer (250ms to preserve leading consonants before speech onset)
    private static final int PRE_ROLL_MS = 250;
    private static final int PRE_ROLL_BYTES = (SAMPLE_RATE * PRE_ROLL_MS / 1000) * BYTES_PER_SAMPLE;
    private final byte[] preRollRingBuffer = new byte[PRE_ROLL_BYTES];
    private int preRollWriteIndex = 0;
    private boolean preRollFull = false;

    // Silero VAD Engine (Direct ONNX Runtime - Shared Singleton Session for 0ms Cold Start)
    private static volatile OrtEnvironment sharedEnv;
    private static volatile OrtSession sharedSession;
    private static final Object INIT_LOCK = new Object();

    private OrtEnvironment ortEnv;
    private OrtSession ortSession;
    private boolean isInitialized = false;

    // Model Recurrent States (H and C states of LSTM)
    private float[] h = new float[128]; // 2 * 1 * 64
    private float[] c = new float[128]; // 2 * 1 * 64

    // Frame buffer for 1024-byte slicing
    private final byte[] frameBuffer = new byte[FRAME_BYTES];
    private int frameBufferOffset = 0;

    // Timing & Debounce parameters (calculated from milliseconds)
    private static final int SPEECH_DEBOUNCE_MS = 180; // 180ms ~ 6 frames
    private static final float CONFIDENCE_THRESHOLD = 0.85f; // Silero high-confidence speech threshold

    private int maxSpeechFrames;
    private int maxSilenceFrames;
    private int speechFramesCount = 0;
    private int silenceFramesCount = 0;

    // State
    private boolean isSpeaking = false;

    public VoiceActivityDetector(Context context, int silenceTimeoutMs, VadListener listener) {
        this.hostContext = context;
        this.silenceTimeoutMs = Math.max(300, silenceTimeoutMs);
        this.listener = listener;

        recalculateFrameCounts();
        initModel();
    }

    private void recalculateFrameCounts() {
        // Frame duration = 32ms (512 samples at 16kHz)
        maxSpeechFrames = Math.max(1, SPEECH_DEBOUNCE_MS / 32);
        maxSilenceFrames = Math.max(1, silenceTimeoutMs / 32);
    }

    private static byte[] loadModelBytes(Context context) {
        // 1. Check if model file already extracted in host cache directory
        if (context != null) {
            try {
                File cached = new File(context.getCacheDir(), "silero_vad.onnx");
                if (cached.exists() && cached.length() > 1000000) {
                    return Files.readAllBytes(cached.toPath());
                }
            } catch (Throwable ignored) {}
        }

        // 2. Extract directly from module APK via MainHook.getModuleApkPath()
        String apkPath = MainHook.getModuleApkPath();
        if (apkPath != null) {
            try (ZipFile zip = new ZipFile(apkPath)) {
                ZipEntry entry = zip.getEntry("assets/silero_vad.onnx");
                if (entry != null) {
                    try (InputStream is = zip.getInputStream(entry)) {
                        ByteArrayOutputStream baos = new ByteArrayOutputStream();
                        byte[] buf = new byte[8192];
                        int r;
                        while ((r = is.read(buf)) != -1) {
                            baos.write(buf, 0, r);
                        }
                        byte[] modelBytes = baos.toByteArray();

                        // Cache to host directory for fast subsequent starts
                        if (context != null && modelBytes.length > 0) {
                            try (FileOutputStream fos = new FileOutputStream(new File(context.getCacheDir(), "silero_vad.onnx"))) {
                                fos.write(modelBytes);
                            } catch (Throwable ignored) {}
                        }
                        Log.i(TAG, "Loaded silero_vad.onnx directly from module APK (" + apkPath + ", " + modelBytes.length + " bytes)");
                        return modelBytes;
                    }
                }
            } catch (Throwable t) {
                Log.w(TAG, "Failed reading silero_vad.onnx from APK zip: " + t.getMessage());
            }
        }

        // 3. Direct assets stream fallback (when running inside module app context)
        if (context != null) {
            try (InputStream is = context.getAssets().open("silero_vad.onnx")) {
                ByteArrayOutputStream baos = new ByteArrayOutputStream();
                byte[] buf = new byte[8192];
                int r;
                while ((r = is.read(buf)) != -1) {
                    baos.write(buf, 0, r);
                }
                byte[] bytes = baos.toByteArray();
                Log.i(TAG, "Loaded silero_vad.onnx via context.getAssets()");
                return bytes;
            } catch (Throwable ignored) {}
        }

        Log.e(TAG, "silero_vad.onnx could not be located or loaded");
        return null;
    }

    public static void prewarm(Context context) {
        if (sharedSession != null) {
            return;
        }
        synchronized (INIT_LOCK) {
            if (sharedSession != null) {
                return;
            }
            try {
                byte[] modelBytes = loadModelBytes(context);
                if (modelBytes == null || modelBytes.length == 0) {
                    Log.w(TAG, "Silero VAD prewarm skipped: model bytes not found");
                    return;
                }

                sharedEnv = OrtEnvironment.getEnvironment();
                OrtSession.SessionOptions options = new OrtSession.SessionOptions();
                options.setIntraOpNumThreads(1);
                options.setInterOpNumThreads(1);
                options.setOptimizationLevel(OrtSession.SessionOptions.OptLevel.ALL_OPT);

                sharedSession = sharedEnv.createSession(modelBytes, options);
                Log.i(TAG, "Silero VAD DNN pre-warmed successfully (0ms ready, " + modelBytes.length + " bytes)");
            } catch (Throwable t) {
                Log.e(TAG, "Error pre-warming Silero VAD: " + t.getMessage(), t);
            }
        }
    }

    private synchronized void initModel() {
        if (sharedSession == null) {
            prewarm(hostContext);
        }

        ortEnv = sharedEnv;
        ortSession = sharedSession;
        isInitialized = (ortSession != null);
        resetStates();

        if (isInitialized) {
            Log.i(TAG, "Silero VAD session active (0ms start, silence: " + silenceTimeoutMs + "ms, speech: " + SPEECH_DEBOUNCE_MS + "ms)");
        }
    }

    private void resetStates() {
        if (h != null) java.util.Arrays.fill(h, 0.0f);
        if (c != null) java.util.Arrays.fill(c, 0.0f);
        speechFramesCount = 0;
        silenceFramesCount = 0;
    }

    public synchronized void setSilenceTimeoutMs(int silenceTimeoutMs) {
        int newTimeout = Math.max(300, silenceTimeoutMs);
        if (this.silenceTimeoutMs != newTimeout) {
            this.silenceTimeoutMs = newTimeout;
            recalculateFrameCounts();
        }
    }

    public synchronized void processBuffer(byte[] buffer, int offset, int length) {
        if (buffer == null || length <= 0) {
            return;
        }

        // Feed into circular pre-roll buffer
        appendPreRoll(buffer, offset, length);

        if (!isInitialized || ortSession == null) {
            return;
        }

        // Accumulate and process in exact 1024-byte (512 sample / 32ms) frames
        int currentOffset = offset;
        int remaining = length;

        while (remaining > 0) {
            int needed = FRAME_BYTES - frameBufferOffset;
            int toCopy = Math.min(needed, remaining);
            System.arraycopy(buffer, currentOffset, frameBuffer, frameBufferOffset, toCopy);
            frameBufferOffset += toCopy;
            currentOffset += toCopy;
            remaining -= toCopy;

            if (frameBufferOffset == FRAME_BYTES) {
                processFrame(frameBuffer);
                frameBufferOffset = 0;
            }
        }
    }

    private void processFrame(byte[] frame) {
        try {
            // 1. Convert 16-bit PCM bytes to normalized float [-1.0f, 1.0f]
            float[] floatAudio = new float[FRAME_SAMPLES];
            for (int i = 0; i < FRAME_SAMPLES; i++) {
                short sample = (short) ((frame[i * 2] & 0xFF) | (frame[i * 2 + 1] << 8));
                floatAudio[i] = sample / 32768.0f;
            }

            // 2. Prepare ONNX input tensors
            Map<String, OnnxTensor> inputs = new HashMap<>(4);
            inputs.put("input", OnnxTensor.createTensor(ortEnv, FloatBuffer.wrap(floatAudio), new long[]{1, FRAME_SAMPLES}));
            inputs.put("sr", OnnxTensor.createTensor(ortEnv, LongBuffer.wrap(new long[]{SAMPLE_RATE}), new long[]{1}));
            inputs.put("h", OnnxTensor.createTensor(ortEnv, FloatBuffer.wrap(h), new long[]{2, 1, 64}));
            inputs.put("c", OnnxTensor.createTensor(ortEnv, FloatBuffer.wrap(c), new long[]{2, 1, 64}));

            // 3. Run inference
            float confidence = 0.0f;
            try (OrtSession.Result result = ortSession.run(inputs)) {
                // Output 0: confidence probability [1, 1]
                Object outputObj = result.get(0).getValue();
                if (outputObj instanceof float[][]) {
                    confidence = ((float[][]) outputObj)[0][0];
                }

                // Output 1: updated hn state [2, 1, 64]
                Object hnObj = result.get(1).getValue();
                if (hnObj instanceof float[][][]) {
                    float[][][] hnVal = (float[][][]) hnObj;
                    int idx = 0;
                    for (int i = 0; i < 2; i++) {
                        for (int j = 0; j < 1; j++) {
                            for (int k = 0; k < 64; k++) {
                                h[idx++] = hnVal[i][j][k];
                            }
                        }
                    }
                }

                // Output 2: updated cn state [2, 1, 64]
                Object cnObj = result.get(2).getValue();
                if (cnObj instanceof float[][][]) {
                    float[][][] cnVal = (float[][][]) cnObj;
                    int idx = 0;
                    for (int i = 0; i < 2; i++) {
                        for (int j = 0; j < 1; j++) {
                            for (int k = 0; k < 64; k++) {
                                c[idx++] = cnVal[i][j][k];
                            }
                        }
                    }
                }
            } finally {
                for (OnnxTensor tensor : inputs.values()) {
                    tensor.close();
                }
            }

            // 4. Continuous speech hysteresis decision
            boolean isFrameSpeech = (confidence >= CONFIDENCE_THRESHOLD);
            boolean speechDetected = evaluateContinuousSpeech(isFrameSpeech);

            if (speechDetected && !isSpeaking) {
                isSpeaking = true;
                Log.d(TAG, "Silero VAD: Speech onset detected (prob: " + confidence + ")");
                if (listener != null) {
                    listener.onSpeechStart();
                }
            } else if (!speechDetected && isSpeaking) {
                isSpeaking = false;
                Log.d(TAG, "Silero VAD: Speech end detected (silence duration elapsed, prob: " + confidence + ")");
                if (listener != null) {
                    listener.onSpeechEnd();
                }
            }
        } catch (Throwable t) {
            Log.w(TAG, "Error in Silero VAD inference: " + t.getMessage());
        }
    }

    private boolean evaluateContinuousSpeech(boolean isSpeech) {
        if (isSpeech) {
            if (speechFramesCount <= maxSpeechFrames) {
                speechFramesCount++;
            }
            if (speechFramesCount > maxSpeechFrames) {
                silenceFramesCount = 0;
                return true;
            }
        } else {
            if (silenceFramesCount <= maxSilenceFrames) {
                silenceFramesCount++;
            }
            if (silenceFramesCount > maxSilenceFrames) {
                speechFramesCount = 0;
                return false;
            } else if (speechFramesCount > maxSpeechFrames) {
                return true;
            }
        }
        return false;
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
        frameBufferOffset = 0;
        preRollWriteIndex = 0;
        preRollFull = false;
        resetStates();
    }

    public synchronized void close() {
        isSpeaking = false;
        frameBufferOffset = 0;
        preRollWriteIndex = 0;
        preRollFull = false;
        resetStates();
        // sharedSession and sharedEnv remain warm for 0ms subsequent sessions
    }

    public boolean isSpeaking() {
        return isSpeaking;
    }
}
