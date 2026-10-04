package com.shiroha.mmdskin.compat.vr;

import net.minecraft.client.KeyMapping;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.AbstractWidget;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.List;
import java.util.function.Consumer;

/** Adds an independent MMD page without modifying Vivecraft's configured or shifted bindings. */
public final class VivecraftRadialPages {
    private static final Logger LOGGER = LogManager.getLogger();
    private static final VrRadialPageState PAGE = new VrRadialPageState();
    private static final String KEY = "gui.mmdskin.vr_radial.";
    private static final int BUTTON_HEIGHT = 22;

    private int titleY;
    private int navigationY;
    private int hintY;

    public void decorate(Screen screen, Consumer<Button> addWidget, Runnable clearWidgets, Runnable rebuild) {
        int centerX = screen.width / 2;
        VrRadialLayout.Layout ring = null;
        if (PAGE.isMmdPage()) {
            clearWidgets.run();
            screen.setFocused(null);
            screen.setDragging(false);
            List<Action> actions = List.of(
                    new Action(VrLinkedActions.isMirrorEnabled() ? "mirror_off" : "mirror_on", VrLinkedActions::toggleMirror),
                    new Action("model_settings", VrLinkedActions::openModelSettings),
                    new Action("hand_settings", VrLinkedActions::openHandSettings),
                    new Action(VrLinkedActions.isHandTrackingEnabled() ? "hand_tracking_off" : "hand_tracking_auto", VrLinkedActions::toggleHandTracking),
                    new Action("pointer_settings", VrLinkedActions::openPointerSettings),
                    new Action("mmd_wheel", VrLinkedActions::openMmdWheel),
                    new Action("vr_settings", VrLinkedActions::openVrSettings));
            var font = Minecraft.getInstance().font;
            int preferredWidth = actions.stream().mapToInt(action -> font.width(Component.translatable(KEY + action.key)) + 12)
                    .max().orElse(120);
            ring = VrRadialLayout.create(screen.width, screen.height, Math.max(120, preferredWidth));
            for (int i = 0; i < actions.size(); i++) {
                Action action = actions.get(i);
                addWidget.accept(actionButton(action.key, ring.actions().get(i), action.run));
            }
        }

        int contentTop = screen.height / 2 - 44;
        int contentBottom = screen.height / 2 + 44;
        for (var child : screen.children()) {
            if (child instanceof AbstractWidget widget) {
                contentTop = Math.min(contentTop, widget.getY());
                contentBottom = Math.max(contentBottom, widget.getBottom());
            }
        }
        titleY = ring == null ? Math.max(4, contentTop - 18) : ring.titleY();
        navigationY = ring == null ? Math.min(screen.height - BUTTON_HEIGHT - 22, contentBottom + 14) : ring.previous().y();
        int navigationWidth = Math.min(110, (screen.width - 24) / 2);
        VrRadialLayout.Bounds previous = ring == null
                ? new VrRadialLayout.Bounds(centerX - navigationWidth - 5, navigationY, navigationWidth, BUTTON_HEIGHT) : ring.previous();
        VrRadialLayout.Bounds next = ring == null
                ? new VrRadialLayout.Bounds(centerX + 5, navigationY, navigationWidth, BUTTON_HEIGHT) : ring.next();
        hintY = ring == null ? navigationY + BUTTON_HEIGHT + 6 : ring.hintY();
        addWidget.accept(Button.builder(Component.translatable(KEY + "previous"), button -> {
            PAGE.changePage(isHoldMode());
            rebuild.run();
        }).bounds(previous.x(), previous.y(), previous.width(), previous.height()).build());
        addWidget.accept(Button.builder(Component.translatable(KEY + "next"), button -> {
            PAGE.changePage(isHoldMode());
            rebuild.run();
        }).bounds(next.x(), next.y(), next.width(), next.height()).build());
    }

