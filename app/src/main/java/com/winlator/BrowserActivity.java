package com.winlator;

import android.content.ActivityNotFoundException;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.net.Uri;
import android.os.Bundle;
import android.view.KeyEvent;
import android.view.View;
import android.view.inputmethod.EditorInfo;
import android.view.inputmethod.InputMethodManager;
import android.widget.EditText;
import android.widget.ImageButton;
import android.widget.ProgressBar;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.appcompat.app.AppCompatActivity;
import androidx.preference.PreferenceManager;

import com.winlator.core.AppUtils;

import org.mozilla.geckoview.AllowOrDeny;
import org.mozilla.geckoview.GeckoResult;
import org.mozilla.geckoview.GeckoRuntime;
import org.mozilla.geckoview.GeckoSession;
import org.mozilla.geckoview.GeckoSessionSettings;
import org.mozilla.geckoview.GeckoView;
import org.mozilla.geckoview.WebRequestError;
import org.mozilla.geckoview.WebResponse;

import java.util.List;
import java.util.Locale;

/**
 * A small browser built on Mozilla's published GeckoView artifact.
 *
 * Gecko is embedded as shipped; nothing here patches or forks it. The activity is deliberately
 * narrow: address bar, back/forward/reload, history across the system back button, and the page
 * filling the rest of the screen. Site permissions (geolocation, camera, microphone, notifications)
 * are denied rather than silently granted, because there is no permission UI to consent with.
 */
public class BrowserActivity extends AppCompatActivity {
    private static final String HOME_URL = "https://duckduckgo.com/";
    private static final String SEARCH_URL = "https://duckduckgo.com/?q=";
    private static final String PREF_HOME = "browser_home_url";
    private static final String PREF_LAST = "browser_last_url";
    private static final String PREF_DESKTOP = "browser_desktop_mode";

    private SharedPreferences preferences;
    private GeckoSession session;
    private BrowserPrompts prompts;

    private GeckoView GVContent;
    private EditText ETBrowserAddress;
    private ProgressBar PBrowserLoad;
    private ImageButton BTBrowserBack;
    private ImageButton BTBrowserForward;
    private ImageButton BTBrowserReload;

    private boolean canGoBack = false;
    private boolean canGoForward = false;
    private String currentUrl = HOME_URL;

    @Override
    protected void onCreate(@Nullable Bundle savedInstanceState) {
        AppUtils.setActivityTheme(this);
        super.onCreate(savedInstanceState);
        setContentView(R.layout.browser_activity);

        preferences = PreferenceManager.getDefaultSharedPreferences(this);

        GVContent = findViewById(R.id.GVContent);
        ETBrowserAddress = findViewById(R.id.ETBrowserAddress);
        PBrowserLoad = findViewById(R.id.PBrowserLoad);
        BTBrowserBack = findViewById(R.id.BTBrowserBack);
        BTBrowserForward = findViewById(R.id.BTBrowserForward);
        BTBrowserReload = findViewById(R.id.BTBrowserReload);

        BTBrowserBack.setOnClickListener(view -> {
            if (session != null) session.goBack();
        });

        BTBrowserForward.setOnClickListener(view -> {
            if (session != null) session.goForward();
        });

        BTBrowserReload.setOnClickListener(view -> {
            if (session != null) session.reload();
        });

        // Long press goes home, so the address bar can be small.
        BTBrowserReload.setOnLongClickListener(view -> {
            openUrl(preferences.getString(PREF_HOME, HOME_URL));
            return true;
        });

        ETBrowserAddress.setOnEditorActionListener((view, actionId, event) -> {
            boolean enter = event != null && event.getKeyCode() == KeyEvent.KEYCODE_ENTER && event.getAction() == KeyEvent.ACTION_DOWN;
            if (actionId != EditorInfo.IME_ACTION_GO && actionId != EditorInfo.IME_ACTION_DONE && !enter) return false;

            String url = normalise(ETBrowserAddress.getText().toString());
            if (url != null) openUrl(url);
            ETBrowserAddress.clearFocus();
            hideKeyboard();
            return true;
        });

        // Long press the address bar to swap between mobile and desktop sites. A web app
        // like MATLAB Online lays itself out for a desktop viewport, a phone-sized site does not.
        ETBrowserAddress.setOnLongClickListener(view -> {
            boolean desktop = !preferences.getBoolean(PREF_DESKTOP, false);
            preferences.edit().putBoolean(PREF_DESKTOP, desktop).apply();
            AppUtils.showToast(this, getString(desktop ? R.string.browser_desktop_on : R.string.browser_desktop_off));
            recreate();
            return true;
        });

        boolean desktopMode = preferences.getBoolean(PREF_DESKTOP, false);
        GeckoSessionSettings settings = new GeckoSessionSettings.Builder()
            .viewportMode(desktopMode ? GeckoSessionSettings.VIEWPORT_MODE_DESKTOP
                                      : GeckoSessionSettings.VIEWPORT_MODE_MOBILE)
            .userAgentMode(desktopMode ? GeckoSessionSettings.USER_AGENT_MODE_DESKTOP
                                       : GeckoSessionSettings.USER_AGENT_MODE_MOBILE)
            .build();

        session = new GeckoSession(settings);
        prompts = new BrowserPrompts(this);
        session.setPromptDelegate(prompts);
        session.setNavigationDelegate(navigationDelegate);
        session.setProgressDelegate(progressDelegate);
        session.setContentDelegate(contentDelegate);
        session.setPermissionDelegate(permissionDelegate);
        session.open(GeckoRuntime.getDefault(this));
        GVContent.setSession(session);

        updateHistoryButtons();
        String start = savedInstanceState == null ? preferences.getString(PREF_LAST, HOME_URL) : HOME_URL;
        openUrl(start != null && !start.isEmpty() ? start : HOME_URL);
    }

