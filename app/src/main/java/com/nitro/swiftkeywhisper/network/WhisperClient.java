package com.nitro.swiftkeywhisper.network;

import android.util.Log;

import com.nitro.swiftkeywhisper.config.ConfigManager;

import org.json.JSONObject;
import org.json.JSONArray;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;


import java.io.IOException;
import java.util.concurrent.TimeUnit;

import okhttp3.Call;
import okhttp3.Callback;
import okhttp3.ConnectionPool;
import okhttp3.MediaType;
import okhttp3.MultipartBody;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.RequestBody;
import okhttp3.Response;

public class WhisperClient {
    private static final String TAG = "SwiftKeyWhisperClient";
    public static final String TAG_PARTIAL = "TAG_PARTIAL";
    public static final String TAG_SEGMENT = "TAG_SEGMENT";

    public interface ModelsCallback {
        void onSuccess(List<String> models);
        void onError(String errorMessage);
    }

    public interface TranscriptionCallback {
        void onSuccess(String text);
        void onError(String errorMessage);
    }

    private static final OkHttpClient httpClient = new OkHttpClient.Builder()
            .connectionPool(new ConnectionPool(5, 5, TimeUnit.MINUTES))
            .connectTimeout(10, TimeUnit.SECONDS)
            .readTimeout(15, TimeUnit.SECONDS)
            .writeTimeout(15, TimeUnit.SECONDS)
            .retryOnConnectionFailure(true)
            .build();

    public static void cancelPartialRequests() {
        for (Call call : httpClient.dispatcher().queuedCalls()) {
            if (TAG_PARTIAL.equals(call.request().tag())) {
                call.cancel();
            }
        }
        for (Call call : httpClient.dispatcher().runningCalls()) {
            if (TAG_PARTIAL.equals(call.request().tag())) {
                call.cancel();
            }
        }
    }

    public static void cancelSegmentRequests() {
        for (Call call : httpClient.dispatcher().queuedCalls()) {
            if (TAG_SEGMENT.equals(call.request().tag())) {
                call.cancel();
            }
        }
        for (Call call : httpClient.dispatcher().runningCalls()) {
            if (TAG_SEGMENT.equals(call.request().tag())) {
                call.cancel();
            }
        }
    }

    public static void cancelAllRequests() {
        for (Call call : httpClient.dispatcher().queuedCalls()) {
            call.cancel();
        }
        for (Call call : httpClient.dispatcher().runningCalls()) {
            call.cancel();
        }
    }

    public static void transcribe(byte[] wavBytes, ConfigManager config, TranscriptionCallback callback) {
        transcribeInternal(wavBytes, config, null, TAG_SEGMENT, callback);
    }

    public static void transcribeSegment(byte[] wavBytes, ConfigManager config, String dynamicLang, TranscriptionCallback callback) {
        transcribeInternal(wavBytes, config, dynamicLang, TAG_SEGMENT, callback);
    }

    public static void transcribePartial(byte[] wavBytes, ConfigManager config, String dynamicLang, TranscriptionCallback callback) {
        transcribeInternal(wavBytes, config, dynamicLang, TAG_PARTIAL, callback);
    }

    private static void transcribeInternal(byte[] wavBytes, ConfigManager config, String dynamicLang, String requestTag, TranscriptionCallback callback) {
        String apiKey = ConfigManager.sanitizeApiKey(config != null ? config.getApiKey() : null);
        if (apiKey.isEmpty()) {
            callback.onError("API Key is not configured in SwiftKey Whisper app.");
            return;
        }

        MediaType mediaType = MediaType.parse("audio/wav");
        RequestBody fileBody = RequestBody.create(wavBytes, mediaType);

        MultipartBody.Builder bodyBuilder = new MultipartBody.Builder()
                .setType(MultipartBody.FORM)
                .addFormDataPart("file", "audio.wav", fileBody)
                .addFormDataPart("model", config.getModel())
                .addFormDataPart("response_format", "json")
                .addFormDataPart("temperature", "0");

        // Resolve language: dynamic intent language takes precedence if autoLanguage is enabled
        String language = null;
        if (config.isAutoLanguage() && dynamicLang != null && !dynamicLang.trim().isEmpty()) {
            language = extractIsoLanguage(dynamicLang);
        }
        if (language == null || language.isEmpty()) {
            language = config.getLanguage();
        }
        if (language != null && !language.trim().isEmpty()) {
            bodyBuilder.addFormDataPart("language", language.trim().toLowerCase());
        }

        // Chained prompt with technical vocabulary and context
        String prompt = config.getChainedPrompt();
        if (language != null && !language.toLowerCase().startsWith("tr") && prompt != null && prompt.contains("Türkçe ses kaydıdır")) {
            prompt = ConfigManager.getDefaultPromptForLanguage(language);
        }
        if (prompt != null && !prompt.trim().isEmpty()) {
            bodyBuilder.addFormDataPart("prompt", prompt.trim());
        }

        Request request = new Request.Builder()
                .url(config.getEndpoint())
                .tag(requestTag)
                .addHeader("Authorization", "Bearer " + apiKey.trim())
                .post(bodyBuilder.build())
                .build();

        Call call = httpClient.newCall(request);
        if (TAG_PARTIAL.equals(requestTag)) {
            call.timeout().timeout(3500, TimeUnit.MILLISECONDS);
        }

        long startTime = System.currentTimeMillis();
        call.enqueue(new Callback() {
            @Override
            public void onFailure(Call call, IOException e) {
                if (call.isCanceled()) {
                    Log.d(TAG, "Call cancelled, ignoring failure for " + requestTag);
                    return;
                }
                Log.e(TAG, "Transcription network failure (" + requestTag + ")", e);
                callback.onError("Network error: " + e.getMessage());
            }

            @Override
            public void onResponse(Call call, Response response) throws IOException {
                if (call.isCanceled()) {
                    Log.d(TAG, "Call cancelled, ignoring response for " + requestTag);
                    response.close();
                    return;
                }

                long duration = System.currentTimeMillis() - startTime;
                String responseBody = response.body() != null ? response.body().string() : "";

                if (call.isCanceled()) {
                    Log.d(TAG, "Call cancelled after body read, ignoring response for " + requestTag);
                    return;
                }

                if (!response.isSuccessful()) {
                    Log.e(TAG, "API error HTTP " + response.code() + ": " + responseBody);
                    if (call.isCanceled()) {
                        Log.d(TAG, "Call cancelled, ignoring error response for " + requestTag);
                        return;
                    }
                    String errorMsg = parseErrorMessage(responseBody, response.code());
                    callback.onError(errorMsg);
                    return;
                }

                try {
                    JSONObject json = new JSONObject(responseBody);
                    String text = json.optString("text", "").trim();
                    text = cleanHallucinations(text);

                    if (call.isCanceled()) {
                        Log.d(TAG, "Call cancelled after parsing, ignoring result for " + requestTag);
                        return;
                    }

                    Log.i(TAG, "Transcription (" + requestTag + ") success in " + duration + "ms: " + text);
                    callback.onSuccess(text);
                } catch (Exception e) {
                    if (call.isCanceled()) {
                        Log.d(TAG, "Call cancelled, ignoring parse exception for " + requestTag);
                        return;
                    }
                    Log.e(TAG, "JSON parse error: " + responseBody, e);
                    callback.onError("Failed parsing response: " + e.getMessage());
                }
            }
        });
    }

