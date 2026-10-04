package com.shiroha.mmdskin.compat.vr;

/** Physical left=0/right=1. A losing held trigger must be released before it may start a new interaction. */
public final class VrTriggerFocusState {
    private final boolean[] previous = new boolean[2];
    private boolean initialized;
    private int focus = 1, owner = -1;
    public record Update(int focus, int owner, boolean pressed, boolean released) {}
    public int focus() { return focus; }
    public void reset() { initialized = false; owner = -1; }
    public void useRightHand() { focus = 1; }
    public Update update(boolean left, boolean right, boolean leftEligible, boolean rightEligible, long leftTime, long rightTime) {
        boolean[] down = {left, right}, eligible = {leftEligible, rightEligible};
        if (!initialized) { previous[0] = left; previous[1] = right; initialized = true; return new Update(focus, -1, false, false); }
        boolean released = owner >= 0 && (!down[owner] || !eligible[owner]);
        if (released) owner = -1;
        boolean pressed = false;
        if (owner < 0) {
            boolean l = left && !previous[0] && leftEligible, r = right && !previous[1] && rightEligible;
            if (l || r) {
                owner = l && r ? leftTime < rightTime ? 0 : rightTime < leftTime ? 1 : focus : l ? 0 : 1;
                focus = owner; pressed = true;
            }
        }
        previous[0] = left; previous[1] = right;
        return new Update(focus, owner, pressed, released);
    }
}
