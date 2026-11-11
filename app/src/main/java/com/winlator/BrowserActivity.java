package com.winlator;

import android.content.SharedPreferences;
import android.os.Bundle;
import android.text.format.Formatter;
import android.view.KeyEvent;
import android.view.LayoutInflater;
import android.view.View;
import android.view.inputmethod.EditorInfo;
import android.view.inputmethod.InputMethodManager;
import android.widget.EditText;
import android.widget.ImageButton;
import android.widget.LinearLayout;
import android.widget.ProgressBar;
import android.widget.TextView;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.appcompat.app.AppCompatActivity;
import androidx.preference.PreferenceManager;

import com.winlator.core.AppUtils;

import org.mozilla.geckoview.WebResponse;

import java.io.File;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.List;
import java.util.Locale;

/**
 * The browser screen.
 *
 * Chrome is a tab strip, an address bar, find-in-page, a downloads list and a settings panel over
 * Mozilla's published GeckoView; the engine itself is untouched. Downloads land in the public
 * Download folder because the container maps it to D:, so an installer fetched here can be run
 * inside Wine without copying it anywhere.
 */
public class BrowserActivity extends AppCompatActivity implements BrowserTabs.Listener, BrowserMenu.Callback {
    private SharedPreferences preferences;
    private BrowserTabs tabs;
    private BrowserPrompts prompts;
    private BrowserMenu menu;
    private BrowserFind find;

    private EditText ETBrowserAddress;
    private ProgressBar PBrowserLoad;
    private ImageButton BTBrowserBack;
    private ImageButton BTBrowserForward;
    private LinearLayout LTabStrip;
    private LinearLayout LDownloadsList;
    private TextView TVTabsCount;
    private TextView TVDownloadsEmpty;
    private View LMenuPanel;
    private View LDownloadsPanel;

    private final SimpleDateFormat stamp = new SimpleDateFormat("d MMM HH:mm", Locale.getDefault());
    private String currentUrl = BrowserTabs.HOME_URL;

    @Override
    protected void onCreate(@Nullable Bundle savedInstanceState) {
        AppUtils.setActivityTheme(this);
        super.onCreate(savedInstanceState);
        setContentView(R.layout.browser_activity);

        preferences = PreferenceManager.getDefaultSharedPreferences(this);

        ETBrowserAddress = findViewById(R.id.ETBrowserAddress);
        PBrowserLoad = findViewById(R.id.PBrowserLoad);
        BTBrowserBack = findViewById(R.id.BTBrowserBack);
        BTBrowserForward = findViewById(R.id.BTBrowserForward);
        LTabStrip = findViewById(R.id.LTabStrip);
        LDownloadsList = findViewById(R.id.LDownloadsList);
        TVTabsCount = findViewById(R.id.TVTabsCount);
        TVDownloadsEmpty = findViewById(R.id.TVDownloadsEmpty);
        LMenuPanel = findViewById(R.id.LMenuPanel);
        LDownloadsPanel = findViewById(R.id.LDownloadsPanel);

        prompts = new BrowserPrompts(this);
        tabs = new BrowserTabs(this, findViewById(R.id.GVContent), prompts, this);
        find = new BrowserFind(this, tabs, findViewById(R.id.LFindBar),
            (EditText) findViewById(R.id.ETFind),
            (TextView) findViewById(R.id.TVFindCount),
            (ImageButton) findViewById(R.id.BTFindPrev),
            (ImageButton) findViewById(R.id.BTFindNext),
            (ImageButton) findViewById(R.id.BTFindClose));
        menu = new BrowserMenu(this, LMenuPanel, preferences, this);

        BTBrowserBack.setOnClickListener(view -> tabs.back());
        BTBrowserForward.setOnClickListener(view -> tabs.forward());
        findViewById(R.id.BTBrowserReload).setOnClickListener(view -> {
            tabs.reload();
            hidePanels();
        });
        findViewById(R.id.BTNewTab).setOnClickListener(view -> newTab());
        findViewById(R.id.BTBTabs).setOnClickListener(view -> newTab());
        findViewById(R.id.BTBHome).setOnClickListener(view -> {
            hidePanels();
            tabs.load(preferences.getString(BrowserTabs.PREF_HOME, BrowserTabs.HOME_URL));
        });
        findViewById(R.id.BTBDownloads).setOnClickListener(view -> openDownloads());
        findViewById(R.id.BTBFind).setOnClickListener(view -> openFind());
        findViewById(R.id.BTBMenu).setOnClickListener(view -> {
            if (menu.isOpen()) {
                menu.hide();
                return;
            }
            hidePanels();
            menu.open();
            LMenuPanel.setVisibility(View.VISIBLE);
        });
        findViewById(R.id.BTDownloadsClose).setOnClickListener(view -> hidePanels());

        ETBrowserAddress.setOnEditorActionListener((view, actionId, event) -> {
            boolean enter = event != null && event.getKeyCode() == KeyEvent.KEYCODE_ENTER
                && event.getAction() == KeyEvent.ACTION_DOWN;
            if (actionId != EditorInfo.IME_ACTION_GO && actionId != EditorInfo.IME_ACTION_DONE && !enter) return false;

            String url = BrowserTabs.normalise(ETBrowserAddress.getText().toString());
            if (url != null) tabs.load(url);
            ETBrowserAddress.clearFocus();
            hideKeyboard();
            return true;
        });

        updateHistoryButtons(false, false);

        String start = savedInstanceState == null
            ? preferences.getString(BrowserTabs.PREF_LAST, BrowserTabs.HOME_URL)
            : BrowserTabs.HOME_URL;
        tabs.open(start == null || start.isEmpty() ? BrowserTabs.HOME_URL : start,
            preferences.getBoolean(BrowserTabs.PREF_PRIVATE, false));
    }

