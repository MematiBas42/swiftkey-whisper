package com.nitro.swiftkeywhisper;

import android.app.Application;
import android.app.Instrumentation;
import android.content.Context;
import android.content.SharedPreferences;
import android.util.Log;

import androidx.annotation.NonNull;

import com.nitro.swiftkeywhisper.audio.EarconPlayer;
import com.nitro.swiftkeywhisper.config.ConfigManager;
import com.nitro.swiftkeywhisper.hook.SpeechRecognizerHook;

import java.lang.reflect.Method;

import io.github.libxposed.api.XposedInterface;
import io.github.libxposed.api.XposedModule;

/**
 * Modern LibXposed module entry (API 101/102).
 * Declared in: src/main/resources/META-INF/xposed/java_init.list
 */
public class MainHook extends XposedModule {
    public static final String TAG = "SwiftKeyWhisper";
    public static volatile MainHook INSTANCE;

    public static String getModuleApkPath() {
        if (INSTANCE != null) {
            try {
                return INSTANCE.getModuleApplicationInfo().sourceDir;
            } catch (Throwable ignored) {}
        }
        return null;
    }

    private static final String TARGET_PKG_1 = "com.touchtype.swiftkey";
    private static final String TARGET_PKG_2 = "com.touchtype.swiftkey.beta";

    private static boolean isInitialized = false;

    @Override
    public void onModuleLoaded(@NonNull ModuleLoadedParam param) {
        log(Log.INFO, TAG, "Module loaded in: " + param.getProcessName() +
                " via " + getFrameworkName() + " (API " + getApiVersion() + ")");
    }

    @Override
    public void onPackageReady(@NonNull PackageReadyParam param) {
        if (!param.isFirstPackage()) return;

        String pkg = param.getPackageName();
        if (!TARGET_PKG_1.equals(pkg) && !TARGET_PKG_2.equals(pkg)) {
            return;
        }

        INSTANCE = this;
        log(Log.INFO, TAG, "Target package ready: " + pkg);

        try {
            // Hook Instrumentation.callApplicationOnCreate to reliably obtain Application Context
            Method callAppOnCreate = Instrumentation.class.getDeclaredMethod(
                    "callApplicationOnCreate", Application.class);

            hook(callAppOnCreate)
                    .setPriority(XposedInterface.PRIORITY_HIGHEST)
                    .setExceptionMode(XposedInterface.ExceptionMode.PROTECTIVE)
                    .intercept(chain -> {
                        Application app = (Application) chain.getArg(0);
                        if (app != null && !isInitialized) {
                            Context context = app.getApplicationContext();
                            if (context != null) {
                                initialize(param.getClassLoader(), context);
                            }
                        }
                        return chain.proceed();
                    });
        } catch (Throwable t) {
            log(Log.ERROR, TAG, "Failed hooking callApplicationOnCreate", t);
        }
    }

    private synchronized void initialize(ClassLoader classLoader, Context context) {
        if (isInitialized) {
            return;
        }
        isInitialized = true;

        log(Log.INFO, TAG, "Initializing SwiftKeyWhisper modern LibXposed hooks...");

        // 1. Initialize configuration via RemotePreferences
        ConfigManager config = ConfigManager.getInstance(context);
        try {
            SharedPreferences remotePrefs = getRemotePreferences(ConfigManager.PREFS_NAME);
            if (remotePrefs != null) {
                config.loadFromPreferences(remotePrefs);
                boolean hasKey = config.getApiKey() != null && !config.getApiKey().isEmpty();
                log(Log.INFO, TAG, "Loaded RemotePreferences (Has API key: " + hasKey + ")");

                remotePrefs.registerOnSharedPreferenceChangeListener((sp, key) -> {
                    log(Log.INFO, TAG, "RemotePreference key updated: " + key);
                    config.loadFromPreferences(sp);
                });
            } else {
                log(Log.WARN, TAG, "RemotePreferences not available, using default/cached config");
            }
        } catch (Throwable t) {
            log(Log.ERROR, TAG, "Error loading RemotePreferences: " + t.getMessage(), t);
        }

        // 2. Pre-warm EarconPlayer SoundPool
        try {
            EarconPlayer.getInstance(context);
            log(Log.INFO, TAG, "Pre-warmed EarconPlayer SoundPool");
        } catch (Throwable t) {
            log(Log.WARN, TAG, "Failed pre-warming EarconPlayer: " + t.getMessage());
        }

        // 3. Hook SpeechRecognizer
        SpeechRecognizerHook.initHook(this, classLoader, context);

        log(Log.INFO, TAG, "All hooks initialized successfully!");
    }
}