    private final GeckoSession.NavigationDelegate navigationDelegate = new GeckoSession.NavigationDelegate() {
        @Override
        public void onLocationChange(@NonNull GeckoSession session, @Nullable String url,
                                     @NonNull List<GeckoSession.PermissionDelegate.ContentPermission> perms,
                                     @Nullable Boolean isTopLevel) {
            if (url == null) return;
            currentUrl = url;
            // Do not fight the user while they are editing the address.
            if (!ETBrowserAddress.isFocused()) ETBrowserAddress.setText(displayUrl(url));
        }

        @Override
        public void onCanGoBack(@NonNull GeckoSession session, boolean value) {
            canGoBack = value;
            updateHistoryButtons();
        }

        @Override
        public void onCanGoForward(@NonNull GeckoSession session, boolean value) {
            canGoForward = value;
            updateHistoryButtons();
        }

        @Override
        public GeckoResult<AllowOrDeny> onLoadRequest(@NonNull GeckoSession session,
                                                      @NonNull GeckoSession.NavigationDelegate.LoadRequest request) {
            String uri = request.uri;
            if (uri == null) return GeckoResult.deny();

            String scheme = Uri.parse(uri).getScheme();
            scheme = scheme == null ? "" : scheme.toLowerCase(Locale.ENGLISH);

            // Anything that is not a web page goes to whatever app claims it. Links using schemes
            // like intent: or market: must never be loaded in the engine itself.
            if (scheme.equals("http") || scheme.equals("https") || scheme.equals("about") || scheme.equals("data")) {
                return GeckoResult.allow();
            }

            if (scheme.equals("file")) return GeckoResult.deny();

            try {
                startActivity(new Intent(Intent.ACTION_VIEW, Uri.parse(uri)));
            }
            catch (ActivityNotFoundException ignored) {
            }
            catch (SecurityException ignored) {
            }

            return GeckoResult.deny();
        }

        @Override
        public GeckoResult<String> onLoadError(@NonNull GeckoSession session, @Nullable String uri,
                                               @NonNull WebRequestError error) {
            runOnUiThread(() -> {
                PBrowserLoad.setVisibility(View.GONE);
                AppUtils.showToast(BrowserActivity.this, getString(R.string.browser_load_error));
            });
            return null;
        }
    };

    private final GeckoSession.ProgressDelegate progressDelegate = new GeckoSession.ProgressDelegate() {
        @Override
        public void onPageStart(@NonNull GeckoSession session, @NonNull String url) {
            PBrowserLoad.setVisibility(View.VISIBLE);
            PBrowserLoad.setProgress(0);
        }

        @Override
        public void onPageStop(@NonNull GeckoSession session, boolean success) {
            PBrowserLoad.setVisibility(View.GONE);
        }

        @Override
        public void onProgressChange(@NonNull GeckoSession session, int progress) {
            PBrowserLoad.setVisibility(progress >= 100 ? View.GONE : View.VISIBLE);
            PBrowserLoad.setProgress(progress);
        }
    };

