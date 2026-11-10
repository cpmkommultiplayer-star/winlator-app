package com.winlator.renderer;

import android.opengl.GLES20;
import android.util.Log;

import com.winlator.renderer.effects.Effect;
import com.winlator.renderer.material.ScreenMaterial;

import java.util.ArrayList;

public class EffectComposer {
    public static final float MIN_RENDER_SCALE = 0.5f;

    private final GLRenderer renderer;
    private RenderTarget sceneBuffer = null;
    private int sceneWidth = 0;
    private int sceneHeight = 0;
    private RenderTarget readBuffer = null;
    private RenderTarget writeBuffer = null;
    private int bufferWidth = 0;
    private int bufferHeight = 0;
    private float renderScale = 1.0f;
    private int sourceWidth = 0;
    private int sourceHeight = 0;
    private float sceneScale = 1.0f;
    private final ArrayList<Effect> effects = new ArrayList<>();
    private int reportedErrors = 0;

    public EffectComposer(GLRenderer renderer) {
        this.renderer = renderer;
    }

    public synchronized void addEffect(Effect effect) {
        if (!effects.contains(effect)) effects.add(effect);
        renderer.xServerView.requestRender();
    }

    public synchronized void removeEffect(Effect effect) {
        effects.remove(effect);
        renderer.xServerView.requestRender();
    }

    public synchronized  <T extends Effect> T getEffect(Class<T> effectClass) {
        for (Effect effect : effects) {
            if (effect.getClass() == effectClass) return (T)effect;
        }
        return null;
    }

    public synchronized boolean hasEffects() {
        return !effects.isEmpty();
    }

    /**
     * Resolution the scene is drawn at, as a fraction of the surface. Effects that
     * upscale (FSR EASU) read this buffer, so lowering it is what makes them cheaper
     * than drawing the scene at full surface resolution.
     */
    public synchronized void setRenderScale(float renderScale) {
        float scale = Math.max(MIN_RENDER_SCALE, Math.min(1.0f, renderScale));
        if (this.renderScale == scale) return;
        this.renderScale = scale;
        sceneWidth = 0;
        sceneHeight = 0;
        renderer.viewportNeedsUpdate = true;
        renderer.xServerView.requestRender();
    }

    public synchronized float getRenderScale() {
        return renderScale;
    }

    /**
     * Draws the scene at exactly this size instead of a fraction of the surface, so a
     * smaller X screen is sampled one to one and the upscaler has the real picture to
     * work from rather than a frame that was already stretched to the surface.
     * Ignored when the size is not smaller than the surface.
     */
    public synchronized void setSourceSize(int width, int height) {
        int w = Math.max(1, width);
        int h = Math.max(1, height);
        if (sourceWidth == w && sourceHeight == h) return;
        sourceWidth = w;
        sourceHeight = h;
        sceneWidth = 0;
        sceneHeight = 0;
        renderer.viewportNeedsUpdate = true;
        renderer.xServerView.requestRender();
    }

    public synchronized int getSourceWidth() {
        return sourceWidth;
    }

    public synchronized int getSourceHeight() {
        return sourceHeight;
    }

    private void swapBuffers() {
        RenderTarget tmp = writeBuffer;
        writeBuffer = readBuffer;
        readBuffer = tmp;
    }

