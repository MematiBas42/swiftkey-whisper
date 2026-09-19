package com.nitro.swiftkeywhisper.config;

import android.content.Context;
import android.content.SharedPreferences;
import android.util.Log;

import java.io.File;
import java.util.LinkedList;
import java.util.Locale;

public class ConfigManager {
    private static final String TAG = "SwiftKeyWhisperConfig";
    public static final String PREFS_NAME = "swiftkey_whisper_prefs";

    public static final String KEY_API_KEY = "api_key";
    public static final String KEY_ENDPOINT = "endpoint";
    public static final String KEY_MODEL = "model";
    public static final String KEY_LANGUAGE = "language";
    public static final String KEY_PROMPT = "prompt";
    public static final String KEY_SILENCE_TIMEOUT_MS = "silence_timeout_ms";
    public static final String KEY_STREAMING_ENABLED = "streaming_enabled";
    public static final String KEY_PARTIAL_INTERVAL_MS = "partial_interval_ms";
    public static final String KEY_AUTO_LANGUAGE = "auto_language";
    public static final String KEY_SOUND_EFFECTS_ENABLED = "sound_effects_enabled";
    public static final String KEY_AUTO_STOP_TIMEOUT_MS = "auto_stop_timeout_ms";

    public static final String DEFAULT_ENDPOINT = "https://api.groq.com/openai/v1/audio/transcriptions";
    public static final String DEFAULT_MODEL = "whisper-large-v3";
    public static final int DEFAULT_SILENCE_TIMEOUT_MS = 750; // Native-fast response (0.75s)
    public static final boolean DEFAULT_STREAMING_ENABLED = true;
    public static final int DEFAULT_PARTIAL_INTERVAL_MS = 1000;
    public static final boolean DEFAULT_AUTO_LANGUAGE = true;
    public static final boolean DEFAULT_SOUND_EFFECTS_ENABLED = true;
    public static final int DEFAULT_AUTO_STOP_TIMEOUT_MS = 1500; // 1.5s silence auto-terminates session to static mic

    public static String getDefaultSystemLanguage() {
        try {
            String lang = Locale.getDefault().getLanguage();
            if (lang != null && !lang.trim().isEmpty()) {
                return lang.toLowerCase().trim();
            }
        } catch (Throwable ignored) {}
        return "en";
    }

    public static String getDefaultPromptForLanguage(String lang) {
        if (lang != null && lang.toLowerCase().startsWith("tr")) {
            return "Bu bir Türkçe ses kaydıdır. Cümle içinde deploy, commit, PR, pull request, merge, bug, build, pipeline, endpoint, refactor, backend, frontend gibi teknik İngilizce terimler geçebilir; Türkçe eklerle doğru ve hatasız yazılmalıdır.";
        } else if (lang != null && lang.toLowerCase().startsWith("de")) {
            return "Dies ist eine deutsche Sprachaufnahme. Bitte auf korrekte Zeichensetzung, Groß-/Kleinschreibung und technische Fachbegriffe achten.";
        } else {
            return "High accuracy speech transcription. Maintain proper capitalization, punctuation, and technical terminology.";
        }
    }

    public static final String DEFAULT_LANGUAGE = getDefaultSystemLanguage();
    public static final String DEFAULT_PROMPT = "";

    private String apiKey = "";
    private String endpoint = DEFAULT_ENDPOINT;
    private String model = DEFAULT_MODEL;
    private String language = getDefaultSystemLanguage();
    private String prompt = DEFAULT_PROMPT;
    private int silenceTimeoutMs = DEFAULT_SILENCE_TIMEOUT_MS;
    private boolean streamingEnabled = DEFAULT_STREAMING_ENABLED;
    private int partialIntervalMs = DEFAULT_PARTIAL_INTERVAL_MS;
    private boolean autoLanguage = DEFAULT_AUTO_LANGUAGE;
    private boolean soundEffectsEnabled = DEFAULT_SOUND_EFFECTS_ENABLED;
    private int autoStopTimeoutMs = DEFAULT_AUTO_STOP_TIMEOUT_MS;

