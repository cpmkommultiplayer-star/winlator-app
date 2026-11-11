package com.winlator;

import android.app.Activity;
import android.content.Intent;
import android.net.Uri;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.preference.PreferenceManager;

import org.mozilla.geckoview.AllowOrDeny;
import org.mozilla.geckoview.GeckoResult;
import org.mozilla.geckoview.GeckoRuntime;
import org.mozilla.geckoview.GeckoSession;
import org.mozilla.geckoview.GeckoSessionSettings;
import org.mozilla.geckoview.GeckoView;
import org.mozilla.geckoview.SessionFinder;
import org.mozilla.geckoview.WebRequestError;
import org.mozilla.geckoview.WebResponse;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * Owns the Gecko sessions behind the browser's tabs.
 *
 * Gecko is embedded exactly as Mozilla publishes it. Each tab is a separate GeckoSession, which
 * is the unit Gecko isolates history, cookies and private mode by; the GeckoView simply displays
 * whichever session is active. Nothing here patches or forks the engine.
 *
 * Settings that Gecko fixes at session creation (JavaScript, private mode, tracking protection,
 * desktop viewport) are read when a tab is created, so changing them affects new tabs. The
 * activity says so in the panel rather than pretending an open page changed underneath.
 */
public final class BrowserTabs {
    public static final String HOME_URL = "https://duckduckgo.com/";
    public static final String SEARCH_URL = "https://duckduckgo.com/?q=";

    public static final String PREF_HOME = "browser_home_url";
    public static final String PREF_LAST = "browser_last_url";
    public static final String PREF_DESKTOP = "browser_desktop_mode";
    public static final String PREF_JAVASCRIPT = "browser_javascript";
    public static final String PREF_PROTECTION = "browser_tracking_protection";
    public static final String PREF_PRIVATE = "browser_private_default";

    public static final boolean DEFAULT_DESKTOP = true;

    /** What the activity has to redraw when the tab model changes. */
    public interface Listener {
        void onTabsChanged();

        void onLocation(@NonNull Tab tab, @NonNull String url);

        void onTitle(@NonNull Tab tab, @Nullable String title);

        void onHistory(boolean canGoBack, boolean canGoForward);

        void onProgress(int progress, boolean loading);

        void onExternalResponse(@NonNull WebResponse response);

        void onLoadError();

        void onCrashed();
    }

    public static final class Tab {
        private final GeckoSession session;
        private final boolean privateTab;

        private String url = HOME_URL;
        private String title = "";
        private boolean loaded = false;
        private boolean canGoBack = false;
        private boolean canGoForward = false;

        private Tab(GeckoSession session, boolean privateTab) {
            this.session = session;
            this.privateTab = privateTab;
        }

        public GeckoSession getSession() {
            return session;
        }

        public boolean isPrivate() {
            return privateTab;
        }

        public String getUrl() {
            return url;
        }

        public String getTitle() {
            return title;
        }
    }

    private final Activity activity;
    private final GeckoView view;
    private final BrowserPrompts prompts;
    private final Listener listener;
    private final List<Tab> tabs = new ArrayList<>();

    private int active = -1;

    public BrowserTabs(Activity activity, GeckoView view, BrowserPrompts prompts, Listener listener) {
        this.activity = activity;
        this.view = view;
        this.prompts = prompts;
        this.listener = listener;
    }

    // ---------------------------------------------------------------- model

    public int count() {
        return tabs.size();
    }

    public int activeIndex() {
        return active;
    }

    @Nullable
    public Tab tabAt(int index) {
        return index >= 0 && index < tabs.size() ? tabs.get(index) : null;
    }

    @Nullable
    public Tab current() {
        return active >= 0 && active < tabs.size() ? tabs.get(active) : null;
    }

    public Tab open(@Nullable String url, boolean privateTab) {
        GeckoSession session = new GeckoSession(buildSettings(privateTab));
        Tab tab = new Tab(session, privateTab);
        if (url != null && !url.isEmpty()) tab.url = url;

        session.setNavigationDelegate(navigationDelegate);
        session.setProgressDelegate(progressDelegate);
        session.setContentDelegate(contentDelegate);
        session.setPermissionDelegate(permissionDelegate);
        session.setPromptDelegate(prompts);
        session.open(GeckoRuntime.getDefault(activity));

        tabs.add(tab);
        show(tabs.size() - 1);
        return tab;
    }

