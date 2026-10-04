package com.shiroha.mmdskin.ui.spatial.lumen.ui;

import com.shiroha.mmdskin.ui.spatial.lumen.render.Canvas;
import org.lwjgl.nanovg.NVGPaint;
import org.lwjgl.system.MemoryStack;

import static org.lwjgl.nanovg.NanoVG.*;

/** Minimal geometric UI symbols and neutral preview placeholders. No illustrative artwork. */
public final class Art {
    private static final int PLACEHOLDER = 0x24070C13;
    private static final int EDGE = 0x28CAD7E9;
    private static final int MUTED = 0xFFADB4C4;
    private static final float STROKE = 1.6f;

    private Art() {}

    /** A static dark studio backdrop. This is excluded when rendering the transparent VR surface. */
    public static void background(Canvas c, float time) {
        c.gradient(0, 0, Canvas.WIDTH, Canvas.HEIGHT, 0, 0xFF121822, 0xFF090D13);
        glow(c, 340, 235, 650, 0x1C46658B);
        glow(c, 1100, 540, 520, 0x0E506278);
        c.gradient(0, 675, Canvas.WIDTH, 225, 0, 0x00121A24, 0x9005070B);
    }

    private static void glow(Canvas c, float x, float y, float radius, int color) {
        try (MemoryStack stack = MemoryStack.stackPush()) {
            NVGPaint paint = NVGPaint.malloc(stack);
            nvgRadialGradient(c.vg, x, y, 0, radius, Canvas.color(stack, color),
                    Canvas.color(stack, color & 0x00FFFFFF), paint);
            nvgBeginPath(c.vg);
            nvgRect(c.vg, 0, 0, Canvas.WIDTH, Canvas.HEIGHT);
            nvgFillPaint(c.vg, paint);
            nvgFill(c.vg);
        }
    }

    /** Neutral bounded area for future model thumbnails or world screenshots. */
    public static void placeholder(Canvas c, float x, float y, float w, float h, String name) {
        if (w <= 0 || h <= 0) return;
        float radius = Math.min(12, Math.min(w, h) / 5);
        c.fill(x, y, w, h, radius, PLACEHOLDER);
        c.stroke(x + .5f, y + .5f, w - 1, h - 1, radius, EDGE, 1);
        boolean caption = w > 150 && h > 100;
        float size = Math.min(caption ? 36 : 26, Math.min(w, h) * .36f);
        float centerY = y + h * .5f - (caption ? 11 : 0);
        icon(c, name == null || name.isBlank() ? "image" : name,
                x + (w - size) * .5f, centerY - size * .5f, size, 0xFF707C90);
        if (caption) c.textCenter("avatar".equals(name) ? "虚拟形象" : "世界", x + w * .5f, centerY + size * .5f + 13, 12, MUTED);
    }

