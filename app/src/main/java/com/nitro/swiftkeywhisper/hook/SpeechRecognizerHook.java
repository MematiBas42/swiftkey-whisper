package com.nitro.swiftkeywhisper.hook;

import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.os.Handler;
import android.os.Looper;
import android.speech.RecognitionListener;
import android.speech.SpeechRecognizer;
import android.util.Log;

import com.nitro.swiftkeywhisper.audio.AudioRecorderManager;
import com.nitro.swiftkeywhisper.config.ConfigManager;
import com.nitro.swiftkeywhisper.util.ReflectionHelper;

import java.lang.ref.WeakReference;
import java.lang.reflect.Constructor;
import java.lang.reflect.Method;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;
import java.util.WeakHashMap;

import io.github.libxposed.api.XposedInterface;
import io.github.libxposed.api.XposedModule;

public class SpeechRecognizerHook {
    private static final String TAG = "SwiftKeyWhisperHook";

    private static final Map<Object, RecognitionListener> listenerMap = new WeakHashMap<>();
    private static volatile RecognitionListener activeListener;
    private static WeakReference<Object> activeRecognizerRef = new WeakReference<>(null);
    private static Context appContext;
    private static final Handler mainHandler = new Handler(Looper.getMainLooper());
    private static final Set<Class<?>> hookedClasses = new HashSet<>();
    private static WeakReference<Object> lastLottieViewRef = new WeakReference<>(null);

    public static void initHook(XposedModule module, ClassLoader classLoader, Context context) {
        appContext = context;

        try {
            // 1. Hook createSpeechRecognizer factory methods to intercept any runtime instance/subclass
            try {
                Method create1 = SpeechRecognizer.class.getDeclaredMethod("createSpeechRecognizer", Context.class);
                module.hook(create1)
                        .setPriority(XposedInterface.PRIORITY_DEFAULT)
                        .setExceptionMode(XposedInterface.ExceptionMode.PROTECTIVE)
                        .intercept(chain -> {
                            Object result = chain.proceed();
                            if (result != null) {
                                hookSpeechRecognizerClass(module, result.getClass());
                            }
                            return result;
                        });
            } catch (Throwable t) {
                module.log(Log.WARN, TAG, "Failed hooking createSpeechRecognizer(Context): " + t.getMessage());
            }

            try {
                Method create2 = SpeechRecognizer.class.getDeclaredMethod("createSpeechRecognizer", Context.class, ComponentName.class);
                module.hook(create2)
                        .setPriority(XposedInterface.PRIORITY_DEFAULT)
                        .setExceptionMode(XposedInterface.ExceptionMode.PROTECTIVE)
                        .intercept(chain -> {
                            Object result = chain.proceed();
                            if (result != null) {
                                hookSpeechRecognizerClass(module, result.getClass());
                            }
                            return result;
                        });
            } catch (Throwable t) {
                module.log(Log.WARN, TAG, "Failed hooking createSpeechRecognizer(Context, ComponentName): " + t.getMessage());
            }

            // 2. Hook base SpeechRecognizer.class
            hookSpeechRecognizerClass(module, SpeechRecognizer.class);

            // 3. Hook Android 14/15/16 implementation class android.speech.SpeechRecognizerImpl
            Class<?> implClass = null;
            try {
                implClass = Class.forName("android.speech.SpeechRecognizerImpl");
            } catch (ClassNotFoundException ignored) {
                try {
                    implClass = Class.forName("android.speech.SpeechRecognizerImpl", true, classLoader);
                } catch (Throwable ignored2) {}
            }

            if (implClass != null) {
                hookSpeechRecognizerClass(module, implClass);
                module.log(Log.INFO, TAG, "android.speech.SpeechRecognizerImpl targeted for hooks");
            }

            // 4. Hook SwiftKey Lottie voice animation view to guarantee calm resting waveform
            hookLottieVoiceMicrophoneView(module, classLoader);

            module.log(Log.INFO, TAG, "SpeechRecognizer factory hooks initialized");

        } catch (Throwable t) {
            module.log(Log.ERROR, TAG, "Error initializing SpeechRecognizer hooks: " + t.getMessage(), t);
        }
    }

