package com.winlator.core;

import android.app.Activity;
import android.os.Handler;
import android.os.Looper;

import com.winlator.R;

/**
 * Watches the hottest thermal zone while a container session is in the foreground.
 *
 * Protection is opt-in. With it disabled (the default) no sampling happens at all. It only ever
 * ends the session if the user asked for it: nothing here touches the OS, the governor or any
 * thermal setting, which is not possible without root.
 */
public abstract class ThermalGuard {
    private static final long INTERVAL_MS = 8000;
    private static final int HYSTERESIS_CELSIUS = 3;

    private static Handler handler;
    private static Activity activity;
    private static boolean warned;

    private static final Runnable TICK = new Runnable() {
        @Override
        public void run() {
            Activity owner = activity;
            Handler current = handler;
            if (owner == null || current == null || owner.isFinishing()) return;

            int celsius = ThermalMonitor.hottestCelsius();
            int level = ThermalMonitor.level(owner, celsius);

            if (level == ThermalMonitor.LEVEL_CRITICAL) {
                AppUtils.showToast(owner, owner.getString(R.string.thermal_critical_close, celsius));
                stop();
                owner.finish();
                return;
            }

            if (level >= ThermalMonitor.LEVEL_HOT) {
                if (!warned) {
                    warned = true;
                    AppUtils.showToast(owner, owner.getString(R.string.thermal_hot_warning, celsius));
                }
            }
            else if (warned && celsius < ThermalMonitor.getWarnCelsius(owner) - HYSTERESIS_CELSIUS) {
                warned = false;
            }

            current.postDelayed(this, INTERVAL_MS);
        }
    };

    public static void start(Activity owner) {
        stop();
        if (!ThermalMonitor.isProtectionEnabled(owner)) return;
        activity = owner;
        warned = false;
        handler = new Handler(Looper.getMainLooper());
        handler.postDelayed(TICK, INTERVAL_MS);
    }

    public static void stop() {
        if (handler != null) handler.removeCallbacksAndMessages(null);
        handler = null;
        activity = null;
        warned = false;
    }
}
