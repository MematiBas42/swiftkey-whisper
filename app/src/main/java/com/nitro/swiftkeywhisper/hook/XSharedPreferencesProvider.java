package com.nitro.swiftkeywhisper.hook;

import com.nitro.swiftkeywhisper.config.ConfigManager;

import de.robv.android.xposed.XSharedPreferences;
import de.robv.android.xposed.XposedBridge;

public class XSharedPreferencesProvider {
    private static final String PACKAGE_NAME = "com.nitro.swiftkeywhisper";
    private static volatile XSharedPreferences xPrefs;

    /**
     * Initializes or returns the XSharedPreferences singleton.
     * Must only be invoked within hooked target processes.
     */
    public static synchronized XSharedPreferences getPrefs() {
        if (xPrefs == null) {
            try {
                xPrefs = new XSharedPreferences(PACKAGE_NAME, ConfigManager.PREFS_NAME);
                xPrefs.makeWorldReadable();
                XposedBridge.log("[SwiftKeyWhisper] Initialized XSharedPreferences for " + PACKAGE_NAME + "/" + ConfigManager.PREFS_NAME);
            } catch (Throwable t) {
                XposedBridge.log("[SwiftKeyWhisper] Failed initializing XSharedPreferences: " + t.getMessage());
            }
        }
        return xPrefs;
    }

    /**
     * Calls reload() to detect file updates and maps fresh values into ConfigManager.
     */
    public static synchronized void reloadAndApply(ConfigManager config) {
        if (config == null) return;
        try {
            XSharedPreferences prefs = getPrefs();
            if (prefs != null) {
                prefs.reload();
                config.loadFromPreferences(prefs);
                XposedBridge.log("[SwiftKeyWhisper] Config reloaded via XSharedPreferences. Has API key: " + (!config.getApiKey().isEmpty()));
            } else {
                XposedBridge.log("[SwiftKeyWhisper] XSharedPreferences instance is null, cannot reload config");
            }
        } catch (Throwable t) {
            XposedBridge.log("[SwiftKeyWhisper] Error reloading XSharedPreferences: " + t.getMessage());
        }
    }
}