    private static boolean isActiveRecognizer(Object thisObject) {
        if (thisObject == null) return false;
        Object active = activeRecognizerRef.get();
        if (active == null) return false;
        if (thisObject == active) return true;

        // In Android 14+, SpeechRecognizerProxy delegates to SpeechRecognizerImpl (mDelegate)
        try {
            Object delegate = ReflectionHelper.getObjectField(active, "mDelegate");
            if (delegate == thisObject) return true;
        } catch (Throwable ignored) {}

        try {
            Object delegate = ReflectionHelper.getObjectField(thisObject, "mDelegate");
            if (delegate == active) return true;
        } catch (Throwable ignored) {}

        return false;
    }

    private static synchronized void hookSpeechRecognizerClass(XposedModule module, Class<?> clazz) {
        if (clazz == null || hookedClasses.contains(clazz)) {
            return;
        }
        hookedClasses.add(clazz);
        module.log(Log.INFO, TAG, "Hooking methods on class: " + clazz.getName());

        try {
            // A. Hook setRecognitionListener
            try {
                Method setListener = clazz.getDeclaredMethod("setRecognitionListener", RecognitionListener.class);
                module.hook(setListener)
                        .setPriority(XposedInterface.PRIORITY_DEFAULT)
                        .setExceptionMode(XposedInterface.ExceptionMode.PROTECTIVE)
                        .intercept(chain -> {
                            Object thisObj = chain.getThisObject();
                            Object listenerArg = chain.getArg(0);
                            if (thisObj != null && listenerArg instanceof RecognitionListener) {
                                listenerMap.put(thisObj, (RecognitionListener) listenerArg);
                            }
                            activeListener = (RecognitionListener) listenerArg;
                            module.log(Log.INFO, TAG, "Captured RecognitionListener on " + clazz.getSimpleName() +
                                    " (" + System.identityHashCode(thisObj) + "): " +
                                    (activeListener != null ? activeListener.getClass().getName() : "null"));
                            return chain.proceed();
                        });
            } catch (NoSuchMethodException ignored) {}

            // B. Hook startListening (intercept and suppress system speech service)
            try {
                Method startListening = clazz.getDeclaredMethod("startListening", Intent.class);
                module.hook(startListening)
                        .setPriority(XposedInterface.PRIORITY_DEFAULT)
                        .setExceptionMode(XposedInterface.ExceptionMode.PROTECTIVE)
                        .intercept(chain -> {
                            Object thisObj = chain.getThisObject();
                            module.log(Log.INFO, TAG, ">>> Intercepted startListening() on " + clazz.getSimpleName() +
                                    " (" + System.identityHashCode(thisObj) + ")");

                            activeRecognizerRef = new WeakReference<>(thisObj);

                            RecognitionListener listener = listenerMap.get(thisObj);
                            if (listener == null) {
                                try {
                                    Object delegate = ReflectionHelper.getObjectField(thisObj, "mDelegate");
                                    if (delegate != null) {
                                        listener = listenerMap.get(delegate);
                                    }
                                } catch (Throwable ignored) {}
                            }
                            if (listener != null) {
                                activeListener = listener;
                            }

                            Intent intent = (Intent) chain.getArg(0);
                            String dynamicLang = null;
                            if (intent != null) {
                                dynamicLang = intent.getStringExtra("android.speech.extra.LANGUAGE");
                                if (dynamicLang == null) {
                                    dynamicLang = intent.getStringExtra("android.speech.extra.LANGUAGE_PREFERENCE");
                                }
                                module.log(Log.INFO, TAG, "Intercepted Intent language: " + dynamicLang);
                            }

                            ConfigManager config = ConfigManager.getInstance(appContext);
                            try {
                                SharedPreferences remotePrefs = module.getRemotePreferences(ConfigManager.PREFS_NAME);
                                if (remotePrefs != null) {
                                    config.loadFromPreferences(remotePrefs);
                                }
                            } catch (Throwable t) {
                                module.log(Log.WARN, TAG, "Failed reloading RemotePreferences in startListening: " + t.getMessage());
                            }

                            AudioRecorderManager.getInstance().startRecording(
                                    appContext,
                                    activeListener,
                                    config,
                                    dynamicLang
                            );

                            // Do NOT call chain.proceed(); return null to suppress system SpeechRecognizer IPC
                            return null;
                        });
            } catch (NoSuchMethodException ignored) {}

            // C. Hook stopListening
            try {
                Method stopListening = clazz.getDeclaredMethod("stopListening");
                module.hook(stopListening)
                        .setPriority(XposedInterface.PRIORITY_DEFAULT)
                        .setExceptionMode(XposedInterface.ExceptionMode.PROTECTIVE)
                        .intercept(chain -> {
                            Object thisObj = chain.getThisObject();
                            module.log(Log.INFO, TAG, ">>> Intercepted stopListening() on " + clazz.getSimpleName() +
                                    " (" + System.identityHashCode(thisObj) + ")");
                            if (isActiveRecognizer(thisObj)) {
                                AudioRecorderManager.getInstance().stopListening();
                            } else {
                                module.log(Log.INFO, TAG, "Ignoring stopListening() on inactive recognizer (" +
                                        System.identityHashCode(thisObj) + ")");
                            }
                            return null;
                        });
            } catch (NoSuchMethodException ignored) {}

            // D. Hook cancel
            try {
                Method cancel = clazz.getDeclaredMethod("cancel");
                module.hook(cancel)
                        .setPriority(XposedInterface.PRIORITY_DEFAULT)
                        .setExceptionMode(XposedInterface.ExceptionMode.PROTECTIVE)
                        .intercept(chain -> {
                            Object thisObj = chain.getThisObject();
                            module.log(Log.INFO, TAG, "Intercepted cancel() on " + clazz.getSimpleName() +
                                    " (" + System.identityHashCode(thisObj) + ")");
                            if (isActiveRecognizer(thisObj)) {
                                activeRecognizerRef.clear();
                                activeListener = null;
                                AudioRecorderManager.getInstance().cancel();
                                resetLottieMicrophoneView(module);
                            } else {
                                module.log(Log.INFO, TAG, "Ignoring cancel() on inactive recognizer (" +
                                        System.identityHashCode(thisObj) + ")");
                            }
                            return null;
                        });
            } catch (NoSuchMethodException ignored) {}

            // E. Hook destroy
            try {
                Method destroy = clazz.getDeclaredMethod("destroy");
                module.hook(destroy)
                        .setPriority(XposedInterface.PRIORITY_DEFAULT)
                        .setExceptionMode(XposedInterface.ExceptionMode.PROTECTIVE)
                        .intercept(chain -> {
                            Object thisObj = chain.getThisObject();
                            module.log(Log.INFO, TAG, "Intercepted destroy() on " + clazz.getSimpleName() +
                                    " (" + System.identityHashCode(thisObj) + ")");
                            if (isActiveRecognizer(thisObj)) {
                                module.log(Log.INFO, TAG, "Destroying active recognizer, stopping voice session");
                                activeRecognizerRef.clear();
                                activeListener = null;
                                AudioRecorderManager.getInstance().cancel();
                                resetLottieMicrophoneView(module);
                            } else {
                                module.log(Log.INFO, TAG, "Ignoring destroy() on inactive recognizer (" +
                                        System.identityHashCode(thisObj) + ", e.g. background check)");
                            }
                            return null;
                        });
            } catch (NoSuchMethodException ignored) {}

            module.log(Log.INFO, TAG, "Successfully hooked speech methods on " + clazz.getName());

        } catch (Throwable t) {
            module.log(Log.ERROR, TAG, "Failed hooking methods on " + clazz.getName() + ": " + t.getMessage());
        }
    }

