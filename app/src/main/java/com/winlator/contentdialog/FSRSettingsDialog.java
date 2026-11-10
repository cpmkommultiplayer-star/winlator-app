package com.winlator.contentdialog;

import android.view.View;
import android.widget.AdapterView;
import android.widget.ArrayAdapter;
import android.widget.Button;
import android.widget.Spinner;
import android.widget.TextView;

import com.winlator.R;
import com.winlator.XServerDisplayActivity;
import com.winlator.container.Container;
import com.winlator.core.AppUtils;
import com.winlator.core.GameProfile;
import com.winlator.core.Upscaler;
import com.winlator.widget.SeekBar;

import java.util.ArrayList;

public class FSRSettingsDialog extends ContentDialog {
    private static final String[] MODE_ENTRIES = {"Off", "FSR 1 (EASU + RCAS)", "Bilinear + RCAS"};

    private final XServerDisplayActivity activity;
    private final Container container;
    private final ArrayList<GameProfile> profiles;
    private final Spinner sProfile;
    private final Spinner sFSRMode;
    private final Spinner sFSRScale;
    private final SeekBar sbSharpness;
    private final TextView tvInfo;

    public FSRSettingsDialog(XServerDisplayActivity activity) {
        super(activity, R.layout.fsr_settings_dialog);
        setTitle("Upscaler");
        setIcon(R.drawable.icon_screen_effect);

        this.activity = activity;
        this.container = activity.getContainer();
        this.profiles = GameProfile.load(activity);

        sProfile = findViewById(R.id.SFSRProfile);
        sFSRMode = findViewById(R.id.SFSRMode);
        sFSRScale = findViewById(R.id.SFSRScale);
        sbSharpness = findViewById(R.id.SBFSRSharpness);
        tvInfo = findViewById(R.id.TVFSRInfo);

        sFSRMode.setAdapter(new ArrayAdapter<>(activity, android.R.layout.simple_spinner_dropdown_item, MODE_ENTRIES));

        final String[] scaleEntries = new String[Upscaler.SCALES.length];
        for (int i = 0; i < Upscaler.SCALES.length; i++) scaleEntries[i] = Upscaler.SCALES[i]+"%";
        sFSRScale.setAdapter(new ArrayAdapter<>(activity, android.R.layout.simple_spinner_dropdown_item, scaleEntries));

        sFSRMode.setSelection(Upscaler.getMode(container));
        sFSRScale.setSelection(indexOfScale(Upscaler.getScale(container)));
        sbSharpness.setValue(Upscaler.getSharpness(container));

        AdapterView.OnItemSelectedListener listener = new AdapterView.OnItemSelectedListener() {
            @Override
            public void onItemSelected(AdapterView<?> parent, View view, int position, long id) {
                updateInfo();
            }

            @Override
            public void onNothingSelected(AdapterView<?> parent) {}
        };

        sFSRMode.setOnItemSelectedListener(listener);
        sFSRScale.setOnItemSelectedListener(listener);

        ((Button)findViewById(R.id.BTApplyProfile)).setOnClickListener((v) -> applySelectedProfile());
        ((Button)findViewById(R.id.BTSaveProfile)).setOnClickListener((v) -> promptProfileName());

        refreshProfileSpinner(-1);
        updateInfo();

        setOnConfirmCallback(() -> {
            int mode = sFSRMode.getSelectedItemPosition();
            int scale = Upscaler.SCALES[Math.max(0, sFSRScale.getSelectedItemPosition())];
            int sharpness = (int)sbSharpness.getValue();
            Upscaler.save(container, mode, scale, sharpness);
        });
    }

    private void refreshProfileSpinner(int selectIndex) {
        String[] entries;
        if (profiles.isEmpty()) {
            entries = new String[]{activity.getString(R.string.upscaler_no_profiles)};
        }
        else {
            entries = new String[profiles.size()];
            for (int i = 0; i < profiles.size(); i++) entries[i] = profiles.get(i).name;
        }

        sProfile.setAdapter(new ArrayAdapter<>(activity, android.R.layout.simple_spinner_dropdown_item, entries));
        if (selectIndex >= 0 && selectIndex < entries.length) sProfile.setSelection(selectIndex);
    }

    private void applySelectedProfile() {
        int index = sProfile.getSelectedItemPosition();
        if (index < 0 || index >= profiles.size()) return;

        GameProfile profile = profiles.get(index);
        sFSRMode.setSelection(profile.mode);
        sFSRScale.setSelection(indexOfScale(profile.scale));
        sbSharpness.setValue(profile.sharpness);
        updateInfo();
    }

    private void promptProfileName() {
        final int mode = sFSRMode.getSelectedItemPosition();
        final int scale = Upscaler.SCALES[Math.max(0, sFSRScale.getSelectedItemPosition())];
        final int sharpness = (int)sbSharpness.getValue();

        ContentDialog.prompt(activity, R.string.upscaler_profile_name, container.getName(), (name) -> {
            GameProfile.save(activity, profiles, new GameProfile(name, mode, scale, sharpness));
            refreshProfileSpinner(indexOfProfile(name));
            AppUtils.showToast(activity, R.string.upscaler_profile_saved);
        });
    }

    private int indexOfProfile(String name) {
        for (int i = 0; i < profiles.size(); i++) {
            if (profiles.get(i).name.equalsIgnoreCase(name)) return i;
        }
        return -1;
    }

    private void updateInfo() {
        final String base = Upscaler.getBaseScreenSize(container);
        final int mode = sFSRMode.getSelectedItemPosition();
        final int scale = Upscaler.SCALES[Math.max(0, sFSRScale.getSelectedItemPosition())];
        final String target = mode == Upscaler.MODE_OFF ? base : Upscaler.scaleScreenSize(base, scale);

        String sizes = mode == Upscaler.MODE_OFF ? "Screen size: "+base : "Screen size: "+base+" -> "+target;
        tvInfo.setText(sizes+"\n\nThe game renders at the smaller size and the upscaler brings it back to the panel, which is where the speed comes from. "
            +"This changes the container's screen size; switching Off restores "+base+". A restart is required for changes to take effect."
            +"\n\nThis session: "+Upscaler.getLastStatus());
    }

    private static int indexOfScale(int scale) {
        for (int i = 0; i < Upscaler.SCALES.length; i++) {
            if (Upscaler.SCALES[i] == scale) return i;
        }
        return 0;
    }
}