    public void renderLabels(Screen screen, GuiGraphics graphics) {
        var font = Minecraft.getInstance().font;
        graphics.drawCenteredString(font,
                Component.translatable(KEY + (PAGE.isMmdPage() ? "mmd_page" : "vivecraft_page")),
                screen.width / 2, titleY, 0xFFFFFFFF);
        if (PAGE.isAwaitingHoldPress()) {
            graphics.drawCenteredString(font, Component.translatable(KEY + "hold_hint"),
                    screen.width / 2, hintY, 0xFFBFD7FF);
        }
    }

    private static Button actionButton(String key, VrRadialLayout.Bounds bounds, Runnable action) {
        return Button.builder(Component.translatable(KEY + key), button -> {
            closeOverlay();
            // Screen changes are queued by VrLinkedActions until the next client tick.
            action.run();
        }).bounds(bounds.x(), bounds.y(), bounds.width(), bounds.height()).build();
    }

    private record Action(String key, Runnable run) {}

    public static void afterTriggerClick() { PAGE.resetInteraction(); }

    public static boolean keepOpenAfterNavigation() {
        return PAGE.consumeNavigationClose();
    }

    public static boolean waitForNextHoldPress() {
        // Vivecraft polls key-up every frame in hold mode. Do not re-click the same
        // position on the new page while the menu waits for another explicit press.
        return PAGE.isAwaitingHoldPress();
    }

    public static boolean onOverlayChange(boolean showing, Object controller) {
        Support support = Holder.SUPPORT;
        if (support != null && PAGE.resumeHoldSelection(showing, controller != null, support.isRadialKeyDown())) {
            // MCVR toggles visibility on every key-down, even in hold mode. The first
            // press after paging resumes selection instead of dismissing the page.
            return true;
        }
        PAGE.resetInteraction();
        return false;
    }

    public static void closeOverlay() {
        PAGE.resetInteraction();
        Support support = Holder.SUPPORT;
        if (support != null) {
            support.closeOverlay();
        }
    }

    private static boolean isHoldMode() {
        Support support = Holder.SUPPORT;
        return support != null && support.isHoldMode();
    }

    private static final class Holder {
        private static final Support SUPPORT = Support.load();
    }

    /** Vivecraft stays optional: no Vivecraft type appears in common signatures. */
    private record Support(Method overlaySetter, Object settings, Field holdMode, KeyMapping radialKey) {
        private static Support load() {
            try {
                Class<?> controller = Class.forName("org.vivecraft.client_vr.provider.ControllerType");
                Class<?> handler = Class.forName("org.vivecraft.client_vr.gameplay.screenhandlers.RadialHandler");
                Class<?> holder = Class.forName("org.vivecraft.client_vr.ClientDataHolderVR");
                Object data = holder.getMethod("getInstance").invoke(null);
                Object settings = holder.getField("vrSettings").get(data);
                Class<?> mod = Class.forName("org.vivecraft.client.VivecraftVRMod");
                Object instance = mod.getField("INSTANCE").get(null);
                KeyMapping key = (KeyMapping) mod.getField("keyRadialMenu").get(instance);
                return new Support(handler.getMethod("setOverlayShowing", boolean.class, controller), settings,
                        settings.getClass().getField("radialModeHold"), key);
            } catch (ReflectiveOperationException | LinkageError exception) {
                LOGGER.warn("Vivecraft radial menu integration is unavailable", exception);
                return null;
            }
        }

        private boolean isHoldMode() {
            try {
                return holdMode.getBoolean(settings);
            } catch (IllegalAccessException exception) {
                return false;
            }
        }

        private boolean isRadialKeyDown() {
            return radialKey.isDown();
        }

        private void closeOverlay() {
            try {
                overlaySetter.invoke(null, false, null);
            } catch (ReflectiveOperationException exception) {
                LOGGER.warn("Could not close Vivecraft radial overlay", exception);
            }
        }
    }
}
