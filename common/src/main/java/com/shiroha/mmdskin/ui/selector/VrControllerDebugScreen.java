package com.shiroha.mmdskin.ui.selector;

import com.shiroha.mmdskin.compat.vr.ControllerDebugSnapshot;
import com.shiroha.mmdskin.compat.vr.ControllerDebugBindings;
import com.shiroha.mmdskin.compat.vr.ControllerDebugSnapshot.Action;
import com.shiroha.mmdskin.compat.vr.ControllerDebugSnapshot.Hand;
import com.shiroha.mmdskin.compat.vr.VivecraftControllerDebug;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;

import java.util.*;

/** Live read-only diagnostics. Keeping the same parent preserves unsaved Cloth Config edits. */
public final class VrControllerDebugScreen extends Screen {
    private final Screen parent;
    private final VivecraftControllerDebug reader = new VivecraftControllerDebug();
    private ControllerDebugSnapshot snapshot = ControllerDebugSnapshot.unavailable("inactive", "Vivecraft");
    private final Map<String, Long> recent = new HashMap<>();
    private final String[] lastButtons = {"", ""};
    private final long[] lastButtonTimes = new long[2];
    private boolean frozen, showAll;
    private int tab, page;
    private Button previous, next, filter;
    private long copiedUntil, exitHintUntil;
    private Component hover;
    private static final int TEXT = 0xFFE4ECF1, MUTED = 0xFF9BAFBB, ACTIVE = 0xFF6FEBB7;

    public VrControllerDebugScreen(Screen parent) { super(text("title")); this.parent = parent; }

    @Override protected void init() {
        int gap = 5, half = (width - 24 - gap) / 2;
        addRenderableWidget(Button.builder(text("raw_tab"), b -> { tab = 0; page = 0; }).bounds(12, 32, half, 20).build());
        addRenderableWidget(Button.builder(text("actions_tab"), b -> { tab = 1; page = 0; }).bounds(17 + half, 32, half, 20).build());
        int y = height - 52, cell = (width - 24 - 3 * gap) / 4;
        previous = addRenderableWidget(Button.builder(Component.literal("<"), b -> page = Math.max(0, page - 1)).bounds(12, y, 30, 20).build());
        next = addRenderableWidget(Button.builder(Component.literal(">"), b -> page++).bounds(width - 42, y, 30, 20).build());
        filter = addRenderableWidget(Button.builder(text("filter_current"), b -> {
            showAll = !showAll; page = 0; b.setMessage(text(showAll ? "filter_all" : "filter_current"));
        }).bounds(width / 2 - 66, y, 132, 20).build());
        filter.setMessage(text(showAll ? "filter_all" : "filter_current"));
        addRenderableWidget(Button.builder(text(frozen ? "resume" : "freeze"), b -> {
            frozen = !frozen; b.setMessage(text(frozen ? "resume" : "freeze"));
        }).bounds(12, height - 27, cell, 20).build());
        addRenderableWidget(Button.builder(text("copy"), b -> {
            String report = snapshot.report();
            for (int side = 0; side < 2; side++) if (!lastButtons[side].isEmpty())
                report += "\nRECENT " + (side == 0 ? "LEFT " : "RIGHT ") + lastButtons[side];
            minecraft.keyboardHandler.setClipboard(report); copiedUntil = System.nanoTime() + 2_000_000_000L;
        }).bounds(17 + cell, height - 27, 2 * cell + gap, 20).build());
        addRenderableWidget(Button.builder(Component.translatable("gui.back"), b -> onClose()).bounds(22 + 3 * cell + gap, height - 27, cell, 20).build());
    }

    @Override public boolean isPauseScreen() { return false; }
    // Vivecraft maps its menu button to Escape. Keep that button observable during inspection.
    @Override public boolean shouldCloseOnEsc() { return false; }
    @Override public boolean keyPressed(int key, int scan, int modifiers) {
        if (key == org.lwjgl.glfw.GLFW.GLFW_KEY_ESCAPE) {
            exitHintUntil = System.nanoTime() + 2_000_000_000L;
            return true;
        }
        return super.keyPressed(key, scan, modifiers);
    }
    @Override public void onClose() { minecraft.setScreen(parent); }
    @Override public void renderBackground(GuiGraphics g, int x, int y, float tick) {}

