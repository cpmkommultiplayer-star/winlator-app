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
    private TextView protonButton;
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
        protonButton = findViewById(R.id.BTLinuxWine);

        findViewById(R.id.BTLinuxBack).setOnClickListener(v -> finish());
        installButton.setOnClickListener(v -> startInstall());
        testButton.setOnClickListener(v -> runTest());
        protonButton.setOnClickListener(v -> startProtonInstall());

        refresh();
    }

    @Override
    protected void onDestroy() {
        worker.shutdownNow();
        super.onDestroy();
    }

    private void refresh() {
        boolean installed = LinuxRuntime.isInstalled(this);
        boolean proton = LinuxRuntime.isProtonInstalled(this);
        boolean hasArchive = LinuxRuntime.rootfsArchive(this).isFile();

        String text;
        if (!installed) {
            text = hasArchive
                ? "ARCHIVE DOWNLOADED\n" + LinuxRuntime.rootfsArchive(this).length() + " bytes, not extracted yet"
                : "NOT INSTALLED\nAbout 830 MB will be downloaded from archlinuxarm.org";
        }
        else if (!proton) {
            text = "RUNTIME READY\nPRoot + Arch Linux ARM installed. box64 "
                + (LinuxRuntime.box64File(this).isFile() ? "present" : "missing")
                + ", Proton not installed yet.";
        }
        else {
            text = "READY\nPRoot + Arch Linux ARM + Proton GE aarch64\n"
                + "Wine here is native arm64; RUN TEST prints its version.";
        }

        statusView.setText(text);
        testButton.setAlpha(installed ? 1f : 0.4f);
        protonButton.setAlpha(installed && !proton ? 1f : 0.4f);
        installButton.setAlpha(busy ? 0.4f : 1f);
    }

    private void setBusy(boolean value) {
        busy = value;
        installButton.setEnabled(!value);
        testButton.setEnabled(!value && LinuxRuntime.isInstalled(this));
        protonButton.setEnabled(!value && LinuxRuntime.isInstalled(this));
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
            if (!LinuxRuntime.installProot(this) || !LinuxRuntime.installBox64(this)) {
                main.post(() -> { setBusy(false); statusView.setText("FAILED: could not install proot/box64"); });
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

    private void startProtonInstall() {
        if (busy || !LinuxRuntime.isInstalled(this)) return;
        setBusy(true);
        statusView.setText("DOWNLOADING PROTON 650 MB");

        worker.execute(() -> {
            LinuxRuntime.Progress progress = (stage, done, total) -> {
                int percent = total > 0 ? (int)(done * 100 / total) : 0;
                main.post(() -> {
                    progressBar.setProgress(percent);
                    statusView.setText(stage + " " + (done / (1024 * 1024)) + " / " + (total / (1024 * 1024)) + " MB");
                });
            };

            if (!LinuxRuntime.downloadProton(this, progress)) {
                main.post(() -> { setBusy(false); statusView.setText("FAILED: proton download or checksum"); });
                return;
            }
            setStage("EXTRACTING PROTON");
            if (!LinuxRuntime.extractProton(this, progress)) {
                main.post(() -> { setBusy(false); statusView.setText("FAILED: proton extraction"); });
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
            String output = LinuxRuntime.run(this,
                "uname -m; id -u; "
                    + "if [ -x /opt/proton/files/bin-arm64/wine ]; then "
                    + "  /opt/proton/files/bin-arm64/wine --version 2>&1 | head -3; "
                    + "  ls /opt/proton/files/share/fex-emu; "
                    + "else echo 'proton not installed'; fi; "
                    + "box64 --version 2>&1 | head -1");
            main.post(() -> {
                logView.setText(output);
                setBusy(false);
            });
        });
    }
}
