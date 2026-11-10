package com.winlator.core;

import android.util.Log;

import com.winlator.container.Container;
import com.winlator.renderer.GLRenderer;
import com.winlator.renderer.effects.BilinearEffect;
import com.winlator.renderer.effects.FSREffect;
import com.winlator.renderer.effects.FSRSharpenEffect;

/**
 * Per-container upscaler settings, stored in the container's extra data so that
 * adding them does not change the container file schema.
 */
public class Upscaler {
    public static final String LOG_TAG = "WinlatorFSR";

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

    public static void save(Container container, int mode, int scale, int sharpness) {
        container.putExtra("fsrMode", Math.max(MODE_OFF, Math.min(MODE_BILINEAR, mode)));
        container.putExtra("fsrScale", scale);
        container.putExtra("fsrSharpness", Math.max(0, Math.min(100, sharpness)));
        container.saveData();
    }

    /**
     * Enables the upscaler on this session's renderer. The game keeps drawing at the
     * container's screen size; the composer draws the scene into a buffer at the
     * chosen fraction of the surface and the upscaler brings it back to the panel.
     */
    public static void apply(GLRenderer renderer, Container container) {
        if (container == null) return;

        int mode = getMode(container);
        if (mode == MODE_OFF) return;

        int scale = getScale(container);
        int sharpness = getSharpness(container);
        boolean downscale = scale < 100;

        if (downscale) renderer.effectComposer.setRenderScale(scale / 100.0f);

        String path;
        if (downscale && mode == MODE_EASU) {
            renderer.effectComposer.addEffect(new FSREffect(scale));
            path = "EASU + RCAS";
        }
        else if (downscale) {
            renderer.effectComposer.addEffect(new BilinearEffect());
            path = "bilinear + RCAS (fallback)";
        }
        else path = "RCAS only (no downscale)";

        renderer.effectComposer.addEffect(new FSRSharpenEffect(sharpness));

        Log.d(LOG_TAG, "FSR 1 active: "+path+", scale "+scale+"%, sharpness "+sharpness);
    }
}
