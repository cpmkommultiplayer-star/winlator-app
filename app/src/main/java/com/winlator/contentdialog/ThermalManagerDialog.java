package com.winlator.contentdialog;

import android.app.Activity;
import android.os.Handler;
import android.os.Looper;
import android.view.View;
import android.widget.AdapterView;
import android.widget.ArrayAdapter;
import android.widget.Button;
import android.widget.CheckBox;
import android.widget.Spinner;
import android.widget.TextView;

import com.winlator.R;
import com.winlator.core.ThermalMonitor;

import java.util.ArrayList;
import java.util.List;

public class ThermalManagerDialog extends ContentDialog {
    private static final int THRESHOLD_MIN = 45;
    private static final int THRESHOLD_MAX = 90;
    private static final int THRESHOLD_STEP = 5;
    private static final long REFRESH_MS = 1500;

    private final Activity activity;
    private final Handler handler = new Handler(Looper.getMainLooper());
    private final TextView TVThermalNow;
    private final TextView TVThermalBattery;
    private final TextView TVThermalSensors;
    private final CheckBox CBThermalProtection;
    private final Spinner SPThermalWarn;
    private final Spinner SPThermalCritical;
    private boolean initialising = true;

    private final Runnable refresh = new Runnable() {
        @Override
        public void run() {
            List<ThermalMonitor.Sensor> sensors = ThermalMonitor.scan();

            if (sensors.isEmpty()) {
                TVThermalNow.setText(R.string.thermal_no_sensors);
                TVThermalSensors.setText("");
            }
            else {
                int hottest = sensors.get(0).celsius;
                TVThermalNow.setText(activity.getString(R.string.thermal_now_format, hottest, ThermalMonitor.levelName(activity, hottest)));
                TVThermalSensors.setText(ThermalMonitor.formatSensors(sensors));
            }

            int battery = ThermalMonitor.batteryCelsius();
            TVThermalBattery.setText(battery > 0 ?
                activity.getString(R.string.thermal_battery_format, battery) :
                activity.getString(R.string.thermal_battery_unknown));

            handler.postDelayed(this, REFRESH_MS);
        }
    };

    public ThermalManagerDialog(Activity activity) {
        super(activity, R.layout.thermal_manager_dialog);
        this.activity = activity;

        setTitle(R.string.thermal_manager);
        setIcon(R.drawable.icon_temperature);

        findViewById(R.id.BTCancel).setVisibility(View.GONE);
        ((Button)findViewById(R.id.BTConfirm)).setText(R.string.thermal_close);

        TVThermalNow = findViewById(R.id.TVThermalNow);
        TVThermalBattery = findViewById(R.id.TVThermalBattery);
        TVThermalSensors = findViewById(R.id.TVThermalSensors);
        CBThermalProtection = findViewById(R.id.CBThermalProtection);
        SPThermalWarn = findViewById(R.id.SPThermalWarn);
        SPThermalCritical = findViewById(R.id.SPThermalCritical);

        CBThermalProtection.setChecked(ThermalMonitor.isProtectionEnabled(activity));
        CBThermalProtection.setOnCheckedChangeListener((view, checked) ->
            ThermalMonitor.setProtectionEnabled(activity, checked));

        setupSpinner(SPThermalWarn, ThermalMonitor.getWarnCelsius(activity));
        setupSpinner(SPThermalCritical, ThermalMonitor.getCriticalCelsius(activity));

        SPThermalWarn.setOnItemSelectedListener(new AdapterView.OnItemSelectedListener() {
            @Override
            public void onItemSelected(AdapterView<?> parent, View view, int position, long id) {
                if (initialising) return;
                int warn = celsiusAt(position);
                ThermalMonitor.setWarnCelsius(activity, warn);
                // Keep the two thresholds ordered, otherwise critical would sit below warn.
                int critical = ThermalMonitor.getCriticalCelsius(activity);
                if (critical <= warn) {
                    critical = Math.min(THRESHOLD_MAX, warn + THRESHOLD_STEP);
                    thermalQuietly(SPThermalCritical, critical);
                    ThermalMonitor.setCriticalCelsius(activity, critical);
                }
            }

            @Override
            public void onNothingSelected(AdapterView<?> parent) {}
        });

        SPThermalCritical.setOnItemSelectedListener(new AdapterView.OnItemSelectedListener() {
            @Override
            public void onItemSelected(AdapterView<?> parent, View view, int position, long id) {
                if (initialising) return;
                int critical = celsiusAt(position);
                ThermalMonitor.setCriticalCelsius(activity, critical);
                int warn = ThermalMonitor.getWarnCelsius(activity);
                if (warn >= critical) {
                    warn = Math.max(THRESHOLD_MIN, critical - THRESHOLD_STEP);
                    thermalQuietly(SPThermalWarn, warn);
                    ThermalMonitor.setWarnCelsius(activity, warn);
                }
            }

            @Override
            public void onNothingSelected(AdapterView<?> parent) {}
        });

        initialising = false;
        handler.post(refresh);
    }

    private static int celsiusAt(int position) {
        return THRESHOLD_MIN + position * THRESHOLD_STEP;
    }

    private int positionOf(int celsius) {
        int position = Math.round((celsius - THRESHOLD_MIN) / (float)THRESHOLD_STEP);
        int last = (THRESHOLD_MAX - THRESHOLD_MIN) / THRESHOLD_STEP;
        return Math.max(0, Math.min(last, position));
    }

    private void setupSpinner(Spinner spinner, int celsius) {
        ArrayList<String> items = new ArrayList<>();
        for (int c = THRESHOLD_MIN; c <= THRESHOLD_MAX; c += THRESHOLD_STEP) {
            items.add(activity.getString(R.string.thermal_celsius_format, c));
        }
        spinner.setAdapter(new ArrayAdapter<>(activity, android.R.layout.simple_spinner_dropdown_item, items));
        spinner.setSelection(positionOf(celsius), false);
    }

    /** Moves a spinner without re-entering the threshold listeners. */
    private void thermalQuietly(Spinner spinner, int celsius) {
        initialising = true;
        spinner.setSelection(positionOf(celsius), false);
        initialising = false;
    }

    @Override
    public void dismiss() {
        handler.removeCallbacksAndMessages(null);
        super.dismiss();
    }
}
