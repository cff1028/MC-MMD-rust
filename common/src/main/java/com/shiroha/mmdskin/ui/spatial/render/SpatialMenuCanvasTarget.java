package com.shiroha.mmdskin.ui.spatial.render;

import com.shiroha.mmdskin.ui.spatial.lumen.input.InputState;
import com.shiroha.mmdskin.ui.spatial.lumen.render.Canvas;
import com.shiroha.mmdskin.ui.spatial.lumen.ui.MenuApp;
import com.shiroha.mmdskin.ui.spatial.lumen.ui.SurfaceSpec;
import org.joml.Matrix4fc;
import org.lwjgl.system.MemoryStack;

import java.util.Objects;
import java.util.function.Consumer;

import static org.lwjgl.nanovg.NanoVG.*;
import static org.lwjgl.nanovg.NanoVGGL3.*;
import static org.lwjgl.opengl.GL33C.*;

/**
 * A reusable, transparent NanoVG surface. Rendering updates the menu exactly once;
 * draw is side-effect-free with respect to menu state and may be called for both eyes.
 * All methods that touch an initialized target must run on its GL context thread.
 */
public final class SpatialMenuCanvasTarget implements AutoCloseable {
    public static final int PIXEL_RATIO = 2;
    public static final int WIDTH = Canvas.WIDTH * PIXEL_RATIO;
    public static final int HEIGHT = Canvas.HEIGHT * PIXEL_RATIO;
    private long vg;
    private Canvas canvas;
    private int framebuffer, texture, depthStencil, program, vertexArray;
    private int projectionUniform, cropUniform, samplerUniform, previewSizeUniform;
    private Thread contextThread;

    /** The caller owns frame deduplication; input.endFrame() is not called here. */
    public void render(MenuApp app, InputState input, float deltaSeconds) {
        Objects.requireNonNull(app);
        Objects.requireNonNull(input);
        renderSurface(surface -> {
            float dt = Float.isFinite(deltaSeconds) ? Math.max(0, deltaSeconds) : 0;
            app.renderSurface(surface, input, dt);
            if (Float.isFinite(input.x) && Float.isFinite(input.y) && input.x >= 0 &&
                input.y >= 0 && input.x < Canvas.WIDTH && input.y < Canvas.HEIGHT) {
                surface.circle(input.x, input.y, input.down ? 4 : 5, 0xEFFFFFFF);
                surface.stroke(input.x - 5, input.y - 5, 10, 10, 5, 0x990B1828, 1);
            }
        });
    }

    /** Shared antialiased canvas path for other VR surfaces, including the keyboard. */
    public void renderSurface(Consumer<Canvas> painter) {
        Objects.requireNonNull(painter);
        checkThread();
        try (NanoVgGlState ignored = new NanoVgGlState()) {
            NanoVgGlState.prepare();
            initialize();
            glBindFramebuffer(GL_FRAMEBUFFER, framebuffer);
            glViewport(0, 0, WIDTH, HEIGHT);
            glStencilMask(0xff);
            glClearColor(0, 0, 0, 0);
            glClearStencil(0);
            glClear(GL_COLOR_BUFFER_BIT | GL_STENCIL_BUFFER_BIT);
            boolean frameOpen = false;
            try {
                nvgBeginFrame(vg, Canvas.WIDTH, Canvas.HEIGHT, PIXEL_RATIO);
                frameOpen = true;
                painter.accept(canvas);
                nvgEndFrame(vg);
                frameOpen = false;
                canvas.finishFrame();
            } finally {
                if (frameOpen) nvgCancelFrame(vg);
            }
        }
    }

    /**
     * Draws only the spec's logical rectangle. The host MVP maps a centered unit quad
     * (x/y -0.5 .. +0.5, z=0) to the physical panel and supplies the current eye matrices.
     * This is an overlay: input should use the same no-world-occlusion policy.
     */
    public void draw(Matrix4fc mvp, SurfaceSpec spec) {
        draw(mvp, spec, false);
    }

    /** A faint, antialiased placement plane; reuses the quad without another texture or FBO. */
    public void drawDistancePreview(Matrix4fc mvp, SurfaceSpec spec) {
        draw(mvp, spec, true);
    }

