package com.shiroha.mmdskin.renderer.runtime.model;

import com.mojang.blaze3d.vertex.PoseStack;
import com.shiroha.mmdskin.NativeFunc;
import net.minecraft.world.entity.Entity;
import org.joml.Vector3f;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/** Exercises the real preview entry point without loading JNI or starting Minecraft. */
class InventoryPoseSnapshotTest {
    private NativeFunc previousNative;
    private FakeNative nativeApi;
    private TestModel world;
    private TestModel preview;

    @BeforeEach void setUp() {
        previousNative = AbstractMMDModel.nf;
        nativeApi = new FakeNative();
        AbstractMMDModel.nf = nativeApi;
        world = new TestModel(11, "avatar", 17);
        world.setVrActive(true);
        preview = new TestModel(22, "avatar", 0);
    }

    @AfterEach void restoreNative() { AbstractMMDModel.nf = previousNative; }

    @Test void copiesSolvedWorldPoseWithoutAdvancingEitherModelOrWritingWorldFlags() {
        assertTrue(preview.drawSnapshot(world));
        assertEquals(List.of("11->22"), nativeApi.copies);
        assertEquals(List.of("physics:22:false", "mask:22:false"), nativeApi.flags);
        assertEquals(17, world.revision());
        assertEquals(1, preview.revision());
        assertEquals(0, world.updates);
        assertEquals(0, preview.updates);
        assertEquals(0, world.draws);
        assertEquals(1, preview.draws);
        assertTrue(world.isVrActive());
        assertFalse(preview.isVrActive());
        assertTrue(preview.lastUpdateTime > 0, "Idle animation clock must stay current while snapshots are displayed");
    }

    @Test void repeatedDrawsReuseSnapshotUntilTheSourcePoseChanges() {
        assertTrue(preview.drawSnapshot(world));
        assertTrue(preview.drawSnapshot(world));
        assertEquals(1, nativeApi.copies.size());
        assertEquals(1, preview.revision());
        world.advanceRevision();
        assertTrue(preview.drawSnapshot(world));
        assertEquals(2, nativeApi.copies.size());
        assertEquals(2, preview.revision());
        assertEquals(3, preview.draws);
        assertEquals(18, world.revision());
    }

    @Test void returningFromIdleFallbackInvalidatesThePreviousCopiedPose() {
        assertTrue(preview.drawSnapshot(world));
        world.setVrActive(false);
        assertFalse(preview.drawSnapshot(world));
        // The ordinary idle preview advanced while VR was disabled.
        preview.advanceRevision();
        world.setVrActive(true);
        assertTrue(preview.drawSnapshot(world));
        assertEquals(2, nativeApi.copies.size());
        assertEquals(3, preview.revision());
    }

    @Test void reloadedSourceWithTheSameRevisionStillReplacesTheSnapshot() {
        assertTrue(preview.drawSnapshot(world));
        TestModel reloaded = new TestModel(33, "avatar", world.revision());
        reloaded.setVrActive(true);
        assertTrue(preview.drawSnapshot(reloaded));
        assertEquals(List.of("11->22", "33->22"), nativeApi.copies);
    }

    @Test void unavailableOrUnsafeSourceUsesIdleFallbackWithoutNativeChanges() {
        assertFalse(preview.drawSnapshot(null));
        assertFalse(world.drawSnapshot(world));
        assertFalse(new TestModel(11, "avatar", 0).drawSnapshot(world));
        TestModel uninitialized = new TestModel(33, "avatar", 0);
        uninitialized.setVrActive(true);
        assertFalse(preview.drawSnapshot(uninitialized));
        TestModel mismatched = new TestModel(33, "another-avatar", 1);
        mismatched.setVrActive(true);
        assertFalse(preview.drawSnapshot(mismatched));
        world.setVrActive(false);
        assertFalse(preview.drawSnapshot(world));
        world.setVrActive(true);
        preview.setVrActive(true);
        assertFalse(preview.drawSnapshot(world), "A world instance cannot be used as the destination");
        assertTrue(nativeApi.copies.isEmpty());
        assertTrue(nativeApi.flags.isEmpty());
        assertEquals(0, preview.draws);
    }

    @Test void failedNativeCopyDoesNotMarkStaleSnapshotAsCurrentAndCanRetry() {
        nativeApi.copySucceeds = false;
        assertFalse(preview.drawSnapshot(world));
        assertEquals(0, preview.revision());
        assertEquals(0, preview.draws);
        assertEquals(17, world.revision());
        nativeApi.copySucceeds = true;
        assertTrue(preview.drawSnapshot(world));
        assertEquals(2, nativeApi.copies.size());
        assertEquals(1, preview.revision());
        assertEquals(1, preview.draws);
    }

    private static final class FakeNative extends NativeFunc {
        boolean copySucceeds = true;
        final List<String> copies = new ArrayList<>();
        final List<String> flags = new ArrayList<>();
        @Override public boolean CopyModelPose(long source, long target) {
            copies.add(source + "->" + target);
            return copySucceeds;
        }
        @Override public void SetPhysicsEnabled(long model, boolean enabled) {
            flags.add("physics:" + model + ":" + enabled);
        }
        @Override public void SetFirstPersonMode(long model, boolean enabled) {
            flags.add("mask:" + model + ":" + enabled);
        }
    }

    private static final class TestModel extends AbstractMMDModel {
        int draws, updates;
        TestModel(long handle, String name, long revision) {
            model = handle;
            modelDir = "test/" + name;
            nativeUpdateRevision.set(revision);
        }
        long revision() { return getNativeUpdateRevision(); }
        void advanceRevision() { nativeUpdateRevision.incrementAndGet(); }
        boolean drawSnapshot(AbstractMMDModel source) {
            return renderInventoryPoseFrom(source, null, 25.0f, null, 0xF000F0);
        }
        @Override protected void doRenderModel(Entity entity, float yaw, float pitch, Vector3f translation,
                                               PoseStack matrixStack, int packedLight) {
            assertEquals(25.0f, yaw);
            assertEquals(0.0f, pitch);
            assertEquals(new Vector3f(), translation);
            draws++;
        }
        @Override protected void onUpdate(float deltaTime) { updates++; }
        @Override public void dispose() {}
    }
}