    public static void resetLottieMicrophoneView(XposedModule module) {
        mainHandler.post(() -> {
            try {
                Object view = lastLottieViewRef != null ? lastLottieViewRef.get() : null;
                if (view != null) {
                    ReflectionHelper.setBooleanField(view, "f7067s", false);

                    float minFrame = 0f;
                    float maxFrame = 0f;
                    int repeatCount = 0;
                    try {
                        Object minObj = ReflectionHelper.callMethod(view, "getMinFrame");
                        if (minObj instanceof Float) minFrame = (Float) minObj;
                        Object maxObj = ReflectionHelper.callMethod(view, "getMaxFrame");
                        if (maxObj instanceof Float) maxFrame = (Float) maxObj;
                        Object repObj = ReflectionHelper.callMethod(view, "getRepeatCount");
                        if (repObj instanceof Integer) repeatCount = (Integer) repObj;
                    } catch (Throwable ignored) {}

                    boolean isAnimating = false;
                    try {
                        Object drawable = ReflectionHelper.getObjectField(view, "f5631h");
                        if (drawable != null) {
                            Object animObj = ReflectionHelper.callMethod(drawable, "h");
                            if (animObj instanceof Boolean) isAnimating = (Boolean) animObj;
                        }
                    } catch (Throwable ignored) {}

                    if (minFrame != 0.0f || maxFrame != 0.0f || repeatCount != 0 || isAnimating) {
                        ReflectionHelper.callMethod(view, "f", 0, 0, 0);
                        if (module != null) {
                            module.log(Log.INFO, TAG, "resetLottieMicrophoneView: forced f(0, 0, 0) on LottieView");
                        }
                    }
                }
            } catch (Throwable t) {
                if (module != null) {
                    module.log(Log.WARN, TAG, "resetLottieMicrophoneView error: " + t.getMessage());
                }
            }
        });
    }

