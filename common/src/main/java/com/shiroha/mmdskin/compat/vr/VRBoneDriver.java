/* 文件职责：把 VR 追踪数据转换到模型局部空间并驱动原生 IK。 */
package com.shiroha.mmdskin.compat.vr;

import com.shiroha.mmdskin.bridge.runtime.NativeModelBridgePorts;
import com.shiroha.mmdskin.bridge.runtime.NativeModelPort;
import com.shiroha.mmdskin.compat.vr.hand.VrFingerDriver;
import com.shiroha.mmdskin.config.ModelConfigData;
import com.shiroha.mmdskin.config.ModelConfigManager;
import com.shiroha.mmdskin.ui.network.PlayerModelSyncManager;
import net.minecraft.util.Mth;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.phys.Vec3;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

import java.util.Map;
import java.util.WeakHashMap;

/**
 * 文件职责：把 VR 追踪数据转换到模型局部空间并驱动原生 IK。
 */
public final class VRBoneDriver {
    static final int TRACKING_POINT_STRIDE = 7;
    static final int TRACKING_PACKET_LENGTH = TRACKING_POINT_STRIDE * 3;

    private static final Logger LOGGER = LogManager.getLogger();
    private static volatile NativeModelPort modelPort = NativeModelBridgePorts.modelPort();
    private static final Map<Player, LocomotionBinding> locomotion = new WeakHashMap<>();
    private static final Map<Player, FingerBinding> fingers = new WeakHashMap<>();
    private static final float[] NO_FINGER_TRACKING = new float[40];

    private VRBoneDriver() {
    }

    public static void configureRuntimeCollaborators(NativeModelPort modelPort) {
        VRBoneDriver.modelPort = modelPort != null ? modelPort : NativeModelBridgePorts.modelPort();
        locomotion.clear();
        fingers.clear();
    }

    public static boolean isVRPlayer(Player player) {
        try {
            return VRDataProvider.isVRPlayer(player);
        } catch (Exception e) {
            return false;
        }
    }

    public static boolean driveModel(long modelHandle, Player player, float tickDelta) {
        return driveModel(modelHandle, player, tickDelta, 0.09f);
    }

    public static boolean driveModel(long modelHandle, Player player, float tickDelta, float worldUnitsPerModelUnit) {
        if (modelHandle == 0 || player == null || !Float.isFinite(worldUnitsPerModelUnit)
                || worldUnitsPerModelUnit <= 1.0e-6f) {
            return false;
        }

        try {
            float[] worldData = VRDataProvider.getRenderTrackingData(player);
            if (!hasUsableTrackingData(worldData)) {
                stopLocomotion(modelHandle, player, tickDelta);
                return false;
            }

            Vec3 renderOrigin = VRDataProvider.getRenderOrigin(player, tickDelta);
            if (!isFiniteVec3(renderOrigin)) {
                stopLocomotion(modelHandle, player, tickDelta);
                LOGGER.debug("Skipped VR bone drive because render origin was invalid");
                return false;
            }

            float px = (float) renderOrigin.x;
            float py = (float) renderOrigin.y;
            float pz = (float) renderOrigin.z;
            float yawRad = VRDataProvider.getBodyYawRad(player, tickDelta);
            if (!Float.isFinite(yawRad)) {
                stopLocomotion(modelHandle, player, tickDelta);
                LOGGER.debug("Skipped VR bone drive because body yaw was invalid");
                return false;
            }

            float[] localTracking = new float[TRACKING_PACKET_LENGTH];
            transformWorldTrackingToPlayerLocal(worldData, px, py, pz, Mth.cos(yawRad), Mth.sin(yawRad), localTracking);
            String modelName = PlayerModelSyncManager.getPlayerModel(player.getUUID(), player.getName().getString(), true);
            ModelConfigData config = ModelConfigManager.getLiveConfig(modelName);
            // Native IK maps controller reach to the avatar; this does not resize limb bones.
            modelPort.setVrArmLengthScale(modelHandle, config.vrArmLengthScale);
            // Adjust only the avatar anchor; the physical camera and interaction rays stay untouched.
            localTracking[0] += config.vrEyeOffsetX;
            localTracking[1] += config.vrEyeOffsetY;
            localTracking[2] += config.vrEyeOffsetZ;
            if (!hasUsableTrackingData(localTracking)) {
                stopLocomotion(modelHandle, player, tickDelta);
                LOGGER.debug("Skipped VR bone drive because transformed tracking packet became invalid");
                return false;
            }

            modelPort.applyVrTrackingInput(modelHandle, localTracking, 1.0f / worldUnitsPerModelUnit);
            updateFingers(modelHandle, player, tickDelta, config);
            updateLocomotion(modelHandle, player, tickDelta, worldData, yawRad, worldUnitsPerModelUnit);
            return true;
        } catch (Exception e) {
            LOGGER.debug("VR bone driving failed", e);
            return false;
        }
    }