    private void draw(Matrix4fc mvp, SurfaceSpec spec, boolean preview) {
        Objects.requireNonNull(mvp);
        Objects.requireNonNull(spec);
        if (texture == 0) return;
        checkThread();
        validateCrop(spec);
        if (!mvp.isFinite()) throw new IllegalArgumentException("Non-finite spatial menu transform");
        try (NanoVgGlState ignored = new NanoVgGlState(); MemoryStack stack = MemoryStack.stackPush()) {
            NanoVgGlState.prepare();
            glEnable(GL_BLEND);
            glBlendEquationSeparate(GL_FUNC_ADD, GL_FUNC_ADD);
            glBlendFuncSeparate(GL_ONE, GL_ONE_MINUS_SRC_ALPHA, GL_ONE, GL_ONE_MINUS_SRC_ALPHA);
            glUseProgram(program);
            glUniformMatrix4fv(projectionUniform, false, mvp.get(stack.mallocFloat(16)));
            // Logical origin is top-left; texture origin is bottom-left. Flip exactly once.
            glUniform4f(cropUniform, spec.x() / Canvas.WIDTH,
                1 - (spec.y() + spec.height()) / Canvas.HEIGHT,
                (spec.x() + spec.width()) / Canvas.WIDTH, 1 - spec.y() / Canvas.HEIGHT);
            glUniform1i(samplerUniform, 0);
            glUniform2f(previewSizeUniform, preview ? spec.width() : 0, preview ? spec.height() : 0);
            glBindTexture(GL_TEXTURE_2D, texture);
            glBindVertexArray(vertexArray);
            glDrawArrays(GL_TRIANGLES, 0, 6);
        }
    }

    public int textureId() { return texture; }
    public int framebufferId() { return framebuffer; }

    private void checkThread() {
        if (contextThread != null && contextThread != Thread.currentThread())
            throw new IllegalStateException("Spatial menu GL resources used off their context thread");
    }

