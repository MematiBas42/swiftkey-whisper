package com.nitro.swiftkeywhisper.config;

import android.content.Context;
import android.content.SharedPreferences;
import android.util.Log;

import org.json.JSONObject;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.util.LinkedList;
import java.util.Locale;

public class ConfigManager {
    private static final String TAG = "SwiftKeyWhisperConfig";
    public static final String PREFS_NAME = "swiftkey_whisper_prefs";
    public static final String CONFIG_FILE_PATH = "/data/local/tmp/swiftkey_whisper_config.json";

    public static final String KEY_API_KEY = "api_key";
    public static final String KEY_ENDPOINT = "endpoint";
    public static final String KEY_MODEL = "model";
    public static final String KEY_LANGUAGE = "language";
    public static final String KEY_PROMPT = "prompt";
    public static final String KEY_SILENCE_TIMEOUT_MS = "silence_timeout_ms";
    public static final String KEY_STREAMING_ENABLED = "streaming_enabled";
    public static final String KEY_PARTIAL_INTERVAL_MS = "partial_interval_ms";
    public static final String KEY_AUTO_LANGUAGE = "auto_language";
    public static final String KEY_DIRECT_INJECTION = "direct_injection";
    public static final String KEY_SOUND_EFFECTS_ENABLED = "sound_effects_enabled";

    public static final String DEFAULT_ENDPOINT = "https://api.groq.com/openai/v1/audio/transcriptions";
    public static final String DEFAULT_MODEL = "whisper-large-v3";
    public static final int DEFAULT_SILENCE_TIMEOUT_MS = 750; // Native-fast response (0.75s)
    public static final boolean DEFAULT_STREAMING_ENABLED = true;
    public static final int DEFAULT_PARTIAL_INTERVAL_MS = 1000;
    public static final boolean DEFAULT_AUTO_LANGUAGE = true;
    public static final boolean DEFAULT_DIRECT_INJECTION = false; // SwiftKey native Fluency Engine handles auto-caps, auto-spacing and model learning
    public static final boolean DEFAULT_SOUND_EFFECTS_ENABLED = true;

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
    public static final String DEFAULT_PROMPT = getDefaultPromptForLanguage(DEFAULT_LANGUAGE);

    private String apiKey = "";
    private String endpoint = DEFAULT_ENDPOINT;
    private String model = DEFAULT_MODEL;
    private String language = getDefaultSystemLanguage();
    private String prompt = getDefaultPromptForLanguage(language);
    private int silenceTimeoutMs = DEFAULT_SILENCE_TIMEOUT_MS;
    private boolean streamingEnabled = DEFAULT_STREAMING_ENABLED;
    private int partialIntervalMs = DEFAULT_PARTIAL_INTERVAL_MS;
    private boolean autoLanguage = DEFAULT_AUTO_LANGUAGE;
    private boolean directInjection = DEFAULT_DIRECT_INJECTION;
    private boolean soundEffectsEnabled = DEFAULT_SOUND_EFFECTS_ENABLED;

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

    public void load(Context context) {
        if (context != null) {
            try {
                SharedPreferences prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE);
                this.apiKey = prefs.getString(KEY_API_KEY, "");
                this.endpoint = prefs.getString(KEY_ENDPOINT, DEFAULT_ENDPOINT);
                this.model = prefs.getString(KEY_MODEL, DEFAULT_MODEL);
                this.language = prefs.getString(KEY_LANGUAGE, DEFAULT_LANGUAGE);
                this.prompt = prefs.getString(KEY_PROMPT, DEFAULT_PROMPT);
                this.silenceTimeoutMs = prefs.getInt(KEY_SILENCE_TIMEOUT_MS, DEFAULT_SILENCE_TIMEOUT_MS);
                this.streamingEnabled = prefs.getBoolean(KEY_STREAMING_ENABLED, DEFAULT_STREAMING_ENABLED);
                this.partialIntervalMs = prefs.getInt(KEY_PARTIAL_INTERVAL_MS, DEFAULT_PARTIAL_INTERVAL_MS);
                this.autoLanguage = prefs.getBoolean(KEY_AUTO_LANGUAGE, DEFAULT_AUTO_LANGUAGE);
                this.directInjection = prefs.getBoolean(KEY_DIRECT_INJECTION, DEFAULT_DIRECT_INJECTION);
                this.soundEffectsEnabled = prefs.getBoolean(KEY_SOUND_EFFECTS_ENABLED, DEFAULT_SOUND_EFFECTS_ENABLED);

                if (!this.apiKey.isEmpty()) {
                    return;
                }
            } catch (Throwable t) {
                Log.w(TAG, "Failed reading from SharedPreferences: " + t.getMessage());
            }
        }

