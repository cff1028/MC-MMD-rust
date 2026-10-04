package com.shiroha.mmdskin.ui.spatial.render;

import com.mojang.blaze3d.systems.RenderSystem;
import com.shiroha.mmdskin.ui.spatial.lumen.render.Canvas;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.renderer.MultiBufferSource;
import net.minecraft.network.chat.Style;
import net.minecraft.util.FormattedCharSequence;
import org.joml.Matrix4f;
import org.joml.Matrix4fc;
import org.joml.Vector2f;
import org.joml.Vector4f;

import java.util.ArrayList;
import java.util.List;

import static org.lwjgl.nanovg.NanoVG.*;
import static org.lwjgl.nanovg.NanoVGGL3.*;
import static org.lwjgl.opengl.GL33C.*;

/**
 * Clear system-font text for native option submenus opened through the spatial menu.
 * The caller limits its scope to those screens. Minecraft font metrics remain the layout
 * contract, but no Minecraft glyphs are rendered. Text triangles require no stencil target.
 */
public final class SpatialMenuNativeFont {
    private static final float FONT_SIZE = 9;
    private static long vg;
    private static Canvas canvas;
    private static Thread contextThread;

    private SpatialMenuNativeFont() {}

    public static int draw(GuiGraphics graphics, Font nativeFont, FormattedCharSequence text,
                           float x, float y, int argb, boolean shadow) {
        return draw(nativeFont, text, x, y, argb, shadow, graphics.pose().last().pose(), graphics::flush);
    }

    /** Tooltip components bypass GuiGraphics.drawString and supply their own pose/buffer. */
    public static int draw(Font nativeFont, FormattedCharSequence text, float x, float y,
                           int argb, boolean shadow, Matrix4fc pose, MultiBufferSource.BufferSource buffers) {
        return draw(nativeFont, text, x, y, argb, shadow, pose, buffers::endBatch);
    }

    private static int draw(Font nativeFont, FormattedCharSequence text, float x, float y,
                            int argb, boolean shadow, Matrix4fc pose, Runnable flush) {
        if (text == null) return (int)x;
        int width = nativeFont.width(text);
        int result = (int)(x + width);
        if (width == 0 || !Float.isFinite(x) || !Float.isFinite(y)) return result;
        checkThread();
        // Flush earlier widget backgrounds so that immediate text preserves native draw order.
        flush.run();
        Matrix4f transform = new Matrix4f(RenderSystem.getProjectionMatrix())
            .mul(RenderSystem.getModelViewMatrix()).mul(pose);
        int[] viewport = new int[4], scissor = new int[4];
        glGetIntegerv(GL_VIEWPORT, viewport);
        boolean clipped = glIsEnabled(GL_SCISSOR_TEST);
        if (clipped) glGetIntegerv(GL_SCISSOR_BOX, scissor);
        if (viewport[2] <= 0 || viewport[3] <= 0) return result;
        Vector2f origin = project(transform, x, y, viewport[2], viewport[3]);
        Vector2f right = project(transform, x + 1, y, viewport[2], viewport[3]).sub(origin);
        Vector2f down = project(transform, x, y + 1, viewport[2], viewport[3]).sub(origin);
        if (!origin.isFinite() || !right.isFinite() || !down.isFinite() ||
            Math.abs(right.x * down.y - right.y * down.x) < 1e-8f) return result;
        List<Run> runs = runs(text, nativeFont);
        // Font.drawInBatch treats a missing/near-zero alpha byte as an opaque RGB color.
        int color = (argb & 0xfc000000) == 0 ? argb | 0xff000000 : argb;
        try (NanoVgGlState ignored = new NanoVgGlState()) {
            NanoVgGlState.prepare();
            initialize();
            boolean frameOpen = false;
            try {
                nvgBeginFrame(vg, viewport[2], viewport[3], 1);
                frameOpen = true;
                if (clipped) nvgScissor(vg, scissor[0] - viewport[0],
                    viewport[3] - (scissor[1] - viewport[1] + scissor[3]), scissor[2], scissor[3]);
                nvgTransform(vg, right.x, right.y, down.x, down.y, origin.x, origin.y);
                if (shadow) drawRuns(runs, color, true);
                drawRuns(runs, color, false);
                nvgEndFrame(vg);
                frameOpen = false;
                canvas.finishFrame();
            } finally {
                if (frameOpen) nvgCancelFrame(vg);
            }
        }
        return result;
    }

    private static Vector2f project(Matrix4f transform, float x, float y, int width, int height) {
        Vector4f p = transform.transform(new Vector4f(x, y, 0, 1));
        if (!p.isFinite() || Math.abs(p.w) < 1e-8f) return new Vector2f(Float.NaN);
        return new Vector2f((p.x / p.w + 1) * width * .5f, (1 - p.y / p.w) * height * .5f);
    }

    private static List<Run> runs(FormattedCharSequence text, Font font) {
        List<Run> runs = new ArrayList<>();
        StringBuilder current = new StringBuilder();
        Style[] previous = {null};
        text.accept((index, style, codepoint) -> {
            if (previous[0] != null && !previous[0].equals(style)) {
                addRun(runs, current, previous[0], font);
                current.setLength(0);
            }
            previous[0] = style;
            current.appendCodePoint(codepoint);
            return true;
        });
        if (!current.isEmpty()) addRun(runs, current, previous[0], font);
        return runs;
    }

    private static void addRun(List<Run> runs, StringBuilder text, Style style, Font font) {
        String value = text.toString();
        runs.add(new Run(value, style, font.width(FormattedCharSequence.forward(value, style))));
    }

    private static void drawRuns(List<Run> runs, int argb, boolean shadow) {
        float x = shadow ? 1 : 0;
        for (Run run : runs) {
            int color = run.style().getColor() == null ? argb :
                (argb & 0xff000000) | run.style().getColor().getValue();
            if (shadow) color = (color & 0xff000000) | ((color & 0x00fcfcfc) >>> 2);
            canvas.save();
            canvas.translate(x, shadow ? 1 : 0);
            float measured = canvas.width(run.text(), FONT_SIZE);
            // Keep native button alignment, ellipsis and hitboxes unchanged across font families.
            if (measured > 0) nvgScale(vg, run.width() / measured, 1);
            if (run.style().isItalic()) nvgTransform(vg, 1, 0, -.16f, 1, FONT_SIZE * .13f, 0);
            if (run.style().isBold()) canvas.textBold(run.text(), 0, 0, FONT_SIZE, color);
            else canvas.text(run.text(), 0, 0, FONT_SIZE, color);
            canvas.restore();
            x += run.width();
        }
    }

    private static void initialize() {
        if (vg != 0) return;
        contextThread = Thread.currentThread();
        try {
            vg = nvgCreate(NVG_ANTIALIAS);
            if (vg == 0) throw new IllegalStateException("Cannot create native menu font context");
            canvas = new Canvas(vg);
        } catch (RuntimeException | Error failure) {
            if (vg != 0) nvgDelete(vg);
            vg = 0;
            canvas = null;
            contextThread = null;
            throw failure;
        }
    }

    private static void checkThread() {
        if (contextThread != null && contextThread != Thread.currentThread())
            throw new IllegalStateException("Native menu font used off its GL context thread");
    }

    public static void close() {
        if (vg == 0) return;
        checkThread();
        try (NanoVgGlState ignored = new NanoVgGlState()) { nvgDelete(vg); }
        finally {
            vg = 0;
            canvas = null;
            contextThread = null;
        }
    }

    private record Run(String text, Style style, float width) {}
}