    private void renderEffect(Effect effect, int inputTextureId, int inputWidth, int inputHeight) {
        ScreenMaterial material = effect.getMaterial();
        material.use();
        renderer.quadVertices.bind(material.programId);

        material.setUniformVec2(material.uniforms.resolution, renderer.surfaceWidth, renderer.surfaceHeight);
        material.setUniformVec2(material.uniforms.inputSize, inputWidth, inputHeight);

        GLES20.glActiveTexture(GLES20.GL_TEXTURE0);
        GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, inputTextureId);
        material.setUniformInt(material.uniforms.screenTexture, 0);
        GLES20.glDrawArrays(GLES20.GL_TRIANGLE_STRIP, 0, renderer.quadVertices.count());
        GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, 0);
    }

    private void initBuffers() {
        int sw = Math.max(1, Math.round(renderer.surfaceWidth * renderScale));
        int sh = Math.max(1, Math.round(renderer.surfaceHeight * renderScale));

        if (sourceWidth > 0 && sourceHeight > 0 && sourceWidth < renderer.surfaceWidth && sourceHeight < renderer.surfaceHeight) {
            sw = sourceWidth;
            sh = sourceHeight;
        }

        sceneScale = sw / (float)renderer.surfaceWidth;

        if (sceneBuffer == null || sceneWidth != sw || sceneHeight != sh) {
            if (sceneBuffer != null) sceneBuffer.destroy();
            sceneBuffer = new RenderTarget();
            sceneBuffer.allocateFramebuffer(sw, sh);
            sceneWidth = sw;
            sceneHeight = sh;
        }

        if (readBuffer == null || writeBuffer == null || bufferWidth != renderer.surfaceWidth || bufferHeight != renderer.surfaceHeight) {
            if (readBuffer != null) readBuffer.destroy();
            if (writeBuffer != null) writeBuffer.destroy();
            readBuffer = new RenderTarget();
            readBuffer.allocateFramebuffer(renderer.surfaceWidth, renderer.surfaceHeight);
            writeBuffer = new RenderTarget();
            writeBuffer.allocateFramebuffer(renderer.surfaceWidth, renderer.surfaceHeight);
            bufferWidth = renderer.surfaceWidth;
            bufferHeight = renderer.surfaceHeight;
        }
    }

    public synchronized void render() {
        initBuffers();

        final ViewTransformation viewTransformation = renderer.viewTransformation;
        final short surfaceWidth = renderer.surfaceWidth;
        final short surfaceHeight = renderer.surfaceHeight;
        final int viewOffsetX = viewTransformation.viewOffsetX;
        final int viewOffsetY = viewTransformation.viewOffsetY;
        final int viewWidth = viewTransformation.viewWidth;
        final int viewHeight = viewTransformation.viewHeight;

        // The scene is drawn into a buffer of its own size, so the viewport GLRenderer
        // computes from the surface has to be expressed in that buffer's pixels.
        if (sceneScale != 1.0f) {
            renderer.surfaceWidth = (short)sceneWidth;
            renderer.surfaceHeight = (short)sceneHeight;
            viewTransformation.viewOffsetX = Math.round(viewOffsetX * sceneScale);
            viewTransformation.viewOffsetY = Math.round(viewOffsetY * sceneScale);
            viewTransformation.viewWidth = Math.round(viewWidth * sceneScale);
            viewTransformation.viewHeight = Math.round(viewHeight * sceneScale);
        }

        renderer.viewportNeedsUpdate = true;
        GLES20.glBindFramebuffer(GLES20.GL_FRAMEBUFFER, sceneBuffer.getFramebuffer());
        renderer.drawFrame();

        if (sceneScale != 1.0f) {
            renderer.surfaceWidth = surfaceWidth;
            renderer.surfaceHeight = surfaceHeight;
            viewTransformation.viewOffsetX = viewOffsetX;
            viewTransformation.viewOffsetY = viewOffsetY;
            viewTransformation.viewWidth = viewWidth;
            viewTransformation.viewHeight = viewHeight;
        }

        int inputTextureId = sceneBuffer.getTextureId();
        int inputWidth = sceneWidth;
        int inputHeight = sceneHeight;

        for (int i = 0; i < effects.size(); i++) {
            Effect effect = effects.get(i);
            boolean renderToScreen = i == effects.size()-1;

            GLES20.glBindFramebuffer(GLES20.GL_FRAMEBUFFER, !renderToScreen ? writeBuffer.getFramebuffer() : 0);
            GLES20.glViewport(0, 0, renderer.surfaceWidth, renderer.surfaceHeight);
            renderer.viewportNeedsUpdate = true;
            GLES20.glClear(GLES20.GL_COLOR_BUFFER_BIT);

            renderEffect(effect, inputTextureId, inputWidth, inputHeight);

            int glError = GLES20.glGetError();
            if (glError != 0 && reportedErrors < 8) {
                Log.e("WinlatorShader", "GL error 0x"+Integer.toHexString(glError)+" in effect pass "+i+" ("+effect.getClass().getSimpleName()+")");
                reportedErrors++;
            }

            swapBuffers();

            inputTextureId = readBuffer.getTextureId();
            inputWidth = renderer.surfaceWidth;
            inputHeight = renderer.surfaceHeight;
        }
    }
}
