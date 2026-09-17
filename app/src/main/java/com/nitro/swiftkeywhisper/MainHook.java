package com.nitro.swiftkeywhisper;

import android.app.Application;
import android.app.Instrumentation;
import android.content.Context;

import com.nitro.swiftkeywhisper.config.ConfigManager;
import com.nitro.swiftkeywhisper.hook.SpeechRecognizerHook;
import com.nitro.swiftkeywhisper.hook.XSharedPreferencesProvider;

import de.robv.android.xposed.IXposedHookLoadPackage;
import de.robv.android.xposed.IXposedHookZygoteInit;
import de.robv.android.xposed.XC_MethodHook;
import de.robv.android.xposed.XC_MethodReplacement;
import de.robv.android.xposed.XposedBridge;
import de.robv.android.xposed.XposedHelpers;
import de.robv.android.xposed.callbacks.XC_LoadPackage;

public class MainHook implements IXposedHookLoadPackage, IXposedHookZygoteInit {
    public static String MODULE_PATH = null;

    private static final String TARGET_PKG_1 = "com.touchtype.swiftkey";
    private static final String TARGET_PKG_2 = "com.touchtype.swiftkey.beta";
    private static final String SELF_PKG = "com.nitro.swiftkeywhisper";

    private static boolean isInitialized = false;

    @Override
    public void initZygote(StartupParam startupParam) throws Throwable {
        MODULE_PATH = startupParam.modulePath;
        XposedBridge.log("[SwiftKeyWhisper] initZygote: modulePath=" + MODULE_PATH);
    }

    @Override
    public void handleLoadPackage(XC_LoadPackage.LoadPackageParam lpparam) throws Throwable {
        // Module status check hook in SwiftKey Whisper UI
        if (SELF_PKG.equals(lpparam.packageName)) {
            try {
                XposedHelpers.findAndHookMethod(
                        "com.nitro.swiftkeywhisper.ui.MainActivity",
                        lpparam.classLoader,
                        "isModuleActive",
                        XC_MethodReplacement.returnConstant(true)
                );
                XposedBridge.log("[SwiftKeyWhisper] Hooked MainActivity.isModuleActive() -> true");
            } catch (Throwable t) {
                XposedBridge.log("[SwiftKeyWhisper] Failed hooking isModuleActive: " + t.getMessage());
            }
            return;
        }

        if (!lpparam.isFirstApplication) {
            return;
        }

        if (!TARGET_PKG_1.equals(lpparam.packageName) && !TARGET_PKG_2.equals(lpparam.packageName)) {
            return;
        }

        XposedBridge.log("[SwiftKeyWhisper] Target package detected: " + lpparam.packageName);

        // Hook Application creation to get application context
        XposedHelpers.findAndHookMethod(
                Instrumentation.class,
                "callApplicationOnCreate",
                Application.class,
                new XC_MethodHook() {
                    @Override
                    protected void beforeHookedMethod(MethodHookParam param) throws Throwable {
                        if (isInitialized) {
                            return;
                        }
                        Application app = (Application) param.args[0];
                        Context context = app.getApplicationContext();
                        if (context != null) {
                            initialize(lpparam.classLoader, context);
                        }
                    }
                }
        );
    }

    private synchronized void initialize(ClassLoader classLoader, Context context) {
        if (isInitialized) {
            return;
        }
        isInitialized = true;

        XposedBridge.log("[SwiftKeyWhisper] Initializing SwiftKeyWhisper hooks...");

        // 1. Initialize configuration via XSharedPreferences
        ConfigManager config = ConfigManager.getInstance(context);
        XSharedPreferencesProvider.reloadAndApply(config);

        // 2. Hook SpeechRecognizer to redirect audio to Whisper
        SpeechRecognizerHook.initHook(classLoader, context);

        XposedBridge.log("[SwiftKeyWhisper] All hooks initialized successfully!");
    }
}
