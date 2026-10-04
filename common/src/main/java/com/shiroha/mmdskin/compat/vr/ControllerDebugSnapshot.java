package com.shiroha.mmdskin.compat.vr;

import java.util.List;
import java.util.Locale;

/** Immutable observations; none of these values are input commands. Hand 0 is physical left. */
public record ControllerDebugSnapshot(String status, String provider, List<Hand> hands, List<Action> actions) {
    public ControllerDebugSnapshot { hands = List.copyOf(hands); actions = List.copyOf(actions); }
    public static ControllerDebugSnapshot unavailable(String status, String provider) {
        return new ControllerDebugSnapshot(status, provider, List.of(), List.of());
    }
    public record Axis(int index, int type, float x, float y) {
        public String typeName() { return switch (type) { case 1 -> "TrackPad"; case 2 -> "Joystick"; case 3 -> "Trigger"; default -> "None"; }; }
    }
    public record Hand(int side, int device, String model, boolean connected, boolean available,
                       long pressed, long touched, List<Axis> axes, List<String> buttons) {
        public Hand { axes = List.copyOf(axes); buttons = List.copyOf(buttons); }
    }
    public record Action(String name, String key, String type, int hand, boolean active, boolean down,
                         float x, float y, float z, String origin, List<String> bindings) {
        public Action { bindings = List.copyOf(bindings); }
        public boolean actuated() { return active && (down || Math.abs(x) > .01f || Math.abs(y) > .01f || Math.abs(z) > .01f); }
        public String id() { return name + ":" + hand; }
        public String value() {
            if (!active) return "inactive";
            return switch (type) {
                case "boolean" -> down ? "DOWN" : "UP";
                case "vector1" -> String.format(Locale.ROOT, "x=%+.3f", x);
                case "vector2" -> String.format(Locale.ROOT, "x=%+.3f y=%+.3f", x, y);
                default -> String.format(Locale.ROOT, "x=%+.3f y=%+.3f z=%+.3f", x, y, z);
            };
        }
    }
    public static boolean bit(long mask, int index) { return index >= 0 && index < 64 && (mask & (1L << index)) != 0; }
    static float finite(float value) { return Float.isFinite(value) ? value : 0; }
    static int physicalHand(int sample, boolean reverse) { return sample == (reverse ? 0 : 1) ? 0 : 1; }
    public String report() {
        StringBuilder text = new StringBuilder("MMD Skin controller debug\nstatus=").append(status).append(" provider=").append(provider).append('\n');
        for (Hand hand : hands) {
            text.append(hand.side == 0 ? "LEFT" : "RIGHT").append(" device=").append(hand.device).append(" model=").append(hand.model)
                .append(" connected=").append(hand.connected).append(" legacyRawAvailable=").append(hand.available)
                .append(String.format(Locale.ROOT, " pressed=0x%016X touched=0x%016X%n", hand.pressed, hand.touched));
            for (String button : hand.buttons) text.append("  ").append(button).append('\n');
            for (Axis axis : hand.axes) text.append(String.format(Locale.ROOT, "  axis%d %s x=%+.4f y=%+.4f%n", axis.index, axis.typeName(), axis.x, axis.y));
        }
        for (Action action : actions) {
            text.append(action.hand == 0 ? "LEFT " : action.hand == 1 ? "RIGHT " : "UNKNOWN ")
                .append(action.key).append(" [").append(action.type).append("] ").append(action.value()).append('\n')
                .append("  ").append(action.name).append(" origin=").append(action.origin).append('\n');
            for (String binding : action.bindings) text.append("  binding: ").append(binding).append('\n');
        }
        return text.toString();
    }
}