    private static void updateLocomotion(long modelHandle, Player player, float tickDelta,
                                         float[] tracking, float yaw, float worldUnitsPerModelUnit) {
        LocomotionBinding binding = locomotion.get(player);
        if (binding == null || binding.modelHandle != modelHandle) {
            binding = new LocomotionBinding(modelHandle, new VrLocomotionState());
            locomotion.put(player, binding);
        }
        Vec3 head = VivecraftReflectionBridge.getWorldRenderHeadPosition(player);
        if (!isFiniteVec3(head)) head = new Vec3(tracking[0], tracking[1], tracking[2]);
        boolean grounded = player.onGround() && !player.getAbilities().flying && !player.isSwimming()
                && !player.isFallFlying() && !player.isPassenger() && !player.isSleeping()
                && !net.minecraft.client.Minecraft.getInstance().isPaused();
        long frame = locomotionFrameId(player, tickDelta);
        var motion = binding.state.sample(frame, System.nanoTime(), head.x, head.z, yaw,
                VRDataProvider.getBodyTurnRateRadians(player), worldUnitsPerModelUnit, grounded, player.isCrouching());
        modelPort.setVrLocomotion(modelHandle, frame, motion.velocityX(), motion.velocityZ(),
                motion.turnRate(), motion.allowed(), motion.crouching());
    }

    private static long locomotionFrameId(Player player, float tickDelta) {
        long frame = VivecraftReflectionBridge.getRenderFrameId();
        return frame != Long.MIN_VALUE ? frame : ((long) player.tickCount << 32) | (Float.floatToIntBits(tickDelta) & 0xffffffffL);
    }

    private static void stopLocomotion(long modelHandle, Player player, float tickDelta) {
        locomotion.remove(player);
        fingers.remove(player);
        modelPort.setVrFingerTracking(modelHandle, NO_FINGER_TRACKING, 0);
        modelPort.setVrLocomotion(modelHandle, locomotionFrameId(player, tickDelta), 0, 0, 0, false, false);
    }

    private static void updateFingers(long modelHandle, Player player, float tickDelta, ModelConfigData config) {
        if (player != net.minecraft.client.Minecraft.getInstance().player) return;
        FingerBinding binding = fingers.get(player);
        if (binding == null || binding.modelHandle != modelHandle) {
            binding = new FingerBinding(modelHandle, new VrFingerDriver());
            fingers.put(player, binding);
        }
        binding.driver.update(modelPort, modelHandle, locomotionFrameId(player, tickDelta), config);
    }

    private record FingerBinding(long modelHandle, VrFingerDriver driver) {}

    private record LocomotionBinding(long modelHandle, VrLocomotionState state) {}

    static void transformWorldTrackingToPlayerLocal(float[] worldData,
                                                    float px,
                                                    float py,
                                                    float pz,
                                                    float cosY,
                                                    float sinY,
                                                    float[] localTracking) {
        for (int i = 0; i < 3; i++) {
            int off = i * TRACKING_POINT_STRIDE;

            float dx = worldData[off] - px;
            float dy = worldData[off + 1] - py;
            float dz = worldData[off + 2] - pz;

            localTracking[off] = cosY * dx + sinY * dz;
            localTracking[off + 1] = dy;
            localTracking[off + 2] = -sinY * dx + cosY * dz;

            transformRotation(worldData, off + 3, localTracking, off + 3, cosY, sinY);
        }
    }

