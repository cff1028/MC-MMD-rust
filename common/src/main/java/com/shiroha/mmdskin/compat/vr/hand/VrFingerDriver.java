package com.shiroha.mmdskin.compat.vr.hand;

import com.shiroha.mmdskin.bridge.runtime.NativeModelPort;
import com.shiroha.mmdskin.config.ModelConfigData;

/** One avatar's independent left/right input filters, updated once per VR frame. */
public final class VrFingerDriver {
    private final VrFingerPoseState left = new VrFingerPoseState();
    private final VrFingerPoseState right = new VrFingerPoseState();
    private final float[] angles = new float[40];
    private long lastFrame = Long.MIN_VALUE;
    private long lastHandle;
    private Settings lastSettings;

    public void update(NativeModelPort nativeModel, long handle, long frame) {
        update(nativeModel, handle, frame, null);
    }

    public void update(NativeModelPort nativeModel, long handle, long frame, ModelConfigData config) {
        // These slots intentionally follow the same Vivecraft poses as body IK;
        // RawHand.physicalLeft still supplies the anatomy of the actual input hand.
        SteamVrHandProvider.RawHand leftInput = SteamVrHandProvider.readHand(1);
        SteamVrHandProvider.RawHand rightInput = SteamVrHandProvider.readHand(0);
        updateForInputs(nativeModel, handle, frame, config, leftInput, rightInput, System.nanoTime());
    }

    void updateForInputs(NativeModelPort nativeModel, long handle, long frame, ModelConfigData config,
                         SteamVrHandProvider.RawHand leftInput, SteamVrHandProvider.RawHand rightInput,
                         long now) {
        Settings settings = Settings.capture(config);
        if (lastFrame == frame && lastHandle == handle && settings.equals(lastSettings)) return;
        if (lastHandle != handle) {
            left.clear();
            right.clear();
        } else if (!settings.equals(lastSettings)) {
            // A menu edit must take effect even if both eyes still share a frame id.
            left.invalidateFrame();
            right.invalidateFrame();
        }
        lastFrame = frame;
        lastHandle = handle;
        lastSettings = settings;
        int validHands = 0;
        if (update(left, leftInput, frame, now, settings.enabled && settings.leftEnabled, settings.left)) validHands |= 1;
        if (update(right, rightInput, frame, now, settings.enabled && settings.rightEnabled, settings.right)) validHands |= 2;
        left.copyTo(angles, 0);
        right.copyTo(angles, 20);
        nativeModel.setVrThumbCalibration(handle, settings.left.axis, settings.right.axis);
        nativeModel.setVrFingerTracking(handle, angles, validHands);
    }

    private static boolean update(VrFingerPoseState state, SteamVrHandProvider.RawHand input,
                                  long frame, long now, boolean enabled, Thumb calibration) {
        if (!enabled) {
            // Explicit Off is immediate; tracking loss alone uses the normal graceful release.
            state.clear();
            return false;
        }
        float[] target = input == null ? null : SteamVrFingerRetargeter.retarget(
                input.bones(), input.referenceOpenHand(), input.physicalLeft(), input.curls(), input.splays());
        if (target != null) calibration.apply(target);
        return state.update(frame, now, input == null ? now : input.sampledAtNanos(), target);
    }

    private record Settings(boolean enabled, boolean leftEnabled, boolean rightEnabled, Thumb left, Thumb right) {
        static Settings capture(ModelConfigData config) {
            return new Settings(config == null || config.vrFingerTrackingEnabled,
                    config == null || config.vrLeftHandTrackingEnabled,
                    config == null || config.vrRightHandTrackingEnabled,
                    Thumb.capture(config == null ? null : config.vrLeftThumb),
                    Thumb.capture(config == null ? null : config.vrRightThumb));
        }
    }

    private record Thumb(float baseScale, float middleScale, float tipScale,
                         float baseOffset, float opposition, float axis) {
        static Thumb capture(ModelConfigData.ThumbCalibration source) {
            ModelConfigData.ThumbCalibration value = source == null
                    ? new ModelConfigData.ThumbCalibration() : source.copy().normalizeInPlace();
            return new Thumb(value.baseCurlScale, value.middleCurlScale, value.tipCurlScale,
                    radians(value.baseCurlOffsetDeg), radians(value.oppositionOffsetDeg),
                    radians(value.curlAxisOffsetDeg));
        }

        void apply(float[] target) {
            target[0] = clamp(target[0] * baseScale + baseOffset, -0.35f, 2.65f);
            target[1] = clamp(target[1] * middleScale, -0.35f, 2.65f);
            target[2] = clamp(target[2] * tipScale, -0.35f, 2.65f);
            // Positive opposition means toward the index/palm, opposite to outward splay.
            target[15] = clamp(target[15] - opposition, -1.2f, 1.2f);
        }

        private static float radians(float degrees) { return (float) Math.toRadians(degrees); }
        private static float clamp(float value, float min, float max) { return Math.max(min, Math.min(max, value)); }
    }
}
