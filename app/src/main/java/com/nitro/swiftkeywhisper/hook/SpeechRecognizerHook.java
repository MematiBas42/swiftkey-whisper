package com.nitro.swiftkeywhisper.hook;

import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.speech.RecognitionListener;
import android.speech.SpeechRecognizer;
import android.util.Log;

import com.nitro.swiftkeywhisper.audio.AudioRecorderManager;
import com.nitro.swiftkeywhisper.config.ConfigManager;

import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.Set;

import de.robv.android.xposed.XC_MethodHook;
import de.robv.android.xposed.XposedBridge;
import de.robv.android.xposed.XposedHelpers;

public class SpeechRecognizerHook {
    private static final String TAG = "SwiftKeyWhisperHook";

    private static RecognitionListener activeListener;
    private static Context appContext;
    private static final Handler mainHandler = new Handler(Looper.getMainLooper());
    private static final Set<Class<?>> hookedClasses = new HashSet<>();

    public static void initHook(ClassLoader classLoader, Context context) {
        appContext = context;

        try {
            // 1. Hook createSpeechRecognizer factory methods to intercept any runtime instance/subclass
            XC_MethodHook factoryHook = new XC_MethodHook() {
                @Override
                protected void afterHookedMethod(MethodHookParam param) throws Throwable {
                    Object result = param.getResult();
                    if (result != null) {
                        Class<?> runtimeClass = result.getClass();
                        hookSpeechRecognizerClass(runtimeClass);
                    }
                }
            };

            XposedHelpers.findAndHookMethod(SpeechRecognizer.class, "createSpeechRecognizer", Context.class, factoryHook);
            XposedHelpers.findAndHookMethod(SpeechRecognizer.class, "createSpeechRecognizer", Context.class, ComponentName.class, factoryHook);

            // 2. Hook base SpeechRecognizer.class
            hookSpeechRecognizerClass(SpeechRecognizer.class);

            // 3. Hook Android 14/15/16 implementation class android.speech.SpeechRecognizerImpl
            Class<?> implClass = null;
            try {
                implClass = Class.forName("android.speech.SpeechRecognizerImpl");
            } catch (ClassNotFoundException ignored) {
                try {
                    implClass = XposedHelpers.findClass("android.speech.SpeechRecognizerImpl", classLoader);
                } catch (Throwable ignored2) {}
            }

            if (implClass != null) {
                hookSpeechRecognizerClass(implClass);
                XposedBridge.log("[SwiftKeyWhisper] android.speech.SpeechRecognizerImpl found and targeted for hooks!");
            }

            // 4. Hook SwiftKey Lottie voice animation view to guarantee calm resting waveform
            hookLottieVoiceMicrophoneView(classLoader);

            XposedBridge.log("[SwiftKeyWhisper] SpeechRecognizer factory hooks initialized");

        } catch (Throwable t) {
            XposedBridge.log("[SwiftKeyWhisper] Error initializing SpeechRecognizer hooks: " + t.getMessage());
        }
    }

