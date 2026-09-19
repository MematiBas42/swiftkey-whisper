package com.nitro.swiftkeywhisper.audio;

import android.content.Context;
import android.content.res.AssetFileDescriptor;
import android.media.AudioAttributes;
import android.media.AudioManager;
import android.media.SoundPool;
import android.util.Log;

import com.nitro.swiftkeywhisper.MainHook;
import com.nitro.swiftkeywhisper.config.ConfigManager;

import java.io.File;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;

public class EarconPlayer {
    private static final String TAG = "SwiftKeyWhisperEarcon";
    private static EarconPlayer instance;

    private SoundPool soundPool;
    private int soundOpenId = 0;
    private int soundSuccessId = 0;
    private int soundFailureId = 0;

    private volatile boolean isOpenLoaded = false;
    private volatile boolean isSuccessLoaded = false;
    private volatile boolean isFailureLoaded = false;

    private AudioManager audioManager;
    private Context appContext;

    public static synchronized EarconPlayer getInstance(Context context) {
        if (instance == null) {
            instance = new EarconPlayer();
        }
        if (context != null && instance.appContext == null) {
            instance.init(context.getApplicationContext() != null ? context.getApplicationContext() : context);
        }
        return instance;
    }

    public synchronized void init(Context context) {
        if (soundPool != null && appContext != null) {
            return;
        }
        this.appContext = context;
        this.audioManager = (AudioManager) context.getSystemService(Context.AUDIO_SERVICE);

        try {
            AudioAttributes attributes = new AudioAttributes.Builder()
                    .setUsage(AudioAttributes.USAGE_ASSISTANCE_SONIFICATION)
                    .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
                    .build();

            soundPool = new SoundPool.Builder()
                    .setMaxStreams(3)
                    .setAudioAttributes(attributes)
                    .build();

            soundPool.setOnLoadCompleteListener((pool, sampleId, status) -> {
                if (status == 0) {
                    if (sampleId == soundOpenId) isOpenLoaded = true;
                    else if (sampleId == soundSuccessId) isSuccessLoaded = true;
                    else if (sampleId == soundFailureId) isFailureLoaded = true;
                    Log.d(TAG, "Sound loaded successfully: sampleId=" + sampleId);
                } else {
                    Log.w(TAG, "Failed loading sound: sampleId=" + sampleId + ", status=" + status);
                }
            });

            loadSounds(context);

        } catch (Throwable t) {
            Log.e(TAG, "Error initializing EarconPlayer SoundPool: " + t.getMessage(), t);
        }
    }

    private void loadSounds(Context context) {
        // Attempt 1: Load from module package context assets
        Context moduleContext = null;
        try {
            moduleContext = context.createPackageContext("com.nitro.swiftkeywhisper", Context.CONTEXT_IGNORE_SECURITY);
        } catch (Throwable t) {
            Log.d(TAG, "Could not create package context for com.nitro.swiftkeywhisper directly: " + t.getMessage());
        }

        soundOpenId = loadSingleSound(context, moduleContext, "earcon_open.wav");
        soundSuccessId = loadSingleSound(context, moduleContext, "earcon_success.wav");
        soundFailureId = loadSingleSound(context, moduleContext, "earcon_failure.wav");
    }

