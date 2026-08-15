package com.wuwa.config.manager;

import android.app.Application;
import android.content.SharedPreferences;

import com.wuwa.config.manager.diagnostics.AppLogger;

public final class WuWaApplication extends Application {
    @Override
    public void onCreate() {
        super.onCreate();
        installRotationCrashRecovery();
        AppLogger.initialize(this);
    }

    private void installRotationCrashRecovery() {
        Thread.UncaughtExceptionHandler previous =
                Thread.getDefaultUncaughtExceptionHandler();
        Thread.setDefaultUncaughtExceptionHandler((thread, throwable) -> {
            try {
                SharedPreferences preferences = getSharedPreferences(
                        "wuwa_config_preferences", MODE_PRIVATE);
                if (preferences.getBoolean("rotation_session_active", false)) {
                    RotationControllerService.requestRecovery(this);
                }
            } catch (RuntimeException ignored) {
                // The external rescue marker remains intact for the next launch.
            }
            if (previous != null) previous.uncaughtException(thread, throwable);
        });
    }
}