    private static synchronized void hookSpeechRecognizerClass(Class<?> clazz) {
        if (clazz == null || hookedClasses.contains(clazz)) {
            return;
        }
        hookedClasses.add(clazz);
        XposedBridge.log("[SwiftKeyWhisper] Hooking methods on class: " + clazz.getName());

        try {
            // A. Hook setRecognitionListener
            XposedHelpers.findAndHookMethod(
                    clazz,
                    "setRecognitionListener",
                    RecognitionListener.class,
                    new XC_MethodHook() {
                        @Override
                        protected void beforeHookedMethod(MethodHookParam param) throws Throwable {
                            activeListener = (RecognitionListener) param.args[0];
                            XposedBridge.log("[SwiftKeyWhisper] Captured RecognitionListener on " + clazz.getSimpleName() +
                                    ": " + (activeListener != null ? activeListener.getClass().getName() : "null"));
                        }
                    }
            );

            // B. Hook startListening
            XposedHelpers.findAndHookMethod(
                    clazz,
                    "startListening",
                    Intent.class,
                    new XC_MethodHook() {
                        @Override
                        protected void beforeHookedMethod(MethodHookParam param) throws Throwable {
                            XposedBridge.log("[SwiftKeyWhisper] >>> Intercepted startListening() on " + clazz.getSimpleName());

                            // Prevent Google TTS / system SpeechRecognizer IPC
                            param.setResult(null);

                            Intent intent = (Intent) param.args[0];
                            String dynamicLang = null;
                            if (intent != null) {
                                dynamicLang = intent.getStringExtra("android.speech.extra.LANGUAGE");
                                if (dynamicLang == null) {
                                    dynamicLang = intent.getStringExtra("android.speech.extra.LANGUAGE_PREFERENCE");
                                }
                                XposedBridge.log("[SwiftKeyWhisper] Intercepted Intent language: " + dynamicLang);
                            }

                            ConfigManager config = ConfigManager.getInstance(appContext);
                            config.loadFileConfig(); // Always reload latest API key / settings

                            AudioRecorderManager.getInstance().startRecording(
                                    activeListener,
                                    config,
                                    dynamicLang,
                                    new AudioRecorderManager.ResultCallback() {
                                        @Override
                                        public void onResult(String text) {
                                            // Fallback direct injection if listener failed to commit
                                            if (config.isDirectInjection()) {
                                                InputConnectionTracker.commitText(text);
                                            }
                                        }

                                        @Override
                                        public void onError(String error) {
                                            XposedBridge.log("[SwiftKeyWhisper] Recording error: " + error);
                                            if (activeListener != null) {
                                                mainHandler.post(() -> activeListener.onError(SpeechRecognizer.ERROR_NETWORK));
                                            }
                                        }
                                    }
                            );
                        }
                    }
            );

            // C. Hook stopListening
            XposedHelpers.findAndHookMethod(
                    clazz,
                    "stopListening",
                    new XC_MethodHook() {
                        @Override
                        protected void beforeHookedMethod(MethodHookParam param) throws Throwable {
                            XposedBridge.log("[SwiftKeyWhisper] >>> Intercepted stopListening() on " + clazz.getSimpleName());
                            param.setResult(null);
                            AudioRecorderManager.getInstance().stopListening();
                        }
                    }
            );

            // D. Hook cancel
            XposedHelpers.findAndHookMethod(
                    clazz,
                    "cancel",
                    new XC_MethodHook() {
                        @Override
                        protected void beforeHookedMethod(MethodHookParam param) throws Throwable {
                            XposedBridge.log("[SwiftKeyWhisper] Intercepted cancel() on " + clazz.getSimpleName());
                            param.setResult(null);
                            AudioRecorderManager.getInstance().cancel();
                        }
                    }
            );

            // E. Hook destroy
            XposedHelpers.findAndHookMethod(
                    clazz,
                    "destroy",
                    new XC_MethodHook() {
                        @Override
                        protected void beforeHookedMethod(MethodHookParam param) throws Throwable {
                            XposedBridge.log("[SwiftKeyWhisper] Intercepted destroy() on " + clazz.getSimpleName());
                            param.setResult(null);
                            AudioRecorderManager.getInstance().cancel();
                            activeListener = null;
                        }
                    }
            );

            XposedBridge.log("[SwiftKeyWhisper] Successfully hooked all speech methods on " + clazz.getName());

        } catch (Throwable t) {
            XposedBridge.log("[SwiftKeyWhisper] Failed hooking methods on " + clazz.getName() + ": " + t.getMessage());
        }
    }

