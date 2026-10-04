/* 文件职责：保存单个模型的独立配置数据并提供归一化边界。 */
package com.shiroha.mmdskin.config;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.annotations.SerializedName;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.OutputStreamWriter;
import java.io.Reader;
import java.io.Writer;
import java.nio.charset.StandardCharsets;
import java.util.HashSet;
import java.util.Set;

public class ModelConfigData {
    private static final Logger LOGGER = LogManager.getLogger();
    private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();

    public static final float DEFAULT_EYE_MAX_ANGLE = 0.1745f;
    public static final float MIN_EYE_MAX_ANGLE = 0.05f;
    public static final float MAX_EYE_MAX_ANGLE = 1.0f;
    public static final float DEFAULT_MODEL_SCALE = 1.0f;
    public static final float MIN_MODEL_SCALE = 0.5f;
    public static final float MAX_MODEL_SCALE = 2.0f;
    public static final float DEFAULT_HELD_ITEM_SCALE = 1.0f;
    public static final float MIN_HELD_ITEM_SCALE = 0.25f;
    public static final float MAX_HELD_ITEM_SCALE = 2.0f;
    public static final float MAX_VR_EYE_OFFSET = 0.5f;
    public static final float MIN_VR_ARM_LENGTH_SCALE = 0.25f;
    public static final float MAX_VR_ARM_LENGTH_SCALE = 4.0f;
    public static final float MAX_HELD_ITEM_OFFSET = 1.0f;
    public static final float MAX_HELD_ITEM_ROTATION = 180.0f;

    public boolean eyeTrackingEnabled = true;
    public float eyeMaxAngle = DEFAULT_EYE_MAX_ANGLE;
    public float modelScale = DEFAULT_MODEL_SCALE;

    @SerializedName(value = "heldItemScale", alternate = {"firstPersonHeldBlockScale"})
    public float heldItemScale = DEFAULT_HELD_ITEM_SCALE;

    /** Model tracking-anchor adjustment in player-local world metres; never moves the HMD camera. */
    public float vrEyeOffsetX = 0.0f;
    public float vrEyeOffsetY = 0.0f;
    public float vrEyeOffsetZ = 0.0f;
    /** Per-model multiplier for the global VR arm IK strength. */
    public float vrArmIkStrength = 1.0f;
    /** User-to-avatar reach ratio for tracking retargeting; avatar bone lengths stay unchanged.
     * The persisted name is retained for compatibility with existing per-model calibration files. */
    public float vrArmLengthScale = 1.0f;

    /** Optional skeletal finger input; disabling it does not disable head/arm VR tracking. */
    public boolean vrFingerTrackingEnabled = true;
    public boolean vrLeftHandTrackingEnabled = true;
    public boolean vrRightHandTrackingEnabled = true;
    public ThumbCalibration vrLeftThumb = new ThumbCalibration();
    public ThumbCalibration vrRightThumb = new ThumbCalibration();

    /** Item-local offsets, before held-item scaling; shared by both hands. */
    public float heldItemOffsetX = 0.0f;
    public float heldItemOffsetY = 0.0f;
    public float heldItemOffsetZ = 0.0f;
    /** Item-local rotations in degrees, applied in X/Y/Z order. */
    public float heldItemRotationX = 0.0f;
    public float heldItemRotationY = 0.0f;
    public float heldItemRotationZ = 0.0f;

    public Set<Integer> hiddenMaterials = new HashSet<>();

    public static ModelConfigData load(File configFile) {
        if (!configFile.exists()) {
            return new ModelConfigData();
        }

        try (Reader reader = new InputStreamReader(new FileInputStream(configFile), StandardCharsets.UTF_8)) {
            ModelConfigData config = GSON.fromJson(reader, ModelConfigData.class);
            if (config == null) {
                return new ModelConfigData();
            }
            return config.normalizeInPlace();
        } catch (Exception e) {
            LOGGER.warn("加载模型配置失败: {}, 使用默认配置", e.getMessage());
            return new ModelConfigData();
        }
    }

