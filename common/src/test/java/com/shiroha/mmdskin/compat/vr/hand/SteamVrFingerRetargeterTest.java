package com.shiroha.mmdskin.compat.vr.hand;

import org.joml.Quaternionf;
import org.joml.Vector3f;
import org.junit.jupiter.api.Test;
import java.util.Arrays;
import static org.junit.jupiter.api.Assertions.*;

class SteamVrFingerRetargeterTest {
    private static final int[] ROOTS = {2, 7, 12, 17, 22};
    private static final float EPSILON = 3e-5f;

    @Test void openReferenceIsNeutralForBothPhysicalHands() {
        for (boolean left : new boolean[]{false, true}) {
            float[] open = hand(left, new float[15], new float[5], new float[5]);
            assertArrayEquals(new float[20], retarget(open, open, left), EPSILON);
        }
    }

    @Test void everyKnuckleCanFlexIndependentlyWithoutCurlingOtherKnucklesOrFingers() {
        for (boolean left : new boolean[]{false, true}) {
            float[] open = hand(left, new float[15], new float[5], new float[5]);
            for (int joint = 0; joint < 15; joint++) {
                float[] curls = new float[15];
                curls[joint] = 0.72f;
                float[] actual = retarget(hand(left, curls, new float[5], new float[5]), open, left);
                float[] expected = new float[20];
                expected[joint] = 0.72f;
                assertArrayEquals(expected, actual, EPSILON, "physicalLeft=" + left + ", joint=" + joint);
            }
        }
    }

    @Test void metacarpalMotionBelongsToTheMmdBaseKnuckleRatherThanDisappearing() {
        float[] curls = new float[15], carpal = new float[5];
        curls[3] = 0.43f;
        carpal[1] = 0.22f;
        float[] result = retarget(hand(false, curls, new float[5], carpal),
                hand(false, new float[15], new float[5], new float[5]), false);
        assertEquals(0.65f, result[3], EPSILON);
        assertEquals(0, result[4], EPSILON);
        assertEquals(0, result[5], EPSILON);
    }

    @Test void deepFlexionDoesNotBecomeMaximumSidewaysSpread() {
        float[] curls = new float[15];
        Arrays.fill(curls, 1.9f);
        float[] result = retarget(hand(false, curls, new float[5], new float[5]),
                hand(false, new float[15], new float[5], new float[5]), false);
        for (int i = 0; i < 15; i++) assertEquals(1.9f, result[i], EPSILON, "joint=" + i);
        for (int i = 15; i < 20; i++) assertEquals(0, result[i], EPSILON, "splay=" + i);
    }

    @Test void splayAndCurlRemainIndependentIncludingDeepCurlOnBothHands() {
        for (boolean left : new boolean[]{false, true}) {
            for (float flex : new float[]{0.6f, 1.9f}) {
                float[] curls = new float[15], splays = new float[5];
                for (int finger = 0; finger < 5; finger++) {
                    curls[finger * 3] = flex;
                    splays[finger] = finger % 2 == 0 ? 0.5f : -0.4f;
                }
                float[] result = retarget(hand(left, curls, splays, new float[5]),
                        hand(left, new float[15], new float[5], new float[5]), left);
                for (int finger = 0; finger < 5; finger++) {
                    assertEquals(flex, result[finger * 3], EPSILON);
                    assertEquals(0, result[finger * 3 + 1], EPSILON);
                    assertEquals(0, result[finger * 3 + 2], EPSILON);
                    assertEquals(splays[finger], result[15 + finger], EPSILON);
                }
            }
        }
    }

    @Test void movingTheEntireHandCannotGenerateCurlOrSpread() {
        for (boolean left : new boolean[]{false, true}) {
            float[] reference = hand(left, new float[15], new float[5], new float[5]);
            float[] current = rigid(reference, new Quaternionf().rotationXYZ(0.9f, -1.2f, 2.1f),
                    new Vector3f(7, -2, 4));
            assertArrayEquals(new float[20], retarget(current, reference, left), EPSILON);
        }
    }

    @Test void fingerAnglesSurviveArbitraryReferenceAndCurrentWristOrientations() {
        float[] curls = new float[15];
        curls[3] = 0.35f; curls[4] = 1.1f; curls[5] = 0.82f;
        float[] splays = new float[5]; splays[1] = -0.3f;
        Quaternionf referenceRotation = new Quaternionf().rotationXYZ(-0.6f, 0.4f, 1.7f);
        Quaternionf movedRotation = new Quaternionf().rotationXYZ(0.7f, 0.8f, -0.2f).mul(referenceRotation);
        float[] reference = rigid(hand(false, new float[15], new float[5], new float[5]),
                referenceRotation, new Vector3f(-3, 1, 2));
        float[] current = rigid(hand(false, curls, splays, new float[5]), movedRotation, new Vector3f(5, 2, -1));
        float[] expected = new float[20];
        System.arraycopy(curls, 0, expected, 0, 15);
        System.arraycopy(splays, 0, expected, 15, 5);
        assertArrayEquals(expected, retarget(current, reference, false), EPSILON);
    }

