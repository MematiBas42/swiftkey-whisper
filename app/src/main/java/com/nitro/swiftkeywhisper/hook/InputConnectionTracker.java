package com.nitro.swiftkeywhisper.hook;

import android.inputmethodservice.InputMethodService;
import android.util.Log;
import android.view.inputmethod.EditorInfo;
import android.view.inputmethod.InputConnection;

import de.robv.android.xposed.XC_MethodHook;
import de.robv.android.xposed.XposedBridge;
import de.robv.android.xposed.XposedHelpers;

public class InputConnectionTracker {
    private static final String TAG = "SwiftKeyWhisperInput";
    private static InputConnection currentInputConnection;
    private static InputMethodService currentInputMethodService;

    public static void initHook(ClassLoader classLoader) {
        try {
            XposedHelpers.findAndHookMethod(
                    InputMethodService.class,
                    "onStartInput",
                    EditorInfo.class,
                    boolean.class,
                    new XC_MethodHook() {
                        @Override
                        protected void afterHookedMethod(MethodHookParam param) throws Throwable {
                            currentInputMethodService = (InputMethodService) param.thisObject;
                            currentInputConnection = currentInputMethodService.getCurrentInputConnection();
                            XposedBridge.log("[SwiftKeyWhisper] Captured InputConnection from onStartInput");
                        }
                    }
            );

            XposedHelpers.findAndHookMethod(
                    InputMethodService.class,
                    "onDestroy",
                    new XC_MethodHook() {
                        @Override
                        protected void beforeHookedMethod(MethodHookParam param) throws Throwable {
                            currentInputConnection = null;
                            currentInputMethodService = null;
                        }
                    }
            );
        } catch (Throwable t) {
            XposedBridge.log("[SwiftKeyWhisper] Failed to hook InputMethodService: " + t.getMessage());
        }
    }

    public static InputConnection getCurrentInputConnection() {
        if (currentInputMethodService != null) {
            InputConnection ic = currentInputMethodService.getCurrentInputConnection();
            if (ic != null) {
                return ic;
            }
        }
        return currentInputConnection;
    }

    public static void commitText(String text) {
        InputConnection ic = getCurrentInputConnection();
        if (ic != null) {
            try {
                ic.beginBatchEdit();
                ic.commitText(text, 1);
                ic.endBatchEdit();
                Log.i(TAG, "Successfully committed text via InputConnection: " + text);
            } catch (Throwable t) {
                Log.e(TAG, "Failed committing text via InputConnection", t);
            }
        } else {
            Log.w(TAG, "No active InputConnection available to commit text");
        }
    }
}
