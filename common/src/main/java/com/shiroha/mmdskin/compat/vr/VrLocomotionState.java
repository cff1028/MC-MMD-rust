package com.shiroha.mmdskin.compat.vr;

/** Frame-independent movement sampling. Physical head travel includes stick and room-scale walking. */
final class VrLocomotionState {
    private static final double MAX_SAMPLE_GAP = 0.25;
    private static final double MAX_SPEED = 16.0;
    private static final double DEAD_SPEED = 0.09;
    private long frameId = Long.MIN_VALUE;
    private long previousTime;
    private double previousX, previousZ;
    private double velocityX, velocityZ;
    private boolean initialized;
    private boolean previouslyAllowed;
    private Motion motion = Motion.STOPPED;

    Motion sample(long frame, long now, double x, double z, float bodyYaw, float bodyTurnRate,
                  float worldUnitsPerModelUnit, boolean allowed, boolean crouching) {
        if (initialized && frame == frameId) return motion;
        frameId = frame;
        if (!Double.isFinite(x) || !Double.isFinite(z) || !Float.isFinite(bodyYaw)
                || !Float.isFinite(bodyTurnRate) || !Float.isFinite(worldUnitsPerModelUnit)
                || worldUnitsPerModelUnit <= 1.0e-6f) {
            reset();
            return motion;
        }
        double elapsed = initialized ? (now - previousTime) * 1.0e-9 : 0;
        double dx = x - previousX, dz = z - previousZ;
        boolean continuous = initialized && elapsed > 0 && elapsed <= MAX_SAMPLE_GAP;
        boolean discontinuity = continuous && Math.hypot(dx, dz) / elapsed > MAX_SPEED;
        if (!continuous || discontinuity || !allowed || !previouslyAllowed) {
            velocityX = velocityZ = 0;
        } else {
            // Smooth sub-frame tracking noise; use an exponential response independent of refresh rate.
            double alpha = -Math.expm1(-elapsed / 0.10);
            velocityX += (dx / elapsed - velocityX) * alpha;
            velocityZ += (dz / elapsed - velocityZ) * alpha;
        }
        previousTime = now;
        previousX = x;
        previousZ = z;
        initialized = true;
        previouslyAllowed = allowed;
        boolean canStep = allowed && continuous && !discontinuity;
        double speed = Math.hypot(velocityX, velocityZ);
        // A small dead zone prevents ordinary headset jitter from continually lifting the feet.
        double weight = speed <= DEAD_SPEED ? 0 : (speed - DEAD_SPEED) / speed;
        double cos = Math.cos(bodyYaw), sin = Math.sin(bodyYaw);
        float localX = (float) ((cos * velocityX + sin * velocityZ) * weight / worldUnitsPerModelUnit);
        float localZ = (float) ((-sin * velocityX + cos * velocityZ) * weight / worldUnitsPerModelUnit);
        // Minecraft yaw and a model-space right-handed Y rotation have opposite signs.
        float turn = canStep ? -bodyTurnRate : 0;
        motion = new Motion(canStep ? localX : 0, canStep ? localZ : 0, turn, canStep, crouching);
        return motion;
    }

    void reset() {
        initialized = false;
        previouslyAllowed = false;
        velocityX = velocityZ = 0;
        frameId = Long.MIN_VALUE;
        motion = Motion.STOPPED;
    }

    record Motion(float velocityX, float velocityZ, float turnRate, boolean allowed, boolean crouching) {
        static final Motion STOPPED = new Motion(0, 0, 0, false, false);
    }
}