    private static String extractIsoLanguage(String tag) {
        if (tag == null) return null;
        String clean = tag.trim().replace('_', '-');
        if (clean.contains("-")) {
            return clean.split("-")[0].toLowerCase();
        }
        return clean.toLowerCase();
    }

    private static String parseErrorMessage(String responseBody, int code) {
        try {
            JSONObject json = new JSONObject(responseBody);
            if (json.has("error")) {
                JSONObject errorObj = json.optJSONObject("error");
                if (errorObj != null && errorObj.has("message")) {
                    return errorObj.getString("message");
                }
                return json.getString("error");
            }
        } catch (Exception ignored) {
        }
        return "HTTP " + code + " error";
    }

    public static String cleanHallucinations(String text) {
        if (text == null) return "";
        String cleaned = text.trim();

        // Common Whisper hallucinations in silent audio
        String[] hallucinations = {
                "Altyazı M.K.",
                "Altyazı",
                "İzlediğiniz için teşekkürler.",
                "İzlediğiniz için teşekkür ederiz.",
                "Abone olmayı unutmayın.",
                "Beğenmeyi ve abone olmayı unutmayın.",
                "Thank you for watching.",
                "Thank you.",
                "Thanks for watching.",
                "Subtitles by the Amara.org community",
                "Subtitles by",
                "Please subscribe"
        };

        for (String h : hallucinations) {
            if (cleaned.equalsIgnoreCase(h) || cleaned.equalsIgnoreCase(h + ".")) {
                return "";
            }
        }

        // Drop isolated repeated punctuation marks e.g. "...", "!"
        if (cleaned.matches("^[.\\-–—,!? ]+$")) {
            return "";
        }

        return cleaned;
    }
    public static void fetchAvailableModels(String apiKey, ModelsCallback callback) {
        String cleanKey = ConfigManager.sanitizeApiKey(apiKey);
        if (cleanKey.isEmpty()) {
            callback.onError("API Key is not configured.");
            return;
        }

        Request request = new Request.Builder()
                .url("https://api.groq.com/openai/v1/models")
                .addHeader("Authorization", "Bearer " + cleanKey)
                .addHeader("User-Agent", "Mozilla/5.0")
                .get()
                .build();

        httpClient.newCall(request).enqueue(new Callback() {
            @Override
            public void onFailure(Call call, IOException e) {
                callback.onError("Bağlantı hatası: " + e.getMessage());
            }

            @Override
            public void onResponse(Call call, Response response) throws IOException {
                String responseBody = response.body() != null ? response.body().string() : "";
                if (!response.isSuccessful()) {
                    callback.onError(parseErrorMessage(responseBody, response.code()));
                    return;
                }

                try {
                    JSONObject json = new JSONObject(responseBody);
                    JSONArray data = json.getJSONArray("data");
                    List<String> models = new ArrayList<>();
                    for (int i = 0; i < data.length(); i++) {
                        JSONObject item = data.getJSONObject(i);
                        String id = item.optString("id", "");
                        if (id.toLowerCase().contains("whisper")) {
                            models.add(id);
                        }
                    }

                    Collections.sort(models, (m1, m2) -> {
                        if (m1.equals(ConfigManager.DEFAULT_MODEL)) return -1;
                        if (m2.equals(ConfigManager.DEFAULT_MODEL)) return 1;
                        return m1.compareTo(m2);
                    });

                    callback.onSuccess(models);
                } catch (Exception e) {
                    callback.onError("Ayrıştırma hatası: " + e.getMessage());
                }
            }
        });
    }

}