    public ModelConfigData copy() {
        ModelConfigData copy = new ModelConfigData();
        copy.eyeTrackingEnabled = eyeTrackingEnabled;
        copy.eyeMaxAngle = eyeMaxAngle;
        copy.modelScale = modelScale;
        copy.heldItemScale = heldItemScale;
        copy.vrEyeOffsetX = vrEyeOffsetX;
        copy.vrEyeOffsetY = vrEyeOffsetY;
        copy.vrEyeOffsetZ = vrEyeOffsetZ;
        copy.vrArmIkStrength = vrArmIkStrength;
        copy.vrArmLengthScale = vrArmLengthScale;
        copy.vrFingerTrackingEnabled = vrFingerTrackingEnabled;
        copy.vrLeftHandTrackingEnabled = vrLeftHandTrackingEnabled;
        copy.vrRightHandTrackingEnabled = vrRightHandTrackingEnabled;
        copy.vrLeftThumb = vrLeftThumb == null ? new ThumbCalibration() : vrLeftThumb.copy();
        copy.vrRightThumb = vrRightThumb == null ? new ThumbCalibration() : vrRightThumb.copy();
        copy.heldItemOffsetX = heldItemOffsetX;
        copy.heldItemOffsetY = heldItemOffsetY;
        copy.heldItemOffsetZ = heldItemOffsetZ;
        copy.heldItemRotationX = heldItemRotationX;
        copy.heldItemRotationY = heldItemRotationY;
        copy.heldItemRotationZ = heldItemRotationZ;
        copy.hiddenMaterials = hiddenMaterials == null ? new HashSet<>() : new HashSet<>(hiddenMaterials);
        return copy;
    }

    public ModelConfigData normalizedCopy() {
        return copy().normalizeInPlace();
    }

    public ModelConfigData normalizeInPlace() {
        eyeMaxAngle = clampOrDefault(eyeMaxAngle, MIN_EYE_MAX_ANGLE, MAX_EYE_MAX_ANGLE, DEFAULT_EYE_MAX_ANGLE);
        modelScale = clampOrDefault(modelScale, MIN_MODEL_SCALE, MAX_MODEL_SCALE, DEFAULT_MODEL_SCALE);
        heldItemScale = clampOrDefault(heldItemScale, MIN_HELD_ITEM_SCALE, MAX_HELD_ITEM_SCALE, DEFAULT_HELD_ITEM_SCALE);
        vrEyeOffsetX = clampOrDefault(vrEyeOffsetX, -MAX_VR_EYE_OFFSET, MAX_VR_EYE_OFFSET, 0.0f);
        vrEyeOffsetY = clampOrDefault(vrEyeOffsetY, -MAX_VR_EYE_OFFSET, MAX_VR_EYE_OFFSET, 0.0f);
        vrEyeOffsetZ = clampOrDefault(vrEyeOffsetZ, -MAX_VR_EYE_OFFSET, MAX_VR_EYE_OFFSET, 0.0f);
        vrArmIkStrength = clampOrDefault(vrArmIkStrength, 0.0f, 1.0f, 1.0f);
        vrArmLengthScale = clampOrDefault(vrArmLengthScale, MIN_VR_ARM_LENGTH_SCALE, MAX_VR_ARM_LENGTH_SCALE, 1.0f);
        vrLeftThumb = (vrLeftThumb == null ? new ThumbCalibration() : vrLeftThumb).normalizeInPlace();
        vrRightThumb = (vrRightThumb == null ? new ThumbCalibration() : vrRightThumb).normalizeInPlace();
        heldItemOffsetX = clampOrDefault(heldItemOffsetX, -MAX_HELD_ITEM_OFFSET, MAX_HELD_ITEM_OFFSET, 0.0f);
        heldItemOffsetY = clampOrDefault(heldItemOffsetY, -MAX_HELD_ITEM_OFFSET, MAX_HELD_ITEM_OFFSET, 0.0f);
        heldItemOffsetZ = clampOrDefault(heldItemOffsetZ, -MAX_HELD_ITEM_OFFSET, MAX_HELD_ITEM_OFFSET, 0.0f);
        heldItemRotationX = clampOrDefault(heldItemRotationX, -MAX_HELD_ITEM_ROTATION, MAX_HELD_ITEM_ROTATION, 0.0f);
        heldItemRotationY = clampOrDefault(heldItemRotationY, -MAX_HELD_ITEM_ROTATION, MAX_HELD_ITEM_ROTATION, 0.0f);
        heldItemRotationZ = clampOrDefault(heldItemRotationZ, -MAX_HELD_ITEM_ROTATION, MAX_HELD_ITEM_ROTATION, 0.0f);
        hiddenMaterials = normalizeHiddenMaterials(hiddenMaterials);
        return this;
    }

