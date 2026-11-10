package com.winlator.contentdialog;

import android.widget.ArrayAdapter;
import android.widget.Spinner;
import android.widget.TextView;

import com.winlator.R;
import com.winlator.XServerDisplayActivity;
import com.winlator.container.Container;
import com.winlator.core.Upscaler;
import com.winlator.widget.SeekBar;

public class FSRSettingsDialog extends ContentDialog {
    private static final String[] MODE_ENTRIES = {"Off", "FSR 1 (EASU + RCAS)", "Bilinear + RCAS"};

    public FSRSettingsDialog(XServerDisplayActivity activity) {
        super(activity, R.layout.fsr_settings_dialog);
        setTitle("Upscaler");
        setIcon(R.drawable.icon_screen_effect);

        final Container container = activity.getContainer();

        final Spinner sFSRMode = findViewById(R.id.SFSRMode);
        final Spinner sFSRScale = findViewById(R.id.SFSRScale);
        final SeekBar sbSharpness = findViewById(R.id.SBFSRSharpness);
        final TextView tvInfo = findViewById(R.id.TVFSRInfo);

        sFSRMode.setAdapter(new ArrayAdapter<>(activity, android.R.layout.simple_spinner_dropdown_item, MODE_ENTRIES));

        final String[] scaleEntries = new String[Upscaler.SCALES.length];
        for (int i = 0; i < Upscaler.SCALES.length; i++) scaleEntries[i] = Upscaler.SCALES[i]+"%";
        sFSRScale.setAdapter(new ArrayAdapter<>(activity, android.R.layout.simple_spinner_dropdown_item, scaleEntries));

        sFSRMode.setSelection(Upscaler.getMode(container));
        sFSRScale.setSelection(indexOfScale(Upscaler.getScale(container)));
        sbSharpness.setValue(Upscaler.getSharpness(container));

        tvInfo.setText("The session is rendered at the scaled resolution and the upscaler brings it back to the panel, "
            +"so a restart is required for changes to take effect. Best results when the container screen size matches the device resolution.");

        setOnConfirmCallback(() -> {
            int mode = sFSRMode.getSelectedItemPosition();
            int scale = Upscaler.SCALES[Math.max(0, sFSRScale.getSelectedItemPosition())];
            int sharpness = (int)sbSharpness.getValue();
            Upscaler.save(container, mode, scale, sharpness);
        });
    }

    private static int indexOfScale(int scale) {
        for (int i = 0; i < Upscaler.SCALES.length; i++) {
            if (Upscaler.SCALES[i] == scale) return i;
        }
        return 0;
    }
}