    public void show(int index) {
        if (index < 0 || index >= tabs.size()) return;

        Tab previous = current();
        if (previous != null) previous.session.setActive(false);

        active = index;
        Tab tab = tabs.get(index);
        view.setSession(tab.session);
        tab.session.setActive(true);

        listener.onLocation(tab, tab.url);
        listener.onTitle(tab, tab.title);
        listener.onHistory(tab.canGoBack, tab.canGoForward);
        listener.onTabsChanged();

        if (!tab.loaded) {
            tab.loaded = true;
            tab.session.loadUri(tab.url);
        }
    }

    public void close(int index) {
        if (index < 0 || index >= tabs.size()) return;

        Tab removed = tabs.remove(index);
        removed.session.close();

        if (tabs.isEmpty()) {
            active = -1;
            open(HOME_URL, false);
            return;
        }

        if (index < active) active--;
        else if (index == active) active = Math.min(active, tabs.size() - 1);
        show(active);
    }

    public void closeAll() {
        for (Tab tab : new ArrayList<>(tabs)) {
            try {
                tab.session.close();
            }
            catch (Exception ignored) {
            }
        }
        tabs.clear();
        active = -1;
    }

    public void load(@NonNull String url) {
        Tab tab = current();
        if (tab == null) return;
        tab.url = url;
        tab.session.loadUri(url);
    }

    public void back() {
        Tab tab = current();
        if (tab != null) tab.session.goBack();
    }

    public void forward() {
        Tab tab = current();
        if (tab != null) tab.session.goForward();
    }

    public void reload() {
        Tab tab = current();
        if (tab != null) tab.session.reload();
    }

    public void stop() {
        Tab tab = current();
        if (tab != null) tab.session.stop();
    }

    @Nullable
    public SessionFinder finder() {
        Tab tab = current();
        return tab == null ? null : tab.session.getFinder();
    }

    public boolean canGoBack() {
        Tab tab = current();
        return tab != null && tab.canGoBack;
    }

    // ------------------------------------------------------------- settings

    private boolean pref(String key, boolean fallback) {
        return PreferenceManager.getDefaultSharedPreferences(activity).getBoolean(key, fallback);
    }

    private GeckoSessionSettings buildSettings(boolean privateTab) {
        boolean desktop = pref(PREF_DESKTOP, DEFAULT_DESKTOP);

        return new GeckoSessionSettings.Builder()
            .viewportMode(desktop ? GeckoSessionSettings.VIEWPORT_MODE_DESKTOP
                                  : GeckoSessionSettings.VIEWPORT_MODE_MOBILE)
            .userAgentMode(desktop ? GeckoSessionSettings.USER_AGENT_MODE_DESKTOP
                                   : GeckoSessionSettings.USER_AGENT_MODE_MOBILE)
            .allowJavascript(pref(PREF_JAVASCRIPT, true))
            .useTrackingProtection(pref(PREF_PROTECTION, true))
            .usePrivateMode(privateTab)
            .build();
    }

    // ------------------------------------------------------------- delegates

    @Nullable
    private Tab tabOf(GeckoSession session) {
        for (Tab tab : tabs) {
            if (tab.session == session) return tab;
        }
        return null;
    }

