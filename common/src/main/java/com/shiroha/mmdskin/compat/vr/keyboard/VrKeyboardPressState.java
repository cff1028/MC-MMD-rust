package com.shiroha.mmdskin.compat.vr.keyboard;

import java.util.Objects;

/** One contact/trigger owns one press until released. Sliding across keys never types a row. */
final class VrKeyboardPressState {
    private static final long REPEAT_DELAY = 450_000_000L;
    private static final long REPEAT_INTERVAL = 70_000_000L;
    private boolean armed;
    private String held;
    private long nextRepeat;

    String update(String key, boolean down, boolean safelyReleased, boolean repeatable, long now) {
        if (safelyReleased) {
            armed = true;
            held = null;
            nextRepeat = 0;
            return null;
        }
        if (!down) return null;
        if (key == null) { armed = false; held = null; return null; }
        if (armed) {
            armed = false;
            held = key;
            nextRepeat = now + REPEAT_DELAY;
            return key;
        }
        if (repeatable && Objects.equals(held, key) && now >= nextRepeat) {
            // A stalled render must not replay a backlog of destructive backspaces.
            nextRepeat = now + REPEAT_INTERVAL;
            return key;
        }
        return null;
    }

    boolean isHeld(String key) { return key != null && key.equals(held); }
    void reset() { armed = false; held = null; nextRepeat = 0; }
}
