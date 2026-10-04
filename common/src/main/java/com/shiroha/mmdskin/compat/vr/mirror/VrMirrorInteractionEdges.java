package com.shiroha.mmdskin.compat.vr.mirror;

/** Tracks physical button edges across render eyes, mode changes and held navigation. */
final class VrMirrorInteractionEdges {
    private final boolean[] grip = new boolean[2];
    private final boolean[] trigger = new boolean[2];

    Presses sample(int hand, boolean gripDown, boolean triggerDown) {
        Presses result = new Presses(gripDown && !grip[hand], triggerDown && !trigger[hand]);
        prime(hand, gripDown, triggerDown);
        return result;
    }

    void prime(int hand, boolean gripDown, boolean triggerDown) {
        grip[hand] = gripDown;
        trigger[hand] = triggerDown;
    }

    record Presses(boolean grab, boolean click) {}
}