    private int loadSingleSound(Context hostContext, Context moduleContext, String filename) {
        // 1. Check if already extracted and valid in host cache directory
        try {
            File cacheFile = new File(hostContext.getCacheDir(), filename);
            if (cacheFile.exists() && cacheFile.length() > 0) {
                int id = soundPool.load(cacheFile.getAbsolutePath(), 1);
                Log.d(TAG, "Loaded " + filename + " from existing cache: " + cacheFile.getAbsolutePath());
                return id;
            }
        } catch (Throwable ignored) {}

        // 2. Extract directly from module APK via MainHook.getModuleApkPath()
        String apkPath = MainHook.getModuleApkPath();
        if (apkPath == null) {
            // Search /data/app/ for module APK as fallback
            try {
                File dataApp = new File("/data/app");
                File[] dirs = dataApp.listFiles((dir, name) -> name.contains("com.nitro.swiftkeywhisper"));
                if (dirs != null && dirs.length > 0) {
                    File baseApk = new File(dirs[0], "base.apk");
                    if (baseApk.exists() && baseApk.canRead()) {
                        apkPath = baseApk.getAbsolutePath();
                    }
                }
            } catch (Throwable ignored) {}
        }

        if (apkPath != null) {
            try {
                File apkFile = new File(apkPath);
                if (apkFile.exists() && apkFile.canRead()) {
                    ZipFile zip = new ZipFile(apkFile);
                    ZipEntry entry = zip.getEntry("assets/earcons/" + filename);
                    if (entry != null) {
                        File cacheFile = new File(hostContext.getCacheDir(), filename);
                        InputStream is = zip.getInputStream(entry);
                        FileOutputStream fos = new FileOutputStream(cacheFile);
                        byte[] buf = new byte[4096];
                        int r;
                        while ((r = is.read(buf)) != -1) {
                            fos.write(buf, 0, r);
                        }
                        fos.close();
                        is.close();
                        zip.close();
                        cacheFile.setReadable(true, false);

                        int id = soundPool.load(cacheFile.getAbsolutePath(), 1);
                        Log.d(TAG, "Extracted & loaded " + filename + " from module APK (" + apkPath + ")");
                        return id;
                    }
                    zip.close();
                }
            } catch (Throwable t) {
                Log.w(TAG, "Failed extracting " + filename + " from APK: " + t.getMessage());
            }
        }

        // 3. Try AssetFileDescriptor from moduleContext
        if (moduleContext != null) {
            try {
                AssetFileDescriptor afd = moduleContext.getAssets().openFd("earcons/" + filename);
                int id = soundPool.load(afd, 1);
                Log.d(TAG, "Loaded " + filename + " via moduleContext AssetFileDescriptor");
                return id;
            } catch (Throwable ignored) {}
        }

        // 4. Try extracting from moduleContext or hostContext assets
        try {
            File cacheFile = new File(hostContext.getCacheDir(), filename);
            InputStream is = null;
            if (moduleContext != null) {
                try {
                    is = moduleContext.getAssets().open("earcons/" + filename);
                } catch (Throwable ignored) {}
            }
            if (is == null) {
                try {
                    is = hostContext.getAssets().open("earcons/" + filename);
                } catch (Throwable ignored) {}
            }

            if (is != null) {
                FileOutputStream fos = new FileOutputStream(cacheFile);
                byte[] buf = new byte[4096];
                int r;
                while ((r = is.read(buf)) != -1) {
                    fos.write(buf, 0, r);
                }
                fos.close();
                is.close();
                cacheFile.setReadable(true, false);

                int id = soundPool.load(cacheFile.getAbsolutePath(), 1);
                Log.d(TAG, "Loaded " + filename + " via extracted stream: " + cacheFile.getAbsolutePath());
                return id;
            }
        } catch (Throwable ignored) {}

        // 5. Fallback: check /data/local/tmp/
        try {
            File tmpFile = new File("/data/local/tmp/" + filename);
            if (tmpFile.exists() && tmpFile.canRead()) {
                int id = soundPool.load(tmpFile.getAbsolutePath(), 1);
                Log.d(TAG, "Loaded " + filename + " via fallback /data/local/tmp/");
                return id;
            }
        } catch (Throwable ignored) {}

        Log.w(TAG, "Could not find or load sound file: " + filename);
        return 0;
    }

    private long lastSuccessPlayedTime = 0;

    public void playOpen() {
        playSound(soundOpenId, isOpenLoaded, "Open/Start");
    }

    public synchronized void playSuccess() {
        long now = System.currentTimeMillis();
        if (now - lastSuccessPlayedTime < 600) {
            return;
        }
        lastSuccessPlayedTime = now;
        playSound(soundSuccessId, isSuccessLoaded, "Success/Stop");
    }

    public void playFailure() {
        playSound(soundFailureId, isFailureLoaded, "Failure/Cancel");
    }

    private void playSound(int sampleId, boolean isLoaded, String label) {
        if (soundPool == null || sampleId == 0) {
            return;
        }

        ConfigManager config = ConfigManager.getInstance(appContext);
        if (config != null && !config.isSoundEffectsEnabled()) {
            return;
        }

        // Respect ringer mode (silent / vibrate = quiet)
        if (audioManager != null) {
            int ringerMode = audioManager.getRingerMode();
            if (ringerMode == AudioManager.RINGER_MODE_SILENT || ringerMode == AudioManager.RINGER_MODE_VIBRATE) {
                Log.d(TAG, "Suppressed earcon " + label + " due to silent/vibrate ringer mode");
                return;
            }
        }

        try {
            float volume = 0.85f;
            if (config != null) {
                volume = Math.max(0.0f, Math.min(1.0f, config.getEarconVolume() / 100.0f));
            }
            if (volume <= 0.0f) {
                return;
            }
            soundPool.play(sampleId, volume, volume, 1, 0, 1.0f);
            Log.d(TAG, "Played earcon sound: " + label + " (vol: " + volume + ")");
        } catch (Throwable t) {
            Log.w(TAG, "Error playing earcon " + label + ": " + t.getMessage());
        }
    }
}
