package com.shiroha.mmdskin.compat.vr.mirror;

/** One small submission budget shared by both reflected eyes in a game tick. */
public final class VrMirrorCompileBudget {
    private static final int SECTIONS_PER_TICK = 4;
    private static final long SUBMISSION_WINDOW_NANOS = 2_000_000L;
    private long tick = Long.MIN_VALUE;
    private long started;
    private int submitted;

    public boolean tryAcquire(long gameTick, long now) {
        if (gameTick != tick) {
            tick = gameTick;
            started = now;
            submitted = 0;
        }
        if (submitted >= SECTIONS_PER_TICK || now - started >= SUBMISSION_WINDOW_NANOS) return false;
        submitted++;
        return true;
    }
}
