package com.shiroha.mmdskin.compat.vr.keyboard;

import com.shiroha.mmdskin.compat.vr.VrTriggerFocusState;
import org.joml.Matrix4f;
import org.joml.Matrix4fc;
import org.joml.Vector3f;

/** Room-space grab with yaw/pitch only and optional frame-rate independent following. */
public final class VrKeyboardGrabState {
    private static final float MAX_PITCH = (float) Math.toRadians(89);
    private final VrTriggerFocusState buttons = new VrTriggerFocusState();
    private Vector3f localOffset, position;
    private float startHandYaw, startHandPitch, startPanelYaw, startPanelPitch, handYaw, yaw, pitch;
    private long lastTime;
    private int owner = -1;
    public int owner() { return owner; }
    public void reset() { buttons.reset(); localOffset = null; position = null; owner = -1; lastTime = 0; }

    public Matrix4f update(Matrix4fc panel, Matrix4fc left, Matrix4fc right, boolean leftHit, boolean rightHit,
                           boolean leftDown, boolean rightDown, long leftTime, long rightTime) {
        return update(panel, left, right, leftHit, rightHit, leftDown, rightDown, leftTime, rightTime,
                false, 0, 0, System.nanoTime());
    }

    public Matrix4f update(Matrix4fc panel, Matrix4fc left, Matrix4fc right, boolean leftHit, boolean rightHit,
                           boolean leftDown, boolean rightDown, long leftTime, long rightTime,
                           boolean smoothing, int positionMs, int rotationMs, long now) {
        var state = buttons.update(leftDown, rightDown,
                valid(left) && (owner == 0 || leftHit), valid(right) && (owner == 1 || rightHit), leftTime, rightTime);
        owner = state.owner();
        // Freeze at the displayed pose on release/tracking loss. A new grab starts here, not at an old target.
        if (state.released()) { localOffset = null; position = null; }
        Matrix4fc hand = owner == 0 ? left : right;
        if (state.pressed() && valid(panel)) {
            handYaw = startHandYaw = yawOf(hand, 0); startHandPitch = pitchOf(hand);
            yaw = startPanelYaw = yawOf(panel, handYaw); pitch = startPanelPitch = pitchOf(panel);
            position = panel.getTranslation(new Vector3f());
            localOffset = new Vector3f(position).sub(hand.getTranslation(new Vector3f()));
            rotation(handYaw, startHandPitch).invert().transformDirection(localOffset);
            lastTime = now;
        }
        if (owner < 0 || localOffset == null) return null;
        handYaw = yawOf(hand, handYaw);
        float handPitch = pitchOf(hand);
        Vector3f target = rotation(handYaw, handPitch).transformDirection(localOffset, new Vector3f())
                .add(hand.getTranslation(new Vector3f()));
        float targetYaw = startPanelYaw + wrap(handYaw - startHandYaw);
        float targetPitch = clampPitch(startPanelPitch + handPitch - startHandPitch);
        double dt = Math.max(0, (now - lastTime) / 1_000_000_000.0); lastTime = now;
        float move = smoothing ? response(dt, positionMs) : 1;
        float turn = smoothing ? response(dt, rotationMs) : 1;
        position.lerp(target, move);
        yaw = wrap(yaw + wrap(targetYaw - yaw) * turn);
        pitch += (targetPitch - pitch) * turn;
        // Interpolate the two permitted angles, not quaternions (which can introduce roll between endpoints).
        return rotation(yaw, pitch).setTranslation(position);
    }

    private static Matrix4f rotation(float yaw, float pitch) { return new Matrix4f().rotationY(yaw).rotateX(pitch); }
    private static float yawOf(Matrix4fc pose, float fallback) {
        Vector3f normal = pose.transformDirection(0, 0, 1, new Vector3f()).normalize();
        // Keep yaw continuous when the controller points straight up/down.
        return normal.x * normal.x + normal.z * normal.z < 1e-4f ? fallback : (float) Math.atan2(normal.x, normal.z);
    }
    private static float pitchOf(Matrix4fc pose) {
        Vector3f normal = pose.transformDirection(0, 0, 1, new Vector3f()).normalize();
        return clampPitch((float) Math.asin(Math.max(-1, Math.min(1, -normal.y))));
    }
    private static float clampPitch(float angle) { return Math.max(-MAX_PITCH, Math.min(MAX_PITCH, angle)); }
    private static float wrap(float angle) { return (float) Math.atan2(Math.sin(angle), Math.cos(angle)); }
    private static float response(double seconds, int milliseconds) {
        // The configured time reaches 95% of a fixed target; zero means immediate following.
        return milliseconds <= 0 ? 1 : (float) -Math.expm1(-Math.log(20) * seconds * 1000 / milliseconds);
    }

    private static boolean valid(Matrix4fc matrix) {
        return matrix != null && matrix.isFinite() && java.lang.Math.abs(matrix.determinant()) > 1e-6f;
    }
}
