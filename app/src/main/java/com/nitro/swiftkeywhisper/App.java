package com.nitro.swiftkeywhisper;

import android.app.Application;
import android.content.SharedPreferences;
import android.util.Log;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import com.nitro.swiftkeywhisper.config.ConfigManager;

import io.github.libxposed.service.XposedService;
import io.github.libxposed.service.XposedServiceHelper;

public class App extends Application implements XposedServiceHelper.OnServiceListener {
    private static final String TAG = "SwiftKeyWhisperApp";

    @Nullable
    private static volatile XposedService service = null;

    @Override
    public void onCreate() {
        super.onCreate();
        // Register listener for LSPosed Framework Service binder
        try {
            XposedServiceHelper.registerListener(this);
            Log.i(TAG, "Registered XposedServiceHelper listener");
        } catch (Throwable t) {
            Log.w(TAG, "Failed registering XposedServiceHelper listener: " + t.getMessage());
        }
    }

    @Override
    public void onServiceBind(@NonNull XposedService xposedService) {
        service = xposedService;
        Log.i(TAG, "Connected to LSPosed service: " + xposedService.getFrameworkName() +
                " (v" + xposedService.getFrameworkVersion() + ", API " + xposedService.getApiVersion() + ")");

        // Sync local settings to RemotePreferences so LSPosed daemon SQLite store is always up to date
        try {
            ConfigManager config = ConfigManager.getInstance(this);
            SharedPreferences remotePrefs = xposedService.getRemotePreferences(ConfigManager.PREFS_NAME);
            if (remotePrefs != null) {
                config.saveToSharedPreferences(remotePrefs);
                Log.i(TAG, "Synced preferences to LSPosed RemotePreferences on bind");
            }
        } catch (Throwable t) {
            Log.w(TAG, "Error syncing preferences onServiceBind: " + t.getMessage());
        }
    }

    @Override
    public void onServiceDied(@NonNull XposedService xposedService) {
        if (service == xposedService) {
            service = null;
            Log.i(TAG, "LSPosed service disconnected");
        }
    }

    @Nullable
    public static XposedService getService() {
        return service;
    }

    public static boolean isModuleActive() {
        return service != null;
    }
}
