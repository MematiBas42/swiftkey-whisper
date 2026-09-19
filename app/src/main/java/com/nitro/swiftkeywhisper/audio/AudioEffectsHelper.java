package com.nitro.swiftkeywhisper.audio;

import android.media.audiofx.AutomaticGainControl;
import android.media.audiofx.NoiseSuppressor;
import android.util.Log;

public class AudioEffectsHelper {
    private static final String TAG = "SwiftKeyAudioEffects";

    private NoiseSuppressor noiseSuppressor;
    private AutomaticGainControl automaticGainControl;

    public synchronized void attach(int audioSessionId) {
        if (audioSessionId <= 0) {
            Log.w(TAG, "Invalid audioSessionId: " + audioSessionId);
            return;
        }

        release();

        // 1. NoiseSuppressor (Disabled to avoid spectral distortion and preserve raw audio for Whisper)
        Log.d(TAG, "NoiseSuppressor explicitly disabled to preserve raw audio for Whisper");

        // 2. AutomaticGainControl
        try {
            if (AutomaticGainControl.isAvailable()) {
                automaticGainControl = AutomaticGainControl.create(audioSessionId);
                if (automaticGainControl != null) {
                    automaticGainControl.setEnabled(true);
                    Log.i(TAG, "AutomaticGainControl attached and enabled on session " + audioSessionId);
                } else {
                    Log.w(TAG, "AutomaticGainControl.create returned null for session " + audioSessionId);
                }
            } else {
                Log.d(TAG, "AutomaticGainControl is not available on this device");
            }
        } catch (Throwable t) {
            Log.w(TAG, "Failed to attach AutomaticGainControl: " + t.getMessage());
        }
    }

    public synchronized void release() {
        if (noiseSuppressor != null) {
            try {
                noiseSuppressor.setEnabled(false);
                noiseSuppressor.release();
            } catch (Throwable t) {
                Log.w(TAG, "Error releasing NoiseSuppressor: " + t.getMessage());
            } finally {
                noiseSuppressor = null;
            }
        }

        if (automaticGainControl != null) {
            try {
                automaticGainControl.setEnabled(false);
                automaticGainControl.release();
            } catch (Throwable t) {
                Log.w(TAG, "Error releasing AutomaticGainControl: " + t.getMessage());
            } finally {
                automaticGainControl = null;
            }
        }
    }
}
