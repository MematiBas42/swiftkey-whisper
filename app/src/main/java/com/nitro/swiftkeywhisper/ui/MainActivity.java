package com.nitro.swiftkeywhisper.ui;

import android.Manifest;
import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.Context;
import android.content.pm.PackageManager;
import android.media.AudioFormat;
import android.media.AudioRecord;
import android.media.MediaRecorder;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.view.View;
import android.widget.ArrayAdapter;
import android.widget.AutoCompleteTextView;
import android.widget.ImageButton;
import android.widget.TextView;
import android.widget.Toast;

import androidx.annotation.NonNull;
import androidx.appcompat.app.AlertDialog;
import androidx.appcompat.app.AppCompatActivity;
import androidx.core.app.ActivityCompat;
import androidx.core.content.ContextCompat;
import androidx.core.graphics.Insets;
import androidx.core.view.ViewCompat;
import androidx.core.view.WindowInsetsCompat;


import com.google.android.material.button.MaterialButton;
import com.google.android.material.chip.Chip;
import com.google.android.material.chip.ChipGroup;
import com.google.android.material.progressindicator.LinearProgressIndicator;
import com.google.android.material.slider.Slider;
import com.google.android.material.switchmaterial.SwitchMaterial;
import com.google.android.material.textfield.TextInputEditText;
import com.nitro.swiftkeywhisper.R;
import com.nitro.swiftkeywhisper.audio.WavWriter;
import com.nitro.swiftkeywhisper.config.ConfigManager;
import com.nitro.swiftkeywhisper.network.WhisperClient;

import java.io.ByteArrayOutputStream;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

public class MainActivity extends AppCompatActivity {
    private static final int PERMISSION_REQ_CODE = 1001;

    // Standard Prompts
    private static final String PROMPT_TECH =
            "Bu bir Türkçe ses kaydıdır. Cümle içinde deploy, commit, PR, pull request, merge, bug, build, pipeline, endpoint, refactor, backend, frontend gibi teknik İngilizce terimler geçebilir; Türkçe eklerle doğru ve hatasız yazılmalıdır.";

    private static final String PROMPT_DAILY =
            "Bu bir Türkçe konuşma ses kaydıdır. Noktalama işaretlerine, büyük ve küçük harf kurallarına ve günlük konuşma dilinin akıcılığına dikkat edilerek yazıya dökülmelidir.";

    private static final String PROMPT_BILINGUAL =
            "Turkish and English mixed speech transcription. Maintain code-switching accuracy, technical terminology, and proper capitalization in both Turkish and English seamlessly.";

    // Hooked by LSPosed when module is active
    public static boolean isModuleActive() {
        return false;
    }

    // Header & Status
    private TextView tvModuleStatus;

    // Model & Engine
    private TextInputEditText etApiKey;
    private AutoCompleteTextView autoCompleteModel;
    private MaterialButton btnFetchModels;
    private TextInputEditText etEndpoint;

    // Pipeline
    private TextView tvSilenceLabel;
    private Slider sliderSilence;
    private SwitchMaterial swStreaming;
    private SwitchMaterial swAutoLanguage;
    private SwitchMaterial swDirectInjection;
    private TextInputEditText etLanguage;

    // Context & Vocabulary
    private ChipGroup chipGroupPrompts;
    private Chip chipPromptTech;
    private Chip chipPromptDaily;
    private Chip chipPromptBilingual;
    private TextInputEditText etPrompt;

    // Live Test & Visualizer
    private ImageButton btnMicTest;
    private LinearProgressIndicator pbAudioVisualizer;
    private TextView tvTestStatus;
    private TextView tvLatencyBadge;
    private ImageButton btnCopyResult;
    private TextView tvTestResult;

    // Save Action
    private MaterialButton btnSave;

    private ConfigManager config;
    private final Handler mainHandler = new Handler(Looper.getMainLooper());
    private ArrayAdapter<String> modelAdapter;
    private final List<String> availableModels = new ArrayList<>(Arrays.asList(
            "whisper-large-v3",
            "whisper-large-v3-turbo"
    ));