    public void save(File configFile) {
        PathConstants.ensureDirectoryExists(configFile.getParentFile());
        try (Writer writer = new OutputStreamWriter(new FileOutputStream(configFile), StandardCharsets.UTF_8)) {
            GSON.toJson(this, writer);
            LOGGER.debug("模型配置已保存: {}", configFile.getName());
        } catch (IOException e) {
            LOGGER.error("保存模型配置失败: {}", e.getMessage());
        }
    }

    private static float clampOrDefault(float value, float min, float max, float fallback) {
        if (!Float.isFinite(value)) {
            return fallback;
        }
        return Math.max(min, Math.min(max, value));
    }

    /** Runtime mapping parameters only. No model asset or rest-bone geometry is modified. */
    public static final class ThumbCalibration {
        public static final float MAX_CURL_SCALE = 3.0f;
        public static final float MAX_OFFSET_DEGREES = 90.0f;
        public static final float MAX_AXIS_DEGREES = 180.0f;
        public float baseCurlScale = 1.0f;
        public float middleCurlScale = 1.0f;
        public float tipCurlScale = 1.0f;
        public float baseCurlOffsetDeg = 0.0f;
        public float oppositionOffsetDeg = 0.0f;
        /** Rotation of the flexion plane around each thumb segment's resting longitudinal axis. */
        public float curlAxisOffsetDeg = 0.0f;

        public ThumbCalibration copy() {
            ThumbCalibration copy = new ThumbCalibration();
            copy.baseCurlScale = baseCurlScale;
            copy.middleCurlScale = middleCurlScale;
            copy.tipCurlScale = tipCurlScale;
            copy.baseCurlOffsetDeg = baseCurlOffsetDeg;
            copy.oppositionOffsetDeg = oppositionOffsetDeg;
            copy.curlAxisOffsetDeg = curlAxisOffsetDeg;
            return copy;
        }

        public ThumbCalibration normalizeInPlace() {
            baseCurlScale = clampOrDefault(baseCurlScale, 0, MAX_CURL_SCALE, 1);
            middleCurlScale = clampOrDefault(middleCurlScale, 0, MAX_CURL_SCALE, 1);
            tipCurlScale = clampOrDefault(tipCurlScale, 0, MAX_CURL_SCALE, 1);
            baseCurlOffsetDeg = clampOrDefault(baseCurlOffsetDeg, -MAX_OFFSET_DEGREES, MAX_OFFSET_DEGREES, 0);
            oppositionOffsetDeg = clampOrDefault(oppositionOffsetDeg, -MAX_OFFSET_DEGREES, MAX_OFFSET_DEGREES, 0);
            curlAxisOffsetDeg = clampOrDefault(curlAxisOffsetDeg, -MAX_AXIS_DEGREES, MAX_AXIS_DEGREES, 0);
            return this;
        }
    }

    private static Set<Integer> normalizeHiddenMaterials(Set<Integer> source) {
        if (source == null || source.isEmpty()) {
            return new HashSet<>();
        }
        Set<Integer> normalized = new HashSet<>();
        for (Integer index : source) {
            if (index != null && index >= 0) {
                normalized.add(index);
            }
        }
        return normalized;
    }
}