    private final GeckoSession.ContentDelegate contentDelegate = new GeckoSession.ContentDelegate() {
        @Override
        public void onTitleChange(@NonNull GeckoSession session, @Nullable String title) {
            setTitle(title != null && !title.trim().isEmpty() ? title : getString(R.string.browser));
        }

        @Override
        public void onCrash(@NonNull GeckoSession session) {
            runOnUiThread(() -> AppUtils.showToast(BrowserActivity.this, getString(R.string.browser_crashed)));
        }

        @Override
        public void onKill(@NonNull GeckoSession session) {
            runOnUiThread(() -> AppUtils.showToast(BrowserActivity.this, getString(R.string.browser_crashed)));
        }

        /**
         * Gecko hands over anything it will not render itself, which is every download.
         * Without this the tap is silently dropped, which is what used to happen.
         */
        @Override
        public void onExternalResponse(@NonNull GeckoSession session, @NonNull WebResponse response) {
            BrowserDownloads.handle(BrowserActivity.this, response);
        }
    };

    /**
     * Every prompt is denied. Granting a permission silently would be indistinguishable from
     * tracking the user, and this screen has no way to ask.
     */
    private final GeckoSession.PermissionDelegate permissionDelegate = new GeckoSession.PermissionDelegate() {
        @Override
        public GeckoResult<Integer> onContentPermissionRequest(@NonNull GeckoSession session,
                                                               @NonNull GeckoSession.PermissionDelegate.ContentPermission permission) {
            return GeckoResult.fromValue(GeckoSession.PermissionDelegate.ContentPermission.VALUE_DENY);
        }

        @Override
        public void onAndroidPermissionsRequest(@NonNull GeckoSession session, @Nullable String[] permissions,
                                                @NonNull GeckoSession.PermissionDelegate.Callback callback) {
            callback.reject();
        }

        @Override
        public void onMediaPermissionRequest(@NonNull GeckoSession session, @NonNull String uri,
                                             @Nullable GeckoSession.PermissionDelegate.MediaSource[] video,
                                             @Nullable GeckoSession.PermissionDelegate.MediaSource[] audio,
                                             @NonNull GeckoSession.PermissionDelegate.MediaCallback callback) {
            callback.reject();
        }
    };

    private void openUrl(@Nullable String url) {
        if (url == null || url.isEmpty()) return;
        currentUrl = url;
        ETBrowserAddress.setText(displayUrl(url));
        if (session != null) session.loadUri(url);
    }

    /** Turns what was typed into a URL, or a search when it does not look like one. */
    private static String normalise(@NonNull String input) {
        String text = input.trim();
        if (text.isEmpty()) return null;

        String lower = text.toLowerCase(Locale.ENGLISH);
        if (lower.startsWith("http://") || lower.startsWith("https://") ||
            lower.startsWith("about:") || lower.startsWith("data:")) return text;

        boolean looksLikeHost = text.contains(".") && !text.contains(" ") && !lower.startsWith("javascript:");
        if (!looksLikeHost) return SEARCH_URL + Uri.encode(text);

        return "https://" + text;
    }

    /** The address bar shows what was typed, without the scheme noise. */
    private static String displayUrl(@NonNull String url) {
        if (url.startsWith("https://")) return url.substring(8);
        if (url.startsWith("http://")) return url.substring(7);
        return url;
    }

    private void updateHistoryButtons() {
        BTBrowserBack.setEnabled(canGoBack);
        BTBrowserBack.setAlpha(canGoBack ? 1.0f : 0.35f);
        BTBrowserForward.setEnabled(canGoForward);
        BTBrowserForward.setAlpha(canGoForward ? 1.0f : 0.35f);
    }

    private void hideKeyboard() {
        InputMethodManager manager = (InputMethodManager)getSystemService(Context.INPUT_METHOD_SERVICE);
        if (manager != null) manager.hideSoftInputFromWindow(ETBrowserAddress.getWindowToken(), 0);
    }

    @Override
    public void onBackPressed() {
        if (session != null && canGoBack) {
            session.goBack();
            return;
        }
        super.onBackPressed();
    }

    @Override
    protected void onActivityResult(int requestCode, int resultCode, Intent data) {
        if (prompts != null) prompts.onActivityResult(requestCode, resultCode, data);
        super.onActivityResult(requestCode, resultCode, data);
    }

    @Override
    public void onRequestPermissionsResult(int requestCode, @NonNull String[] permissions, @NonNull int[] grantResults) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults);
        if (requestCode != BrowserDownloads.REQUEST_STORAGE) return;
        boolean granted = grantResults.length > 0 && grantResults[0] == android.content.pm.PackageManager.PERMISSION_GRANTED;
        BrowserDownloads.onPermissionResult(this, granted);
    }

    @Override
    protected void onPause() {
        super.onPause();
        if (preferences != null) preferences.edit().putString(PREF_LAST, currentUrl).apply();
    }

    @Override
    protected void onDestroy() {
        if (prompts != null) prompts.cancelPending();
        if (GVContent != null) GVContent.releaseSession();
        if (session != null) session.close();
        session = null;
        super.onDestroy();
    }
}