    // Test recording state
    private volatile boolean isTestRecording = false;
    private AudioRecord testAudioRecord = null;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);

        // Handle Android 15/16 status bar insets properly
        ViewCompat.setOnApplyWindowInsetsListener(findViewById(R.id.rootLayout), (v, insets) -> {
            Insets systemBars = insets.getInsets(WindowInsetsCompat.Type.systemBars());
            v.setPadding(systemBars.left, systemBars.top, systemBars.right, 0);
            return insets;
        });

        config = ConfigManager.getInstance(this);

        initViews();
        setupModelDropdown();
        setupListeners();
        loadUiFromConfig();
        updateModuleStatusBadge();
        checkPermissions();
    }

    private void initViews() {
        tvModuleStatus = findViewById(R.id.tvModuleStatus);

        etApiKey = findViewById(R.id.etApiKey);
        autoCompleteModel = findViewById(R.id.autoCompleteModel);
        btnFetchModels = findViewById(R.id.btnFetchModels);
        etEndpoint = findViewById(R.id.etEndpoint);

        tvSilenceLabel = findViewById(R.id.tvSilenceLabel);
        sliderSilence = findViewById(R.id.sliderSilence);
        swStreaming = findViewById(R.id.swStreaming);
        swAutoLanguage = findViewById(R.id.swAutoLanguage);
        swDirectInjection = findViewById(R.id.swDirectInjection);
        etLanguage = findViewById(R.id.etLanguage);

        chipGroupPrompts = findViewById(R.id.chipGroupPrompts);
        chipPromptTech = findViewById(R.id.chipPromptTech);
        chipPromptDaily = findViewById(R.id.chipPromptDaily);
        chipPromptBilingual = findViewById(R.id.chipPromptBilingual);
        etPrompt = findViewById(R.id.etPrompt);

        btnMicTest = findViewById(R.id.btnMicTest);
        pbAudioVisualizer = findViewById(R.id.pbAudioVisualizer);
        tvTestStatus = findViewById(R.id.tvTestStatus);
        tvLatencyBadge = findViewById(R.id.tvLatencyBadge);
        btnCopyResult = findViewById(R.id.btnCopyResult);
        tvTestResult = findViewById(R.id.tvTestResult);

        btnSave = findViewById(R.id.btnSave);
    }

    private void setupModelDropdown() {
        modelAdapter = new ArrayAdapter<>(this, android.R.layout.simple_dropdown_item_1line, availableModels);
        autoCompleteModel.setAdapter(modelAdapter);
    }

    private void setupListeners() {
        // Module Status click
        tvModuleStatus.setOnClickListener(v -> showModuleStatusDialog());


        // Fetch Live Models from Groq
        btnFetchModels.setOnClickListener(v -> fetchLiveModels());

        // Silence Slider
        sliderSilence.addOnChangeListener((slider, value, fromUser) -> {
            int ms = (int) value;
            String profile = ms <= 600 ? "(Ultra Hızlı)" : ms <= 900 ? "(Dengeli / Önerilen)" : "(Geniş Cümle)";
            tvSilenceLabel.setText("Sessizlik Eşiği: " + ms + " ms " + profile);
        });

        // Enable vertical scrolling inside multiline prompt within NestedScrollView
        etPrompt.setOnTouchListener((v, event) -> {
            if (v.getId() == R.id.etPrompt) {
                v.getParent().requestDisallowInterceptTouchEvent(true);
                if ((event.getAction() & android.view.MotionEvent.ACTION_MASK) == android.view.MotionEvent.ACTION_UP) {
                    v.getParent().requestDisallowInterceptTouchEvent(false);
                }
            }
            return false;
        });

        // Prompt Preset Chips
        chipPromptTech.setOnClickListener(v -> etPrompt.setText(PROMPT_TECH));
        chipPromptDaily.setOnClickListener(v -> etPrompt.setText(PROMPT_DAILY));
        chipPromptBilingual.setOnClickListener(v -> etPrompt.setText(PROMPT_BILINGUAL));

        // Interactive Mic Test Button
        btnMicTest.setOnClickListener(v -> toggleTestRecording());

        // Copy Result Button
        btnCopyResult.setOnClickListener(v -> copyResultToClipboard());

        // Save Button
        btnSave.setOnClickListener(v -> saveConfig());
    }

    private void updateModuleStatusBadge() {
        if (isModuleActive()) {
            tvModuleStatus.setText("⚡ LSPosed: AKTİF");
            tvModuleStatus.setBackgroundResource(R.drawable.bg_badge_active);
            tvModuleStatus.setTextColor(ContextCompat.getColor(this, R.color.cyber_green));
        } else {
            tvModuleStatus.setText("⚠️ LSPosed: ETKİN DEĞİL");
            tvModuleStatus.setBackgroundResource(R.drawable.bg_badge_inactive);
            tvModuleStatus.setTextColor(ContextCompat.getColor(this, R.color.cyber_amber));
        }
    }

    private void showModuleStatusDialog() {
        String message = isModuleActive()
                ? "LSPosed modülü başarıyla devrede!\n\nSwiftKey klavyenizdeki mikrofon butonu doğrudan Groq Whisper motoruna yönlendirilmektedir."
                : "LSPosed modülü henüz etkinleştirilmedi veya SwiftKey kapsamı seçilmedi.\n\nLütfen LSPosed uygulamasını açın, 'SwiftKey Whisper' modülünü etkinleştirin ve 'Microsoft SwiftKey Klavye'yi kapsam (scope) olarak işaretleyin.";

        new AlertDialog.Builder(this)
                .setTitle("LSPosed Modül Durumu")
                .setMessage(message)
                .setPositiveButton("Tamam", null)
                .show();
    }

    private void fetchLiveModels() {
        String key = etApiKey.getText() != null ? etApiKey.getText().toString().trim() : "";
        if (key.isEmpty()) {
            Toast.makeText(this, "Lütfen önce geçerli bir Groq API Anahtarı girin.", Toast.LENGTH_SHORT).show();
            return;
        }

        btnFetchModels.setEnabled(false);
        btnFetchModels.setText("Alınıyor...");

        WhisperClient.fetchAvailableModels(key, new WhisperClient.ModelsCallback() {
            @Override
            public void onSuccess(List<String> models) {
                mainHandler.post(() -> {
                    btnFetchModels.setEnabled(true);
                    btnFetchModels.setText("Modeller");

                    availableModels.clear();
                    availableModels.addAll(models);
                    modelAdapter.notifyDataSetChanged();

                    if (!models.isEmpty()) {
                        autoCompleteModel.setText(models.get(0), false);
                    }
                    Toast.makeText(MainActivity.this, models.size() + " adet Whisper modeli başarıyla getirildi!", Toast.LENGTH_SHORT).show();
                });
            }

            @Override
            public void onError(String errorMessage) {
                mainHandler.post(() -> {
                    btnFetchModels.setEnabled(true);
                    btnFetchModels.setText("Modeller");
                    Toast.makeText(MainActivity.this, "Hata: " + errorMessage, Toast.LENGTH_LONG).show();
                });
            }
        });
    }

    private void loadUiFromConfig() {
        config.load(this);

        etApiKey.setText(config.getApiKey());
        autoCompleteModel.setText(config.getModel(), false);
        etEndpoint.setText(config.getEndpoint());
        etLanguage.setText(config.getLanguage());
        etPrompt.setText(config.getPrompt());

        int silenceMs = config.getSilenceTimeoutMs();
        sliderSilence.setValue(Math.max(300, Math.min(3000, silenceMs)));
        String profile = silenceMs <= 600 ? "(Ultra Hızlı)" : silenceMs <= 900 ? "(Dengeli / Önerilen)" : "(Geniş Cümle)";
        tvSilenceLabel.setText("Sessizlik Eşiği: " + silenceMs + " ms " + profile);

        swStreaming.setChecked(config.isStreamingEnabled());
        swAutoLanguage.setChecked(config.isAutoLanguage());
        swDirectInjection.setChecked(config.isDirectInjection());


    }

    private void saveConfig() {
        config.setApiKey(etApiKey.getText() != null ? etApiKey.getText().toString().trim() : "");
        config.setModel(autoCompleteModel.getText() != null ? autoCompleteModel.getText().toString().trim() : ConfigManager.DEFAULT_MODEL);
        config.setEndpoint(etEndpoint.getText() != null ? etEndpoint.getText().toString().trim() : ConfigManager.DEFAULT_ENDPOINT);
        config.setLanguage(etLanguage.getText() != null ? etLanguage.getText().toString().trim() : ConfigManager.DEFAULT_LANGUAGE);
        config.setPrompt(etPrompt.getText() != null ? etPrompt.getText().toString().trim() : ConfigManager.DEFAULT_PROMPT);
        config.setSilenceTimeoutMs((int) sliderSilence.getValue());
        config.setStreamingEnabled(swStreaming.isChecked());
        config.setAutoLanguage(swAutoLanguage.isChecked());
        config.setDirectInjection(swDirectInjection.isChecked());

        config.save(this);
        Toast.makeText(this, "Tüm ayarlar kaydedildi ve güncellendi!", Toast.LENGTH_SHORT).show();
    }

    private void toggleTestRecording() {
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
            checkPermissions();
            return;
        }

        if (isTestRecording) {
            // Stop recording early
            isTestRecording = false;
            btnMicTest.setBackgroundResource(R.drawable.bg_mic_idle);
            btnMicTest.setImageResource(R.drawable.ic_mic);
            tvTestStatus.setText("Durum: Analiz ediliyor...");
        } else {
            startTestRecording();
        }
    }

    private void startTestRecording() {
        saveConfig();

        String apiKey = config.getApiKey();
        if (apiKey == null || apiKey.trim().isEmpty()) {
            tvTestResult.setText("> HATA: Lütfen geçerli bir Groq API Anahtarı girin.");
            return;
        }

        isTestRecording = true;
        btnMicTest.setBackgroundResource(R.drawable.bg_mic_recording);
        btnMicTest.setImageResource(R.drawable.ic_stop);
        tvTestStatus.setText("Dinleniyor... (Konuşun, bitirmek için tekrar basın)");
        tvLatencyBadge.setVisibility(View.GONE);
        tvTestResult.setText("> Ses kaydı dinleniyor...");
        pbAudioVisualizer.setProgress(0);

        new Thread(() -> {
            int sampleRate = 16000;
            int minBuf = AudioRecord.getMinBufferSize(sampleRate, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT);
            int bufferSize = Math.max(minBuf, 4096);

            try {
                testAudioRecord = new AudioRecord(
                        MediaRecorder.AudioSource.MIC,
                        sampleRate,
                        AudioFormat.CHANNEL_IN_MONO,
                        AudioFormat.ENCODING_PCM_16BIT,
                        bufferSize
                );

                if (testAudioRecord.getState() != AudioRecord.STATE_INITIALIZED) {
                    throw new IllegalStateException("AudioRecord başlatılamadı.");
                }

                testAudioRecord.startRecording();
                ByteArrayOutputStream pcmStream = new ByteArrayOutputStream();
                byte[] buf = new byte[1024];
                long startTime = System.currentTimeMillis();

                // Record while flag is true, maximum 6 seconds
                while (isTestRecording && (System.currentTimeMillis() - startTime < 6000)) {
                    int read = testAudioRecord.read(buf, 0, buf.length);
                    if (read > 0) {
                        pcmStream.write(buf, 0, read);

                        // Calculate RMS energy for visualizer
                        long sum = 0;
                        for (int i = 0; i < read; i += 2) {
                            short sample = (short) ((buf[i] & 0xFF) | (buf[i + 1] << 8));
                            sum += (long) sample * sample;
                        }
                        double rms = Math.sqrt((double) sum / (read / 2.0));
                        int progress = (int) Math.min(100, (rms / 32768.0) * 400);
                        mainHandler.post(() -> pbAudioVisualizer.setProgress(progress));
                    }
                }

                // Cleanup AudioRecord
                isTestRecording = false;
                try {
                    testAudioRecord.stop();
                    testAudioRecord.release();
                    testAudioRecord = null;
                } catch (Exception ignored) {}

                mainHandler.post(() -> {
                    btnMicTest.setBackgroundResource(R.drawable.bg_mic_idle);
                    btnMicTest.setImageResource(R.drawable.ic_mic);
                    pbAudioVisualizer.setProgress(0);
                    tvTestStatus.setText("Durum: Groq LPU'ya iletiliyor...");
                });

                byte[] pcmData = pcmStream.toByteArray();
                if (pcmData.length < 4800) {
                    mainHandler.post(() -> {
                        tvTestStatus.setText("Durum: Ses algılanmadı.");
                        tvTestResult.setText("> Ses çok kısa veya mikrofon algılanmadı.");
                    });
                    return;
                }

                byte[] wavBytes = WavWriter.pcmToWav(pcmData, sampleRate, 1, 16);
                long reqStart = System.currentTimeMillis();

                WhisperClient.transcribe(wavBytes, config, new WhisperClient.TranscriptionCallback() {
                    @Override
                    public void onSuccess(String text) {
                        long latency = System.currentTimeMillis() - reqStart;
                        mainHandler.post(() -> {
                            tvTestStatus.setText("Durum: Tamamlandı");
                            tvLatencyBadge.setText("⚡ " + latency + " ms");
                            tvLatencyBadge.setVisibility(View.VISIBLE);
                            tvTestResult.setText("> " + (text.isEmpty() ? "(Boş metin)" : text));
                        });
                    }

                    @Override
                    public void onError(String errorMessage) {
                        mainHandler.post(() -> {
                            tvTestStatus.setText("Durum: Hata");
                            tvLatencyBadge.setVisibility(View.GONE);
                            tvTestResult.setText("> HATA:\n" + errorMessage);
                        });
                    }
                });

            } catch (Throwable t) {
                isTestRecording = false;
                if (testAudioRecord != null) {
                    try { testAudioRecord.release(); } catch (Exception ignored) {}
                    testAudioRecord = null;
                }
                mainHandler.post(() -> {
                    btnMicTest.setBackgroundResource(R.drawable.bg_mic_idle);
                    btnMicTest.setImageResource(R.drawable.ic_mic);
                    pbAudioVisualizer.setProgress(0);
                    tvTestStatus.setText("Durum: Hata oluştu");
                    tvTestResult.setText("> Mikrofon Hatası: " + t.getMessage());
                });
            }
        }).start();
    }

    private void copyResultToClipboard() {
        CharSequence text = tvTestResult.getText();
        if (text != null && text.length() > 2) {
            String clean = text.toString().replaceFirst("^>\\s*", "").trim();
            ClipboardManager cm = (ClipboardManager) getSystemService(Context.CLIPBOARD_SERVICE);
            if (cm != null) {
                cm.setPrimaryClip(ClipData.newPlainText("Whisper Transkripsiyon", clean));
                Toast.makeText(this, "Metin panoya kopyalandı!", Toast.LENGTH_SHORT).show();
            }
        }
    }

    private void checkPermissions() {
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO)
                != PackageManager.PERMISSION_GRANTED) {
            ActivityCompat.requestPermissions(this,
                    new String[]{Manifest.permission.RECORD_AUDIO},
                    PERMISSION_REQ_CODE);
        }
    }

    @Override
    public void onRequestPermissionsResult(int requestCode, @NonNull String[] permissions, @NonNull int[] grantResults) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults);
        if (requestCode == PERMISSION_REQ_CODE) {
            if (grantResults.length > 0 && grantResults[0] == PackageManager.PERMISSION_GRANTED) {
                Toast.makeText(this, "Mikrofon izni onaylandı.", Toast.LENGTH_SHORT).show();
            } else {
                Toast.makeText(this, "Ses testi için mikrofon izni gereklidir.", Toast.LENGTH_LONG).show();
            }
        }
    }

    @Override
    protected void onPause() {
        super.onPause();
        if (isTestRecording) {
            isTestRecording = false;
            if (testAudioRecord != null) {
                try { testAudioRecord.release(); } catch (Exception ignored) {}
                testAudioRecord = null;
            }
        }
    }
}
