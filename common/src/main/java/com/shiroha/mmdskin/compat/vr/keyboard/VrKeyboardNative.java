package com.shiroha.mmdskin.compat.vr.keyboard;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.shiroha.mmdskin.NativeFunc;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/** Windows text services for a private document, called on the GLFW window's owning thread.
 * Native keys are tagged, filtered from game bindings, and queued only while the game has focus.
 */
public final class VrKeyboardNative {
    private static boolean opened;
    private static boolean failed;

    private VrKeyboardNative() {}

    public record Event(String text, int key) {}
    public record Snapshot(boolean available, boolean imeEnabled, String composition, int cursor,
                           List<String> candidates, int selection, int pageStart, int pageSize,
                           List<Event> events, String status) {
        public static final Snapshot EMPTY = new Snapshot(false, false, "", 0, List.of(), 0, 0, 0, List.of(), "");
    }

    public static boolean supported() {
        return System.getProperty("os.name", "").toLowerCase(Locale.ROOT).startsWith("windows") && !failed;
    }

    public static boolean isSupported() { return supported(); }

    /** ownerHwnd must be the current thread's GLFW Win32 window. No OS focus is requested. */
    public static boolean open(long ownerHwnd) {
        if (opened) return true;
        if (!supported()) return false;
        try {
            NativeFunc.GetInst();
            opened = nativeOpen(ownerHwnd);
            return opened;
        } catch (LinkageError | RuntimeException e) {
            failed = true;
            return false;
        }
    }

    public static void close() {
        if (!opened) return;
        try { nativeClose(); } catch (LinkageError | RuntimeException e) { failed = true; }
        opened = false;
    }

    /** Windows virtual-key code, including VK_BACK, VK_RETURN and VK_SPACE. True means queued. */
    public static boolean key(int virtualKey, boolean shift) { return command(0, virtualKey, shift); }
    public static boolean setImeEnabled(boolean enabled) { return command(1, enabled ? 1 : 0, false); }
    /** Candidate index in Snapshot.candidates(), not its index within the visible page. */
    public static boolean selectCandidate(int index) { return command(2, index, false); }
    public static boolean pageCandidates(int direction) { return command(3, direction < 0 ? -1 : 1, false); }

    public static boolean text(String literal) {
        if (!opened || literal == null || literal.isEmpty()) return false;
        try { return nativeText(literal); } catch (LinkageError | RuntimeException e) { close(); failed = true; return false; }
    }

    private static boolean command(int kind, int value, boolean shift) {
        if (!opened) return false;
        try { return nativeCommand(kind, value, shift); } catch (LinkageError | RuntimeException e) { close(); failed = true; return false; }
    }

    /** Drains text/key events exactly once, in the order in which the IME produced them. */
    public static Snapshot poll() {
        if (!opened) return Snapshot.EMPTY;
        try {
            JsonObject value = JsonParser.parseString(nativePoll()).getAsJsonObject();
            List<String> candidates = new ArrayList<>();
            for (JsonElement entry : value.getAsJsonArray("candidates")) candidates.add(entry.getAsString());
            List<Event> events = new ArrayList<>();
            for (JsonElement entry : value.getAsJsonArray("events")) {
                JsonObject event = entry.getAsJsonObject();
                events.add(new Event(event.get("text").getAsString(), event.get("key").getAsInt()));
            }
            return new Snapshot(value.get("available").getAsBoolean(), value.get("imeEnabled").getAsBoolean(),
                    value.get("composition").getAsString(), value.get("cursor").getAsInt(), List.copyOf(candidates),
                    value.get("selection").getAsInt(), value.get("pageStart").getAsInt(), value.get("pageSize").getAsInt(),
                    List.copyOf(events), value.get("status").getAsString());
        } catch (LinkageError | RuntimeException e) { close(); failed = true; return Snapshot.EMPTY; }
    }

    private static native boolean nativeOpen(long ownerHwnd);
    private static native void nativeClose();
    private static native boolean nativeCommand(int kind, int value, boolean shift);
    private static native boolean nativeText(String literal);
    private static native String nativePoll();
}
