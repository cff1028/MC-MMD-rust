package com.shiroha.mmdskin.ui.spatial.render;

import org.lwjgl.system.MemoryStack;
import org.lwjgl.opengl.GL;
import org.lwjgl.opengl.GL40C;
import org.lwjgl.opengl.ARBDrawBuffersBlend;

import java.nio.ByteBuffer;

import static org.lwjgl.opengl.GL33C.*;

/**
 * Isolates the raw GL calls made by NanoVG and our panel compositor. No RenderSystem /
 * GlStateManager calls occur inside this scope, so their cached values remain valid when
 * the exact incoming GPU state has been restored. Must be used on the context thread.
 */
public final class NanoVgGlState implements AutoCloseable {
    private final int drawFramebuffer = glGetInteger(GL_DRAW_FRAMEBUFFER_BINDING);
    private final int readFramebuffer = glGetInteger(GL_READ_FRAMEBUFFER_BINDING);
    private final int renderbuffer = glGetInteger(GL_RENDERBUFFER_BINDING);
    private final int program = glGetInteger(GL_CURRENT_PROGRAM);
    private final int vao = glGetInteger(GL_VERTEX_ARRAY_BINDING);
    private final int arrayBuffer = glGetInteger(GL_ARRAY_BUFFER_BINDING);
    private final int elementBuffer = glGetInteger(GL_ELEMENT_ARRAY_BUFFER_BINDING);
    private final int uniformBuffer = glGetInteger(GL_UNIFORM_BUFFER_BINDING);
    private final int uniformBuffer0 = glGetIntegeri(GL_UNIFORM_BUFFER_BINDING, 0);
    private final long uniformStart0 = glGetInteger64i(GL_UNIFORM_BUFFER_START, 0);
    private final long uniformSize0 = glGetInteger64i(GL_UNIFORM_BUFFER_SIZE, 0);
    private final int unpackBuffer = glGetInteger(GL_PIXEL_UNPACK_BUFFER_BINDING);
    private final int activeTexture = glGetInteger(GL_ACTIVE_TEXTURE);
    private final int texture0;
    private final int sampler0 = glGetIntegeri(GL_SAMPLER_BINDING, 0);
    private final int[] viewport = new int[4], scissor = new int[4], polygonMode = new int[2];
    private final float[] clearColor = new float[4];
    private final boolean[] colorMask = new boolean[4];
    private final DrawBuffer[] drawBuffers;
    private final boolean[] clipDistances;
    private final int clearStencil = glGetInteger(GL_STENCIL_CLEAR_VALUE);
    private final double clearDepth = glGetDouble(GL_DEPTH_CLEAR_VALUE);
    private final boolean depthMask = glGetBoolean(GL_DEPTH_WRITEMASK);
    private final int depthFunc = glGetInteger(GL_DEPTH_FUNC);
    private final int cullFace = glGetInteger(GL_CULL_FACE_MODE);
    private final int frontFace = glGetInteger(GL_FRONT_FACE);
    private final int blendSrcRgb = glGetInteger(GL_BLEND_SRC_RGB);
    private final int blendDstRgb = glGetInteger(GL_BLEND_DST_RGB);
    private final int blendSrcAlpha = glGetInteger(GL_BLEND_SRC_ALPHA);
    private final int blendDstAlpha = glGetInteger(GL_BLEND_DST_ALPHA);
    private final int blendEqRgb = glGetInteger(GL_BLEND_EQUATION_RGB);
    private final int blendEqAlpha = glGetInteger(GL_BLEND_EQUATION_ALPHA);
    private final int unpackAlignment = glGetInteger(GL_UNPACK_ALIGNMENT);
    private final int unpackRowLength = glGetInteger(GL_UNPACK_ROW_LENGTH);
    private final int unpackSkipPixels = glGetInteger(GL_UNPACK_SKIP_PIXELS);
    private final int unpackSkipRows = glGetInteger(GL_UNPACK_SKIP_ROWS);
    private final boolean unpackSwapBytes = glGetBoolean(GL_UNPACK_SWAP_BYTES);
    private final boolean unpackLsbFirst = glGetBoolean(GL_UNPACK_LSB_FIRST);
    private final Stencil front = new Stencil(false), back = new Stencil(true);
    private static final int[] CAPABILITIES = {GL_BLEND, GL_DEPTH_TEST, GL_CULL_FACE,
        GL_SCISSOR_TEST, GL_STENCIL_TEST, GL_FRAMEBUFFER_SRGB, GL_RASTERIZER_DISCARD,
        GL_COLOR_LOGIC_OP, GL_SAMPLE_ALPHA_TO_COVERAGE, GL_SAMPLE_COVERAGE};
    private final boolean[] enabled = new boolean[CAPABILITIES.length];
    private boolean restored;

