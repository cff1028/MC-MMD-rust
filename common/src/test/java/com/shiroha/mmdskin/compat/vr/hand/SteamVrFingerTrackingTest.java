package com.shiroha.mmdskin.compat.vr.hand;

import org.joml.Matrix4f;
import org.joml.Vector3f;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class SteamVrFingerTrackingTest {
    @Test void fingertipPositionsUseActionPoseAndActualTipBones() {
        float[] bones = new float[31 * 7];
        for (int i = 0; i < 31; i++) { bones[i * 7] = i * .01f; bones[i * 7 + 6] = 1; }
        var result = SteamVrFingerTracking.transformTips(bones, new Matrix4f().translation(1, 2, 3).rotateY((float)Math.PI / 2));
        assertNotNull(result);
        assertEquals(5, result.length);
        assertEquals(1, result[1].x, 1e-6);
        assertEquals(2, result[1].y, 1e-6);
        assertEquals(2.9, result[1].z, 1e-6);
        assertEquals(2.75, result[4].z, 1e-6);
    }

    @Test void missingNativeProviderAndSummaryOnlyDataCannotGenerateFingerContacts() {
        SteamVrFingerTracking.reset();
        SteamVrFingerTracking.poll(null);
        SteamVrFingerTracking.poll(new Object());
        assertNull(SteamVrFingerTracking.readRoomTips(new Object(), 0, new Vector3f()));
        assertNull(SteamVrFingerTracking.transformTips(null, new Matrix4f()));
        assertNull(SteamVrFingerTracking.transformTips(new float[31 * 7], new Matrix4f()));
    }
}