    // Rolling context history for Whisper prompt chaining (~150 words)
    private final LinkedList<String> recentWords = new LinkedList<>();
    private static final int MAX_CONTEXT_WORDS = 150;

    private static ConfigManager instance;

    public static synchronized ConfigManager getInstance(Context context) {
        if (instance == null) {
            instance = new ConfigManager();
            instance.load(context);
        }
        return instance;
    }

    public static SharedPreferences getSharedPreferences(Context context) {
        if (context == null) {
            return null;
        }
        return context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE);
    }

    public static String sanitizeApiKey(String key) {
        if (key == null) return "";
        String trimmed = key.replaceAll("[\\r\\n\\t ]+", "").trim();
        int gskIndex = trimmed.indexOf("gsk_");
        if (gskIndex != -1) {
            String sub = trimmed.substring(gskIndex);
            int end = 0;
            while (end < sub.length()) {
                char c = sub.charAt(end);
                if ((c >= 'a' && c <= 'z') || (c >= 'A' && c <= 'Z') || (c >= '0' && c <= '9') || c == '_') {
                    end++;
                } else {
                    break;
                }
            }
            return sub.substring(0, end);
        }
        return trimmed;
    }

    public synchronized void loadFromPreferences(SharedPreferences prefs) {
        if (prefs == null) return;
        try {
            this.apiKey = sanitizeApiKey(prefs.getString(KEY_API_KEY, this.apiKey != null ? this.apiKey : ""));
            this.endpoint = prefs.getString(KEY_ENDPOINT, DEFAULT_ENDPOINT);
            this.model = prefs.getString(KEY_MODEL, DEFAULT_MODEL);
            this.language = prefs.getString(KEY_LANGUAGE, DEFAULT_LANGUAGE);
            this.prompt = prefs.getString(KEY_PROMPT, DEFAULT_PROMPT);
            this.silenceTimeoutMs = prefs.getInt(KEY_SILENCE_TIMEOUT_MS, DEFAULT_SILENCE_TIMEOUT_MS);
            this.streamingEnabled = prefs.getBoolean(KEY_STREAMING_ENABLED, DEFAULT_STREAMING_ENABLED);
            this.partialIntervalMs = prefs.getInt(KEY_PARTIAL_INTERVAL_MS, DEFAULT_PARTIAL_INTERVAL_MS);
            this.autoLanguage = prefs.getBoolean(KEY_AUTO_LANGUAGE, DEFAULT_AUTO_LANGUAGE);
            this.soundEffectsEnabled = prefs.getBoolean(KEY_SOUND_EFFECTS_ENABLED, DEFAULT_SOUND_EFFECTS_ENABLED);
            this.autoStopTimeoutMs = prefs.getInt(KEY_AUTO_STOP_TIMEOUT_MS, DEFAULT_AUTO_STOP_TIMEOUT_MS);
        } catch (Throwable t) {
            Log.w(TAG, "Failed reading preferences: " + t.getMessage());
        }
    }

    public synchronized void load(Context context) {
        if (context != null) {
            SharedPreferences prefs = getSharedPreferences(context);
            if (prefs != null) {
                loadFromPreferences(prefs);
            }
        }
    }

    public synchronized void saveToSharedPreferences(SharedPreferences prefs) {
        if (prefs == null) return;
        prefs.edit()
                .putString(KEY_API_KEY, apiKey)
                .putString(KEY_ENDPOINT, endpoint)
                .putString(KEY_MODEL, model)
                .putString(KEY_LANGUAGE, language)
                .putString(KEY_PROMPT, prompt)
                .putInt(KEY_SILENCE_TIMEOUT_MS, silenceTimeoutMs)
                .putBoolean(KEY_STREAMING_ENABLED, streamingEnabled)
                .putInt(KEY_PARTIAL_INTERVAL_MS, partialIntervalMs)
                .putBoolean(KEY_AUTO_LANGUAGE, autoLanguage)
                .putBoolean(KEY_SOUND_EFFECTS_ENABLED, soundEffectsEnabled)
                .putInt(KEY_AUTO_STOP_TIMEOUT_MS, autoStopTimeoutMs)
                .apply();
    }

    public synchronized void save(Context context) {
        if (context == null) return;
        try {
            SharedPreferences prefs = getSharedPreferences(context);
            if (prefs != null) {
                saveToSharedPreferences(prefs);
                Log.i(TAG, "Saved local private preferences to " + PREFS_NAME);
            }
        } catch (Throwable t) {
            Log.e(TAG, "Error saving local SharedPreferences", t);
        }

        try {
            io.github.libxposed.service.XposedService svc = com.nitro.swiftkeywhisper.App.getService();
            if (svc != null) {
                SharedPreferences remotePrefs = svc.getRemotePreferences(PREFS_NAME);
                if (remotePrefs != null) {
                    saveToSharedPreferences(remotePrefs);
                    Log.i(TAG, "Saved remote preferences to LSPosed daemon: " + PREFS_NAME);
                }
            }
        } catch (Throwable t) {
            Log.w(TAG, "RemotePreferences not available or not bound: " + t.getMessage());
        }
    }

    public synchronized void appendContextText(String text) {
        if (text == null || text.trim().isEmpty()) return;
        String[] words = text.trim().split("\\s+");
        for (String w : words) {
            recentWords.add(w);
        }
        while (recentWords.size() > MAX_CONTEXT_WORDS) {
            recentWords.removeFirst();
        }
    }

    public synchronized String getChainedPrompt() {
        if (recentWords.isEmpty()) {
            return prompt;
        }
        StringBuilder sb = new StringBuilder();
        if (prompt != null && !prompt.trim().isEmpty()) {
            sb.append(prompt.trim()).append(" ");
        }
        for (String w : recentWords) {
            sb.append(w).append(" ");
        }
        return sb.toString().trim();
    }

    public synchronized void clearContext() {
        recentWords.clear();
    }

    // Getters and Setters
    public String getApiKey() { return apiKey; }
    public void setApiKey(String apiKey) { this.apiKey = sanitizeApiKey(apiKey); }

    public String getEndpoint() { return endpoint; }
    public void setEndpoint(String endpoint) { this.endpoint = endpoint; }

    public String getModel() { return model; }
    public void setModel(String model) { this.model = model; }

    public String getLanguage() { return language; }
    public void setLanguage(String language) { this.language = language; }

    public String getPrompt() { return prompt; }
    public void setPrompt(String prompt) { this.prompt = prompt; }

    public int getSilenceTimeoutMs() { return silenceTimeoutMs; }
    public void setSilenceTimeoutMs(int silenceTimeoutMs) { this.silenceTimeoutMs = silenceTimeoutMs; }

    public boolean isStreamingEnabled() { return streamingEnabled; }
    public void setStreamingEnabled(boolean streamingEnabled) { this.streamingEnabled = streamingEnabled; }

    public int getPartialIntervalMs() { return partialIntervalMs; }
    public void setPartialIntervalMs(int partialIntervalMs) { this.partialIntervalMs = partialIntervalMs; }

    public boolean isAutoLanguage() { return autoLanguage; }
    public void setAutoLanguage(boolean autoLanguage) { this.autoLanguage = autoLanguage; }

    public boolean isSoundEffectsEnabled() { return soundEffectsEnabled; }
    public void setSoundEffectsEnabled(boolean soundEffectsEnabled) { this.soundEffectsEnabled = soundEffectsEnabled; }

    public int getAutoStopTimeoutMs() { return autoStopTimeoutMs; }
    public void setAutoStopTimeoutMs(int autoStopTimeoutMs) { this.autoStopTimeoutMs = autoStopTimeoutMs; }
}
