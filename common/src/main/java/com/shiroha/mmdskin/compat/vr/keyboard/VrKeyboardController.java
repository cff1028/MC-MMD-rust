package com.shiroha.mmdskin.compat.vr.keyboard;

import com.shiroha.mmdskin.compat.vr.VrLinkedActions;
import com.shiroha.mmdskin.config.ConfigManager;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.components.EditBox;
import net.minecraft.client.gui.components.events.ContainerEventHandler;
import net.minecraft.client.gui.components.events.GuiEventListener;
import net.minecraft.client.gui.screens.Screen;
import org.lwjgl.glfw.GLFW;
import org.lwjgl.glfw.GLFWNativeWin32;

/** Delivers committed text only to the screen for which the keyboard was opened. */
public final class VrKeyboardController {
    private static Screen target;
    private static GuiEventListener recipient;
    private static boolean opened, nativeOpen, ime, shift, caps;
    private static long lastShift;
    private static long generation;
    private static VrKeyboardLayout.Page page = VrKeyboardLayout.Page.LETTERS;
    private static VrKeyboardNative.Snapshot snapshot = VrKeyboardNative.Snapshot.EMPTY;

    private VrKeyboardController() {}
    public static VrKeyboardLayout.Page page() { return page; }
    public static boolean shifted() { return shift || caps; }
    public static boolean imeEnabled() { return nativeOpen && snapshot.available() && snapshot.imeEnabled(); }
    public static VrKeyboardNative.Snapshot snapshot() { return snapshot; }

    public static void open() {
        if (opened) return;
        opened = true;
        page = VrKeyboardLayout.Page.LETTERS;
        shift = caps = false;
        ime = ConfigManager.isVRKeyboardImeEnabled();
        bindTarget(Minecraft.getInstance().screen);
    }

    private static void bindTarget(Screen screen) {
        generation++;
        VivecraftKeyboardBridge.onInputTargetChanged();
        VrKeyboardNative.close();
        snapshot = VrKeyboardNative.Snapshot.EMPTY;
        target = screen;
        recipient = focusedReceiver(screen);
        nativeOpen = false;
        if (target != null && ConfigManager.isVRKeyboardImeEnabled() && VrKeyboardNative.supported()) {
            long hwnd = GLFWNativeWin32.glfwGetWin32Window(Minecraft.getInstance().getWindow().getWindow());
            nativeOpen = VrKeyboardNative.open(hwnd);
            if (nativeOpen) VrKeyboardNative.setImeEnabled(ime);
        }
    }

    public static void tick() {
        if (!opened) return;
        // A screen transition cancels the previous composition rather than inserting it elsewhere.
        if (target != Minecraft.getInstance().screen || recipient != focusedReceiver(target)) bindTarget(Minecraft.getInstance().screen);
        if (!nativeOpen) return;
        long batchGeneration = generation;
        Screen batchTarget = target;
        GuiEventListener batchRecipient = recipient;
        var polled = VrKeyboardNative.poll();
        if (!hasCurrentRecipient(batchGeneration, batchTarget, batchRecipient)) return;
        snapshot = polled;
        for (var event : polled.events()) {
            if (!hasCurrentRecipient(batchGeneration, batchTarget, batchRecipient)) {
                if (opened && generation == batchGeneration) bindTarget(Minecraft.getInstance().screen);
                break;
            }
            if (!event.text().isEmpty()) deliverText(event.text());
            else if (event.key() != 0) deliverKey(event.key());
        }
        if (!VrKeyboardNative.supported()) nativeOpen = false;
    }

    public static void close() {
        generation++;
        opened = false;
        VrKeyboardNative.close();
        target = null;
        recipient = null;
        opened = nativeOpen = shift = caps = false;
        snapshot = VrKeyboardNative.Snapshot.EMPTY;
    }

