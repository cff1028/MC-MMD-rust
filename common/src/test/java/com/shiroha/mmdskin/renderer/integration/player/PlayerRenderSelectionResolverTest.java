package com.shiroha.mmdskin.renderer.integration.player;

import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;
import static com.shiroha.mmdskin.renderer.integration.player.PlayerMixinDelegate.RenderAction.*;

class PlayerRenderSelectionResolverTest {
    private static PlayerRenderSelectionResolver.PolicyInput state(boolean preview, boolean body,
            boolean vr, boolean vanilla, boolean ysm) {
        return new PlayerRenderSelectionResolver.PolicyInput(preview, true, true, body, vr, vanilla, ysm);
    }

    @Test void inventoryAllowsMmdWhenDesktopFirstPersonBodyIsDisabled() {
        assertNull(PlayerRenderSelectionResolver.resolvePolicy(state(true, false, false, false, false)));
        assertEquals(FALLTHROUGH, PlayerRenderSelectionResolver.resolvePolicy(state(false, false, false, false, false)));
    }

    @Test void inventoryDoesNotCancelVanillaOrAnotherModsPreviewBecauseOfWorldBodyPolicy() {
        assertEquals(FALLTHROUGH, PlayerRenderSelectionResolver.resolvePolicy(state(true, true, false, true, false)));
        assertEquals(FALLTHROUGH, PlayerRenderSelectionResolver.resolvePolicy(state(true, true, false, false, true)));
        assertEquals(CANCEL, PlayerRenderSelectionResolver.resolvePolicy(state(false, true, false, true, false)));
        assertEquals(CANCEL, PlayerRenderSelectionResolver.resolvePolicy(state(false, true, false, false, true)));
    }

    @Test void vrWorldStillUsesMmdWhileInventoryIsIndependentlyEligible() {
        assertNull(PlayerRenderSelectionResolver.resolvePolicy(state(false, false, true, false, false)));
        assertNull(PlayerRenderSelectionResolver.resolvePolicy(state(true, false, true, false, false)));
    }

    @Test void remoteAndThirdPersonPlayersRetainTheirNormalWorldEligibility() {
        assertNull(PlayerRenderSelectionResolver.resolvePolicy(new PlayerRenderSelectionResolver.PolicyInput(
                false, false, true, false, false, false, false)));
        assertNull(PlayerRenderSelectionResolver.resolvePolicy(new PlayerRenderSelectionResolver.PolicyInput(
                false, true, false, false, false, false, false)));
    }
}
