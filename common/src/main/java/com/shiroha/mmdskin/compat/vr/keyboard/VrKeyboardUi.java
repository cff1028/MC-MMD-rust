package com.shiroha.mmdskin.compat.vr.keyboard;

import com.shiroha.mmdskin.config.ConfigManager;
import com.mojang.blaze3d.systems.RenderSystem;
import com.shiroha.mmdskin.ui.spatial.lumen.render.Canvas;
import com.shiroha.mmdskin.ui.spatial.lumen.ui.SurfaceSpec;
import com.shiroha.mmdskin.ui.spatial.render.SpatialMenuCanvasTarget;
import org.joml.Matrix4f;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.network.chat.Component;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Locale;

/** Rounded, layered keyboard drawn into Vivecraft's existing stereo keyboard surface. */
public final class VrKeyboardUi {
    private static final String[] hovered = new String[2];
    private static final boolean[] pressed = new boolean[2];
    private static String lastKey;
    private static long lastPress;
    private static final SpatialMenuCanvasTarget SURFACE = new SpatialMenuCanvasTarget();
    private static final SurfaceSpec CROP = new SurfaceSpec(SurfaceSpec.Anchor.HEAD_STABLE,
        0, 0, VrKeyboardLayout.WIDTH, VrKeyboardLayout.HEIGHT, .9f, true);
    private VrKeyboardUi() {}

    public static void onOpen() { clearHover(); VrKeyboardController.open(); }
    public static void onClose() { clearHover(); VrKeyboardController.close(); }
    public static void reset() { onClose(); }
    public static void tick() { VrKeyboardController.tick(); }
    private static void clearHover() { Arrays.fill(hovered, null); Arrays.fill(pressed, false); lastKey = null; }
    public static void setHover(int hand, String key, boolean down) {
        if (hand < 0 || hand >= 2) return;
        hovered[hand] = key;
        pressed[hand] = down;
    }
    public static void press(String key) {
        lastKey = key; lastPress = System.nanoTime();
        VrKeyboardController.press(key);
    }
    public static boolean isRepeatable(String key) {
        return "backspace".equals(key) || "left".equals(key) || "right".equals(key);
    }
    public static String hit(double u, double v, int width, int height) {
        return VrKeyboardLayout.hit(keys(), u, v, width, height);
    }

    private static List<VrKeyboardLayout.Key> keys() {
        List<VrKeyboardLayout.Key> keys = new ArrayList<>(VrKeyboardLayout.keys(VrKeyboardController.page(), VrKeyboardController.shifted()));
        var state = VrKeyboardController.snapshot();
        int first = Math.clamp(state.pageStart(), 0, state.candidates().size());
        int count = Math.min(Math.max(1, state.pageSize()), state.candidates().size() - first);
        if (count > 0) {
            // All candidates in the IME's current page remain reachable, including long pages.
            int cellWidth = 916 / count;
            for (int i = 0; i < count; i++) {
                keys.add(new VrKeyboardLayout.Key("candidate:" + (first + i), state.candidates().get(first + i),
                        Integer.toString(i + 1), 92 + i * cellWidth, 66, cellWidth - 4, 38));
            }
            keys.add(new VrKeyboardLayout.Key("candidate-prev", "‹", "", 26, 66, 54, 38));
            keys.add(new VrKeyboardLayout.Key("candidate-next", "›", "", 1020, 66, 54, 38));
        }
        return keys;
    }

    public static void render(GuiGraphics graphics, int width, int height) {
        // Only the pixels change: Vivecraft still owns the same framebuffer, plane,
        // pointer coordinates, fingertip contacts, dragging and native IME session.
        graphics.flush();
        SURFACE.renderSurface(VrKeyboardUi::paint);
        var transform = VrKeyboardLayout.transform(width, height);
        float scale = (float)transform.scale();
        Matrix4f mvp = new Matrix4f(RenderSystem.getProjectionMatrix())
            .mul(RenderSystem.getModelViewMatrix()).mul(graphics.pose().last().pose())
            .translate((float)transform.x() + VrKeyboardLayout.WIDTH * scale / 2,
                (float)transform.y() + VrKeyboardLayout.HEIGHT * scale / 2, 0)
            // The source canvas is top-down and the quad is bottom-up.
            .scale(VrKeyboardLayout.WIDTH * scale, -VrKeyboardLayout.HEIGHT * scale, 1);
        SURFACE.draw(mvp, CROP);
    }

    public static void releaseRenderer() { SURFACE.close(); }