    @Override public void render(GuiGraphics g, int mouseX, int mouseY, float tick) {
        long now = System.nanoTime();
        if (!frozen) {
            snapshot = reader.read();
            recent.entrySet().removeIf(e -> now - e.getValue() > 3_000_000_000L);
            for (Action action : snapshot.actions()) if (action.actuated()) recent.put(action.id(), now);
            for (int side = 0; side < 2; side++) {
                int physicalHand = side;
                List<Action> inputs = snapshot.actions().stream().filter(ControllerDebugBindings::isDebug)
                        .filter(a -> a.hand() == physicalHand && a.actuated()).toList();
                if (!snapshot.status().equals("live")) lastButtons[side] = "";
                else if (!inputs.isEmpty()) {
                    lastButtons[side] = String.join(" | ", inputs.stream().map(a -> a.key() + " " + a.value()).toList());
                    lastButtonTimes[side] = now;
                } else if (now - lastButtonTimes[side] > 3_000_000_000L) lastButtons[side] = "";
            }
        }
        hover = null;
        filter.active = tab == 1;
        g.fill(0, 0, width, height, 0xF0101720);
        g.drawCenteredString(font, title, width / 2, 8, TEXT);
        g.drawCenteredString(font, text("status." + snapshot.status()).append(" · " + snapshot.provider())
                .append(frozen ? " · " + text("frozen").getString() : ""), width / 2, 21,
                snapshot.status().equals("live") ? ACTIVE : MUTED);
        if (tab == 0) renderRaw(g, mouseX, mouseY, now); else renderActions(g, mouseX, mouseY, now);
        super.render(g, mouseX, mouseY, tick);
        if (copiedUntil > now) g.drawCenteredString(font, text("copied"), width / 2, height - 65, ACTIVE);
        else if (exitHintUntil > now) g.drawCenteredString(font, text("exit_hint"), width / 2, height - 65, ACTIVE);
        if (hover != null) g.renderTooltip(font, font.split(hover, Math.min(480, width - 24)), mouseX, mouseY);
    }

    private void renderRaw(GuiGraphics g, int mx, int my, long now) {
        int w = (width - 30) / 2, top = 60, bottom = height - 76;
        int rows = Math.max(1, (bottom - top - 71) / 15);
        List<List<Action>> hands = new ArrayList<>();
        for (int side = 0; side < 2; side++) {
            List<Action> inputs = new ArrayList<>();
            for (var control : ControllerDebugBindings.CONTROLS) if (control.hand() == side) {
                snapshot.actions().stream().filter(a -> a.name().equals(control.name()))
                        .filter(a -> a.active() || a.bindings().stream().anyMatch(v -> v.startsWith("/user/hand/")))
                        .findFirst().ifPresent(inputs::add);
            }
            hands.add(inputs);
        }
        int count = hands.stream().mapToInt(inputs -> (int) inputs.stream().filter(a -> !a.type().equals("boolean")).count()).max().orElse(0);
        int pages = Math.max(1, (count + rows - 1) / rows);
        page = Math.clamp(page, 0, pages - 1);
        previous.active = page > 0; next.active = page + 1 < pages;
        for (int side = 0; side < 2; side++) {
            int x = 12 + side * (w + 6);
            g.fill(x, top, x + w, bottom, 0xFF1C2933);
            Hand hand = null;
            for (Hand value : snapshot.hands()) if (value.side() == side) hand = value;
            line(g, text(side == 0 ? "left" : "right").getString() + (hand == null ? "" : " · device " + hand.device()), x + 5, top + 5, w - 10, TEXT, mx, my);
            line(g, hand == null ? text("disconnected").getString() : hand.model(), x + 5, top + 18, w - 10, MUTED, mx, my);
            List<Action> inputs = hands.get(side);
            if (inputs.isEmpty()) { line(g, text("no_physical").getString(), x + 5, top + 34, w - 10, MUTED, mx, my); continue; }
            // Digital inputs are always visible, regardless of analog pagination or GUI action bindings.
            int y = top + 32;
            for (String slot : List.of("click", "touch")) {
                List<Action> buttons = inputs.stream().filter(a -> a.key().endsWith("/" + slot)).toList();
                StringJoiner names = new StringJoiner(" ");
                for (Action input : buttons) if (input.down()) names.add(input.key().substring(0, input.key().indexOf('/')));
                String value = text(slot.equals("click") ? "pressed" : "touched").getString() + (names.length() == 0 ? "—" : names.toString());
                line(g, value, x + 5, y, w - 10, names.length() == 0 ? MUTED : ACTIVE, mx, my);
                if (mx >= x && mx < x + w && my >= y && my < y + 11) {
                    StringJoiner detail = new StringJoiner("\n");
                    for (Action input : buttons) detail.add(input.origin() + "/" + slot + " " + input.value());
                    hover = Component.literal(detail.toString());
                }
                y += 12;
            }
            y = top + 58;
            for (Action input : inputs.stream().filter(a -> !a.type().equals("boolean")).skip((long) page * rows).limit(rows).toList()) {
                line(g, input.key().substring(0, input.key().indexOf('/')) + " " + input.value(), x + 5, y, w - 30, input.actuated() ? ACTIVE : MUTED, mx, my);
                if (input.type().equals("vector2")) {
                    int cx = x + w - 13, cy = y + 5, radius = 6;
                    g.fill(cx - radius, cy - radius, cx + radius + 1, cy + radius + 1, 0xFF101920);
                    g.fill(cx, cy - radius, cx + 1, cy + radius + 1, 0xFF45606A); g.fill(cx - radius, cy, cx + radius + 1, cy + 1, 0xFF45606A);
                    if (input.active()) {
                        int dx = Math.round(Math.clamp(input.x(), -1, 1) * radius), dy = Math.round(Math.clamp(input.y(), -1, 1) * -radius);
                        g.fill(cx + dx - 1, cy + dy - 1, cx + dx + 2, cy + dy + 2, ACTIVE);
                    }
                }
                if (mx >= x && mx < x + w && my >= y && my < y + 13)
                    hover = Component.literal(input.origin() + "\n" + input.key() + " " + input.value() + "\n" + input.name());
                y += 15;
            }
            if (!lastButtons[side].isEmpty() && (frozen || now - lastButtonTimes[side] < 3_000_000_000L))
                line(g, text("recent").getString() + lastButtons[side], x + 5, bottom - 12, w - 10, ACTIVE, mx, my);
        }
        line(g, (pages > 1 ? (page + 1) + "/" + pages + " · " : "") + text("raw_hint").getString(), 12, height - 71, width - 24, MUTED, mx, my);
    }

