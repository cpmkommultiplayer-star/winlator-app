package com.winlator.core;

import android.content.Context;
import android.content.SharedPreferences;

import androidx.preference.PreferenceManager;

import java.io.File;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Locale;

/**
 * Reads the device's thermal sensors.
 *
 * The existing {@link CPUStatus#getTemperature()} probes a fixed list of paths and stops at the
 * first readable one, so on most devices it reports a single number that may not be the hottest
 * part of the SoC. This scans every registered thermal zone instead, which is read-only and works
 * without root. Zones whose value cannot be parsed are skipped rather than reported as 0 degC.
 */
public abstract class ThermalMonitor {
    public static final int LEVEL_COOL = 0;
    public static final int LEVEL_WARM = 1;
    public static final int LEVEL_HOT = 2;
    public static final int LEVEL_CRITICAL = 3;

    public static final String PREF_PROTECTION = "thermal_protection_enabled";
    private static final String PREF_WARN = "thermal_warn_celsius";
    private static final String PREF_CRITICAL = "thermal_critical_celsius";

    public static final int DEFAULT_WARN_CELSIUS = 70;
    public static final int DEFAULT_CRITICAL_CELSIUS = 80;

    public static class Sensor implements Comparable<Sensor> {
        public final String name;
        public final int celsius;

        Sensor(String name, int celsius) {
            this.name = name;
            this.celsius = celsius;
        }

        @Override
        public int compareTo(Sensor other) {
            return Integer.compare(other.celsius, celsius);
        }
    }

    /** Every readable thermal zone, hottest first. Empty when the device exposes none. */
    public static List<Sensor> scan() {
        ArrayList<Sensor> sensors = new ArrayList<>();
        File dir = new File("/sys/class/thermal");
        File[] zones = dir.listFiles((d, name) -> name.startsWith("thermal_zone"));
        if (zones == null) return sensors;

        for (File zone : zones) {
            int celsius = readCelsius(new File(zone, "temp"));
            if (celsius <= 0) continue;
            String name = readText(new File(zone, "type"));
            sensors.add(new Sensor(name.isEmpty() ? zone.getName() : name, celsius));
        }

        Collections.sort(sensors);
        return sensors;
    }

    /** Hottest zone in degC, or 0 when the device exposes no readable sensor. */
    public static int hottestCelsius() {
        List<Sensor> sensors = scan();
        return sensors.isEmpty() ? 0 : sensors.get(0).celsius;
    }

    /** Battery temperature in degC, or 0 when unavailable. */
    public static int batteryCelsius() {
        final String[] paths = {
            "/sys/class/power_supply/battery/temp",
            "/sys/class/power_supply/bms/temp"
        };

        for (String path : paths) {
            int raw = FileUtils.readInt(path);
            if (raw <= 0) continue;
            // The power supply class reports tenths of a degree; some vendor kernels use milli.
            int celsius = raw >= 1000 ? raw / 1000 : raw / 10;
            if (celsius >= 5 && celsius <= 150) return celsius;
        }

        return 0;
    }

    /**
     * sysfs thermal values are milli-degrees on most kernels and tenths on a few older ones.
     * Anything outside a plausible range is treated as unreadable, because a bogus 0 degC would
     * read as cool and silently disable the warning.
     */
    private static int readCelsius(File file) {
        int raw = FileUtils.readInt(file.getAbsolutePath());
        if (raw <= 0) return 0;

        int celsius;
        if (raw >= 10000) celsius = raw / 1000;
        else if (raw >= 1000) celsius = raw / 100;
        else if (raw >= 100) celsius = raw / 10;
        else celsius = raw;

        return celsius >= 5 && celsius <= 150 ? celsius : 0;
    }

    private static String readText(File file) {
        try {
            return FileUtils.readString(file).trim();
        }
        catch (Exception e) {
            return "";
        }
    }

    public static int level(Context context, int celsius) {
        if (celsius <= 0) return LEVEL_COOL;
        int warn = getWarnCelsius(context);
        if (celsius >= getCriticalCelsius(context)) return LEVEL_CRITICAL;
        if (celsius >= warn) return LEVEL_HOT;
        if (celsius >= warn - 10) return LEVEL_WARM;
        return LEVEL_COOL;
    }

    public static String levelName(Context context, int celsius) {
        switch (level(context, celsius)) {
            case LEVEL_WARM: return context.getString(com.winlator.R.string.thermal_level_warm);
            case LEVEL_HOT: return context.getString(com.winlator.R.string.thermal_level_hot);
            case LEVEL_CRITICAL: return context.getString(com.winlator.R.string.thermal_level_critical);
            default: return context.getString(com.winlator.R.string.thermal_level_cool);
        }
    }

    public static String formatSensors(List<Sensor> sensors) {
        if (sensors.isEmpty()) return "";
        StringBuilder out = new StringBuilder();
        for (Sensor sensor : sensors) {
            out.append(String.format(Locale.ENGLISH, "%-22s %3d\u00baC\n", sensor.name, sensor.celsius));
        }
        return out.toString().trim();
    }

    private static SharedPreferences prefs(Context context) {
        return PreferenceManager.getDefaultSharedPreferences(context);
    }

    public static int getWarnCelsius(Context context) {
        return prefs(context).getInt(PREF_WARN, DEFAULT_WARN_CELSIUS);
    }

    public static int getCriticalCelsius(Context context) {
        return prefs(context).getInt(PREF_CRITICAL, DEFAULT_CRITICAL_CELSIUS);
    }

    public static void setWarnCelsius(Context context, int celsius) {
        prefs(context).edit().putInt(PREF_WARN, celsius).apply();
    }

    public static void setCriticalCelsius(Context context, int celsius) {
        prefs(context).edit().putInt(PREF_CRITICAL, celsius).apply();
    }

    public static boolean isProtectionEnabled(Context context) {
        return prefs(context).getBoolean(PREF_PROTECTION, false);
    }

    public static void setProtectionEnabled(Context context, boolean enabled) {
        prefs(context).edit().putBoolean(PREF_PROTECTION, enabled).apply();
    }
}
