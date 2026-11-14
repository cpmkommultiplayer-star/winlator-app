package com.winlator;

import android.Manifest;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.graphics.Bitmap;
import android.os.Build;
import android.os.Bundle;
import android.view.LayoutInflater;
import android.view.View;
import android.view.WindowManager;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.TextView;

import androidx.appcompat.app.AppCompatActivity;

import com.winlator.container.ContainerManager;
import com.winlator.container.Shortcut;
import com.winlator.core.AppUtils;

import java.util.ArrayList;

/**
 * Console shell: the app opens here instead of straight into the container manager.
 * Everything it shows is an existing shortcut, and every launch goes through the same
 * XServerDisplayActivity intent the shortcut list uses. No behaviour is reimplemented.
 */
public class DeckActivity extends AppCompatActivity {
    private ContainerManager manager;
    private LinearLayout shelf;
    private LinearLayout heroBlock;
    private TextView emptyView;
    private TextView countView;
    private ImageView heroIcon;
    private TextView heroName;
    private TextView heroMeta;
    private Shortcut featured;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        AppUtils.setActivityTheme(this);
        super.onCreate(savedInstanceState);
        setContentView(R.layout.deck_activity);

        shelf = findViewById(R.id.LDeckShelf);
        heroBlock = findViewById(R.id.LDeckHero);
        emptyView = findViewById(R.id.TVDeckEmpty);
        countView = findViewById(R.id.TVDeckCount);
        heroIcon = findViewById(R.id.IHDeckHeroIcon);
        heroName = findViewById(R.id.TVDeckHeroName);
        heroMeta = findViewById(R.id.TVDeckHeroMeta);

        findViewById(R.id.BDeckHome).setOnClickListener(v -> reload());
        findViewById(R.id.BDeckLibrary).setOnClickListener(v -> openMenu(R.id.menu_item_shortcuts));
        findViewById(R.id.BDeckContainers).setOnClickListener(v -> openMenu(R.id.menu_item_containers));
        findViewById(R.id.BDeckBrowser).setOnClickListener(v -> startActivity(new Intent(this, BrowserActivity.class)));
        findViewById(R.id.BDeckSettings).setOnClickListener(v -> openMenu(R.id.menu_item_settings));
        findViewById(R.id.BTDeckManager).setOnClickListener(v -> openMenu(R.id.menu_item_containers));
        findViewById(R.id.BTDeckRescan).setOnClickListener(v -> reload());
        findViewById(R.id.BTDeckPlay).setOnClickListener(v -> {
            if (featured != null) launch(featured);
        });

        getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
        getWindow().getDecorView().setSystemUiVisibility(
            View.SYSTEM_UI_FLAG_LAYOUT_STABLE
                | View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION
                | View.SYSTEM_UI_FLAG_HIDE_NAVIGATION
                | View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY);

        reload();
    }

    @Override
    protected void onResume() {
        super.onResume();
        reload();
    }

    private boolean hasStorageAccess() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.M) return true;
        return checkSelfPermission(Manifest.permission.WRITE_EXTERNAL_STORAGE) == PackageManager.PERMISSION_GRANTED;
    }

    private void reload() {
        shelf.removeAllViews();
        featured = null;

        ArrayList<Shortcut> games = new ArrayList<>();
        int containerCount = 0;
        boolean readable = false;

        if (hasStorageAccess()) {
            try {
                if (manager == null) manager = new ContainerManager(this);
                containerCount = manager.getContainers().size();
                for (Shortcut shortcut : manager.loadShortcuts(null)) {
                    if (!shortcut.file.isDirectory()) games.add(shortcut);
                }
                readable = true;
            }
            catch (Exception e) {
                readable = false;
            }
        }

        countView.setText(games.size() + (games.size() == 1 ? " GAME" : " GAMES")
            + "  \u00b7  " + containerCount + (containerCount == 1 ? " CONTAINER" : " CONTAINERS"));

        if (!readable) {
            heroBlock.setVisibility(View.GONE);
            emptyView.setVisibility(View.VISIBLE);
            emptyView.setText("STORAGE ACCESS NEEDED\nOPEN THE MANAGER AND ALLOW IT");
            return;
        }

        if (games.isEmpty()) {
            heroBlock.setVisibility(View.GONE);
            emptyView.setVisibility(View.VISIBLE);
            emptyView.setText("NO SHORTCUTS YET\nCREATE A CONTAINER, THEN ADD A SHORTCUT TO ITS DESKTOP");
            return;
        }

        emptyView.setVisibility(View.GONE);
        heroBlock.setVisibility(View.VISIBLE);

        featured = games.get(0);
        bindHero(featured);

        LayoutInflater inflater = LayoutInflater.from(this);
        for (Shortcut game : games) {
            View tile = inflater.inflate(R.layout.deck_tile, shelf, false);
            ImageView icon = tile.findViewById(R.id.IVTileIcon);
            TextView name = tile.findViewById(R.id.TVTileName);
            TextView meta = tile.findViewById(R.id.TVTileMeta);

            if (game.icon != null) icon.setImageBitmap(game.icon);
            else icon.setImageResource(R.drawable.icon_shortcut);
            name.setText(game.name.toUpperCase());
            meta.setText(game.container.getName().toUpperCase());

            tile.setOnClickListener(v -> launch(game));
            shelf.addView(tile);
        }

        if (shelf.getChildCount() > 0) shelf.getChildAt(0).requestFocus();
    }

    private void bindHero(Shortcut game) {
        Bitmap icon = game.icon;
        if (icon != null) heroIcon.setImageBitmap(icon);
        else heroIcon.setImageResource(R.drawable.icon_shortcut);
        heroName.setText(game.name.toUpperCase());
        heroMeta.setText(game.container.getName().toUpperCase());
    }

    private void launch(Shortcut shortcut) {
        Intent intent = new Intent(this, XServerDisplayActivity.class);
        intent.putExtra("container_id", shortcut.container.id);
        intent.putExtra("shortcut_path", shortcut.file.getPath());
        startActivity(intent);
    }

    private void openMenu(int menuItemId) {
        Intent intent = new Intent(this, MainActivity.class);
        intent.putExtra("selected_menu_item_id", menuItemId);
        intent.addFlags(Intent.FLAG_ACTIVITY_REORDER_TO_FRONT);
        startActivity(intent);
    }
}