    // ------------------------------------------------------------ tab actions

    private void newTab() {
        hidePanels();
        tabs.open(BrowserTabs.HOME_URL, preferences.getBoolean(BrowserTabs.PREF_PRIVATE, false));
    }

    private void openFind() {
        hidePanels();
        find.open();
    }

    private void openDownloads() {
        hidePanels();
        menu.hide();
        LDownloadsPanel.setVisibility(View.VISIBLE);
        renderDownloads();
    }

    private void hidePanels() {
        LMenuPanel.setVisibility(View.GONE);
        if (find != null) find.hide();
        LDownloadsPanel.setVisibility(View.GONE);
    }

    // ------------------------------------------------------------ tab strip

    private void renderTabStrip() {
        LTabStrip.removeAllViews();
        LayoutInflater inflater = getLayoutInflater();

        for (int i = 0; i < tabs.count(); i++) {
            BrowserTabs.Tab tab = tabs.tabAt(i);
            if (tab == null) continue;

            View chip = inflater.inflate(R.layout.browser_tab, LTabStrip, false);
            TextView title = chip.findViewById(R.id.TVTabTitle);

            String label = tab.getTitle();
            if (label == null || label.isEmpty()) label = BrowserTabs.displayUrl(tab.getUrl());
            if (tab.isPrivate()) label = getString(R.string.browser_tab_private) + " · " + label;
            title.setText(label);

            boolean selected = i == tabs.activeIndex();
            chip.setBackgroundResource(selected ? R.drawable.px_tab_active : R.drawable.px_tab);
            title.setTextColor(getColor(selected ? R.color.px_text : R.color.px_text_dim));

            final int index = i;
            chip.setOnClickListener(view -> {
                hidePanels();
                tabs.show(index);
            });
            chip.findViewById(R.id.BTTabClose).setOnClickListener(view -> tabs.close(index));

            LTabStrip.addView(chip);
        }

        TVTabsCount.setText(String.valueOf(Math.max(1, tabs.count())));
    }

    // ----------------------------------------------------------- downloads

    private void renderDownloads() {
        LDownloadsList.removeAllViews();
        List<File> files = BrowserFiles.files();
        TVDownloadsEmpty.setVisibility(files.isEmpty() ? View.VISIBLE : View.GONE);

        LayoutInflater inflater = getLayoutInflater();
        for (File file : files) {
            View row = inflater.inflate(R.layout.browser_download_row, LDownloadsList, false);
            ((TextView) row.findViewById(R.id.TVDownloadName)).setText(file.getName());

            String meta = Formatter.formatShortFileSize(this, file.length())
                + "  ·  " + stamp.format(new Date(file.lastModified()));
            ((TextView) row.findViewById(R.id.TVDownloadMeta)).setText(meta);

            row.setOnClickListener(view -> BrowserFiles.open(this, file));
            LDownloadsList.addView(row);
        }
    }