    @Test void malformedSkeletonFallsBackToFiniteSummaryAndInvalidSummaryReleasesTracking() {
        float[] open = hand(false, new float[15], new float[5], new float[5]);
        float[] summary = {0, 0.25f, 0.5f, 0.75f, 1};
        float[] expected = SteamVrFingerRetargeter.retarget(null, null, false, summary, null);
        for (float[] broken : new float[][]{new float[4], new float[217], open.clone()}) {
            broken[0] = Float.NaN;
            assertArrayEquals(expected, SteamVrFingerRetargeter.retarget(broken, open, false, summary, null), EPSILON);
        }
        assertEquals(0, expected[0]);
        assertTrue(expected[12] > expected[3]);
        assertTrue(expected[13] > 1);
        assertNull(SteamVrFingerRetargeter.retarget(null, open, false, new float[]{0, 0, Float.NaN, 0, 0}, null));
    }

    @Test void detailedSkeletonWinsOverSummaryAndNonUnitQuaternionsAreNormalized() {
        float[] open = hand(false, new float[15], new float[5], new float[5]);
        float[] current = open.clone();
        for (int bone = 0; bone < 31; bone++) for (int i = 3; i < 7; i++) current[bone * 7 + i] *= -3;
        assertArrayEquals(new float[20], SteamVrFingerRetargeter.retarget(current, open, false,
                new float[]{1, 1, 1, 1, 1}, new float[]{0, 0, 0, 0}), EPSILON);
    }

    private static float[] retarget(float[] current, float[] reference, boolean left) {
        float[] result = SteamVrFingerRetargeter.retarget(current, reference, left, null, null);
        assertNotNull(result);
        return result;
    }

    /** Independent forward kinematics: local rotations produce matching model-space positions/quaternions. */
    private static float[] hand(boolean left, float[] curls, float[] splays, float[] carpals) {
        float[] bones = new float[31 * 7];
        for (int i = 0; i < 31; i++) bones[i * 7 + 6] = 1;
        float side = left ? -1 : 1;
        float[] lateral = {0.75f, 0.4f, 0, -0.3f, -0.6f};
        float[] segmentLengths = {0.5f, 0.35f, 0.25f};
        for (int finger = 0; finger < 5; finger++) {
            int root = ROOTS[finger];
            Vector3f position = new Vector3f(lateral[finger] * side, 0.2f, 0);
            Quaternionf parent = new Quaternionf().rotationX(carpals[finger]);
            if (finger > 0) put(bones, root - 1, position, parent);
            position.add(parent.transform(new Vector3f(0, finger == 0 ? 0.35f : 0.8f, 0)));
            for (int joint = 0; joint < 3; joint++) {
                Quaternionf local = new Quaternionf();
                if (joint == 0) local.rotateZ(-side * splays[finger]);
                local.rotateX(curls[finger * 3 + joint]);
                Quaternionf orientation = new Quaternionf(parent).mul(local);
                put(bones, root + joint, position, orientation);
                position.add(orientation.transform(new Vector3f(0, segmentLengths[joint], 0)));
                parent = orientation;
            }
            put(bones, root + 3, position, parent);
        }
        return bones;
    }

    private static float[] rigid(float[] original, Quaternionf rotation, Vector3f translation) {
        float[] result = original.clone();
        for (int bone = 0; bone < 31; bone++) {
            int b = bone * 7;
            Vector3f position = rotation.transform(new Vector3f(original[b], original[b + 1], original[b + 2])).add(translation);
            Quaternionf orientation = new Quaternionf(rotation).mul(new Quaternionf(
                    original[b + 3], original[b + 4], original[b + 5], original[b + 6]));
            put(result, bone, position, orientation);
        }
        return result;
    }

    private static void put(float[] bones, int bone, Vector3f position, Quaternionf rotation) {
        int b = bone * 7;
        bones[b] = position.x; bones[b + 1] = position.y; bones[b + 2] = position.z;
        bones[b + 3] = rotation.x; bones[b + 4] = rotation.y; bones[b + 5] = rotation.z; bones[b + 6] = rotation.w;
    }
}
