package com.winlator;

import android.Manifest;
import android.app.AlertDialog;
import android.content.Context;
import android.content.pm.PackageManager;
import android.content.Intent;
import android.content.SharedPreferences;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.view.KeyEvent;
import android.view.LayoutInflater;
import android.view.MotionEvent;
import android.view.View;
import android.view.ViewGroup;
import android.view.inputmethod.InputMethodManager;
import android.widget.LinearLayout;
import android.widget.ProgressBar;
import android.widget.TextView;

import androidx.appcompat.app.AppCompatActivity;
import androidx.core.content.FileProvider;

import com.winlator.core.AppUtils;
import com.winlator.core.FileUtils;
import com.winlator.inputcontrols.ControlsProfile;
import com.winlator.inputcontrols.InputControlsManager;
import com.winlator.widget.InputControlsView;
import com.winlator.widget.TouchpadView;
import com.winlator.widget.XServerView;
import com.winlator.xconnector.UnixSocketConfig;
import com.winlator.xenvironment.RootFS;
import com.winlator.xenvironment.XEnvironment;
import com.winlator.xenvironment.components.PulseAudioComponent;
import com.winlator.xenvironment.components.VirGLRendererComponent;
import com.winlator.xenvironment.components.XServerComponent;
import com.winlator.xserver.ScreenInfo;
import com.winlator.xserver.XServer;

import java.io.File;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * Installs the runtime and hosts a display for it.
 *
 * The X server and the PulseAudio server are the same components a container session uses.
 * Their sockets are created inside the runtime's own rootfs tree, so /tmp/.X11-unix/X0 and
 * /tmp/.sound/PS0 exist at exactly the paths Proton looks for; no extra bind mounts are
 * needed and the container path is untouched.
 *
 * Input has two switches. KEYBOARD shows or hides the soft keyboard over the command field;
 * INPUT turns the pointer layer (overlay plus touchpad) on or off, which is what you want once
 * a game is running and only its own controls should react. Keys are not gated by INPUT.
 */
public class LinuxRuntimeActivity extends AppCompatActivity {
    private static final String PREFS = "linux_runtime";
    private static final String PREF_PROFILE_ID = "input_profile_id";
    private static final int NO_PROFILE = 0;

    /**
     * Guest screen sizes, widest first. On a path where rendering is the bottleneck, pixel count
     * is the cheapest frame rate there is: a game does not care how big the window it fills is.
     */
    private static final String[] RESOLUTIONS = {"1280x720", "960x540", "854x480"};
    private static final String PREF_RESOLUTION = "screen_resolution";

    /**
     * The guest half of the graphics check, run inside the container. It answers three
     * questions: does the renderer socket exist for the guest, can a client resolve libGL at
     * all, and do that library's own dependencies resolve. glxinfo is not in a base Arch
     * rootfs, so if it is absent the probe says so instead of pretending to have looked.
     */
    private static final String GRAPHICS_PROBE =
        "echo '--- GRAPHICS ---'; "
            + "echo \"server=$VIRGL_SERVER_PATH driver=$GALLIUM_DRIVER\"; "
            + "ls -l /tmp/.virgl/V0 2>&1; "
            + "ls -l /usr/lib/libGL.so* 2>&1; "
            + "if [ -e /usr/lib/libGL.so.1 ]; then echo 'libGL.so.1: RESOLVES'; "
            + "else echo 'libGL.so.1: MISSING, no client can load GL'; fi; "
            + "if [ -e /usr/lib/libGL.so.1.7.0 ]; then "
            + "  if /lib/ld-linux-aarch64.so.1 --list /usr/lib/libGL.so.1.7.0 2>&1 | grep -q 'not found'; then "
            + "    echo 'libGL deps: BROKEN'; "
            + "    /lib/ld-linux-aarch64.so.1 --list /usr/lib/libGL.so.1.7.0 2>&1 | grep 'not found'; "
            + "  else echo 'libGL deps: OK'; fi; "
            + "else echo 'libGL deps: UNKNOWN, the library itself is absent'; fi; "
            + "if command -v glxinfo >/dev/null 2>&1; then echo 'GLX:'; "
            + "  glxinfo -B 2>&1 | grep -Ei 'OpenGL renderer|OpenGL version|direct rendering'; "
            + "else echo 'glxinfo: not installed, so no live renderer line yet'; fi";

