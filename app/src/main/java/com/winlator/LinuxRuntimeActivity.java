package com.winlator;

import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.view.KeyEvent;
import android.view.MotionEvent;
import android.view.View;
import android.widget.ProgressBar;
import android.widget.TextView;

import androidx.appcompat.app.AppCompatActivity;

import com.winlator.container.Container;
import com.winlator.core.AppUtils;
import com.winlator.widget.TouchpadView;
import com.winlator.widget.XServerView;
import com.winlator.xconnector.UnixSocketConfig;
import com.winlator.xenvironment.XEnvironment;
import com.winlator.xenvironment.components.PulseAudioComponent;
import com.winlator.xenvironment.components.XServerComponent;
import com.winlator.xserver.ScreenInfo;
import com.winlator.xserver.XServer;

import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * Installs the runtime and hosts a display for it.
 *
 * The X server and the PulseAudio server are the same components a container session uses.
 * Their sockets are created inside the runtime's own rootfs tree, so /tmp/.X11-unix/X0 and
 * /tmp/.sound/PS0 exist at exactly the paths Proton looks for; no extra bind mounts are
 * needed and the container path is untouched.
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
    private TextView runButton;
    private TextView commandView;
    private View displayContainer;
    private boolean busy = false;

    private XServer xServer;
    private XServerView xServerView;
    private TouchpadView touchpadView;
    private XEnvironment environment;

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
        runButton = findViewById(R.id.BTLinuxRun);
        commandView = findViewById(R.id.ETLinuxCommand);
        displayContainer = findViewById(R.id.FLRuntimeDisplay);

        findViewById(R.id.BTLinuxBack).setOnClickListener(v -> finish());
        installButton.setOnClickListener(v -> startInstall());
        testButton.setOnClickListener(v -> runTest());
        protonButton.setOnClickListener(v -> startProtonInstall());
        runButton.setOnClickListener(v -> runCommand());

        refresh();
    }

    @Override
    protected void onResume() {
        super.onResume();
        if (LinuxRuntime.isInstalled(this)) startDisplay();
        refresh();
    }

    @Override
    protected void onDestroy() {
        if (environment != null) environment.stopEnvironmentComponents();
        worker.shutdownNow();
        super.onDestroy();
    }

    @Override
    public boolean dispatchKeyEvent(KeyEvent event) {
        if (xServer != null && xServer.keyboard.onKeyEvent(event)) return true;
        return super.dispatchKeyEvent(event);
    }

    @Override
    public boolean dispatchGenericMotionEvent(MotionEvent event) {
        return !(touchpadView != null && touchpadView.onExternalMouseEvent(event)) && super.dispatchGenericMotionEvent(event);
    }

    /** Starts the app's own X server and PulseAudio against the runtime rootfs. */
    private void startDisplay() {
        if (xServer != null) return;

        String rootPath = LinuxRuntime.rootfsDir(this).getPath();
        ScreenInfo screenInfo = new ScreenInfo(Container.DEFAULT_SCREEN_SIZE);

        xServer = new XServer(this, screenInfo);
        xServerView = new XServerView(this, xServer);
        xServer.setRenderer(xServerView.getRenderer());
        ((android.view.ViewGroup)displayContainer).addView(xServerView);

        touchpadView = new TouchpadView(this, xServer, true);
        touchpadView.setSensitivity(1.0f);
        ((android.view.ViewGroup)displayContainer).addView(touchpadView);

        environment = new XEnvironment(this, null);
        environment.addComponent(new XServerComponent(xServer, UnixSocketConfig.create(rootPath, "/tmp/.X11-unix/X0")));
        environment.addComponent(new PulseAudioComponent(UnixSocketConfig.create(rootPath, "/tmp/.sound/PS0")));
        environment.startEnvironmentComponents();
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
                + "Display :0 and PulseAudio are live for this screen.";
        }

        statusView.setText(text);
        testButton.setAlpha(installed ? 1f : 0.4f);
        protonButton.setAlpha(installed && !proton ? 1f : 0.4f);
        installButton.setAlpha(busy ? 0.4f : 1f);
        runButton.setAlpha(installed ? 1f : 0.4f);
    }

    private void setBusy(boolean value) {
        busy = value;
        installButton.setEnabled(!value);
        testButton.setEnabled(!value && LinuxRuntime.isInstalled(this));
        protonButton.setEnabled(!value && LinuxRuntime.isInstalled(this));
        runButton.setEnabled(!value && LinuxRuntime.isInstalled(this));
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
                startDisplay();
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
                "uname -m; id -u; echo DISPLAY=$DISPLAY; echo PULSE=$PULSE_SERVER; "
                    + "if [ -x /opt/proton/files/bin-arm64/wine ]; then "
                    + "  /opt/proton/files/bin-arm64/wine --version 2>&1 | head -3; "
                    + "  ls /opt/proton/files/share/fex-emu; "
                    + "else echo 'proton not installed'; fi");
            main.post(() -> {
                logView.setText(output);
                setBusy(false);
            });
        });
    }

    private void runCommand() {
        if (busy || !LinuxRuntime.isInstalled(this)) return;
        String command = commandView.getText().toString().trim();
        if (command.isEmpty()) return;

        setBusy(true);
        logView.setText("running: " + command);
        worker.execute(() -> {
            String output = LinuxRuntime.run(this, command);
            main.post(() -> {
                logView.setText(output.isEmpty() ? "(no output)" : output);
                setBusy(false);
            });
        });
    }
}