    // ------------------------------------------------------------- listener

    @Override
    public void onTabsChanged() {
        renderTabStrip();
    }

    @Override
    public void onLocation(@NonNull BrowserTabs.Tab tab, @NonNull String url) {
        currentUrl = url;
        // Never fight the user while they are typing in the bar.
        if (!ETBrowserAddress.isFocused()) ETBrowserAddress.setText(BrowserTabs.displayUrl(url));
    }

    @Override
    public void onTitle(@NonNull BrowserTabs.Tab tab, @Nullable String title) {
        setTitle(title == null || title.isEmpty() ? getString(R.string.browser) : title);
    }

    @Override
    public void onHistory(boolean canGoBack, boolean canGoForward) {
        updateHistoryButtons(canGoBack, canGoForward);
    }

    @Override
    public void onProgress(int progress, boolean loading) {
        PBrowserLoad.setVisibility(loading ? View.VISIBLE : View.GONE);
        PBrowserLoad.setProgress(progress);
    }

    @Override
    public void onExternalResponse(@NonNull WebResponse response) {
        BrowserDownloads.handle(this, response);
        if (LDownloadsPanel.getVisibility() == View.VISIBLE) renderDownloads();
    }

    @Override
    public void onLoadError() {
        PBrowserLoad.setVisibility(View.GONE);
        AppUtils.showToast(this, getString(R.string.browser_load_error));
    }

    @Override
    public void onCrashed() {
        AppUtils.showToast(this, getString(R.string.browser_crashed));
    }

    // --------------------------------------------------------------- menu

    @Override
    public void onDesktopChanged() {
        // Viewport and user agent are fixed when a session is created, so the change takes
        // effect on the next launch rather than silently doing nothing to this page.
        AppUtils.showToast(this, getString(preferences.getBoolean(BrowserTabs.PREF_DESKTOP, BrowserTabs.DEFAULT_DESKTOP)
            ? R.string.browser_desktop_on : R.string.browser_desktop_off));
    }

    @Override
    public void onNewTab() {
        newTab();
    }

    @Override
    public void onFind() {
        openFind();
    }

    @Override
    public void onDownloads() {
        openDownloads();
    }

    @Override
    public void onHome() {
        hidePanels();
        tabs.load(preferences.getString(BrowserTabs.PREF_HOME, BrowserTabs.HOME_URL));
    }

    @Override
    public void onCloseTab() {
        tabs.close(tabs.activeIndex());
    }

    // ---------------------------------------------------------------- misc

    private void updateHistoryButtons(boolean canGoBack, boolean canGoForward) {
        BTBrowserBack.setEnabled(canGoBack);
        BTBrowserBack.setAlpha(canGoBack ? 1.0f : 0.35f);
        BTBrowserForward.setEnabled(canGoForward);
        BTBrowserForward.setAlpha(canGoForward ? 1.0f : 0.35f);
    }

    private void hideKeyboard() {
        InputMethodManager manager = (InputMethodManager) getSystemService(INPUT_METHOD_SERVICE);
        if (manager != null) manager.hideSoftInputFromWindow(ETBrowserAddress.getWindowToken(), 0);
    }

    @Override
    public void onBackPressed() {
        if (menu.isOpen() || LDownloadsPanel.getVisibility() == View.VISIBLE || find.isOpen()) {
            hidePanels();
            return;
        }
        if (tabs.canGoBack()) {
            tabs.back();
            return;
        }
        super.onBackPressed();
    }

    @Override
    public void onRequestPermissionsResult(int requestCode, @NonNull String[] permissions, @NonNull int[] grantResults) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults);
        if (requestCode != BrowserDownloads.REQUEST_STORAGE) return;
        boolean granted = grantResults.length > 0
            && grantResults[0] == android.content.pm.PackageManager.PERMISSION_GRANTED;
        BrowserDownloads.onPermissionResult(this, granted);
    }

    @Override
    protected void onPause() {
        super.onPause();
        if (preferences != null) preferences.edit().putString(BrowserTabs.PREF_LAST, currentUrl).apply();
    }

    @Override
    protected void onDestroy() {
        if (prompts != null) prompts.cancelPending();
        if (tabs != null) tabs.closeAll();
        super.onDestroy();
    }
}