    private final ExecutorService worker = Executors.newSingleThreadExecutor();
    private final Handler main = new Handler(Looper.getMainLooper());
    private TextView statusView;
    private TextView logView;
    private ProgressBar progressBar;
    private TextView installButton;
    private TextView testButton;
    private TextView protonButton;
    private TextView runButton;
    private TextView profileButton;
    private TextView keyboardButton;
    private TextView inputButton;
    private TextView saveLogButton;
    private TextView resolutionButton;
    private TextView commandView;
    private ViewGroup displayContainer;
    private LinearLayout gamesShelf;
    private SharedPreferences preferences;
    private boolean busy = false;
    private int resolutionIndex = 0;
    private boolean inputEnabled = true;
    private boolean keyboardShown = false;

    private XServer xServer;
    private XServerView xServerView;
    private TouchpadView touchpadView;
    private InputControlsView inputControlsView;
    private InputControlsManager inputControlsManager;
    private ControlsProfile currentProfile;
    private XEnvironment environment;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        AppUtils.setActivityTheme(this);
        super.onCreate(savedInstanceState);
        setContentView(R.layout.linux_runtime);

        preferences = getSharedPreferences(PREFS, MODE_PRIVATE);

        statusView = findViewById(R.id.TVLinuxStatus);
        logView = findViewById(R.id.TVLinuxLog);
        progressBar = findViewById(R.id.PBLinux);
        installButton = findViewById(R.id.BTLinuxInstall);
        testButton = findViewById(R.id.BTLinuxTest);
        protonButton = findViewById(R.id.BTLinuxWine);
        runButton = findViewById(R.id.BTLinuxRun);
        profileButton = findViewById(R.id.BTLinuxProfile);
        keyboardButton = findViewById(R.id.BTLinuxKeyboard);
        inputButton = findViewById(R.id.BTLinuxInput);
        saveLogButton = findViewById(R.id.BTLinuxSaveLog);
        resolutionButton = findViewById(R.id.BTLinuxResolution);
        commandView = findViewById(R.id.ETLinuxCommand);
        displayContainer = findViewById(R.id.FLRuntimeDisplay);
        gamesShelf = findViewById(R.id.LLinuxGames);

        findViewById(R.id.BTLinuxBack).setOnClickListener(v -> finish());
        installButton.setOnClickListener(v -> startInstall());
        testButton.setOnClickListener(v -> runTest());
        protonButton.setOnClickListener(v -> startProtonInstall());
        runButton.setOnClickListener(v -> runCommand());
        profileButton.setOnClickListener(v -> showProfileDialog());
        keyboardButton.setOnClickListener(v -> setKeyboardShown(!keyboardShown));
        inputButton.setOnClickListener(v -> setInputEnabled(!inputEnabled));
        saveLogButton.setOnClickListener(v -> saveLog());
        resolutionIndex = preferences.getInt(PREF_RESOLUTION, 0);
        if (resolutionIndex < 0 || resolutionIndex >= RESOLUTIONS.length) resolutionIndex = 0;
        updateResolutionButton();
        resolutionButton.setOnClickListener(v -> setResolution((resolutionIndex + 1) % RESOLUTIONS.length));
        commandView.setOnEditorActionListener((v, actionId, event) -> {
            runCommand();
            return true;
        });