    static boolean hasUsableTrackingData(float[] trackingData) {
        if (trackingData == null || trackingData.length < TRACKING_PACKET_LENGTH) {
            return false;
        }
        return isUsableTrackingPoint(trackingData, 0)
                && (isUsableTrackingPoint(trackingData, TRACKING_POINT_STRIDE)
                || isUsableTrackingPoint(trackingData, TRACKING_POINT_STRIDE * 2));
    }

    static void transformRotationToPlayerLocal(float[] src,
                                               int si,
                                               float[] dst,
                                               int di,
                                               float yawRad) {
        transformRotation(src, si, dst, di, Mth.cos(yawRad), Mth.sin(yawRad));
    }

    private static boolean isUsableTrackingPoint(float[] trackingData, int offset) {
        return isFiniteTrackingSegment(trackingData, offset)
                && isFiniteQuaternion(trackingData, offset + 3)
                && quaternionLengthSquared(trackingData, offset + 3) > 1.0e-6f;
    }

    private static boolean isFiniteTrackingSegment(float[] trackingData, int offset) {
        for (int i = 0; i < TRACKING_POINT_STRIDE; i++) {
            if (!Float.isFinite(trackingData[offset + i])) {
                return false;
            }
        }
        return true;
    }

    private static boolean isFiniteQuaternion(float[] trackingData, int offset) {
        return Float.isFinite(trackingData[offset])
                && Float.isFinite(trackingData[offset + 1])
                && Float.isFinite(trackingData[offset + 2])
                && Float.isFinite(trackingData[offset + 3]);
    }

    private static float quaternionLengthSquared(float[] trackingData, int offset) {
        float qx = trackingData[offset];
        float qy = trackingData[offset + 1];
        float qz = trackingData[offset + 2];
        float qw = trackingData[offset + 3];
        return qx * qx + qy * qy + qz * qz + qw * qw;
    }

    private static boolean isFiniteVec3(Vec3 vec3) {
        return vec3 != null
                && Double.isFinite(vec3.x)
                && Double.isFinite(vec3.y)
                && Double.isFinite(vec3.z);
    }

    private static void transformRotation(float[] src, int si, float[] dst, int di, float cosY, float sinY) {
        float cosH = (float) Math.sqrt((1.0f + cosY) * 0.5f);
        float sinH = (float) Math.sqrt(Math.max(0.0f, (1.0f - cosY) * 0.5f));
        if (sinY < 0.0f) {
            sinH = -sinH;
        }

        float qx = src[si];
        float qy = src[si + 1];
        float qz = src[si + 2];
        float qw = src[si + 3];
        dst[di] = cosH * qx + sinH * qz;
        dst[di + 1] = cosH * qy + sinH * qw;
        dst[di + 2] = cosH * qz - sinH * qx;
        dst[di + 3] = cosH * qw - sinH * qy;

        float len = (float) Math.sqrt(
                dst[di] * dst[di] + dst[di + 1] * dst[di + 1]
                        + dst[di + 2] * dst[di + 2] + dst[di + 3] * dst[di + 3]);
        if (len > 1.0e-6f) {
            float inv = 1.0f / len;
            dst[di] *= inv;
            dst[di + 1] *= inv;
            dst[di + 2] *= inv;
            dst[di + 3] *= inv;
        }
    }

    public static void setVREnabled(long modelHandle, boolean enabled) {
        if (modelHandle == 0) {
            return;
        }
        try {
            modelPort.setVrEnabled(modelHandle, enabled);
            if (!enabled) {
                locomotion.values().removeIf(binding -> binding.modelHandle == modelHandle);
                fingers.values().removeIf(binding -> binding.modelHandle == modelHandle);
            }
        } catch (Exception e) {
            LOGGER.debug("Failed to set VR mode", e);
        }
    }

    public static void setVRIKParams(long modelHandle, float armIKStrength) {
        if (modelHandle == 0 || !Float.isFinite(armIKStrength)) {
            return;
        }
        try {
            modelPort.setVrIkParams(modelHandle, armIKStrength);
        } catch (Exception e) {
            LOGGER.debug("Failed to set VR IK params", e);
        }
    }
}
