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
abstract class ThermalMonitor {