        setKeyboardShown(false);
        setInputEnabled(true);
        requestGameFolderAccess();
        refreshGames();
        refresh();
    }

    /**
     * The games folder lives on shared storage so a file manager can reach it, which means the
     * first visit has to ask for that access once. Denying it only costs the shelf, never the
     * runtime: the command field still works.
     */
    private void requestGameFolderAccess() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.M) return;
        if (checkSelfPermission(Manifest.permission.WRITE_EXTERNAL_STORAGE) == PackageManager.PERMISSION_GRANTED) return;
        requestPermissions(new String[]{Manifest.permission.WRITE_EXTERNAL_STORAGE}, 1);
    }

    @Override
    public void onRequestPermissionsResult(int requestCode, String[] permissions, int[] grantResults) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults);
        refreshGames();
    }

    /**
     * Fills the shelf with every launchable file in shared Download, the same way the Deck
     * screen fills its shelf from shortcuts. With nothing there it shows where to put files
     * instead of an empty strip, because an empty shelf says nothing about what to do.
     */
    private void refreshGames() {
        if (gamesShelf == null) return;
        gamesShelf.removeAllViews();

        List<String> games = LinuxRuntime.listGames(this);
        LayoutInflater inflater = LayoutInflater.from(this);

        if (games.isEmpty()) {
            View tile = inflater.inflate(R.layout.runtime_game_tile, gamesShelf, false);
            ((TextView)tile.findViewById(R.id.TVRuntimeGame)).setText("PUT .EXE FILES IN Download");
            ((TextView)tile.findViewById(R.id.TVRuntimeGameMeta)).setText("SAME FOLDER AS THE CONTAINER D: DRIVE");
            gamesShelf.addView(tile);
            return;
        }

        for (String game : games) {
            View tile = inflater.inflate(R.layout.runtime_game_tile, gamesShelf, false);
            ((TextView)tile.findViewById(R.id.TVRuntimeGame)).setText(game.toUpperCase(Locale.US));
            ((TextView)tile.findViewById(R.id.TVRuntimeGameMeta)).setText("PROTON");
            tile.setOnClickListener(v -> launchGame(game));
            gamesShelf.addView(tile);
        }
    }

    /**
     * Starts one game straight through Proton's Wine. No container and no box64 sit in this
     * path, which is the whole point of the runtime: the game meets native arm64 Wine directly.
     */
    private void launchGame(String name) {
        if (busy) return;

        if (!LinuxRuntime.isProtonInstalled(this)) {
            statusView.setText("ADD PROTON FIRST\nA game needs Proton's Wine. About 650 MB.");
            return;
        }

        setKeyboardShown(false);
        setBusy(true);
        String command = LinuxRuntime.gameCommand(name);
        logView.setText("running: " + command);

        worker.execute(() -> {
            String output = LinuxRuntime.run(this, command);
            main.post(() -> {
                logView.setText(output.isEmpty() ? "(no output)" : output);
                setBusy(false);
                refreshGames();
            });
        });
    }

    @Override
    protected void onResume() {
        super.onResume();
        // An install written by an earlier build has the driver file but not the libGL names
        // Wine asks for. Repairing here means a device that is already set up picks up the
        // fix just by opening this screen, with no download and no reinstall.
        if (LinuxRuntime.isInstalled(this)) LinuxRuntime.repairVirgl(this);
        if (LinuxRuntime.isInstalled(this)) startDisplay();
        refreshGames();
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
        if (commandView.hasFocus()) return super.dispatchKeyEvent(event);
        if (inputEnabled && inputControlsView != null && inputControlsView.onKeyEvent(event)) return true;
        // Keys are their own switch (KEYBOARD), so INPUT OFF only stops the pointer layer.
        if (xServer != null && xServer.keyboard.onKeyEvent(event)) return true;
        return super.dispatchKeyEvent(event);
    }

    @Override
    public boolean dispatchGenericMotionEvent(MotionEvent event) {
        return !(inputEnabled && touchpadView != null && touchpadView.onExternalMouseEvent(event))
            && super.dispatchGenericMotionEvent(event);
    }

    /** Starts the app's own X server, VirGL renderer and PulseAudio against the runtime rootfs. */
    private void startDisplay() {
        if (xServer != null) return;

        xServer = new XServer(this, new ScreenInfo(RESOLUTIONS[resolutionIndex]));
        xServerView = new XServerView(this, xServer);
        xServer.setRenderer(xServerView.getRenderer());
        displayContainer.addView(xServerView);

        touchpadView = new TouchpadView(this, xServer, true);
        displayContainer.addView(touchpadView);

        inputControlsManager = new InputControlsManager(this);
        inputControlsManager.loadProfiles(false);
        inputControlsView = new InputControlsView(this);
        inputControlsView.setOverlayOpacity(InputControlsView.DEFAULT_OVERLAY_OPACITY);
        inputControlsView.setTouchpadView(touchpadView);
        inputControlsView.setXServer(xServer);
        displayContainer.addView(inputControlsView);

        applyProfile(preferences.getInt(PREF_PROFILE_ID, NO_PROFILE));

        String rootPath = LinuxRuntime.rootfsDir(this).getPath();
        environment = new XEnvironment(this, RootFS.at(LinuxRuntime.rootfsDir(this)));
        environment.addComponent(new XServerComponent(xServer, UnixSocketConfig.create(rootPath, "/tmp/.X11-unix/X0")));
        environment.addComponent(new PulseAudioComponent(UnixSocketConfig.create(rootPath, "/tmp/.sound/PS0")));
        if (LinuxRuntime.isVirglInstalled(this)) {
            environment.addComponent(new VirGLRendererComponent(xServer, UnixSocketConfig.create(rootPath, LinuxRuntime.VIRGL_SOCKET)));
        }
        environment.startEnvironmentComponents();
    }

    /**
     * Applies an input-controls profile to the overlay. Id {@link #NO_PROFILE}, or an id whose
     * profile no longer exists, leaves the overlay hidden and the plain touchpad in charge.
     */
    private void applyProfile(int id) {
        currentProfile = id == NO_PROFILE || inputControlsManager == null ? null : inputControlsManager.getProfile(id);
        applyInputState();
        updateProfileButton();
        preferences.edit().putInt(PREF_PROFILE_ID, currentProfile != null ? currentProfile.id : NO_PROFILE).apply();
    }

    /** The single place that decides whether the pointer layer is live and how it behaves. */
    private void applyInputState() {
        boolean mouseDisabled = currentProfile != null && currentProfile.isDisableMouseInput();

        if (inputControlsView != null) {
            inputControlsView.setProfile(currentProfile);
            inputControlsView.setVisibility(inputEnabled && currentProfile != null ? View.VISIBLE : View.GONE);
        }
        if (touchpadView != null) {
            touchpadView.setEnabled(inputEnabled && !mouseDisabled);
            touchpadView.setSensitivity(currentProfile != null ? currentProfile.getCursorSpeed() : 1.0f);
        }
        if (xServerView != null) xServerView.getRenderer().setCursorVisible(inputEnabled && !mouseDisabled);
    }

    private void updateResolutionButton() {
        String size = RESOLUTIONS[resolutionIndex];
        resolutionButton.setText("RES " + size.substring(0, size.indexOf('x')));
    }

    /**
     * Cycles the guest display size and rebuilds the display at it. The renderer is the slow part
     * of this stack, so this is the lever that most directly raises the frame rate.
     */
    private void setResolution(int index) {
        if (busy) return;

        resolutionIndex = index;
        preferences.edit().putInt(PREF_RESOLUTION, index).apply();
        updateResolutionButton();
        restartDisplay();
    }

    /** Tears the display down and starts it again at the current size. A running game blocks it. */
    private void restartDisplay() {
        if (busy || xServer == null) {
            refresh();
            return;
        }

        if (environment != null) environment.stopEnvironmentComponents();
        environment = null;
        displayContainer.removeAllViews();

        xServer = null;
        xServerView = null;
        touchpadView = null;
        inputControlsView = null;
        inputControlsManager = null;
        currentProfile = null;

        startDisplay();
        refresh();
    }

    private void setInputEnabled(boolean enabled) {
        inputEnabled = enabled;
        applyInputState();
        inputButton.setText(enabled ? "INPUT ON" : "INPUT OFF");
        inputButton.setAlpha(enabled ? 1f : 0.6f);
    }

    /**
     * The soft keyboard types into the command field. Tapping the display moves focus away from
     * that field, which is when key events reach the runtime instead.
     */
    private void setKeyboardShown(boolean show) {
        keyboardShown = show;
        keyboardButton.setText(show ? "KEYBOARD ON" : "KEYBOARD OFF");
        keyboardButton.setAlpha(show ? 1f : 0.6f);

        InputMethodManager imm = (InputMethodManager)getSystemService(Context.INPUT_METHOD_SERVICE);
        if (imm == null) return;

        if (show) {
            commandView.requestFocus();
            imm.showSoftInput(commandView, InputMethodManager.SHOW_FORCED);
        }
        else {
            imm.hideSoftInputFromWindow(commandView.getWindowToken(), 0);
            commandView.clearFocus();
            if (xServerView != null) xServerView.requestFocus();
        }
    }

    /** The button is the only place that names the current profile. */
    private void updateProfileButton() {
        profileButton.setText(currentProfile != null ? "PROFILE "+currentProfile.getName() : "PROFILE NONE");
        profileButton.setAlpha(xServer == null ? 0.4f : currentProfile != null ? 1f : 0.6f);
    }

    private void showProfileDialog() {
        if (inputControlsManager == null) return;

        // Profiles are edited on another screen, so re-read them before showing the list.
        inputControlsManager.loadProfiles(false);
        int stored = preferences.getInt(PREF_PROFILE_ID, NO_PROFILE);
        if (stored != NO_PROFILE && inputControlsManager.getProfile(stored) == null) applyProfile(NO_PROFILE);

        final ArrayList<ControlsProfile> profiles = inputControlsManager.getProfiles();
        String[] items = new String[profiles.size()+1];
        items[0] = "None";
        for (int i = 0; i < profiles.size(); i++) items[i+1] = profiles.get(i).getName();

        new AlertDialog.Builder(this)
            .setTitle("Input profile")
            .setItems(items, (dialog, which) -> applyProfile(which == 0 ? NO_PROFILE : profiles.get(which - 1).id))
            .setNegativeButton("Cancel", null)
            .show();
    }

    private int versionCode() {
        try {
            return getPackageManager().getPackageInfo(getPackageName(), 0).versionCode;
        }
        catch (Exception e) {
            return 0;
        }
    }

    /**
     * Writes the last run's output to a file and offers to share it. The file lives in the app's
     * external files directory, so it is reachable from a file manager without any permission.
     */
    private void saveLog() {
        String output = logView.getText().toString();
        if (output.trim().isEmpty()) output = statusView.getText().toString();

        StringBuilder report = new StringBuilder();
        report.append("Winlator FSR runtime log\n");
        report.append("versionCode ").append(versionCode()).append("\n");
        report.append("installed=").append(LinuxRuntime.isInstalled(this));
        report.append(" proton=").append(LinuxRuntime.isProtonInstalled(this));
        report.append(" virgl=").append(LinuxRuntime.isVirglInstalled(this)).append("\n\n");
        report.append(output).append("\n");
        report.append(LinuxRuntime.graphicsReport(this)).append("\n");

        try {
            File dir = getExternalFilesDir(null);
            if (dir == null) throw new IllegalStateException("no external files dir");
            if (!dir.isDirectory() && !dir.mkdirs()) throw new IllegalStateException("cannot create "+dir);

            File file = new File(dir, "winlator-runtime-log.txt");
            if (!FileUtils.writeString(file, report.toString())) throw new IllegalStateException("cannot write "+file);

            Uri uri = FileProvider.getUriForFile(this, getPackageName()+".FileProvider", file);
            Intent share = new Intent(Intent.ACTION_SEND)
                .setType("text/plain")
                .putExtra(Intent.EXTRA_STREAM, uri)
                .putExtra(Intent.EXTRA_SUBJECT, "Winlator FSR runtime log")
                .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);
            startActivity(Intent.createChooser(share, "Send runtime log"));
            logView.setText(output + "\n\nsaved to " + file.getAbsolutePath());
        }
        catch (Exception e) {
            logView.setText(output + "\n\nCOULD NOT SAVE: " + e);
        }
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
                + "Display :0, PulseAudio and VirGL are live for this screen.";
        }

        // A broken graphics stack has to stay visible after the install finishes, not scroll
        // past in the log, because without it every game renders in software. The problem is
        // read from disk, so the banner survives an app restart, which the in-memory reason
        // and the marker file both do not.
        String graphicsIssue = installed ? LinuxRuntime.graphicsProblem(this) : null;
        boolean graphicsBroken = graphicsIssue != null;
        if (graphicsBroken) {
            text = "!! GRAPHICS " + graphicsIssue
                + "\nSoftware rendering until this is fixed. Open this screen again or run INSTALL.\n" + text;
        }

        statusView.setText(text);
        statusView.setTextColor(getColor(graphicsBroken ? R.color.px_hot : R.color.px_text));
        testButton.setAlpha(installed ? 1f : 0.4f);
        protonButton.setAlpha(installed && !proton ? 1f : 0.4f);
        installButton.setAlpha(busy ? 0.4f : 1f);
        runButton.setAlpha(installed ? 1f : 0.4f);
        updateProfileButton();
    }

    private void setBusy(boolean value) {
        busy = value;
        boolean installed = LinuxRuntime.isInstalled(this);

        installButton.setEnabled(!value);
        testButton.setEnabled(!value && installed);
        protonButton.setEnabled(!value && installed);
        runButton.setEnabled(!value && installed);
        progressBar.setVisibility(value ? View.VISIBLE : View.GONE);
        refresh();
    }

    private LinuxRuntime.Progress progressReporter() {
        return (stage, done, total) -> {
            int percent = total > 0 ? (int)(done * 100 / total) : 0;
            main.post(() -> {
                progressBar.setProgress(percent);
                statusView.setText(stage + " " + (done / (1024 * 1024)) + " / " + (total / (1024 * 1024)) + " MB");
            });
        };
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

            LinuxRuntime.Progress progress = progressReporter();

            main.post(() -> statusView.setText("DOWNLOADING ROOTFS"));
            if (!LinuxRuntime.downloadRootfs(this, progress)) {
                main.post(() -> { setBusy(false); statusView.setText("FAILED: rootfs download"); });
                return;
            }
            if (!LinuxRuntime.extractRootfs(this, progress)) {
                main.post(() -> { setBusy(false); statusView.setText("FAILED: rootfs extraction"); });
                return;
            }

            main.post(() -> statusView.setText("GRAPHICS"));
            // A graphics failure must not throw away the 830 MB rootfs that just unpacked, so
            // the install continues and the reason stays on screen instead.
            boolean graphicsFailed = !LinuxRuntime.installVirgl(this, progress);
            final String graphicsReason = LinuxRuntime.graphicsProblem(this);
            if (graphicsFailed) {
                main.post(() -> logView.setText("GRAPHICS DRIVER FAILED: " + graphicsReason
                    + "\nThe runtime still runs, but Proton cannot render through VirGL."));
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
            LinuxRuntime.Progress progress = progressReporter();

            if (!LinuxRuntime.downloadProton(this, progress)) {
                main.post(() -> { setBusy(false); statusView.setText("FAILED: proton download or checksum"); });
                return;
            }
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
                    + GRAPHICS_PROBE + "; "
                    + "if [ -x /opt/proton/files/bin-arm64/wine ]; then "
                    + "  /opt/proton/files/bin-arm64/wine --version 2>&1 | head -3; "
                    + "  ls /opt/proton/files/share/fex-emu; "
                    + "else echo 'proton not installed'; fi")
                + "\n" + LinuxRuntime.graphicsReport(this);
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

        setKeyboardShown(false);
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
