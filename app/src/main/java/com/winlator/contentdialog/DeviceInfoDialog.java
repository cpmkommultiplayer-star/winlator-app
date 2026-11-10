package com.winlator.contentdialog;

import android.app.Activity;
import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.Context;
import android.view.View;
import android.widget.Button;
import android.widget.TextView;

import com.winlator.R;
import com.winlator.core.AppUtils;
import com.winlator.core.DeviceScan;

public class DeviceInfoDialog extends ContentDialog {
    public DeviceInfoDialog(Activity activity) {
        super(activity, R.layout.device_info_dialog);
        setTitle(R.string.device_info);
        setIcon(R.drawable.icon_popup_menu_info);

        findViewById(R.id.BTCancel).setVisibility(View.GONE);
        ((Button)findViewById(R.id.BTConfirm)).setText(R.string.copy);

        final TextView tvReport = findViewById(R.id.TVDeviceReport);
        tvReport.setText(R.string.device_info_scanning);

        // The GPU part of the scan builds a temporary EGL context, so it must stay off the UI thread.
        (new Thread(() -> {
            final String report = DeviceScan.buildReport(activity);
            activity.runOnUiThread(() -> {
                tvReport.setText(report);
                DeviceScan.log(report);
            });
        })).start();

        setOnConfirmCallback(() -> {
            ClipboardManager clipboardManager = (ClipboardManager)activity.getSystemService(Context.CLIPBOARD_SERVICE);
            clipboardManager.setPrimaryClip(ClipData.newPlainText("device_info", tvReport.getText()));
            AppUtils.showToast(activity, R.string.copied_to_clipboard);
        });
    }
}
