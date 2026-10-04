package com.shiroha.mmdskin.compat.vr.mirror;

import net.minecraft.world.phys.Vec3;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class SodiumMirrorCompatTest {
    @Test void absentSodiumLeavesVanillaPathUsableWithoutCreatingAMinecraftClient() {
        assertThrows(ClassNotFoundException.class, () -> Class.forName("net.caffeinemc.mods.sodium.client.render.SodiumWorldRenderer"));
        assertDoesNotThrow(() -> {
            try (var ignored = SodiumMirrorCompat.begin()) {
                assertFalse(SodiumMirrorCompat.prepare(null, null));
            }
            SodiumMirrorCompat.queueMissing(null);
            SodiumMirrorCompat.clearPending();
        });
    }

    @Test void reflectedSectionDistanceUsesItsBoundsInsteadOfOnlyTheSectionOrigin() {
        Vec3 eye = new Vec3(8, 8, 8);
        assertTrue(SodiumMirrorCompat.withinDistance(eye, 0, 0, 0, 16));
        assertTrue(SodiumMirrorCompat.withinDistance(eye, -16, 0, 0, 16));
        assertTrue(SodiumMirrorCompat.withinDistance(eye, 16, 0, 0, 16));
        assertFalse(SodiumMirrorCompat.withinDistance(eye, 32, 0, 0, 16));
        assertFalse(SodiumMirrorCompat.withinDistance(eye, 0, 32, 0, 16));
        assertFalse(SodiumMirrorCompat.withinDistance(eye, 0, 0, 32, 16));
    }
}