    public NanoVgGlState() {
        glGetIntegerv(GL_VIEWPORT, viewport);
        glGetIntegerv(GL_SCISSOR_BOX, scissor);
        glGetIntegerv(GL_POLYGON_MODE, polygonMode);
        glGetFloatv(GL_COLOR_CLEAR_VALUE, clearColor);
        try (MemoryStack stack = MemoryStack.stackPush()) {
            ByteBuffer mask = stack.malloc(4);
            glGetBooleanv(GL_COLOR_WRITEMASK, mask);
            for (int i = 0; i < 4; i++) colorMask[i] = mask.get(i) != 0;
        }
        for (int i = 0; i < enabled.length; i++) enabled[i] = glIsEnabled(CAPABILITIES[i]);
        drawBuffers = new DrawBuffer[glGetInteger(GL_MAX_DRAW_BUFFERS)];
        for (int i = 0; i < drawBuffers.length; i++) drawBuffers[i] = new DrawBuffer(i);
        clipDistances = new boolean[glGetInteger(GL_MAX_CLIP_DISTANCES)];
        for (int i = 0; i < clipDistances.length; i++) clipDistances[i] = glIsEnabled(GL_CLIP_DISTANCE0 + i);
        glActiveTexture(GL_TEXTURE0);
        texture0 = glGetInteger(GL_TEXTURE_BINDING_2D);
        glActiveTexture(activeTexture);
    }

    /** Neutral state shared by NanoVG and the texture compositor. Does not bind a target. */
    static void prepare() {
        glDisable(GL_SCISSOR_TEST);
        glDisable(GL_DEPTH_TEST);
        glDisable(GL_CULL_FACE);
        glDisable(GL_STENCIL_TEST);
        glDisable(GL_FRAMEBUFFER_SRGB);
        glDisable(GL_RASTERIZER_DISCARD);
        glDisable(GL_COLOR_LOGIC_OP);
        glDisable(GL_SAMPLE_ALPHA_TO_COVERAGE);
        glDisable(GL_SAMPLE_COVERAGE);
        for (int i = 0, count = glGetInteger(GL_MAX_CLIP_DISTANCES); i < count; i++)
            glDisable(GL_CLIP_DISTANCE0 + i);
        glPolygonMode(GL_FRONT_AND_BACK, GL_FILL);
        glColorMask(true, true, true, true);
        glDepthMask(false);
        // NanoVG sets blend factors but assumes additive equations from the host.
        glBlendEquationSeparate(GL_FUNC_ADD, GL_FUNC_ADD);
        glBlendFuncSeparate(GL_ONE, GL_ONE_MINUS_SRC_ALPHA, GL_ONE, GL_ONE_MINUS_SRC_ALPHA);
        glActiveTexture(GL_TEXTURE0);
        glBindSampler(0, 0);
        glBindBuffer(GL_PIXEL_UNPACK_BUFFER, 0);
        glPixelStorei(GL_UNPACK_ALIGNMENT, 1);
        glPixelStorei(GL_UNPACK_ROW_LENGTH, 0);
        glPixelStorei(GL_UNPACK_SKIP_PIXELS, 0);
        glPixelStorei(GL_UNPACK_SKIP_ROWS, 0);
        glPixelStorei(GL_UNPACK_SWAP_BYTES, GL_FALSE);
        glPixelStorei(GL_UNPACK_LSB_FIRST, GL_FALSE);
    }

    @Override public void close() {
        if (restored) return;
        restored = true;
        glBindFramebuffer(GL_DRAW_FRAMEBUFFER, drawFramebuffer);
        glBindFramebuffer(GL_READ_FRAMEBUFFER, readFramebuffer);
        glBindRenderbuffer(GL_RENDERBUFFER, renderbuffer);
        glViewport(viewport[0], viewport[1], viewport[2], viewport[3]);
        glScissor(scissor[0], scissor[1], scissor[2], scissor[3]);
        glUseProgram(program);
        glBindVertexArray(vao);
        glBindBuffer(GL_ARRAY_BUFFER, arrayBuffer);
        // Element bindings belong to a VAO; binding them with VAO 0 is invalid in core GL.
        if (vao != 0) glBindBuffer(GL_ELEMENT_ARRAY_BUFFER, elementBuffer);
        if (uniformBuffer0 != 0 && uniformSize0 > 0)
            glBindBufferRange(GL_UNIFORM_BUFFER, 0, uniformBuffer0, uniformStart0, uniformSize0);
        else glBindBufferBase(GL_UNIFORM_BUFFER, 0, uniformBuffer0);
        glBindBuffer(GL_UNIFORM_BUFFER, uniformBuffer);
        glBindBuffer(GL_PIXEL_UNPACK_BUFFER, unpackBuffer);
        glActiveTexture(GL_TEXTURE0);
        glBindTexture(GL_TEXTURE_2D, texture0);
        glBindSampler(0, sampler0);
        glActiveTexture(activeTexture);
        glBlendFuncSeparate(blendSrcRgb, blendDstRgb, blendSrcAlpha, blendDstAlpha);
        glBlendEquationSeparate(blendEqRgb, blendEqAlpha);
        glDepthMask(depthMask);
        glDepthFunc(depthFunc);
        glCullFace(cullFace);
        glFrontFace(frontFace);
        glColorMask(colorMask[0], colorMask[1], colorMask[2], colorMask[3]);
        // The game's core profile only supports one polygon mode for both faces.
        glPolygonMode(GL_FRONT_AND_BACK, polygonMode[0]);
        front.restore(GL_FRONT);
        back.restore(GL_BACK);
        glClearColor(clearColor[0], clearColor[1], clearColor[2], clearColor[3]);
        glClearStencil(clearStencil);
        glClearDepth(clearDepth);
        glPixelStorei(GL_UNPACK_ALIGNMENT, unpackAlignment);
        glPixelStorei(GL_UNPACK_ROW_LENGTH, unpackRowLength);
        glPixelStorei(GL_UNPACK_SKIP_PIXELS, unpackSkipPixels);
        glPixelStorei(GL_UNPACK_SKIP_ROWS, unpackSkipRows);
        glPixelStorei(GL_UNPACK_SWAP_BYTES, unpackSwapBytes ? GL_TRUE : GL_FALSE);
        glPixelStorei(GL_UNPACK_LSB_FIRST, unpackLsbFirst ? GL_TRUE : GL_FALSE);
        for (int i = 0; i < enabled.length; i++) {
            if (enabled[i]) glEnable(CAPABILITIES[i]); else glDisable(CAPABILITIES[i]);
        }
        // Restore per-target masks/enables after global calls above. Shader packs may use MRT.
        for (int i = 0; i < drawBuffers.length; i++) drawBuffers[i].restore(i);
        for (int i = 0; i < clipDistances.length; i++) {
            if (clipDistances[i]) glEnable(GL_CLIP_DISTANCE0 + i); else glDisable(GL_CLIP_DISTANCE0 + i);
        }
    }

