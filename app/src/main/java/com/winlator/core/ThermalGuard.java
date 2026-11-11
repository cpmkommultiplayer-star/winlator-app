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
 *
 * A single reading is never enough to end a session. Vendor kernels occasionally return nonsense
 * from a sensor, and killing a session because one sample said 140 degC once would be worse than
 * the heat it was meant to prevent, so the session only closes after the same reading repeats.
 */
public abstract class ThermalGuard {
    private static final long INTERVAL_MS = 8000;
    private static final int HYSTERESIS_CELSIUS = 3;
    private static final int CRITICAL_SAMPLES = 3;

    private static Handler handler;
    private static Activity activity;
    private static boolean warned;
    private static int criticalStreak;

    private static final Runnable TICK = new Runnable() {
        @Override
        public void run() {
            Activity owner = activity;
            Handler current = handler;
            if (owner == null || current == null || owner.isFinishing()) return;

            int celsius = ThermalMonitor.hottestCelsius();
            int level = ThermalMonitor.level(owner, celsius);

            if (level == ThermalMonitor.LEVEL_CRITICAL) {
                criticalStreak++;
                if (criticalStreak >= CRITICAL_SAMPLES) {
                    AppUtils.showToast(owner, owner.getString(R.string.thermal_critical_close, celsius));
                    stop();
                    owner.finish();
                    return;
                }
            }
            else {
                criticalStreak = 0;
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
        criticalStreak = 0;
        handler = new Handler(Looper.getMainLooper());
        handler.postDelayed(TICK, INTERVAL_MS);
    }

    public static void stop() {
        if (handler != null) handler.removeCallbacksAndMessages(null);
        handler = null;
        activity = null;
        warned = false;
        criticalStreak = 0;
    }
}
