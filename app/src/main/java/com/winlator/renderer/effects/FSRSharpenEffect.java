package com.winlator.renderer.effects;

import com.winlator.renderer.material.ScreenMaterial;

/*
 * AMD FidelityFX Super Resolution 1 - RCAS stage (FsrRcasF).
 *
 * Ported from ffx_fsr1.h / ffx_a.h (v1.20210629), 32-bit non-packed path,
 * with FSR_RCAS_DENOISE enabled and FSR_RCAS_LIMIT at its default value.
 *
 * Copyright (c) 2021 Advanced Micro Devices, Inc. All rights reserved.
 * Permission is hereby granted, free of charge, to any person obtaining a copy
 * of this software and associated documentation files(the "Software"), to deal
 * in the Software without restriction, including without limitation the rights
 * to use, copy, modify, merge, publish, distribute, sublicense, and / or sell
 * copies of the Software, and to permit persons to whom the Software is
 * furnished to do so, subject to the following conditions :
 * The above copyright notice and this permission notice shall be included in
 * all copies or substantial portions of the Software.
 * THE SOFTWARE IS PROVIDED "AS IS", WITHOUT WARRANTY OF ANY KIND, EXPRESS OR
 * IMPLIED, INCLUDING BUT NOT LIMITED TO THE WARRANTIES OF MERCHANTABILITY,
 * FITNESS FOR A PARTICULAR PURPOSE AND NONINFRINGEMENT.IN NO EVENT SHALL THE
 * AUTHORS OR COPYRIGHT HOLDERS BE LIABLE FOR ANY CLAIM, DAMAGES OR OTHER
 * LIABILITY, WHETHER IN AN ACTION OF CONTRACT, TORT OR OTHERWISE, ARISING FROM,
 * OUT OF OR IN CONNECTION WITH THE SOFTWARE OR THE USE OR OTHER DEALINGS IN
 * THE SOFTWARE.
 *
 * The sharpness constant is uploaded already transformed: the reference stores
 * exp2(-stops) in con.x, so the uniform below carries that value directly.
 * RCAS only looks at the four axis neighbours, so the bottom-up orientation of
 * our render targets does not need the row flip that EASU does.
 */
public class FSRSharpenEffect extends Effect {
    // 0 = sharpest, 100 = softest. FSR's own default is 0.2 stops.
    public static final int DEFAULT_SHARPNESS = 80;

    private final int sharpnessPercent;
    private final ScreenMaterial.Uniform sharpnessUniform = new ScreenMaterial.Uniform("sharpness");

    public FSRSharpenEffect(int sharpnessPercent) {
        this.sharpnessPercent = Math.max(0, Math.min(100, sharpnessPercent));
    }

    public int getSharpnessPercent() {
        return sharpnessPercent;
    }

    /** Converts the 0..100 UI value to FSR's "stops of reduction" scale. */
    private float getSharpnessStops() {
        return (100 - sharpnessPercent) / 100.0f;
    }

    @Override
    public ScreenMaterial createMaterial() {
        return new ScreenMaterial() {
            @Override
            protected String getFragmentShader() {
                return String.join("\n",
                    "precision highp float;",
                    "precision highp int;",

                    "#define RCAS_LIMIT 0.1875",

                    "uniform sampler2D screenTexture;",
                    "uniform vec2 resolution;",
                    "uniform float sharpness;",

                    "layout(location = 0) out vec4 outFragColor;",

                    "float prxMedRcp(float a) {",
                        "float b = uintBitsToFloat(0x7EF19FFFu - floatBitsToUint(a));",
                        "return b * (-b * a + 2.0);",
                    "}",

                    "vec4 fetch(ivec2 p) {",
                        "ivec2 maxp = ivec2(int(resolution.x) - 1, int(resolution.y) - 1);",
                        "return texelFetch(screenTexture, clamp(p, ivec2(0), maxp), 0);",
                    "}",

                    "float luma2(vec3 c) { return c.b * 0.5 + (c.r * 0.5 + c.g); }",

                    "void main() {",
                        "ivec2 sp = ivec2(gl_FragCoord.xy);",
                        "vec4 ee = fetch(sp);",
                        "vec3 b = fetch(sp + ivec2( 0, -1)).rgb;",
                        "vec3 d = fetch(sp + ivec2(-1,  0)).rgb;",
                        "vec3 e = ee.rgb;",
                        "vec3 f = fetch(sp + ivec2( 1,  0)).rgb;",
                        "vec3 h = fetch(sp + ivec2( 0,  1)).rgb;",

                        "float bL = luma2(b);",
                        "float dL = luma2(d);",
                        "float eL = luma2(e);",
                        "float fL = luma2(f);",
                        "float hL = luma2(h);",

                        // Noise detection.
                        "float nz = 0.25 * bL + 0.25 * dL + 0.25 * fL + 0.25 * hL - eL;",
                        "nz = clamp(abs(nz) * prxMedRcp(max(max(max(bL, dL), max(eL, fL)), hL) - min(min(min(bL, dL), min(eL, fL)), hL)), 0.0, 1.0);",
                        "nz = -0.5 * nz + 1.0;",

                        // Min and max of ring.
                        "float mn4R = min(min(b.r, d.r), min(f.r, h.r));",
                        "float mn4G = min(min(b.g, d.g), min(f.g, h.g));",
                        "float mn4B = min(min(b.b, d.b), min(f.b, h.b));",
                        "float mx4R = max(max(b.r, d.r), max(f.r, h.r));",
                        "float mx4G = max(max(b.g, d.g), max(f.g, h.g));",
                        "float mx4B = max(max(b.b, d.b), max(f.b, h.b));",

                        // High precision reciprocal limiters.
                        "float hitMinR = min(mn4R, e.r) / (4.0 * mx4R);",
                        "float hitMinG = min(mn4G, e.g) / (4.0 * mx4G);",
                        "float hitMinB = min(mn4B, e.b) / (4.0 * mx4B);",
                        "float hitMaxR = (1.0 - max(mx4R, e.r)) / (4.0 * mn4R - 4.0);",
                        "float hitMaxG = (1.0 - max(mx4G, e.g)) / (4.0 * mn4G - 4.0);",
                        "float hitMaxB = (1.0 - max(mx4B, e.b)) / (4.0 * mn4B - 4.0);",

                        "float lobeR = max(-hitMinR, hitMaxR);",
                        "float lobeG = max(-hitMinG, hitMaxG);",
                        "float lobeB = max(-hitMinB, hitMaxB);",
                        "float lobe = max(-RCAS_LIMIT, min(max(max(lobeR, lobeG), lobeB), 0.0)) * sharpness;",

                        // Apply noise removal.
                        "lobe *= nz;",

                        "float rcpL = prxMedRcp(4.0 * lobe + 1.0);",
                        "vec3 pix = (lobe * b + lobe * d + lobe * h + lobe * f + e) * rcpL;",
                        "outFragColor = vec4(pix, ee.a);",
                    "}"
                );
            }

            @Override
            public void use() {
                super.use();
                setUniformFloat(sharpnessUniform, (float)Math.pow(2.0, -getSharpnessStops()));
            }
        };
    }
}
