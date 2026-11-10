package com.winlator.core;

import com.winlator.container.Container;

/**
 * Per-container upscaler settings, stored in the container's extra data so that
 * adding them does not change the container file schema.
 */
public class Upscaler {
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
}