    /** Coherent 24-unit regular icon family; every symbol uses the same 1.6-unit stroke. */
    public static void icon(Canvas c, String name, float x, float y, float size, int color) {
        if (size <= 0) return;
        c.save();
        c.translate(x, y);
        c.scale(size / 24f);
        switch (name == null ? "info" : name) {
            case "home" -> {
                path(c, color, 3, 10.5f, 12, 3, 21, 10.5f);
                path(c, color, 5.5f, 9, 5.5f, 21, 10, 21, 10, 15, 14, 15, 14, 21, 18.5f, 21, 18.5f, 9);
            }
            case "world" -> {
                ring(c, 12, 12, 9, color);
                c.line(3, 12, 21, 12, color, STROKE);
                try (MemoryStack stack = MemoryStack.stackPush()) {
                    nvgBeginPath(c.vg);nvgEllipse(c.vg, 12, 12, 4.25f, 9);
                    stroke(c, stack, color);
                }
            }
            case "avatar", "account" -> {
                ring(c, 12, 7.25f, 3.75f, color);
                try (MemoryStack stack = MemoryStack.stackPush()) {
                    nvgBeginPath(c.vg);nvgMoveTo(c.vg, 4.5f, 21);
                    nvgLineTo(c.vg, 4.5f, 19.5f);nvgBezierTo(c.vg, 4.5f, 12.5f, 19.5f, 12.5f, 19.5f, 19.5f);
                    nvgLineTo(c.vg, 19.5f, 21);stroke(c, stack, color);
                }
            }
            case "stage", "spark" -> {
                c.stroke(3, 3.5f, 18, 13, 2, color, STROKE);
                path(c, color, 7, 3.5f, 7, 11, 3, 13);
                path(c, color, 17, 3.5f, 17, 11, 21, 13);
                path(c, color, 7, 20.5f, 17, 20.5f);
                c.line(12, 16.5f, 12, 20.5f, color, STROKE);
            }
            case "settings" -> {
                ring(c, 12, 12, 3.5f, color);
                path(c, color, 9, 3, 15, 3, 15.6f, 5.5f, 18, 6.8f, 20.4f, 6.1f,
                        22, 11.4f, 20, 13, 20, 15.5f, 21, 17.6f, 16.7f, 21,
                        14.5f, 19.6f, 11.8f, 20, 10, 21.5f, 5.3f, 18.6f,
                        6, 16, 4.5f, 13.8f, 2, 13, 3, 7.5f, 5.5f, 7.3f, 7.4f, 5.5f, 9, 3);
            }
            case "arrow" -> path(c, color, 9, 5, 16, 12, 9, 19);
            case "back" -> path(c, color, 15, 5, 8, 12, 15, 19);
            case "chevron-down" -> path(c, color, 5, 9, 12, 16, 19, 9);
            case "close" -> {c.line(6, 6, 18, 18, color, STROKE);c.line(18, 6, 6, 18, color, STROKE);}
            case "search" -> {ring(c, 10.5f, 10.5f, 6.5f, color);c.line(15.5f, 15.5f, 21, 21, color, STROKE);}
            case "check" -> path(c, color, 4.5f, 12.5f, 9.5f, 17.5f, 19.5f, 6.5f);
            case "plus" -> {c.line(12, 4, 12, 20, color, STROKE);c.line(4, 12, 20, 12, color, STROKE);}
            case "play" -> path(c, color, 7, 3.5f, 20, 12, 7, 20.5f, 7, 3.5f);
            case "pause" -> {c.stroke(6, 4, 3, 16, .7f, color, STROKE);c.stroke(15, 4, 3, 16, .7f, color, STROKE);}
            case "stop" -> c.stroke(5, 5, 14, 14, 2, color, STROKE);
            case "heart" -> {
                try (MemoryStack stack = MemoryStack.stackPush()) {
                    nvgBeginPath(c.vg);nvgMoveTo(c.vg, 12, 20.5f);
                    nvgBezierTo(c.vg, 10, 18.5f, 3, 13.5f, 3, 8);
                    nvgBezierTo(c.vg, 3, 3, 9, 2, 12, 7);
                    nvgBezierTo(c.vg, 15, 2, 21, 3, 21, 8);
                    nvgBezierTo(c.vg, 21, 13.5f, 14, 18.5f, 12, 20.5f);stroke(c, stack, color);
                }
            }
            case "mirror" -> {
                c.stroke(5, 2, 14, 16, 4, color, STROKE);
                c.line(12, 18, 12, 22, color, STROKE);c.line(8, 22, 16, 22, color, STROKE);
                c.line(8, 11, 13, 6, color, STROKE);
            }
            case "reset" -> {
                try (MemoryStack stack = MemoryStack.stackPush()) {
                    nvgBeginPath(c.vg);nvgArc(c.vg, 12, 12, 8, (float)-Math.PI*.85f, (float)Math.PI*.75f, NVG_CW);
                    stroke(c, stack, color);
                }
                path(c, color, 3, 3, 4, 8, 9, 7);
            }
            case "volume" -> {
                path(c, color, 3, 9, 7, 9, 12, 4, 12, 20, 7, 15, 3, 15, 3, 9);
                path(c, color, 16, 8, 18, 12, 16, 16);path(c, color, 19, 4, 22, 12, 19, 20);
            }
            case "headset" -> {
                c.stroke(2.5f, 6, 19, 12, 3, color, STROKE);
                path(c, color, 8, 18, 10, 14.5f, 14, 14.5f, 16, 18);
                ring(c, 7.5f, 11, 1.2f, color);ring(c, 16.5f, 11, 1.2f, color);
            }
            case "hand" -> path(c, color, 6, 12, 6, 5.5f, 9, 5.5f, 9, 11, 9, 2.5f, 12, 2.5f,
                    12, 11, 12, 4, 15, 4, 15, 12, 15, 7, 18, 7, 18, 17, 14, 21.5f,
                    8.5f, 21.5f, 3, 15, 3, 11, 6, 12);
            case "folder" -> path(c, color, 3, 6, 10, 6, 12, 8.5f, 21, 8.5f, 21, 20, 3, 20, 3, 6);
            case "link" -> {
                path(c, color, 10, 7, 14, 3, 18, 3, 21, 6, 21, 10, 17, 14, 14, 14);
                path(c, color, 14, 17, 10, 21, 6, 21, 3, 18, 3, 14, 7, 10, 10, 10);
                c.line(8.5f, 15.5f, 15.5f, 8.5f, color, STROKE);
            }
            case "grid" -> {for(int a=0;a<2;a++)for(int b=0;b<2;b++)c.stroke(3+a*11, 3+b*11, 7, 7, 1.5f, color, STROKE);}
            case "moon" -> {
                try (MemoryStack stack = MemoryStack.stackPush()) {
                    nvgBeginPath(c.vg);nvgMoveTo(c.vg, 20.5f, 15);
                    nvgBezierTo(c.vg, 14, 18.5f, 6.5f, 11, 10, 3.5f);
                    nvgBezierTo(c.vg, 1, 5.5f, .5f, 17.5f, 9, 21);
                    nvgBezierTo(c.vg, 14, 23, 19, 20, 20.5f, 15);stroke(c, stack, color);
                }
            }
            case "image" -> {
                c.stroke(3, 3.5f, 18, 17, 2, color, STROKE);ring(c, 8, 8.5f, 1.5f, color);
                path(c, color, 4, 18, 9, 13, 12, 16, 16, 11, 20, 16);
            }
            case "sliders" -> {
                c.line(4, 6, 20, 6, color, STROKE);c.line(4, 12, 20, 12, color, STROKE);c.line(4, 18, 20, 18, color, STROKE);
                c.fill(7, 3, 3, 6, 1, color);c.fill(14, 9, 3, 6, 1, color);c.fill(9, 15, 3, 6, 1, color);
            }
            default -> {ring(c, 12, 12, 9, color);c.circle(12, 7, .9f, color);c.line(12, 11, 12, 17, color, STROKE);}
        }
        c.restore();
    }

    private static void path(Canvas c, int color, float... points) {
        try (MemoryStack stack = MemoryStack.stackPush()) {
            nvgBeginPath(c.vg);nvgMoveTo(c.vg, points[0], points[1]);
            for (int i=2;i<points.length;i+=2)nvgLineTo(c.vg, points[i], points[i+1]);
            stroke(c, stack, color);
        }
    }

    private static void ring(Canvas c, float x, float y, float radius, int color) {
        c.stroke(x-radius, y-radius, radius*2, radius*2, radius, color, STROKE);
    }

    private static void stroke(Canvas c, MemoryStack stack, int color) {
        nvgStrokeColor(c.vg, Canvas.color(stack, color));
        nvgStrokeWidth(c.vg, STROKE);nvgLineCap(c.vg, NVG_ROUND);nvgLineJoin(c.vg, NVG_ROUND);nvgStroke(c.vg);
    }
}
