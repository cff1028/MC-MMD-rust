package com.shiroha.mmdskin.compat.vr;

/** Avatar-only heading. Controller positions deliberately cannot steer the torso. */
public final class VrBodyYawState {
    private static final double TWO_PI = Math.PI * 2;
    private static final float STANDING_START = radians(40);
    private static final float STANDING_STOP = radians(12);
    private static final float MOVING_START = radians(12);
    private static final float MOVING_STOP = radians(4);
    private static final float STANDING_SPEED = radians(150);
    private static final float MOVING_SPEED = radians(240);
    private boolean initialized;
    private boolean following;
    private long lastFrame;
    private long lastNanos;
    private float yaw = Float.NaN;
    private float roomRotation;
    private float turnRate;

    public void reset() {
        initialized = false;
        following = false;
        yaw = Float.NaN;
        turnRate = 0;
    }

    public float yawRadians() { return yaw; }

    /** Physical torso-follow rate only; virtual room/snap turns are excluded. */
    public float turnRateRadians() { return turnRate; }

    public float update(long frameId, long nowNanos, float headYaw, float roomRotationRadians,
                        double horizontalSpeedPerTick) {
        if (initialized && frameId == lastFrame) return yaw;
        if (!initialized) {
            if (!Float.isFinite(headYaw)) return Float.NaN;
            initialized = true;
            yaw = wrap(headYaw);
            roomRotation = Float.isFinite(roomRotationRadians) ? roomRotationRadians : 0;
            lastFrame = frameId;
            lastNanos = nowNanos;
            turnRate = 0;
            return yaw;
        }
        lastFrame = frameId;
        double elapsed = Math.max(0, Math.min(0.1, (nowNanos - lastNanos) * 1.0e-9));
        lastNanos = Math.max(nowNanos, lastNanos);
        // Vivecraft rotates its room vectors with JOML +Y; Minecraft yaw has the opposite sign.
        if (Float.isFinite(roomRotationRadians)) {
            yaw = wrap(yaw - wrap(roomRotationRadians - roomRotation));
            roomRotation = roomRotationRadians;
        }
        turnRate = 0;
        if (!Float.isFinite(headYaw) || elapsed == 0) return yaw;
        boolean moving = Double.isFinite(horizontalSpeedPerTick) && horizontalSpeedPerTick > 0.025;
        float start = moving ? MOVING_START : STANDING_START;
        float stop = moving ? MOVING_STOP : STANDING_STOP;
        float error = wrap(headYaw - yaw);
        float distance = Math.abs(error);
        if (distance > start) following = true;
        if (distance <= stop) following = false;
        if (following) {
            float step = (float) Math.min(distance - stop, (moving ? MOVING_SPEED : STANDING_SPEED) * elapsed);
            step = Math.copySign(step, error);
            yaw = wrap(yaw + step);
            turnRate = (float) (step / elapsed);
        }
        return yaw;
    }

    /** Reject near-vertical or invalid HMD forward vectors instead of amplifying yaw noise. */
    public static float headYaw(float forwardX, float forwardZ) {
        if (!Float.isFinite(forwardX) || !Float.isFinite(forwardZ)
                || forwardX * forwardX + forwardZ * forwardZ < 0.04f) return Float.NaN;
        return (float) Math.atan2(-forwardX, forwardZ);
    }

    static float wrap(float angle) {
        return (float) (angle - TWO_PI * Math.floor((angle + Math.PI) / TWO_PI));
    }

    private static float radians(float degrees) { return (float) Math.toRadians(degrees); }
}