    private void initialize() {
        if (vg != 0) return;
        contextThread = Thread.currentThread();
        try {
            texture = glGenTextures();
            glBindTexture(GL_TEXTURE_2D, texture);
            glTexImage2D(GL_TEXTURE_2D, 0, GL_RGBA8, WIDTH, HEIGHT, 0, GL_RGBA, GL_UNSIGNED_BYTE, 0L);
            glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_MIN_FILTER, GL_LINEAR);
            glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_MAG_FILTER, GL_LINEAR);
            glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_WRAP_S, GL_CLAMP_TO_EDGE);
            glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_WRAP_T, GL_CLAMP_TO_EDGE);
            depthStencil = glGenRenderbuffers();
            glBindRenderbuffer(GL_RENDERBUFFER, depthStencil);
            glRenderbufferStorage(GL_RENDERBUFFER, GL_DEPTH24_STENCIL8, WIDTH, HEIGHT);
            framebuffer = glGenFramebuffers();
            glBindFramebuffer(GL_FRAMEBUFFER, framebuffer);
            glFramebufferTexture2D(GL_FRAMEBUFFER, GL_COLOR_ATTACHMENT0, GL_TEXTURE_2D, texture, 0);
            glFramebufferRenderbuffer(GL_FRAMEBUFFER, GL_DEPTH_STENCIL_ATTACHMENT, GL_RENDERBUFFER, depthStencil);
            if (glCheckFramebufferStatus(GL_FRAMEBUFFER) != GL_FRAMEBUFFER_COMPLETE)
                throw new IllegalStateException("Spatial menu RGBA/stencil framebuffer is incomplete");
            program = createProgram();
            projectionUniform = glGetUniformLocation(program, "projection");
            cropUniform = glGetUniformLocation(program, "crop");
            samplerUniform = glGetUniformLocation(program, "panel");
            previewSizeUniform = glGetUniformLocation(program, "previewSize");
            vertexArray = glGenVertexArrays();
            vg = nvgCreate(NVG_ANTIALIAS | NVG_STENCIL_STROKES);
            if (vg == 0) throw new IllegalStateException("Cannot create spatial menu NanoVG context");
            canvas = new Canvas(vg);
        } catch (RuntimeException | Error failure) {
            deleteResources();
            throw failure;
        }
    }

    private static void validateCrop(SurfaceSpec spec) {
        if (!Float.isFinite(spec.x()) || !Float.isFinite(spec.y()) ||
            !Float.isFinite(spec.width()) || !Float.isFinite(spec.height()) ||
            spec.x() < 0 || spec.y() < 0 || spec.width() <= 0 || spec.height() <= 0 ||
            spec.x() + spec.width() > Canvas.WIDTH || spec.y() + spec.height() > Canvas.HEIGHT)
            throw new IllegalArgumentException("Spatial menu crop is outside the logical canvas");
    }

    private static int createProgram() {
        int vertex = 0, fragment = 0, result = 0;
        try {
            vertex = compile(GL_VERTEX_SHADER, """
                #version 330 core
                const vec2 points[6] = vec2[6](vec2(0,0),vec2(1,0),vec2(1,1),
                    vec2(0,0),vec2(1,1),vec2(0,1));
                uniform mat4 projection;
                uniform vec4 crop;
                out vec2 uv;
                out vec2 quad;
                void main() {
                    vec2 p = points[gl_VertexID];
                    quad = p;
                    uv = mix(crop.xy, crop.zw, p);
                    gl_Position = projection * vec4(p - vec2(0.5), 0.0, 1.0);
                }
                """);
            fragment = compile(GL_FRAGMENT_SHADER, """
                #version 330 core
                uniform sampler2D panel;
                uniform vec2 previewSize;
                in vec2 uv;
                in vec2 quad;
                out vec4 color;
                void main() {
                    if (previewSize.x <= 0.0) { color = texture(panel, uv); return; }
                    vec2 q = abs((quad - 0.5) * previewSize) - previewSize * 0.5 + 24.0;
                    float edge = length(max(q, 0.0)) + min(max(q.x, q.y), 0.0) - 24.0;
                    float aa = max(fwidth(edge), 0.75);
                    float inside = 1.0 - smoothstep(-aa, aa, edge);
                    float border = 1.0 - smoothstep(1.5 - aa, 1.5 + aa, abs(edge));
                    float opacity = mix(0.10, 0.50, border) * inside;
                    color = vec4(vec3(0.52, 0.76, 1.0) * opacity, opacity);
                }
                """);
            result = glCreateProgram();
            glAttachShader(result, vertex);
            glAttachShader(result, fragment);
            glLinkProgram(result);
            if (glGetProgrami(result, GL_LINK_STATUS) == GL_FALSE)
                throw new IllegalStateException("Spatial menu shader link: " + glGetProgramInfoLog(result));
            return result;
        } catch (RuntimeException | Error failure) {
            if (result != 0) glDeleteProgram(result);
            throw failure;
        } finally {
            if (vertex != 0) glDeleteShader(vertex);
            if (fragment != 0) glDeleteShader(fragment);
        }
    }

    private static int compile(int type, String source) {
        int shader = glCreateShader(type);
        glShaderSource(shader, source);
        glCompileShader(shader);
        if (glGetShaderi(shader, GL_COMPILE_STATUS) == GL_FALSE) {
            String error = glGetShaderInfoLog(shader);
            glDeleteShader(shader);
            throw new IllegalStateException("Spatial menu shader compile: " + error);
        }
        return shader;
    }

    /** May be initialized again after resource reload, on the same or a new context thread. */
    @Override public void close() {
        if (contextThread == null) return;
        checkThread();
        try (NanoVgGlState ignored = new NanoVgGlState()) { deleteResources(); }
    }

    private void deleteResources() {
        if (vg != 0) nvgDelete(vg);
        if (vertexArray != 0) glDeleteVertexArrays(vertexArray);
        if (program != 0) glDeleteProgram(program);
        if (framebuffer != 0) glDeleteFramebuffers(framebuffer);
        if (depthStencil != 0) glDeleteRenderbuffers(depthStencil);
        if (texture != 0) glDeleteTextures(texture);
        vg = 0;
        canvas = null;
        vertexArray = program = framebuffer = depthStencil = texture = 0;
        contextThread = null;
    }
}