    /**
     * Hooks SwiftKey's LottieVoiceMicrophoneView to eliminate the race condition where
     * the calm resting waveform (VOICE_QUIET, frames 1..151) is skipped when SpeechRecognizer
     * signals onReadyForSpeech while the initial frame 0 animation is still running.
     */
    private static void hookLottieVoiceMicrophoneView(ClassLoader classLoader) {
        try {
            Class<?> lottieViewClass = null;
            try {
                lottieViewClass = XposedHelpers.findClass("com.swiftkey.voice.LottieVoiceMicrophoneView", classLoader);
            } catch (Throwable t) {
                XposedBridge.log("[SwiftKeyWhisper] LottieVoiceMicrophoneView not found: " + t.getMessage());
                return;
            }

            for (Method method : lottieViewClass.getDeclaredMethods()) {
                if ("setState".equals(method.getName()) && method.getParameterTypes().length == 1) {
                    XposedBridge.hookMethod(method, new XC_MethodHook() {
                        @Override
                        protected void afterHookedMethod(MethodHookParam param) throws Throwable {
                            Object b1Var = param.args[0];
                            if (b1Var == null) return;

                            String className = b1Var.getClass().getName();

                            // 1. VoiceTypingStarted (u50.z0): Triggered by onReadyForSpeech
                            if (className.endsWith(".z0")) {
                                boolean speaking = false;
                                try {
                                    speaking = (Boolean) XposedHelpers.callMethod(b1Var, "a");
                                } catch (Throwable t) {
                                    try {
                                        speaking = XposedHelpers.getBooleanField(b1Var, "f43346a");
                                    } catch (Throwable ignored) {}
                                }

                                if (!speaking) {
                                    try {
                                        XposedHelpers.setBooleanField(param.thisObject, "f7067s", false);
                                    } catch (Throwable ignored) {}

                                    float minFrame = 0f;
                                    float maxFrame = 0f;
                                    int repeatCount = 0;
                                    try {
                                        minFrame = (Float) XposedHelpers.callMethod(param.thisObject, "getMinFrame");
                                        maxFrame = (Float) XposedHelpers.callMethod(param.thisObject, "getMaxFrame");
                                        repeatCount = (Integer) XposedHelpers.callMethod(param.thisObject, "getRepeatCount");
                                    } catch (Throwable ignored) {}

                                    // If not currently playing the calm resting wave (1..151 or 32..151 repeating infinitely):
                                    if ((minFrame != 1.0f && minFrame != 32.0f) || maxFrame < 150.0f || repeatCount != -1) {
                                        XposedHelpers.callMethod(param.thisObject, "f", 1, 151, -1);
                                        XposedBridge.log("[SwiftKeyWhisper] LottieVoiceMicrophoneView: Started calm resting wave f(1, 151, -1) for z0 (speaking=false)");
                                    }
                                } else {
                                    float minFrame = 0f;
                                    try {
                                        minFrame = (Float) XposedHelpers.callMethod(param.thisObject, "getMinFrame");
                                    } catch (Throwable ignored) {}

                                    if (minFrame < 152.0f) {
                                        XposedHelpers.callMethod(param.thisObject, "f", 152, 281, -1);
                                        XposedBridge.log("[SwiftKeyWhisper] LottieVoiceMicrophoneView: Started active talk wave f(152, 281, -1) for z0 (speaking=true)");
                                    }
                                }
                            }
                            // 2. VoiceTypingFragment (u50.p0 or any other u50.k0 state)
                            else if (className.endsWith(".p0") || className.endsWith(".k0")) {
                                boolean speaking = false;
                                try {
                                    speaking = (Boolean) XposedHelpers.callMethod(b1Var, "a");
                                } catch (Throwable ignored) {}

                                if (!speaking) {
                                    float minFrame = 0f;
                                    float maxFrame = 0f;
                                    int repeatCount = 0;
                                    try {
                                        minFrame = (Float) XposedHelpers.callMethod(param.thisObject, "getMinFrame");
                                        maxFrame = (Float) XposedHelpers.callMethod(param.thisObject, "getMaxFrame");
                                        repeatCount = (Integer) XposedHelpers.callMethod(param.thisObject, "getRepeatCount");
                                    } catch (Throwable ignored) {}

                                    if (minFrame != 32.0f || maxFrame < 150.0f || repeatCount != -1) {
                                        XposedHelpers.callMethod(param.thisObject, "f", 32, 151, -1);
                                        XposedBridge.log("[SwiftKeyWhisper] LottieVoiceMicrophoneView: Resumed calm quiet wave f(32, 151, -1) for p0/k0 (speaking=false)");
                                    }
                                } else {
                                    float minFrame = 0f;
                                    try {
                                        minFrame = (Float) XposedHelpers.callMethod(param.thisObject, "getMinFrame");
                                    } catch (Throwable ignored) {}

                                    if (minFrame < 152.0f) {
                                        XposedHelpers.callMethod(param.thisObject, "f", 152, 281, -1);
                                        XposedBridge.log("[SwiftKeyWhisper] LottieVoiceMicrophoneView: Resumed active talk wave f(152, 281, -1) for p0/k0 (speaking=true)");
                                    }
                                }
                            }
                        }
                    });
                    XposedBridge.log("[SwiftKeyWhisper] Hooked LottieVoiceMicrophoneView.setState successfully!");
                    break;
                }
            }
        } catch (Throwable t) {
            XposedBridge.log("[SwiftKeyWhisper] Failed to hook LottieVoiceMicrophoneView: " + t.getMessage());
        }
    }
}
