package com.winlator.renderer.effects;

import com.winlator.renderer.material.ScreenMaterial;

/*
 * AMD FidelityFX Super Resolution 1 - EASU stage (FsrEasuF).
 *
 * Ported from ffx_fsr1.h / ffx_a.h (v1.20210629), 32-bit non-packed path.
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
 * Differences from the reference header, all of which are behaviour preserving:
 *  - The EASU constants (con0..con3) are built in the shader from inputSize and
 *    outputSize instead of being uploaded from the CPU. The reference stores
 *    float bit patterns in uvec4 and bitcasts them back on use, so passing the
 *    ratios directly is the same computation.
 *  - textureGather is not available on GLES 3.0, so the four 2x2 gathers are
 *    replaced by explicit texelFetch calls in the same order. The reference
 *    samples a top-left origin image; our render targets are bottom-up, so the
 *    row index is flipped on fetch.
 */
public class FSREffect extends Effect {
    private final int scalePercent;

    public FSREffect(int scalePercent) {
        this.scalePercent = scalePercent;
    }

    public int getScalePercent() {
        return scalePercent;
    }

    @Override
    public ScreenMaterial createMaterial() {
        return new ScreenMaterial() {
            @Override
            protected String getFragmentShader() {
                return String.join("\n",
                    "precision highp float;",
                    "precision highp int;",

                    "uniform sampler2D screenTexture;",
                    "uniform vec2 resolution;",
                    "uniform vec2 inputSize;",

                    "layout(location = 0) out vec4 outFragColor;",

                    // ffx_a.h approximations, 32-bit path.
                    "float prxLoRcp(float a) { return uintBitsToFloat(0x7EF07EBBu - floatBitsToUint(a)); }",
                    "float prxLoRsq(float a) { return uintBitsToFloat(0x5F347D74u - (floatBitsToUint(a) >> 1u)); }",

                    // Fetch a texel given a top-left origin texel coordinate, as the reference does.
                    "vec3 tapF(vec2 p) {",
                        "ivec2 maxp = ivec2(int(inputSize.x) - 1, int(inputSize.y) - 1);",
                        "ivec2 ip = ivec2(int(p.x), int(inputSize.y) - 1 - int(p.y));",
                        "return texelFetch(screenTexture, clamp(ip, ivec2(0), maxp), 0).rgb;",
                    "}",

                    // Luma times 2.
                    "float luma(vec3 c) { return c.b * 0.5 + (c.r * 0.5 + c.g); }",

                    "void setDir(inout vec2 dir, inout float len, float w, float lA, float lB, float lC, float lD, float lE) {",
                        "float dc = lD - lC;",
                        "float cb = lC - lB;",
                        "float lenX = max(abs(dc), abs(cb));",
                        "lenX = prxLoRcp(lenX);",
                        "float dirX = lD - lB;",
                        "dir.x += dirX * w;",
                        "lenX = clamp(abs(dirX) * lenX, 0.0, 1.0);",
                        "lenX *= lenX;",
                        "len += lenX * w;",

                        "float ec = lE - lC;",
                        "float ca = lC - lA;",
                        "float lenY = max(abs(ec), abs(ca));",
                        "lenY = prxLoRcp(lenY);",
                        "float dirY = lE - lA;",
                        "dir.y += dirY * w;",
                        "lenY = clamp(abs(dirY) * lenY, 0.0, 1.0);",
                        "lenY *= lenY;",
                        "len += lenY * w;",
                    "}",

                    "void tap(inout vec3 aC, inout float aW, vec2 off, vec2 dir, vec2 len2, float lob, float clp, vec3 c) {",
                        "vec2 v;",
                        "v.x = off.x * dir.x + off.y * dir.y;",
                        "v.y = off.x * (-dir.y) + off.y * dir.x;",
                        "v *= len2;",
                        "float d2 = v.x * v.x + v.y * v.y;",
                        "d2 = min(d2, clp);",
                        // Approximation of lanczos2 without sin() or rcp().
                        "float wB = 0.4 * d2 - 1.0;",
                        "float wA = lob * d2 - 1.0;",
                        "wB *= wB;",
                        "wA *= wA;",
                        "wB = 1.5625 * wB - 0.5625;",
                        "float w = wB * wA;",
                        "aC += c * w;",
                        "aW += w;",
                    "}",

                    "void main() {",
                        "vec2 op = vec2(floor(gl_FragCoord.x), resolution.y - 1.0 - floor(gl_FragCoord.y));",
                        "vec2 scale = inputSize / resolution;",
                        "vec2 pp = (op + 0.5) * scale - 0.5;",
                        "vec2 fp = floor(pp);",
                        "pp -= fp;",

                        "vec3 tB = tapF(fp + vec2( 0.0, -1.0));",
                        "vec3 tC = tapF(fp + vec2( 1.0, -1.0));",
                        "vec3 tE = tapF(fp + vec2(-1.0,  0.0));",
                        "vec3 tF = tapF(fp + vec2( 0.0,  0.0));",
                        "vec3 tG = tapF(fp + vec2( 1.0,  0.0));",
                        "vec3 tH = tapF(fp + vec2( 2.0,  0.0));",
                        "vec3 tI = tapF(fp + vec2(-1.0,  1.0));",
                        "vec3 tJ = tapF(fp + vec2( 0.0,  1.0));",
                        "vec3 tK = tapF(fp + vec2( 1.0,  1.0));",
                        "vec3 tL = tapF(fp + vec2( 2.0,  1.0));",
                        "vec3 tN = tapF(fp + vec2( 0.0,  2.0));",
                        "vec3 tO = tapF(fp + vec2( 1.0,  2.0));",

                        "float bL = luma(tB), cL = luma(tC), eL = luma(tE), fL = luma(tF), gL = luma(tG);",
                        "float hL = luma(tH), iL = luma(tI), jL = luma(tJ), kL = luma(tK), lL = luma(tL);",
                        "float nL = luma(tN), oL = luma(tO);",

                        "vec2 dir = vec2(0.0);",
                        "float len = 0.0;",
                        "float wx = pp.x;",
                        "float wy = pp.y;",
                        "setDir(dir, len, (1.0 - wx) * (1.0 - wy), bL, eL, fL, gL, jL);",
                        "setDir(dir, len, wx * (1.0 - wy), cL, fL, gL, hL, kL);",
                        "setDir(dir, len, (1.0 - wx) * wy, fL, iL, jL, kL, nL);",
                        "setDir(dir, len, wx * wy, gL, jL, kL, lL, oL);",

                        "vec2 dir2 = dir * dir;",
                        "float dirR = dir2.x + dir2.y;",
                        "if (dirR < (1.0 / 32768.0)) {",
                            "dir.x = 1.0;",
                            "dirR = 1.0;",
                        "}",
                        "else dirR = prxLoRsq(dirR);",
                        "dir *= dirR;",

                        "len *= 0.5;",
                        "len *= len;",
                        "float stretch = (dir.x * dir.x + dir.y * dir.y) * prxLoRcp(max(abs(dir.x), abs(dir.y)));",
                        "vec2 len2 = vec2(1.0 + (stretch - 1.0) * len, 1.0 - 0.5 * len);",
                        "float lob = 0.5 - 0.29 * len;",
                        "float clp = prxLoRcp(lob);",

                        "vec3 min4 = min(min(tF, tG), min(tJ, tK));",
                        "vec3 max4 = max(max(tF, tG), max(tJ, tK));",

                        "vec3 aC = vec3(0.0);",
                        "float aW = 0.0;",
                        "tap(aC, aW, vec2( 0.0, -1.0) - pp, dir, len2, lob, clp, tB);",
                        "tap(aC, aW, vec2( 1.0, -1.0) - pp, dir, len2, lob, clp, tC);",
                        "tap(aC, aW, vec2(-1.0,  1.0) - pp, dir, len2, lob, clp, tI);",
                        "tap(aC, aW, vec2( 0.0,  1.0) - pp, dir, len2, lob, clp, tJ);",
                        "tap(aC, aW, vec2( 0.0,  0.0) - pp, dir, len2, lob, clp, tF);",
                        "tap(aC, aW, vec2(-1.0,  0.0) - pp, dir, len2, lob, clp, tE);",
                        "tap(aC, aW, vec2( 1.0,  1.0) - pp, dir, len2, lob, clp, tK);",
                        "tap(aC, aW, vec2( 2.0,  1.0) - pp, dir, len2, lob, clp, tL);",
                        "tap(aC, aW, vec2( 2.0,  0.0) - pp, dir, len2, lob, clp, tH);",
                        "tap(aC, aW, vec2( 1.0,  0.0) - pp, dir, len2, lob, clp, tG);",
                        "tap(aC, aW, vec2( 1.0,  2.0) - pp, dir, len2, lob, clp, tO);",
                        "tap(aC, aW, vec2( 0.0,  2.0) - pp, dir, len2, lob, clp, tN);",

                        // Normalize and dering.
                        "vec3 pix = min(max4, max(min4, aC * (1.0 / aW)));",
                        "outFragColor = vec4(pix, 1.0);",
                    "}"
                );
            }
        };
    }
}