    private static void hookLottieVoiceMicrophoneView(XposedModule module, ClassLoader classLoader) {
        try {
            Class<?> lottieViewClass = null;
            try {
                lottieViewClass = Class.forName("com.swiftkey.voice.LottieVoiceMicrophoneView", true, classLoader);
            } catch (Throwable t) {
                module.log(Log.WARN, TAG, "LottieVoiceMicrophoneView not found: " + t.getMessage());
                return;
            }

            // Capture instance from constructor
            for (Constructor<?> ctor : lottieViewClass.getDeclaredConstructors()) {
                module.hook(ctor)
                        .setPriority(XposedInterface.PRIORITY_DEFAULT)
                        .setExceptionMode(XposedInterface.ExceptionMode.PROTECTIVE)
                        .intercept(chain -> {
                            chain.proceed();
                            lastLottieViewRef = new WeakReference<>(chain.getThisObject());
                            module.log(Log.INFO, TAG, "Captured LottieVoiceMicrophoneView instance from constructor");
                            return null; // Constructors MUST return null in LibXposed!
                        });
            }

            for (Method method : lottieViewClass.getDeclaredMethods()) {
                if ("setState".equals(method.getName()) && method.getParameterTypes().length == 1) {
                    module.hook(method)
                            .setPriority(XposedInterface.PRIORITY_DEFAULT)
                            .setExceptionMode(XposedInterface.ExceptionMode.PROTECTIVE)
                            .intercept(chain -> {
                                Object result = chain.proceed();
                                Object b1Var = chain.getArg(0);
                                if (b1Var != null) {
                                    handleLottieState(module, chain.getThisObject(), b1Var);
                                }
                                return result;
                            });
                    module.log(Log.INFO, TAG, "Hooked LottieVoiceMicrophoneView.setState successfully!");
                    break;
                }
            }
        } catch (Throwable t) {
            module.log(Log.ERROR, TAG, "Failed to hook LottieVoiceMicrophoneView: " + t.getMessage());
        }
    }

