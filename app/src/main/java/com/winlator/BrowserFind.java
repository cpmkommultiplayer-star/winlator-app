package com.winlator;

import android.app.Activity;
import android.text.Editable;
import android.text.TextWatcher;
import android.view.View;
import android.view.inputmethod.InputMethodManager;
import android.widget.EditText;
import android.widget.ImageButton;
import android.widget.TextView;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import org.mozilla.geckoview.GeckoSession;
import org.mozilla.geckoview.SessionFinder;

/**
 * Find in page, on top of Gecko's own finder.
 *
 * Gecko does the searching and the highlighting, so matches are found in the rendered document
 * rather than in the HTML source. Direction comes from Gecko's own flag constants: forward is 0,
 * so "previous" is the only direction that needs one.
 */
public final class BrowserFind {
    private final Activity activity;
    private final BrowserTabs tabs;
    private final View bar;
    private final EditText field;
    private final TextView count;

    private String lastQuery = "";

    public BrowserFind(Activity activity, BrowserTabs tabs, @NonNull View bar, @NonNull EditText field,
                       @NonNull TextView count, @NonNull ImageButton previous,
                       @NonNull ImageButton next, @NonNull ImageButton close) {
        this.activity = activity;
        this.tabs = tabs;
        this.bar = bar;
        this.field = field;
        this.count = count;

        close.setOnClickListener(view -> hide());
        previous.setOnClickListener(view -> step(GeckoSession.FINDER_FIND_BACKWARDS));
        next.setOnClickListener(view -> step(GeckoSession.FINDER_FIND_FORWARD));

        field.addTextChangedListener(new TextWatcher() {
            @Override
            public void beforeTextChanged(CharSequence text, int start, int count, int after) {
            }

            @Override
            public void onTextChanged(CharSequence text, int start, int before, int count) {
            }

            @Override
            public void afterTextChanged(Editable text) {
                search(text == null ? "" : text.toString());
            }
        });
    }

    public void open() {
        bar.setVisibility(View.VISIBLE);
        field.requestFocus();

        InputMethodManager manager = (InputMethodManager) activity.getSystemService(Activity.INPUT_METHOD_SERVICE);
        if (manager != null) manager.showSoftInput(field, 0);

        String query = field.getText().toString();
        if (!query.isEmpty()) search(query);
    }

    public void hide() {
        SessionFinder finder = tabs.finder();
        if (finder != null) finder.clear();

        lastQuery = "";
        field.setText("");
        count.setText("");
        bar.setVisibility(View.GONE);

        InputMethodManager manager = (InputMethodManager) activity.getSystemService(Activity.INPUT_METHOD_SERVICE);
        if (manager != null) manager.hideSoftInputFromWindow(field.getWindowToken(), 0);
    }

    public boolean isOpen() {
        return bar.getVisibility() == View.VISIBLE;
    }

    private void search(@Nullable String text) {
        String query = text == null ? "" : text.trim();
        lastQuery = query;

        SessionFinder finder = tabs.finder();
        if (finder == null) return;

        if (query.isEmpty()) {
            finder.clear();
            count.setText("");
            return;
        }

        run(finder, query, GeckoSession.FINDER_FIND_FORWARD);
    }

    private void step(int direction) {
        SessionFinder finder = tabs.finder();
        if (finder == null || lastQuery.isEmpty()) return;
        run(finder, lastQuery, direction);
    }

    private void run(@NonNull SessionFinder finder, @NonNull String query, int direction) {
        finder.setDisplayFlags(GeckoSession.FINDER_DISPLAY_DIM_PAGE | GeckoSession.FINDER_DISPLAY_HIGHLIGHT_ALL);

        finder.find(query, direction).then(result -> {
            if (result == null) return null;

            activity.runOnUiThread(() -> count.setText(result.found
                ? activity.getString(R.string.browser_find_count, result.current, result.total)
                : activity.getString(R.string.browser_find_none)));
            return null;
        });
    }
}
