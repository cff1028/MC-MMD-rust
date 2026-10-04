package com.shiroha.mmdskin.compat.vr.hand;

import org.joml.Quaternionf;
import org.joml.Vector3f;

/** Converts SteamVR's fixed hand skeleton into anatomical angles, never bone lengths. */
public final class SteamVrFingerRetargeter {
    public static final int ANGLES_PER_HAND = 20;
    private static final int BONE_STRIDE = 7;
    private static final int[] ROOTS = {2, 7, 12, 17, 22};

    private SteamVrFingerRetargeter() {}

    /** Three flexions per finger, then five base splays; thumb, index, middle, ring, little. */
    public static float[] retarget(float[] bones, float[] reference, boolean physicalLeft,
                                   float[] curls, float[] splays) {
        float[] detailed = fromSkeleton(bones, reference, physicalLeft);
        return detailed != null ? detailed : fromSummary(curls, splays);
    }

    static float[] fromSkeleton(float[] bones, float[] reference, boolean physicalLeft) {
        if (!validSkeleton(bones) || !validSkeleton(reference)) return null;
        Vector3f forward = direction(reference, 1, 12);
        Vector3f thumbSide = direction(reference, 22, 7);
        if (!unit(forward)) return null;
        thumbSide.sub(new Vector3f(forward).mul(thumbSide.dot(forward)));
        if (!unit(thumbSide)) return null;
        Vector3f palmNormal = new Vector3f(thumbSide).cross(forward).mul(physicalLeft ? -1 : 1);
        float[] result = new float[ANGLES_PER_HAND];
        for (int finger = 0; finger < ROOTS.length; finger++) {
            for (int joint = 0; joint < 3; joint++) {
                int bone = ROOTS[finger] + joint;
                // Most avatar rigs have no separate metacarpals: include their
                // motion in the first finger joint, measured relative to the wrist.
                int parent = joint == 0 ? 1 : bone - 1;
                Vector3f rest = direction(reference, bone, bone + 1);
                if (!unit(rest)) return null;
                // Cancel the moving parent before measuring this joint: wrist motion
                // and upstream finger curl must not be counted again at every knuckle.
                Quaternionf delta = quaternion(reference, parent)
                        .mul(quaternion(bones, parent).conjugate())
                        .mul(quaternion(bones, bone))
                        .mul(quaternion(reference, bone).conjugate()).normalize();
                Vector3f towardPalm = new Vector3f(palmNormal)
                        .sub(new Vector3f(rest).mul(palmNormal.dot(rest)));
                if (!unit(towardPalm)) return null;
                Vector3f bendAxis = new Vector3f(rest).cross(towardPalm).normalize();
                if (joint == 0) {
                    Vector3f spreadAxis = new Vector3f(rest).cross(thumbSide);
                    spreadAxis.sub(new Vector3f(bendAxis).mul(spreadAxis.dot(bendAxis)));
                    if (!unit(spreadAxis)) return null;
                    // Remove spread before measuring curl. A projected finger direction
                    // reverses after 90 degrees of curl and would invent a sideways bend.
                    Quaternionf spread = twist(delta, spreadAxis);
                    result[15 + finger] = clamp(signedAngle(spread, spreadAxis), -1.2f, 1.2f);
                    delta = spread.conjugate().mul(delta).normalize();
                }
                result[finger * 3 + joint] = clamp(signedAngle(twist(delta, bendAxis), bendAxis), -0.35f, 2.65f);
            }
        }
        return result;
    }

    static float[] fromSummary(float[] curls, float[] splays) {
        if (!finite(curls, 5)) return null;
        float[] result = new float[ANGLES_PER_HAND];
        for (int finger = 0; finger < 5; finger++) {
            float curl = clamp(curls[finger], 0, 1);
            result[finger * 3] = curl * (finger == 0 ? 0.9f : 1.4f);
            result[finger * 3 + 1] = curl * (finger == 0 ? 1.1f : 1.7f);
            result[finger * 3 + 2] = curl * 1.3f;
        }
        if (finite(splays, 4)) {
            result[15] = (clamp(splays[0], 0, 1) - 1) * 0.6f;
            result[16] = (clamp(splays[1], 0, 1) - 1) * 0.35f;
            result[18] = (1 - clamp(splays[2], 0, 1)) * 0.35f;
            result[19] = result[18] + (1 - clamp(splays[3], 0, 1)) * 0.35f;
        }
        return result;
    }

    private static boolean validSkeleton(float[] bones) {
        if (!finite(bones, 31 * BONE_STRIDE)) return false;
        for (int bone = 0; bone < 26; bone++) {
            int offset = bone * BONE_STRIDE + 3;
            float length = 0;
            for (int i = 0; i < 4; i++) length += bones[offset + i] * bones[offset + i];
            if (!Float.isFinite(length) || length < 1e-6f) return false;
        }
        return true;
    }

    private static boolean finite(float[] values, int count) {
        if (values == null || values.length < count) return false;
        for (int i = 0; i < count; i++) if (!Float.isFinite(values[i])) return false;
        return true;
    }

    private static Quaternionf quaternion(float[] bones, int index) {
        int offset = index * BONE_STRIDE + 3;
        return new Quaternionf(bones[offset], bones[offset + 1], bones[offset + 2], bones[offset + 3]).normalize();
    }

    private static Quaternionf twist(Quaternionf rotation, Vector3f axis) {
        float projection = rotation.x * axis.x + rotation.y * axis.y + rotation.z * axis.z;
        Quaternionf twist = new Quaternionf(axis.x * projection, axis.y * projection, axis.z * projection, rotation.w);
        return twist.lengthSquared() < 1e-8f ? twist.identity() : twist.normalize();
    }

    private static float signedAngle(Quaternionf rotation, Vector3f axis) {
        float projection = rotation.x * axis.x + rotation.y * axis.y + rotation.z * axis.z;
        return wrap(2 * (float) Math.atan2(projection, rotation.w));
    }

    private static Vector3f direction(float[] bones, int from, int to) {
        int a = from * BONE_STRIDE, b = to * BONE_STRIDE;
        return new Vector3f(bones[b] - bones[a], bones[b + 1] - bones[a + 1], bones[b + 2] - bones[a + 2]);
    }

    private static boolean unit(Vector3f vector) {
        if (!Float.isFinite(vector.lengthSquared()) || vector.lengthSquared() < 1e-10f) return false;
        vector.normalize();
        return true;
    }

    private static float clamp(float value, float min, float max) { return Math.max(min, Math.min(max, value)); }
    private static float wrap(float angle) { return (float) Math.atan2(Math.sin(angle), Math.cos(angle)); }
}
