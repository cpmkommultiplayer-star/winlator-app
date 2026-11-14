package com.winlator;

import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.view.View;
import android.widget.ProgressBar;
import android.widget.TextView;

import androidx.appcompat.app.AppCompatActivity;

import com.winlator.core.AppUtils;

import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * Installs and tests the Linux runtime. Deliberately separate from the Wine containers:
 * this screen can do nothing to a working container, and a working container can do
 * nothing to it.
 */
public class LinuxRuntimeActivity extends AppCompatActivity {
    private final ExecutorService worker = Executors.newSingleThreadExecutor();
    private final Handler main = new Handler(Looper.getMainLooper());
    private TextView statusView;
    private TextView logView;
    private ProgressBar progressBar;
    private TextView installButton;
    private TextView testButton;
    private boolean busy = false;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        AppUtils.setActivityTheme(this);
        super.onCreate(savedInstanceState);
        setContentView(R.layout.linux_runtime);

        statusView = findViewById(R.id.TVLinuxStatus);
        logView = findViewById(R.id.TVLinuxLog);
        progressBar = findViewById(R.id.PBLinux);
        installButton = findViewById(R.id.BTLinuxInstall);
        testButton = findViewById(R.id.BTLinuxTest);

        findViewById(R.id.BTLinuxBack).setOnClickListener(v -> finish());
        installButton.setOnClickListener(v -> startInstall());
        testButton.setOnClickListener(v -> runTest());

        refresh();
    }

    @Override
    protected void onDestroy() {
        worker.shutdownNow();
        super.onDestroy();
    }

    private void refresh() {
        boolean installed = LinuxRuntime.isInstalled(this);
        boolean hasArchive = LinuxRuntime.rootfsArchive(this).isFile();
        statusView.setText(installed
            ? "RUNTIME READY\nPRoot + Arch Linux ARM installed in app storage"
            : hasArchive
                ? "ARCHIVE DOWNLOADED\n" + LinuxRuntime.rootfsArchive(this).length() + " bytes, not extracted yet"
                : "NOT INSTALLED\nAbout 830 MB will be downloaded from archlinuxarm.org");
        testButton.setAlpha(installed ? 1f : 0.4f);
        installButton.setAlpha(busy ? 0.4f : 1f);
    }

    private void setBusy(boolean value) {
        busy = value;
        installButton.setEnabled(!value);
        testButton.setEnabled(!value && LinuxRuntime.isInstalled(this));
        progressBar.setVisibility(value ? View.VISIBLE : View.GONE);
        refresh();
    }

    private void setStage(String text) {
        main.post(() -> statusView.setText(text));
    }

    private void startInstall() {
        if (busy) return;
        setBusy(true);
        statusView.setText("INSTALLING");
        logView.setText("");

        worker.execute(() -> {
            if (!LinuxRuntime.installProot(this)) {
                main.post(() -> { setBusy(false); statusView.setText("FAILED: could not install proot"); });
                return;
            }
            setStage("DOWNLOADING ROOTFS");

            LinuxRuntime.Progress progress = (stage, done, total) -> {
                int percent = total > 0 ? (int)(done * 100 / total) : 0;
                main.post(() -> {
                    progressBar.setProgress(percent);
                    statusView.setText(stage + " " + (done / (1024 * 1024)) + " / " + (total / (1024 * 1024)) + " MB");
                });
            };

            if (!LinuxRuntime.downloadRootfs(this, progress)) {
                main.post(() -> { setBusy(false); statusView.setText("FAILED: rootfs download"); });
                return;
            }
            setStage("EXTRACTING");
            if (!LinuxRuntime.extractRootfs(this, progress)) {
                main.post(() -> { setBusy(false); statusView.setText("FAILED: rootfs extraction"); });
                return;
            }

            main.post(() -> {
                setBusy(false);
                refresh();
            });
        });
    }

    private void runTest() {
        if (busy || !LinuxRuntime.isInstalled(this)) return;
        setBusy(true);
        logView.setText("running test...");
        worker.execute(() -> {
            String output = LinuxRuntime.run(this, "uname -a; id; cat /etc/os-release | head -2; ls /");
            main.post(() -> {
                logView.setText(output);
                setBusy(false);
            });
        });
    }
}