    private static void paint(Canvas canvas) {
        canvas.fill(0, 0, 1100, 510, 26, 0xF025272B);
        canvas.fill(18, 18, 1064, 90, 24, 0xFF42464B);
        var state = VrKeyboardController.snapshot();
        boolean composing = !state.composition().isEmpty();
        String context = composing ? state.composition() : VrKeyboardController.contextText();
        if (context.isEmpty()) context = text("input_hint");
        String visibleContext = fitSuffix(canvas, context, 990, 22);
        canvas.text(visibleContext, 45, 28, 22, composing ? 0xFFA2EEE6 : 0xFFF0F1F2);
        if (composing) {
            int cursor = Math.clamp(state.cursor(), 0, state.composition().length());
            float removed = Math.max(0, canvas.width(context, 22) - canvas.width(visibleContext, 22));
            float caret = Math.clamp(45 + canvas.width(context.substring(0, cursor), 22) - removed, 45, 1035);
            canvas.fill(caret, 28, 2, 25, 1, 0xFFB6FAF0);
        }
        if (state.candidates().isEmpty()) {
            boolean focusRequired = "focus_required".equals(state.status());
            boolean unavailable = "input_unavailable".equals(state.status());
            boolean modifierHeld = "modifier_held".equals(state.status());
            boolean initializing = "initializing".equals(state.status());
            String hint = focusRequired ? "focus_required" : unavailable ? "input_unavailable" : modifierHeld ? "modifier_held"
                : initializing ? "initializing" : VrKeyboardController.imeEnabled() ? "ime_hint" : "latin_hint";
            canvas.text(fitSuffix(canvas, text(hint), 990, 16), 45, 69, 16,
                focusRequired || unavailable || modifierHeld ? 0xFFFFD18A : 0xFFBCC3C9);
        }
        for (var key : keys()) renderKey(canvas, key);
        String footer = text("mode." + ConfigManager.getVRKeyboardMode().name().toLowerCase(Locale.ROOT));
        if (!VrKeyboardNative.supported()) footer += " · " + text("latin_only");
        else if (VrKeyboardController.imeEnabled()) footer += " · " + text("windows_ime");
        canvas.textCenter(footer, 550, 481, 15, 0xFFBAC4C8);
    }

    private static String fitSuffix(Canvas canvas, String value, float width, float size) {
        if (canvas.width(value, size) <= width) return value;
        int[] points = value.codePoints().toArray();
        int low = 0, high = points.length;
        while (low < high) {
            int middle = (low + high) >>> 1;
            String tail = "…" + new String(points, middle, points.length - middle);
            if (canvas.width(tail, size) > width) low = middle + 1;
            else high = middle;
        }
        return "…" + new String(points, low, points.length - low);
    }

    private static void renderKey(Canvas canvas, VrKeyboardLayout.Key key) {
        boolean hover = key.id().equals(hovered[0]) || key.id().equals(hovered[1]);
        boolean down = key.id().equals(hovered[0]) && pressed[0] || key.id().equals(hovered[1]) && pressed[1]
            || key.id().equals(lastKey) && System.nanoTime() - lastPress < 110_000_000L;
        boolean enter = key.id().equals("enter");
        boolean shift = key.id().startsWith("shift-") && VrKeyboardController.shifted();
        boolean selectedCandidate = key.id().equals("candidate:" + VrKeyboardController.snapshot().selection());
        int background = down ? 0xFF73CFC3 : hover ? 0xFF536F78 : enter ? 0xFFF4F6F8
            : shift || selectedCandidate ? 0xFF456864 : 0xFF383C41;
        int foreground = enter || down ? 0xFF152326 : 0xFFF4F6F8;
        canvas.fill(key.x(), key.y() + (down ? 3 : 0), key.width(), key.height() - (down ? 3 : 0),
            Math.min(18, key.height() / 3), background);
        if (key.id().equals("backspace")) {
            backspaceIcon(canvas, key.x() + key.width() / 2, key.y() + key.height() / 2 + (down ? 3 : 0), foreground);
            return;
        }
        String label = key.label();
        if (key.id().startsWith("candidate:")) label = key.hint() + " " + label;
        if (key.id().equals("language")) label = VrKeyboardController.imeEnabled() ? text("ime_short") : "EN";
        if (key.id().equals("space")) label = text("space");
        if (key.id().equals("settings")) label = text("settings_short");
        float size = key.id().startsWith("candidate:") ? 18 : label.length() > 4 ? 22 : 28;
        float labelWidth = canvas.width(label, size);
        if (labelWidth > 0) size *= Math.min(1, (key.width() - 16f) / labelWidth);
        canvas.textCenter(label, key.x() + key.width() / 2f,
            key.y() + key.height() / 2f - size / 2 + (down ? 3 : 0), size, foreground);
        if (!key.hint().isEmpty() && !key.id().startsWith("candidate:") && !enter) {
            canvas.textCenter(key.hint(), key.x() + key.width() - 20, key.y() + 8, 14, 0xFFADB5BA);
        }
    }

    private static void backspaceIcon(Canvas canvas, int x, int y, int color) {
        int[][] outline = {{-22, 0}, {-10, -12}, {20, -12}, {20, 12}, {-10, 12}, {-22, 0}};
        for (int i = 1; i < outline.length; i++) canvas.line(x + outline[i - 1][0], y + outline[i - 1][1],
            x + outline[i][0], y + outline[i][1], color, 2.5f);
        canvas.line(x - 2, y - 6, x + 10, y + 6, color, 2.5f);
        canvas.line(x - 2, y + 6, x + 10, y - 6, color, 2.5f);
    }

    private static String text(String key) { return Component.translatable("gui.mmdskin.keyboard." + key).getString(); }
}
