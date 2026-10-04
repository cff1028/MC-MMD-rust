package com.shiroha.mmdskin.compat.vr.hand;

import java.util.Arrays;

/** Per-hand, frame-rate independent filtering; losing a hand releases its override. */
public final class VrFingerPoseState {
    private static final long MAX_SAMPLE_AGE = 250_000_000L;
    private static final long RELEASE_AFTER = 220_000_000L;
    private final float[] angles = new float[20];
    private long lastFrame = Long.MIN_VALUE;
    private long lastTime;
    private long lastValidTime;
    private boolean active;

    public boolean update(long frame, long now, long sampledAt, float[] target) {
        if (lastFrame == frame) return active;
        lastFrame = frame;
        float dt = lastTime == 0 ? 1f / 90f : Math.max(0, Math.min(0.1f, (now - lastTime) * 1e-9f));
        lastTime = now;
        boolean valid = valid(target) && sampledAt <= now && now - sampledAt <= MAX_SAMPLE_AGE;
        if (valid) {
            active = true;
            lastValidTime = now;
        } else if (!active || now - lastValidTime >= RELEASE_AFTER) {
            Arrays.fill(angles, 0);
            active = false;
            return false;
        }
        float blend = (float) -Math.expm1(-dt / (valid ? 0.035 : 0.06));
        for (int i = 0; i < angles.length; i++) {
            float next = valid ? target[i] : 0;
            angles[i] += (next - angles[i]) * blend;
        }
        return true;
    }

    public void copyTo(float[] destination, int offset) {
        System.arraycopy(angles, 0, destination, offset, angles.length);
    }

    public void clear() {
        Arrays.fill(angles, 0);
        lastFrame = Long.MIN_VALUE;
        lastTime = lastValidTime = 0;
        active = false;
    }

    /** A live calibration edit may arrive between two draws of the same frame. */
    void invalidateFrame() {
        lastFrame = Long.MIN_VALUE;
    }

    private static boolean valid(float[] values) {
        if (values == null || values.length != 20) return false;
        for (float value : values) if (!Float.isFinite(value)) return false;
        return true;
    }
}