    private void renderActions(GuiGraphics g, int mx, int my, long now) {
        List<Action> visible = snapshot.actions().stream().filter(a -> showAll || a.actuated() || recent.containsKey(a.id())).toList();
        int rowHeight = 48, rows = Math.max(1, (height - 137) / rowHeight);
        int pages = Math.max(1, (visible.size() + rows - 1) / rows); page = Math.clamp(page, 0, pages - 1);
        previous.active = page > 0; next.active = page + 1 < pages;
        g.drawCenteredString(font, Component.literal((page + 1) + " / " + pages + " · " + visible.size()), width / 2, height - 83, MUTED);
        if (visible.isEmpty()) line(g, text("no_actions").getString(), 15, 70, width - 30, MUTED, mx, my);
        for (int index = 0; index < rows && page * rows + index < visible.size(); index++) {
            Action action = visible.get(page * rows + index); int y = 59 + index * rowHeight;
            g.fill(12, y, width - 12, y + rowHeight - 3, action.actuated() ? 0xFF244B46 : 0xFF1C2933);
            String hand = text(action.hand() == 0 ? "left" : action.hand() == 1 ? "right" : "unknown").getString();
            String heading = hand + " · " + Component.translatable(action.key()).getString() + "  [" + action.type() + "] " + action.value();
            line(g, heading, 17, y + 3, width - 34, action.actuated() ? ACTIVE : TEXT, mx, my);
            line(g, action.key() + "  " + action.name(), 17, y + 14, width - 34, TEXT, mx, my);
            line(g, action.origin().isEmpty() ? text("no_origin").getString() : action.origin(), 17, y + 25, width - 34, MUTED, mx, my);
            line(g, action.bindings().isEmpty() ? text("no_binding").getString() : String.join(" | ", action.bindings()), 17, y + 35, width - 34, MUTED, mx, my);
        }
        line(g, text("actions_hint").getString(), 12, height - 71, width - 24, MUTED, mx, my);
    }
    private void line(GuiGraphics g, String value, int x, int y, int w, int color, int mx, int my) {
        String shown = font.width(value) <= w ? value : font.plainSubstrByWidth(value, Math.max(0, w - font.width("…"))) + "…";
        g.drawString(font, shown, x, y, color, false);
        if (mx >= x && mx <= x + w && my >= y && my < y + 11) hover = Component.literal(value);
    }
    private static net.minecraft.network.chat.MutableComponent text(String key) { return Component.translatable("gui.mmdskin.controller_debug." + key); }
}
