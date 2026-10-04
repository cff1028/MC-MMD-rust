/* 文件职责：定义模型运行时写入相关的 native 能力边界。 */
package com.shiroha.mmdskin.bridge.runtime;

public interface NativeModelPort {

    boolean setLayerBoneMask(long modelHandle, int layer, String rootBoneName);

    boolean setLayerBoneExclude(long modelHandle, int layer, String rootBoneName);

    long getModelMemoryUsage(long modelHandle);

    void setFirstPersonMode(long modelHandle, boolean enabled);

    void getEyeBonePosition(long modelHandle, float[] output);

    void applyVrTrackingInput(long modelHandle, float[] trackingData);

    default void applyVrTrackingInput(long modelHandle, float[] trackingData, float modelUnitsPerWorldUnit) {
        applyVrTrackingInput(modelHandle, trackingData);
    }

    void setVrEnabled(long modelHandle, boolean enabled);

    void setVrIkParams(long modelHandle, float armIkStrength);

    /** User reach / rendered avatar rest reach; changes target mapping, never bone lengths. */
    default void setVrArmLengthScale(long modelHandle, float scale) {
    }

    /** Model-local velocity and +Y angular velocity; sampleId is shared by both eyes. */
    default void setVrLocomotion(long modelHandle, long sampleId, float velocityXModel,
            float velocityZModel, float turnRateRadians, boolean allowed, boolean crouching) {
    }

    default float[] getVrCalibrationDimensions(long modelHandle) {
        return new float[9];
    }

    default void setVrFingerTracking(long modelHandle, float[] jointAngles, int validHands) {
    }

    default void setVrThumbCalibration(long modelHandle, float leftTwistRadians, float rightTwistRadians) {
    }

    int getMaterialCount(long modelHandle);

    void setMaterialVisible(long modelHandle, int materialIndex, boolean visible);

    void setAllMaterialsVisible(long modelHandle, boolean visible);

    void deleteModel(long modelHandle);
}
