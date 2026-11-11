package com.winlator;

import android.app.Activity;
import android.content.SharedPreferences;
import android.graphics.Typeface;
import android.view.View;
import android.widget.LinearLayout;
import android.widget.TextView;

import androidx.appcompat.widget.SwitchCompat;

/**
 * The settings panel.
 *
 * Every row maps to a setting Gecko actually honours, so nothing here is decoration: JavaScript,
 * private mode and tracking protection are session settings fixed when a tab is created, and the
 * desktop switch changes viewport and user agent. There is no zoom row because GeckoView 137
 * exposes no zoom factor, and no "block images" row for the same reason.
 */
public final class BrowserMenu {
    public interface Callback {
        void onDesktopChanged();

        void onNewTab();

        void onFind();

        void onDownloads();

        void onHome();

        void onCloseTab();
    }

    private final Activity activity;
    private final SharedPreferences preferences;
    private final Callback callback;
    private final View panel;
    private final LinearLayout rows;

    private SwitchCompat desktop;
    private SwitchCompat javascript;
    private SwitchCompat protection;
    private SwitchCompat privateTab;

    public BrowserMenu(Activity activity, View panel, SharedPreferences preferences, Callback callback) {
        this.activity = activity;
        this.panel = panel;
        this.preferences = preferences;
        this.callback = callback;

        rows = panel.findViewById(R.id.LMenuRows);
        build();

        panel.findViewById(R.id.BTMenuClose).setOnClickListener(view -> hide());
    }

    private void build() {
        rows.removeAllViews();

        section(R.string.browser_menu_site);
        desktop = toggle(R.string.browser_opt_desktop, R.string.browser_opt_desktop_desc,
            BrowserTabs.PREF_DESKTOP, BrowserTabs.DEFAULT_DESKTOP, callback::onDesktopChanged);

        section(R.string.browser_menu_engine);
        javascript = toggle(R.string.browser_opt_javascript, R.string.browser_opt_javascript_desc,
            BrowserTabs.PREF_JAVASCRIPT, true, () -> {});
        protection = toggle(R.string.browser_opt_protection, R.string.browser_opt_protection_desc,
            BrowserTabs.PREF_PROTECTION, true, () -> {});
        privateTab = toggle(R.string.browser_opt_private, R.string.browser_opt_private_desc,
            BrowserTabs.PREF_PRIVATE, false, () -> {});

        section(R.string.browser_tabs);
        action(R.string.browser_action_new_tab, R.color.px_text, callback::onNewTab);
        action(R.string.browser_action_find, R.color.px_text, callback::onFind);
        action(R.string.browser_action_downloads, R.color.px_text, callback::onDownloads);
        action(R.string.browser_action_home, R.color.px_text, callback::onHome);
        action(R.string.browser_action_close_tab, R.color.px_hot, callback::onCloseTab);
    }

    private void section(int title) {
        TextView view = new TextView(activity);
        view.setText(title);
        view.setTextColor(activity.getColor(R.color.px_hot));
        view.setTextSize(11);
        view.setTypeface(Typeface.MONOSPACE, Typeface.BOLD);
        view.setAllCaps(true);
        view.setPadding(0, dp(12), 0, dp(6));
        rows.addView(view);
    }

    private SwitchCompat toggle(int title, int description, String key, boolean fallback, Runnable after) {
        View row = activity.getLayoutInflater().inflate(R.layout.browser_menu_row, rows, false);
        ((TextView) row.findViewById(R.id.TVRowTitle)).setText(title);
        ((TextView) row.findViewById(R.id.TVRowDesc)).setText(description);

        SwitchCompat toggle = row.findViewById(R.id.SWRow);
        toggle.setVisibility(View.VISIBLE);
        toggle.setChecked(preferences.getBoolean(key, fallback));

        row.setOnClickListener(view -> {
            boolean next = !preferences.getBoolean(key, fallback);
            preferences.edit().putBoolean(key, next).apply();
            toggle.setChecked(next);
            after.run();
        });

        rows.addView(row);
        return toggle;
    }

    private void action(int label, int color, Runnable run) {
        View row = activity.getLayoutInflater().inflate(R.layout.browser_menu_row, rows, false);
        TextView title = row.findViewById(R.id.TVRowTitle);
        title.setText(label);
        title.setTextColor(activity.getColor(color));
        row.findViewById(R.id.TVRowDesc).setVisibility(View.GONE);

        row.setOnClickListener(view -> {
            hide();
            run.run();
        });

        rows.addView(row);
    }

    private int dp(int value) {
        return (int) (activity.getResources().getDisplayMetrics().density * value);
    }

    public void refresh() {
        desktop.setChecked(preferences.getBoolean(BrowserTabs.PREF_DESKTOP, BrowserTabs.DEFAULT_DESKTOP));
        javascript.setChecked(preferences.getBoolean(BrowserTabs.PREF_JAVASCRIPT, true));
        protection.setChecked(preferences.getBoolean(BrowserTabs.PREF_PROTECTION, true));
        privateTab.setChecked(preferences.getBoolean(BrowserTabs.PREF_PRIVATE, false));
    }

    public void open() {
        refresh();
        panel.setVisibility(View.VISIBLE);
    }

    public void hide() {
        panel.setVisibility(View.GONE);
    }

    public boolean isOpen() {
        return panel.getVisibility() == View.VISIBLE;
    }
}