    private static final class DrawBuffer {
        private final boolean blend;
        private final boolean[] colorMask = new boolean[4];
        private final boolean indexedBlend = GL.getCapabilities().OpenGL40 ||
            GL.getCapabilities().GL_ARB_draw_buffers_blend;
        private final int[] factors;
        DrawBuffer(int index) {
            blend = glIsEnabledi(GL_BLEND, index);
            try (MemoryStack stack = MemoryStack.stackPush()) {
                ByteBuffer mask = stack.malloc(4);
                glGetBooleani_v(GL_COLOR_WRITEMASK, index, mask);
                for (int i = 0; i < 4; i++) colorMask[i] = mask.get(i) != 0;
            }
            factors = indexedBlend ? new int[]{glGetIntegeri(GL_BLEND_SRC_RGB, index),
                glGetIntegeri(GL_BLEND_DST_RGB, index), glGetIntegeri(GL_BLEND_SRC_ALPHA, index),
                glGetIntegeri(GL_BLEND_DST_ALPHA, index), glGetIntegeri(GL_BLEND_EQUATION_RGB, index),
                glGetIntegeri(GL_BLEND_EQUATION_ALPHA, index)} : null;
        }
        void restore(int index) {
            if (blend) glEnablei(GL_BLEND, index); else glDisablei(GL_BLEND, index);
            glColorMaski(index, colorMask[0], colorMask[1], colorMask[2], colorMask[3]);
            if (indexedBlend) {
                if (GL.getCapabilities().OpenGL40) {
                    GL40C.glBlendFuncSeparatei(index, factors[0], factors[1], factors[2], factors[3]);
                    GL40C.glBlendEquationSeparatei(index, factors[4], factors[5]);
                } else {
                    ARBDrawBuffersBlend.glBlendFuncSeparateiARB(index, factors[0], factors[1], factors[2], factors[3]);
                    ARBDrawBuffersBlend.glBlendEquationSeparateiARB(index, factors[4], factors[5]);
                }
            }
        }
    }

    private static final class Stencil {
        private final int function, reference, valueMask, writeMask, fail, depthFail, depthPass;
        Stencil(boolean back) {
            function = glGetInteger(back ? GL_STENCIL_BACK_FUNC : GL_STENCIL_FUNC);
            reference = glGetInteger(back ? GL_STENCIL_BACK_REF : GL_STENCIL_REF);
            valueMask = glGetInteger(back ? GL_STENCIL_BACK_VALUE_MASK : GL_STENCIL_VALUE_MASK);
            writeMask = glGetInteger(back ? GL_STENCIL_BACK_WRITEMASK : GL_STENCIL_WRITEMASK);
            fail = glGetInteger(back ? GL_STENCIL_BACK_FAIL : GL_STENCIL_FAIL);
            depthFail = glGetInteger(back ? GL_STENCIL_BACK_PASS_DEPTH_FAIL : GL_STENCIL_PASS_DEPTH_FAIL);
            depthPass = glGetInteger(back ? GL_STENCIL_BACK_PASS_DEPTH_PASS : GL_STENCIL_PASS_DEPTH_PASS);
        }
        void restore(int face) {
            glStencilFuncSeparate(face, function, reference, valueMask);
            glStencilMaskSeparate(face, writeMask);
            glStencilOpSeparate(face, fail, depthFail, depthPass);
        }
    }
}
