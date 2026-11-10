package com.winlator.core;

import android.util.Log;

import com.winlator.container.Container;
import com.winlator.renderer.GLRenderer;
import com.winlator.renderer.effects.BilinearEffect;
import com.winlator.renderer.effects.FSREffect;
import com.winlator.renderer.effects.FSRSharpenEffect;
import com.winlator.xserver.ScreenInfo;

/**
 * Per-container upscaler settings, stored in the container's extra data so that
 * adding them does not change the container file schema.
 *
 * The render scale is applied to the container screen size, because that is the
 * only thing that actually makes the guest draw fewer pixels; the composer then
 * samples that picture one to one and the upscaler brings it back to the panel.
 */
public class Upscaler {
    public static final String LOG_TAG = "WinlatorFSR";
    public static final String BASE_SCREEN_SIZE_KEY = "fsrBaseScreenSize";

    public static final byte MODE_OFF = 0;
    public static final byte MODE_EASU = 1;
    public static final byte MODE_BILINEAR = 2;
    public static final int[] SCALES = {50, 59, 67, 77, 100};
    public static final int DEFAULT_SCALE = 67;
    public static final int DEFAULT_SHARPNESS = 80;

    private static int parseInt(String value, int fallback) {
        try {
            return Integer.parseInt(value.trim());
        }
        catch (NumberFormatException e) {
            return fallback;
        }
    }

    public static byte getMode(Container container) {
        int mode = parseInt(container.getExtra("fsrMode", "0"), MODE_OFF);
        return (byte)Math.max(MODE_OFF, Math.min(MODE_BILINEAR, mode));
    }

    public static int getScale(Container container) {
        int scale = parseInt(container.getExtra("fsrScale", String.valueOf(DEFAULT_SCALE)), DEFAULT_SCALE);
        for (int value : SCALES) if (value == scale) return value;
        return DEFAULT_SCALE;
    }

    public static int getSharpness(Container container) {
        return Math.max(0, Math.min(100, parseInt(container.getExtra("fsrSharpness", String.valueOf(DEFAULT_SHARPNESS)), DEFAULT_SHARPNESS)));
    }

    /** The screen size the container is set to when the upscaler is off. */
    public static String getBaseScreenSize(Container container) {
        String base = container.getExtra(BASE_SCREEN_SIZE_KEY, "");
        return base.isEmpty() ? container.getScreenSize() : base;
    }

    /** Scales a "WxH" screen size, keeping it even and above the X server minimum. */
    public static String scaleScreenSize(String screenSize, int percent) {
        try {
            String[] parts = screenSize.split("x");
            int width = Integer.parseInt(parts[0].trim());
            int height = Integer.parseInt(parts[1].trim());
            int scaledWidth = Math.max(ScreenInfo.MIN_WIDTH, Math.round(width * percent / 100.0f));
            int scaledHeight = Math.max(ScreenInfo.MIN_HEIGHT, Math.round(height * percent / 100.0f));
            return (scaledWidth - (scaledWidth % 2))+"x"+(scaledHeight - (scaledHeight % 2));
        }
        catch (RuntimeException e) {
            return screenSize;
        }
    }

    public static void save(Container container, int mode, int scale, int sharpness) {
        final int previousScale = getScale(container);
        String base = container.getExtra(BASE_SCREEN_SIZE_KEY, "");
        final String current = container.getScreenSize();

        // If the current size is not the one this feature last wrote, the user changed it
        // by hand, and that size is the new base instead of the stale one.
        if (!base.isEmpty() && !current.equals(scaleScreenSize(base, previousScale))) base = current;
        if (base.isEmpty()) base = current;

        container.putExtra(BASE_SCREEN_SIZE_KEY, base);
        container.setScreenSize(mode == MODE_OFF ? base : scaleScreenSize(base, scale));
        container.putExtra("fsrMode", Math.max(MODE_OFF, Math.min(MODE_BILINEAR, mode)));
        container.putExtra("fsrScale", scale);
        container.putExtra("fsrSharpness", Math.max(0, Math.min(100, sharpness)));
        container.saveData();
    }

    /**
     * Enables the upscaler on this session's renderer. The composer draws the X screen
     * one to one and the effects upscale it to the panel, so the picture the game
     * produced is what gets sharpened rather than an already stretched one.
     */
    public static void apply(GLRenderer renderer, Container container, ScreenInfo screenInfo, int panelWidth) {
        if (container == null || screenInfo == null) return;

        final int mode = getMode(container);
        if (mode == MODE_OFF) return;

        final int sharpness = getSharpness(container);
        final boolean upscaling = panelWidth > 0 && screenInfo.width < panelWidth;

        if (upscaling) renderer.effectComposer.setSourceSize(screenInfo.width, screenInfo.height);

        String path;
        if (upscaling && mode == MODE_EASU) {
            renderer.effectComposer.addEffect(new FSREffect(Math.round(100.0f * screenInfo.width / panelWidth)));
            path = "EASU + RCAS";
        }
        else if (upscaling) {
            renderer.effectComposer.addEffect(new BilinearEffect());
            path = "bilinear + RCAS (fallback)";
        }
        else path = "RCAS only (no upscale)";

        renderer.effectComposer.addEffect(new FSRSharpenEffect(sharpness));

        Log.d(LOG_TAG, "FSR 1 active: "+path+", screen "+screenInfo+" -> panel width "+panelWidth+", sharpness "+sharpness);
    }
}