        loadFileConfig();
    }

    public void loadFileConfig() {
        try {
            File file = new File(CONFIG_FILE_PATH);
            if (file.exists() && file.canRead()) {
                FileInputStream fis = new FileInputStream(file);
                byte[] data = new byte[(int) file.length()];
                fis.read(data);
                fis.close();

                JSONObject json = new JSONObject(new String(data, "UTF-8"));
                this.apiKey = json.optString(KEY_API_KEY, this.apiKey);
                this.endpoint = json.optString(KEY_ENDPOINT, this.endpoint);
                this.model = json.optString(KEY_MODEL, this.model);
                this.language = json.optString(KEY_LANGUAGE, this.language);
                this.prompt = json.optString(KEY_PROMPT, this.prompt);
                this.silenceTimeoutMs = json.optInt(KEY_SILENCE_TIMEOUT_MS, this.silenceTimeoutMs);
                this.streamingEnabled = json.optBoolean(KEY_STREAMING_ENABLED, this.streamingEnabled);
                this.partialIntervalMs = json.optInt(KEY_PARTIAL_INTERVAL_MS, this.partialIntervalMs);
                this.autoLanguage = json.optBoolean(KEY_AUTO_LANGUAGE, this.autoLanguage);
                this.directInjection = json.optBoolean(KEY_DIRECT_INJECTION, this.directInjection);
                this.soundEffectsEnabled = json.optBoolean(KEY_SOUND_EFFECTS_ENABLED, this.soundEffectsEnabled);
                Log.i(TAG, "Loaded config from " + CONFIG_FILE_PATH);
            }
        } catch (Throwable t) {
            Log.w(TAG, "Failed loading file config: " + t.getMessage());
        }
    }

    public void save(Context context) {
        if (context != null) {
            try {
                SharedPreferences prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE);
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
                        .putBoolean(KEY_DIRECT_INJECTION, directInjection)
                        .putBoolean(KEY_SOUND_EFFECTS_ENABLED, soundEffectsEnabled)
                        .apply();
            } catch (Throwable t) {
                Log.e(TAG, "Error saving SharedPreferences", t);
            }
        }

        try {
            JSONObject json = new JSONObject();
            json.put(KEY_API_KEY, apiKey);
            json.put(KEY_ENDPOINT, endpoint);
            json.put(KEY_MODEL, model);
            json.put(KEY_LANGUAGE, language);
            json.put(KEY_PROMPT, prompt);
            json.put(KEY_SILENCE_TIMEOUT_MS, silenceTimeoutMs);
            json.put(KEY_STREAMING_ENABLED, streamingEnabled);
            json.put(KEY_PARTIAL_INTERVAL_MS, partialIntervalMs);
            json.put(KEY_AUTO_LANGUAGE, autoLanguage);
            json.put(KEY_DIRECT_INJECTION, directInjection);
            json.put(KEY_SOUND_EFFECTS_ENABLED, soundEffectsEnabled);

            File file = new File(CONFIG_FILE_PATH);
            FileOutputStream fos = new FileOutputStream(file);
            fos.write(json.toString(2).getBytes("UTF-8"));
            fos.close();
            file.setReadable(true, false);
            file.setWritable(true, false);
            Log.i(TAG, "Saved config to " + CONFIG_FILE_PATH);
        } catch (Throwable t) {
            Log.e(TAG, "Error saving file config", t);
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
    public void setApiKey(String apiKey) { this.apiKey = apiKey; }

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

    public boolean isDirectInjection() { return directInjection; }
    public void setDirectInjection(boolean directInjection) { this.directInjection = directInjection; }

    public boolean isSoundEffectsEnabled() { return soundEffectsEnabled; }
    public void setSoundEffectsEnabled(boolean soundEffectsEnabled) { this.soundEffectsEnabled = soundEffectsEnabled; }
}
