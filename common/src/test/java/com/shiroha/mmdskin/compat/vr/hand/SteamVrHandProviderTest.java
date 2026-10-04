package com.shiroha.mmdskin.compat.vr.hand;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class SteamVrHandProviderTest {
    @Test void missingOptionalProviderNeverResolvesOpenVrNativeApi() throws Exception {
        SteamVrHandProvider.reset();
        var api = SteamVrHandProvider.class.getDeclaredField("api");
        api.setAccessible(true);
        Object before = api.get(null);
        SteamVrHandProvider.poll(null);
        SteamVrHandProvider.poll(new Object());
        assertSame(before, api.get(null));
        assertFalse(SteamVrHandProvider.isOpenVrProvider(null));
        assertFalse(SteamVrHandProvider.isOpenVrProvider(new Object()));
    }

    @Test void sourceMatchingFollowsActualDeviceAcrossReverseHandsAndRejectsOtherTrackers() {
        assertEquals(0, SteamVrHandProvider.matchController(7, 7, true, 9, true));
        assertEquals(1, SteamVrHandProvider.matchController(7, 9, true, 7, true));
        assertEquals(-1, SteamVrHandProvider.matchController(7, 7, false, 9, true));
        assertEquals(-1, SteamVrHandProvider.matchController(12, 7, true, 9, true));
        assertEquals(-1, SteamVrHandProvider.matchController(-1, -1, true, 9, true));
    }

    @Test void stalledOrFutureSnapshotsAreRejectedRatherThanFreezingTheFingers() {
        assertTrue(SteamVrHandProvider.isFresh(100, 100));
        assertTrue(SteamVrHandProvider.isFresh(100, 250_000_100));
        assertFalse(SteamVrHandProvider.isFresh(100, 250_000_101));
        assertFalse(SteamVrHandProvider.isFresh(101, 100));
    }

    @Test void activeSkeletonWithoutAConnectedValidPoseCannotKeepDrivingFingers() {
        assertTrue(SteamVrHandProvider.validTrackedPose(true, 7, true, true));
        assertFalse(SteamVrHandProvider.validTrackedPose(true, 7, false, true));
        assertFalse(SteamVrHandProvider.validTrackedPose(true, 7, true, false));
        assertFalse(SteamVrHandProvider.validTrackedPose(false, 7, true, true));
        assertFalse(SteamVrHandProvider.validTrackedPose(true, 0, true, true));
    }

    @Test void bonesRequireCompleteFinitePositionsAndNonzeroRotations() {
        float[] bones = new float[31 * 7];
        for (int i = 0; i < 31; i++) bones[i * 7 + 6] = 1;
        assertTrue(SteamVrHandProvider.validBones(bones));
        assertFalse(SteamVrHandProvider.validBones(null));
        assertFalse(SteamVrHandProvider.validBones(new float[30 * 7]));
        bones[6] = 0;
        assertFalse(SteamVrHandProvider.validBones(bones));
        bones[6] = 1;
        bones[8] = Float.NaN;
        assertFalse(SteamVrHandProvider.validBones(bones));
        bones[8] = Float.POSITIVE_INFINITY;
        assertFalse(SteamVrHandProvider.validBones(bones));
    }

    @Test void summaryFallbackIsFiniteClampedAndDetachedFromNativeStorage() {
        float[] values = { -.01f, .1f, .5f, .9f, 1.01f };
        float[] summary = SteamVrHandProvider.finiteSummary(values, 5);
        assertArrayEquals(new float[]{0, .1f, .5f, .9f, 1}, summary);
        values[2] = 0;
        assertEquals(.5f, summary[2]);
        assertNull(SteamVrHandProvider.finiteSummary(new float[]{Float.NaN}, 1));
        assertNull(SteamVrHandProvider.finiteSummary(new float[4], 5));
    }

    @Test void sameOriginModeAndDeviceChangesDiscardThePreviousOpenHandReference() {
        var cache = new SteamVrHandProvider.ReferenceCache();
        var controller = new SteamVrHandProvider.ReferenceSource(42, 7, 1);
        float[] oldReference = new float[31 * 7];
        assertNull(cache.forSource(controller));
        cache.store(oldReference);
        assertSame(oldReference, cache.forSource(new SteamVrHandProvider.ReferenceSource(42, 7, 1)));

        // Same activeOrigin, but Steam Link switches to fully tracked hands.
        var fullHand = new SteamVrHandProvider.ReferenceSource(42, 7, 2);
        assertNull(cache.forSource(fullHand));
        float[] handReference = new float[31 * 7];
        cache.store(handReference);
        assertSame(handReference, cache.forSource(fullHand));

        // Source slot replaced while origin handle and tracking accuracy stay the same.
        var replacement = new SteamVrHandProvider.ReferenceSource(42, 9, 2);
        assertNull(cache.forSource(replacement));
        cache.store(handReference);
        assertNull(cache.forSource(new SteamVrHandProvider.ReferenceSource(43, 9, 2)));
        cache.store(handReference);
        cache.clear();
        assertNull(cache.forSource(new SteamVrHandProvider.ReferenceSource(43, 9, 2)));
    }
}