    private final GeckoSession.NavigationDelegate navigationDelegate = new GeckoSession.NavigationDelegate() {
        @Override
        public void onLocationChange(@NonNull GeckoSession session, @Nullable String url,
                                     @NonNull List<GeckoSession.PermissionDelegate.ContentPermission> perms,
                                     @Nullable Boolean isTopLevel) {
            if (url == null) return;
            Tab tab = tabOf(session);
            if (tab == null) return;
            tab.url = url;
            if (tab == current()) listener.onLocation(tab, url);
        }

        @Override
        public void onCanGoBack(@NonNull GeckoSession session, boolean value) {
            Tab tab = tabOf(session);
            if (tab == null) return;
            tab.canGoBack = value;
            if (tab == current()) listener.onHistory(tab.canGoBack, tab.canGoForward);
        }

        @Override
        public void onCanGoForward(@NonNull GeckoSession session, boolean value) {
            Tab tab = tabOf(session);
            if (tab == null) return;
            tab.canGoForward = value;
            if (tab == current()) listener.onHistory(tab.canGoBack, tab.canGoForward);
        }

        @Override
        public GeckoResult<AllowOrDeny> onLoadRequest(@NonNull GeckoSession session,
                                                      @NonNull GeckoSession.NavigationDelegate.LoadRequest request) {
            String uri = request.uri;
            if (uri == null) return GeckoResult.deny();

            String scheme = Uri.parse(uri).getScheme();
            scheme = scheme == null ? "" : scheme.toLowerCase(Locale.ENGLISH);

            // Anything that is not a web page belongs to whichever app claims it. intent: and
            // market: links must never be handed to the engine itself.
            if (scheme.equals("http") || scheme.equals("https") || scheme.equals("about") || scheme.equals("data")) {
                return GeckoResult.allow();
            }

            if (scheme.equals("file")) return GeckoResult.deny();

            try {
                activity.startActivity(new Intent(Intent.ACTION_VIEW, Uri.parse(uri)));
            }
            catch (Exception ignored) {
            }

            return GeckoResult.deny();
        }

        @Override
        public GeckoResult<String> onLoadError(@NonNull GeckoSession session, @Nullable String uri,
                                               @NonNull WebRequestError error) {
            activity.runOnUiThread(() -> listener.onLoadError());
            return null;
        }
    };

    private final GeckoSession.ProgressDelegate progressDelegate = new GeckoSession.ProgressDelegate() {
        @Override
        public void onPageStart(@NonNull GeckoSession session, @NonNull String url) {
            if (session != sessionOfCurrent()) return;
            activity.runOnUiThread(() -> listener.onProgress(0, true));
        }

        @Override
        public void onPageStop(@NonNull GeckoSession session, boolean success) {
            if (session != sessionOfCurrent()) return;
            activity.runOnUiThread(() -> listener.onProgress(100, false));
        }

        @Override
        public void onProgressChange(@NonNull GeckoSession session, int progress) {
            if (session != sessionOfCurrent()) return;
            activity.runOnUiThread(() -> listener.onProgress(progress, progress < 100));
        }
    };

    private final GeckoSession.ContentDelegate contentDelegate = new GeckoSession.ContentDelegate() {
        @Override
        public void onTitleChange(@NonNull GeckoSession session, @Nullable String title) {
            Tab tab = tabOf(session);
            if (tab == null) return;
            tab.title = title == null ? "" : title.trim();
            activity.runOnUiThread(() -> {
                if (tab == current()) listener.onTitle(tab, tab.title);
                listener.onTabsChanged();
            });
        }

        @Override
        public void onCrash(@NonNull GeckoSession session) {
            activity.runOnUiThread(() -> listener.onCrashed());
        }

        /**
         * Gecko hands over anything it will not render itself, which is every download. Without
         * this the tap is dropped, which is exactly what used to happen.
         */
        @Override
        public void onExternalResponse(@NonNull GeckoSession session, @NonNull WebResponse response) {
            activity.runOnUiThread(() -> listener.onExternalResponse(response));
        }
    };

    /**
     * Permission requests are refused. Granting one silently would be indistinguishable from
     * tracking the user, and this screen has no place to ask.
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

    @Nullable
    private GeckoSession sessionOfCurrent() {
        Tab tab = current();
        return tab == null ? null : tab.session;
    }

    // ----------------------------------------------------------------- utils

    /** What was typed, turned into a URL, or a search when it does not look like one. */
    @Nullable
    public static String normalise(@NonNull String input) {
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
    @NonNull
    public static String displayUrl(@NonNull String url) {
        if (url.startsWith("https://")) return url.substring(8);
        if (url.startsWith("http://")) return url.substring(7);
        return url;
    }
}
