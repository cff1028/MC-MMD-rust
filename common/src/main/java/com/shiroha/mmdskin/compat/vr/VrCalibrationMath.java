package com.shiroha.mmdskin.compat.vr;

import com.shiroha.mmdskin.config.ModelConfigData;

/** Geometry-only calibration using actual HMD/controllers and unanimated native bone dimensions. */
public final class VrCalibrationMath {
    private VrCalibrationMath() {}

    public enum Problem { NONE, TRACKING, STANDING, T_POSE, MODEL, RANGE }

    public record ModelMetrics(float eyeHeight, float leftArmLength, float rightArmLength,
                               float shoulderWidth, float baseWorldScale) {
        public static ModelMetrics fromNative(float[] data, float baseWorldScale) {
            if (data == null || data.length < 9 || !positive(baseWorldScale)) return null;
            for (int i = 0; i < 9; i++) if (!Float.isFinite(data[i])) return null;
            if (!positive(data[0]) || !positive(data[1]) || !positive(data[2])) return null;
            float dx = data[3] - data[6], dz = data[5] - data[8];
            float shoulderWidth = (float) Math.sqrt(dx * dx + dz * dz);
            if (!positive(shoulderWidth)) return null;
            return new ModelMetrics(data[0], data[1], data[2], shoulderWidth, baseWorldScale);
        }

        public float baseEyeHeightMetres() { return eyeHeight * baseWorldScale; }

        public float meanArmLengthMetres(float modelScale) {
            return (leftArmLength + rightArmLength) * 0.5f * baseWorldScale * modelScale;
        }
    }

    public record Measurement(Problem problem, float eyeHeightMetres, float spanMetres) {
        public boolean usable() { return problem == Problem.NONE; }
        static Measurement failure(Problem problem) { return new Measurement(problem, 0, 0); }
    }

    /** armLengthScale is a user-to-avatar reach ratio; it never scales the avatar's bones. */
    public record Result(Problem problem, float modelScale, float armLengthScale) {
        public boolean usable() { return problem == Problem.NONE; }
        static Result failure(Problem problem) { return new Result(problem, 1, 1); }
    }

    /** Packet order: head, anatomical right hand, anatomical left hand, each XYZ + quaternion. */
    public static Measurement measure(float[] packet, float floorY) {
        if (packet == null || packet.length < 21 || !Float.isFinite(floorY)) return Measurement.failure(Problem.TRACKING);
        for (int offset = 0; offset < 21; offset += 7) {
            float quaternionNorm = 0;
            for (int index = 0; index < 7; index++) {
                if (!Float.isFinite(packet[offset + index])) return Measurement.failure(Problem.TRACKING);
                if (index >= 3) quaternionNorm += packet[offset + index] * packet[offset + index];
            }
            if (!Float.isFinite(quaternionNorm) || quaternionNorm < 0.25f) return Measurement.failure(Problem.TRACKING);
        }
        float height = packet[1] - floorY;
        if (height < 0.75f || height > 2.5f) return Measurement.failure(Problem.STANDING);
        float dx = packet[7] - packet[14], dz = packet[9] - packet[16];
        float span = (float) Math.sqrt(dx * dx + dz * dz);
        float rightBelowEye = packet[1] - packet[8], leftBelowEye = packet[1] - packet[15];
        if (span < 0.8f || span > 2.6f || Math.abs(packet[8] - packet[15]) > 0.15f
                || rightBelowEye < 0.10f || rightBelowEye > 0.65f
                || leftBelowEye < 0.10f || leftBelowEye > 0.65f) return Measurement.failure(Problem.T_POSE);
        float sx = dx / span, sz = dz / span;
        float rightSide = (packet[7] - packet[0]) * sx + (packet[9] - packet[2]) * sz;
        float leftSide = (packet[14] - packet[0]) * sx + (packet[16] - packet[2]) * sz;
        if (rightSide < 0.25f || leftSide > -0.25f || Math.abs(rightSide + leftSide) > 0.3f)
            return Measurement.failure(Problem.T_POSE);

        float qx = packet[3], qy = packet[4], qz = packet[5], qw = packet[6];
        float qNorm = qx * qx + qy * qy + qz * qz + qw * qw;
        float forwardX = -2 * (qx * qz + qy * qw) / qNorm;
        float forwardZ = -(1 - 2 * (qx * qx + qy * qy) / qNorm);
        float forwardLength = (float) Math.sqrt(forwardX * forwardX + forwardZ * forwardZ);
        if (forwardLength < 0.7f) return Measurement.failure(Problem.T_POSE);
        forwardX /= forwardLength;
        forwardZ /= forwardLength;
        float handDepth = ((packet[7] + packet[14]) * 0.5f - packet[0]) * forwardX
                + ((packet[9] + packet[16]) * 0.5f - packet[2]) * forwardZ;
        if (Math.abs(sx * forwardX + sz * forwardZ) > 0.4f || Math.abs(handDepth) > 0.3f)
            return Measurement.failure(Problem.T_POSE);
        return new Measurement(Problem.NONE, height, span);
    }