    public static void press(String id) {
        if (id == null || !opened) return;
        if (id.equals("close")) { VivecraftKeyboardBridge.hide(); return; }
        if (id.equals("settings")) {
            VivecraftKeyboardBridge.hide();
            VrLinkedActions.showVrSettings();
            return;
        }
        switch (id) {
            case "page" -> { page = page == VrKeyboardLayout.Page.LETTERS ? VrKeyboardLayout.Page.NUMBERS : VrKeyboardLayout.Page.LETTERS; return; }
            case "more" -> { page = page == VrKeyboardLayout.Page.NUMBERS ? VrKeyboardLayout.Page.SYMBOLS : VrKeyboardLayout.Page.NUMBERS; return; }
            case "shift-left", "shift-right" -> {
                long now = System.nanoTime();
                if (shift && now - lastShift < 350_000_000L) { caps = true; shift = false; }
                else if (caps) { caps = false; shift = false; }
                else shift = !shift;
                lastShift = now;
                return;
            }
            case "language" -> { if (nativeOpen) { ime = !ime; VrKeyboardNative.setImeEnabled(ime); } return; }
            case "candidate-prev" -> { if (nativeOpen) VrKeyboardNative.pageCandidates(-1); return; }
            case "candidate-next" -> { if (nativeOpen) VrKeyboardNative.pageCandidates(1); return; }
        }
        if (target == null || target != Minecraft.getInstance().screen) return;
        if (id.startsWith("candidate:")) {
            if (nativeOpen) try { VrKeyboardNative.selectCandidate(Integer.parseInt(id.substring(10))); } catch (NumberFormatException ignored) {}
            return;
        }
        if (id.startsWith("letter:")) {
            char letter = id.charAt(7);
            boolean upper = shifted();
            if (!nativeOpen || !VrKeyboardNative.key(Character.toUpperCase(letter), upper)) {
                deliverText(String.valueOf(upper ? Character.toUpperCase(letter) : letter));
            }
            shift = false;
        } else if (id.startsWith("text:")) {
            String literal = id.substring(5);
            boolean digit = literal.length() == 1 && literal.charAt(0) >= '0' && literal.charAt(0) <= '9';
            if (!nativeOpen || !(digit ? VrKeyboardNative.key(literal.charAt(0), false) : VrKeyboardNative.text(literal))) deliverText(literal);
        } else {
            int vk = switch (id) {
                case "backspace" -> 8;
                case "enter" -> 13;
                case "space" -> 32;
                case "left" -> 37;
                case "right" -> 39;
                default -> 0;
            };
            if (vk != 0 && (!nativeOpen || !VrKeyboardNative.key(vk, shifted()))) deliverKey(vk);
        }
    }

    private static void deliverText(String text) {
        long eventGeneration = generation;
        Screen eventTarget = target;
        GuiEventListener eventRecipient = recipient;
        for (char c : text.toCharArray()) {
            if (!hasCurrentRecipient(eventGeneration, eventTarget, eventRecipient)) break;
            eventTarget.charTyped(c, 0);
        }
    }
    private static void deliverKey(int vk) {
        long eventGeneration = generation;
        Screen eventTarget = target;
        GuiEventListener eventRecipient = recipient;
        if (!hasCurrentRecipient(eventGeneration, eventTarget, eventRecipient)) return;
        if (vk == 32) { deliverText(" "); return; }
        int key = switch (vk) {
            case 8 -> GLFW.GLFW_KEY_BACKSPACE;
            case 9 -> GLFW.GLFW_KEY_TAB;
            case 13 -> GLFW.GLFW_KEY_ENTER;
            case 27 -> GLFW.GLFW_KEY_ESCAPE;
            case 33 -> GLFW.GLFW_KEY_PAGE_UP;
            case 34 -> GLFW.GLFW_KEY_PAGE_DOWN;
            case 35 -> GLFW.GLFW_KEY_END;
            case 36 -> GLFW.GLFW_KEY_HOME;
            case 37 -> GLFW.GLFW_KEY_LEFT;
            case 38 -> GLFW.GLFW_KEY_UP;
            case 39 -> GLFW.GLFW_KEY_RIGHT;
            case 40 -> GLFW.GLFW_KEY_DOWN;
            case 46 -> GLFW.GLFW_KEY_DELETE;
            default -> GLFW.GLFW_KEY_UNKNOWN;
        };
        if (key == GLFW.GLFW_KEY_UNKNOWN) return;
        eventTarget.keyPressed(key, 0, 0);
        if (hasCurrentRecipient(eventGeneration, eventTarget, eventRecipient)) eventTarget.keyReleased(key, 0, 0);
    }

    private static boolean hasCurrentRecipient(long expectedGeneration, Screen expectedTarget, GuiEventListener expectedRecipient) {
        return opened && generation == expectedGeneration && expectedTarget != null
            && target == expectedTarget && recipient == expectedRecipient
            && expectedTarget == Minecraft.getInstance().screen && expectedRecipient == focusedReceiver(expectedTarget);
    }

    public static String contextText() {
        if (target == null || target != Minecraft.getInstance().screen) return "";
        GuiEventListener current = focusedReceiver(target);
        return current instanceof EditBox edit ? edit.getValue() : "";
    }
    private static GuiEventListener focusedReceiver(Screen screen) {
        if (screen == null) return null;
        GuiEventListener current = screen.getFocused();
        for (int depth = 0; depth < 12 && current != null; depth++) {
            GuiEventListener next = current instanceof ContainerEventHandler container ? container.getFocused() : null;
            if (next == null || next == current) return current;
            current = next;
        }
        return current;
    }
}
