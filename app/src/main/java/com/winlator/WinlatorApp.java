package com.winlator;

import android.app.Activity;
import android.app.Application;
import android.os.Bundle;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import com.winlator.core.ThermalGuard;

/**
 * Runs the thermal guard only while a container session is on screen.
 *
 * The session activity is large and has nothing to do with thermals, so the hook is registered
 * here instead of being edited into it. With protection switched off the guard does nothing.
 */
public class WinlatorApp extends Application {
    @Override
    public void onCreate() {
        super.onCreate();

        registerActivityLifecycleCallbacks(new ActivityLifecycleCallbacks() {
            @Override
            public void onActivityResumed(@NonNull Activity activity) {
                if (activity instanceof XServerDisplayActivity) ThermalGuard.start(activity);
            }

            @Override
            public void onActivityPaused(@NonNull Activity activity) {
                if (activity instanceof XServerDisplayActivity) ThermalGuard.stop();
            }

            @Override
            public void onActivityCreated(@NonNull Activity activity, @Nullable Bundle savedInstanceState) {}

            @Override
            public void onActivityStarted(@NonNull Activity activity) {}

            @Override
            public void onActivityStopped(@NonNull Activity activity) {}

            @Override
            public void onActivitySaveInstanceState(@NonNull Activity activity, @NonNull Bundle outState) {}

            @Override
            public void onActivityDestroyed(@NonNull Activity activity) {}
        });
    }
}