    public static Result solve(ModelMetrics model, Measurement measurement) {
        if (model == null || !positive(model.eyeHeight()) || !positive(model.leftArmLength())
                || !positive(model.rightArmLength()) || !positive(model.shoulderWidth())
                || !positive(model.baseWorldScale())) return Result.failure(Problem.MODEL);
        if (measurement == null || !measurement.usable())
            return Result.failure(measurement == null ? Problem.TRACKING : measurement.problem());
        float scale = measurement.eyeHeightMetres() / model.baseEyeHeightMetres();
        float units = model.baseWorldScale() * scale;
        // Retarget physical reach to the fixed avatar skeleton after uniform height matching.
        float armScale = (measurement.spanMetres() - model.shoulderWidth() * units)
                / ((model.leftArmLength() + model.rightArmLength()) * units);
        if (!Float.isFinite(scale) || !Float.isFinite(armScale)
                || scale < ModelConfigData.MIN_MODEL_SCALE || scale > ModelConfigData.MAX_MODEL_SCALE
                || armScale < ModelConfigData.MIN_VR_ARM_LENGTH_SCALE || armScale > ModelConfigData.MAX_VR_ARM_LENGTH_SCALE)
            return Result.failure(Problem.RANGE);
        return new Result(Problem.NONE, scale, armScale);
    }

    /** Require 0.4 seconds of stable poses at the normal 20 Hz client tick before accepting a trigger. */
    public static final class StabilityWindow {
        private static final int SAMPLES = 8;
        private final float[] heights = new float[SAMPLES];
        private final float[] spans = new float[SAMPLES];
        private int count;
        private int next;

        public void clear() { count = 0; next = 0; }

        public void add(Measurement measurement) {
            if (measurement == null || !measurement.usable()) { clear(); return; }
            for (int index = 0; index < count; index++) {
                if (Math.abs(heights[index] - measurement.eyeHeightMetres()) > 0.035f
                        || Math.abs(spans[index] - measurement.spanMetres()) > 0.05f) {
                    clear();
                    break;
                }
            }
            heights[next] = measurement.eyeHeightMetres();
            spans[next] = measurement.spanMetres();
            next = (next + 1) % SAMPLES;
            count = Math.min(SAMPLES, count + 1);
        }

        public boolean ready() { return count == SAMPLES; }

        public float progress() { return count / (float) SAMPLES; }

        public Measurement average() {
            if (!ready()) return Measurement.failure(Problem.T_POSE);
            float height = 0, span = 0;
            for (int index = 0; index < SAMPLES; index++) { height += heights[index]; span += spans[index]; }
            return new Measurement(Problem.NONE, height / SAMPLES, span / SAMPLES);
        }
    }

    private static boolean positive(float value) { return Float.isFinite(value) && value > 1.0e-5f; }
}
