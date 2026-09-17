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

import java.lang.ref.WeakReference;
import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;
import java.util.WeakHashMap;

import de.robv.android.xposed.XC_MethodHook;
import de.robv.android.xposed.XposedBridge;
import de.robv.android.xposed.XposedHelpers;

public class SpeechRecognizerHook {
    private static final String TAG = "SwiftKeyWhisperHook";

    private static final Map<Object, RecognitionListener> listenerMap = new WeakHashMap<>();
    private static volatile RecognitionListener activeListener;
    private static WeakReference<Object> activeRecognizerRef = new WeakReference<>(null);
    private static Context appContext;
    private static final Handler mainHandler = new Handler(Looper.getMainLooper());
    private static final Set<Class<?>> hookedClasses = new HashSet<>();
    private static WeakReference<Object> lastLottieViewRef = new WeakReference<>(null);

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

    private static boolean isActiveRecognizer(Object thisObject) {
        if (thisObject == null) return false;
        Object active = activeRecognizerRef.get();
        if (active == null) return false;
        if (thisObject == active) return true;

        // In Android 14+, SpeechRecognizerProxy delegates to SpeechRecognizerImpl (mDelegate)
        try {
            Object delegate = XposedHelpers.getObjectField(active, "mDelegate");
            if (delegate == thisObject) return true;
        } catch (Throwable ignored) {}

        try {
            Object delegate = XposedHelpers.getObjectField(thisObject, "mDelegate");
            if (delegate == active) return true;
        } catch (Throwable ignored) {}

        return false;
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
                            if (param.thisObject != null && param.args[0] instanceof RecognitionListener) {
                                listenerMap.put(param.thisObject, (RecognitionListener) param.args[0]);
                            }
                            activeListener = (RecognitionListener) param.args[0];
                            XposedBridge.log("[SwiftKeyWhisper] Captured RecognitionListener on " + clazz.getSimpleName() +
                                    " (" + System.identityHashCode(param.thisObject) + "): " +
                                    (activeListener != null ? activeListener.getClass().getName() : "null"));
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
                            XposedBridge.log("[SwiftKeyWhisper] >>> Intercepted startListening() on " + clazz.getSimpleName() +
                                    " (" + System.identityHashCode(param.thisObject) + ")");

                            // Prevent Google TTS / system SpeechRecognizer IPC
                            param.setResult(null);

                            activeRecognizerRef = new WeakReference<>(param.thisObject);

                            RecognitionListener listener = listenerMap.get(param.thisObject);
                            if (listener == null) {
                                try {
                                    Object delegate = XposedHelpers.getObjectField(param.thisObject, "mDelegate");
                                    if (delegate != null) {
                                        listener = listenerMap.get(delegate);
                                    }
                                } catch (Throwable ignored) {}
                            }
                            if (listener != null) {
                                activeListener = listener;
                            }

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
                            XSharedPreferencesProvider.reloadAndApply(config); // Always reload latest API key / settings via XSharedPreferences

                            AudioRecorderManager.getInstance().startRecording(
                                    appContext,
                                    activeListener,
                                    config,
                                    dynamicLang
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
                            XposedBridge.log("[SwiftKeyWhisper] >>> Intercepted stopListening() on " + clazz.getSimpleName() +
                                    " (" + System.identityHashCode(param.thisObject) + ")");
                            param.setResult(null);
                            if (isActiveRecognizer(param.thisObject)) {
                                AudioRecorderManager.getInstance().stopListening();
                            } else {
                                XposedBridge.log("[SwiftKeyWhisper] Ignoring stopListening() on inactive recognizer (" +
                                        System.identityHashCode(param.thisObject) + ")");
                            }
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
                            XposedBridge.log("[SwiftKeyWhisper] Intercepted cancel() on " + clazz.getSimpleName() +
                                    " (" + System.identityHashCode(param.thisObject) + ")");
                            param.setResult(null);
                            if (isActiveRecognizer(param.thisObject)) {
                                activeRecognizerRef.clear();
                                activeListener = null;
                                AudioRecorderManager.getInstance().cancel();
                                resetLottieMicrophoneView();
                            } else {
                                XposedBridge.log("[SwiftKeyWhisper] Ignoring cancel() on inactive recognizer (" +
                                        System.identityHashCode(param.thisObject) + ")");
                            }
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
                            XposedBridge.log("[SwiftKeyWhisper] Intercepted destroy() on " + clazz.getSimpleName() +
                                    " (" + System.identityHashCode(param.thisObject) + ")");
                            param.setResult(null);
                            if (isActiveRecognizer(param.thisObject)) {
                                XposedBridge.log("[SwiftKeyWhisper] Destroying active recognizer, stopping voice session");
                                activeRecognizerRef.clear();
                                activeListener = null;
                                AudioRecorderManager.getInstance().cancel();
                                resetLottieMicrophoneView();
                            } else {
                                XposedBridge.log("[SwiftKeyWhisper] Ignoring destroy() on inactive recognizer (" +
                                        System.identityHashCode(param.thisObject) + ", e.g. background capability check)");
                            }
                        }
                    }
            );

            XposedBridge.log("[SwiftKeyWhisper] Successfully hooked all speech methods on " + clazz.getName());

        } catch (Throwable t) {
            XposedBridge.log("[SwiftKeyWhisper] Failed hooking methods on " + clazz.getName() + ": " + t.getMessage());
        }
    }

    /**
     * Forces SwiftKey's LottieVoiceMicrophoneView back to the static microphone icon (frame 0).
     */
    public static void resetLottieMicrophoneView() {
        mainHandler.post(() -> {
            try {
                Object view = lastLottieViewRef != null ? lastLottieViewRef.get() : null;
                if (view != null) {
                    try {
                        XposedHelpers.setBooleanField(view, "f7067s", false);
                    } catch (Throwable ignored) {}

                    float minFrame = 0f;
                    float maxFrame = 0f;
                    int repeatCount = 0;
                    try {
                        minFrame = (Float) XposedHelpers.callMethod(view, "getMinFrame");
                        maxFrame = (Float) XposedHelpers.callMethod(view, "getMaxFrame");
                        repeatCount = (Integer) XposedHelpers.callMethod(view, "getRepeatCount");
                    } catch (Throwable ignored) {}

                    boolean isAnimating = false;
                    try {
                        Object drawable = XposedHelpers.getObjectField(view, "f5631h");
                        if (drawable != null) {
                            isAnimating = (Boolean) XposedHelpers.callMethod(drawable, "h");
                        }
                    } catch (Throwable ignored) {}

                    if (minFrame != 0.0f || maxFrame != 0.0f || repeatCount != 0 || isAnimating) {
                        XposedHelpers.callMethod(view, "f", 0, 0, 0);
                        XposedBridge.log("[SwiftKeyWhisper] resetLottieMicrophoneView: forced f(0, 0, 0) on LottieView");
                    }
                }
            } catch (Throwable t) {
                XposedBridge.log("[SwiftKeyWhisper] resetLottieMicrophoneView error: " + t.getMessage());
            }
        });
    }

    /**
     * Hooks SwiftKey's LottieVoiceMicrophoneView to:
     * 1. Guarantee calm resting waveform (VOICE_QUIET, frames 1..151) when SpeechRecognizer
     *    signals onReadyForSpeech while the initial frame 0 animation is still running.
     * 2. Immediately stop undulating waveform and return to static mic icon (f(0, 0, 0))
     *    on ANY voice stop event (DELETE, TYPING, ERROR, IDLE), fixing SwiftKey's native bug
     *    where non-BUTTON stop triggers get stuck in an infinite undulating loop.
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

            // Capture instance from constructor
            XposedBridge.hookAllConstructors(lottieViewClass, new XC_MethodHook() {
                @Override
                protected void afterHookedMethod(MethodHookParam param) throws Throwable {
                    lastLottieViewRef = new WeakReference<>(param.thisObject);
                    XposedBridge.log("[SwiftKeyWhisper] Captured LottieVoiceMicrophoneView instance from constructor");
                }
            });

            for (Method method : lottieViewClass.getDeclaredMethods()) {
                if ("setState".equals(method.getName()) && method.getParameterTypes().length == 1) {
                    XposedBridge.hookMethod(method, new XC_MethodHook() {
                        @Override
                        protected void afterHookedMethod(MethodHookParam param) throws Throwable {
                            Object b1Var = param.args[0];
                            if (b1Var == null) return;
                            lastLottieViewRef = new WeakReference<>(param.thisObject);

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
                            // 2. VoiceTypingFragment / Completed (u50.p0, u50.y0 or any other u50.k0 state)
                            else if (className.endsWith(".p0") || className.endsWith(".k0") || className.endsWith(".y0")) {
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
                            // 3. VoiceTypingOver (u50.o0), VoiceTypingError (u50.m0), VoiceTypingIdle (u50.e1)
                            else if (className.endsWith(".o0") || className.endsWith(".m0") || className.endsWith(".e1")) {
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

                                boolean isAnimating = false;
                                try {
                                    Object drawable = XposedHelpers.getObjectField(param.thisObject, "f5631h");
                                    if (drawable != null) {
                                        isAnimating = (Boolean) XposedHelpers.callMethod(drawable, "h");
                                    }
                                } catch (Throwable ignored) {}

                                if (minFrame != 0.0f || maxFrame != 0.0f || repeatCount != 0 || isAnimating) {
                                    XposedHelpers.callMethod(param.thisObject, "f", 0, 0, 0);
                                    XposedBridge.log("[SwiftKeyWhisper] LottieVoiceMicrophoneView: Forced f(0, 0, 0) for " + className);
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
