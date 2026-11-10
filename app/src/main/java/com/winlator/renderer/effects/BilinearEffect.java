package com.winlator.renderer.effects;

import com.winlator.renderer.material.ScreenMaterial;

/**
 * Plain bilinear upscale. Used as the fallback upscaler when FSR's EASU is not
 * wanted, so the reduced-resolution scene still reaches the panel with the same
 * RCAS sharpening appended after it.
 */
public class BilinearEffect extends Effect {
    @Override
    public ScreenMaterial createMaterial() {
        return new ScreenMaterial();
    }
}