    private static void handleLottieState(XposedModule module, Object view, Object b1Var) {
        lastLottieViewRef = new WeakReference<>(view);
        String className = b1Var.getClass().getName();

        // 1. VoiceTypingStarted (u50.z0): Triggered by onReadyForSpeech
        if (className.endsWith(".z0")) {
            boolean speaking = false;
            try {
                Object speakObj = ReflectionHelper.callMethod(b1Var, "a");
                if (speakObj instanceof Boolean) speaking = (Boolean) speakObj;
                else speaking = ReflectionHelper.getBooleanField(b1Var, "f43346a");
            } catch (Throwable ignored) {}

            if (!speaking) {
                ReflectionHelper.setBooleanField(view, "f7067s", false);

                float minFrame = 0f;
                float maxFrame = 0f;
                int repeatCount = 0;
                try {
                    Object minObj = ReflectionHelper.callMethod(view, "getMinFrame");
                    if (minObj instanceof Float) minFrame = (Float) minObj;
                    Object maxObj = ReflectionHelper.callMethod(view, "getMaxFrame");
                    if (maxObj instanceof Float) maxFrame = (Float) maxObj;
                    Object repObj = ReflectionHelper.callMethod(view, "getRepeatCount");
                    if (repObj instanceof Integer) repeatCount = (Integer) repObj;
                } catch (Throwable ignored) {}

                if ((minFrame != 1.0f && minFrame != 32.0f) || maxFrame < 150.0f || repeatCount != -1) {
                    ReflectionHelper.callMethod(view, "f", 1, 151, -1);
                    module.log(Log.INFO, TAG, "LottieVoiceMicrophoneView: Started calm resting wave f(1, 151, -1) for z0 (speaking=false)");
                }
            } else {
                float minFrame = 0f;
                try {
                    Object minObj = ReflectionHelper.callMethod(view, "getMinFrame");
                    if (minObj instanceof Float) minFrame = (Float) minObj;
                } catch (Throwable ignored) {}

                if (minFrame < 152.0f) {
                    ReflectionHelper.callMethod(view, "f", 152, 281, -1);
                    module.log(Log.INFO, TAG, "LottieVoiceMicrophoneView: Started active talk wave f(152, 281, -1) for z0 (speaking=true)");
                }
            }
        }
        // 2. VoiceTypingFragment / Completed (u50.p0, u50.y0 or any other u50.k0 state)
        else if (className.endsWith(".p0") || className.endsWith(".k0") || className.endsWith(".y0")) {
            boolean speaking = false;
            try {
                Object speakObj = ReflectionHelper.callMethod(b1Var, "a");
                if (speakObj instanceof Boolean) speaking = (Boolean) speakObj;
            } catch (Throwable ignored) {}

            if (!speaking) {
                float minFrame = 0f;
                float maxFrame = 0f;
                int repeatCount = 0;
                try {
                    Object minObj = ReflectionHelper.callMethod(view, "getMinFrame");
                    if (minObj instanceof Float) minFrame = (Float) minObj;
                    Object maxObj = ReflectionHelper.callMethod(view, "getMaxFrame");
                    if (maxObj instanceof Float) maxFrame = (Float) maxObj;
                    Object repObj = ReflectionHelper.callMethod(view, "getRepeatCount");
                    if (repObj instanceof Integer) repeatCount = (Integer) repObj;
                } catch (Throwable ignored) {}

                if (minFrame != 32.0f || maxFrame < 150.0f || repeatCount != -1) {
                    ReflectionHelper.callMethod(view, "f", 32, 151, -1);
                    module.log(Log.INFO, TAG, "LottieVoiceMicrophoneView: Resumed calm quiet wave f(32, 151, -1) for p0/k0 (speaking=false)");
                }
            } else {
                float minFrame = 0f;
                try {
                    Object minObj = ReflectionHelper.callMethod(view, "getMinFrame");
                    if (minObj instanceof Float) minFrame = (Float) minObj;
                } catch (Throwable ignored) {}

                if (minFrame < 152.0f) {
                    ReflectionHelper.callMethod(view, "f", 152, 281, -1);
                    module.log(Log.INFO, TAG, "LottieVoiceMicrophoneView: Resumed active talk wave f(152, 281, -1) for p0/k0 (speaking=true)");
                }
            }
        }
        // 3. VoiceTypingOver (u50.o0), VoiceTypingError (u50.m0), VoiceTypingIdle (u50.e1)
        else if (className.endsWith(".o0") || className.endsWith(".m0") || className.endsWith(".e1")) {
            ReflectionHelper.setBooleanField(view, "f7067s", false);

            float minFrame = 0f;
            float maxFrame = 0f;
            int repeatCount = 0;
            try {
                Object minObj = ReflectionHelper.callMethod(view, "getMinFrame");
                if (minObj instanceof Float) minFrame = (Float) minObj;
                Object maxObj = ReflectionHelper.callMethod(view, "getMaxFrame");
                if (maxObj instanceof Float) maxFrame = (Float) maxObj;
                Object repObj = ReflectionHelper.callMethod(view, "getRepeatCount");
                if (repObj instanceof Integer) repeatCount = (Integer) repObj;
            } catch (Throwable ignored) {}

            boolean isAnimating = false;
            try {
                Object drawable = ReflectionHelper.getObjectField(view, "f5631h");
                if (drawable != null) {
                    Object animObj = ReflectionHelper.callMethod(drawable, "h");
                    if (animObj instanceof Boolean) isAnimating = (Boolean) animObj;
                }
            } catch (Throwable ignored) {}

            if (minFrame != 0.0f || maxFrame != 0.0f || repeatCount != 0 || isAnimating) {
                ReflectionHelper.callMethod(view, "f", 0, 0, 0);
                module.log(Log.INFO, TAG, "LottieVoiceMicrophoneView: Forced f(0, 0, 0) for " + className);
            }
        }
    }
}
