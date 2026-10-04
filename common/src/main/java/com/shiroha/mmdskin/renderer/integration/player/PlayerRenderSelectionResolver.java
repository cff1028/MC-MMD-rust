package com.shiroha.mmdskin.renderer.integration.player;

import com.shiroha.mmdskin.compat.vr.VRArmHider;
import com.shiroha.mmdskin.player.model.PlayerModelResolver;
import com.shiroha.mmdskin.player.runtime.FirstPersonManager;
import com.shiroha.mmdskin.ui.network.PlayerModelSyncManager;
import net.minecraft.client.Minecraft;
import net.minecraft.client.player.AbstractClientPlayer;

final class PlayerRenderSelectionResolver {

    private static final String DEFAULT_RENDER_LABEL = "默认 (原版渲染)";

    private PlayerRenderSelectionResolver() {
    }

    static PlayerRenderSelection resolve(AbstractClientPlayer player, boolean isYsmActive) {
        Minecraft minecraft = Minecraft.getInstance();
        boolean isLocalPlayer = minecraft.player != null && minecraft.player.getUUID().equals(player.getUUID());
        String playerName = player.getName().getString();
        String selectedModel = PlayerModelSyncManager.getPlayerModel(player.getUUID(), playerName, isLocalPlayer);
        boolean inventoryPreview = InventoryEntityRenderScope.isRendering(player);
        var policy = new PolicyInput(inventoryPreview, isLocalPlayer,
                minecraft.options.getCameraType().isFirstPerson(),
                !inventoryPreview && FirstPersonManager.shouldRenderFirstPerson(),
                !inventoryPreview && VRArmHider.isLocalPlayerInVR(),
                shouldUseVanillaRenderer(selectedModel, player), isYsmActive);
        var earlyAction = resolvePolicy(policy);
        if (earlyAction != null) return PlayerRenderSelection.terminal(earlyAction);

        if (!inventoryPreview && !PlayerPerformanceGate.allowsMmd(player)) {
            return PlayerRenderSelection.terminal(PlayerMixinDelegate.RenderAction.FALLTHROUGH);
        }

        return PlayerRenderSelection.render(selectedModel, PlayerModelResolver.getCacheKey(player), isLocalPlayer);
    }

    record PolicyInput(boolean inventoryPreview, boolean localPlayer, boolean firstPersonCamera,
                       boolean renderDesktopBody, boolean localVr, boolean vanillaModel, boolean ysmActive) {}

    /** GUI previews are independent of world first-person visibility and camera state. */
    static PlayerMixinDelegate.RenderAction resolvePolicy(PolicyInput input) {
        if (!input.inventoryPreview) {
            if (input.localPlayer && input.firstPersonCamera && !input.renderDesktopBody && !input.localVr)
                return PlayerMixinDelegate.RenderAction.FALLTHROUGH;
            if (input.localPlayer && input.renderDesktopBody && (input.vanillaModel || input.ysmActive))
                return PlayerMixinDelegate.RenderAction.CANCEL;
        }
        if (input.vanillaModel || input.ysmActive) return PlayerMixinDelegate.RenderAction.FALLTHROUGH;
        return null;
    }

    private static boolean shouldUseVanillaRenderer(String selectedModel, AbstractClientPlayer player) {
        return selectedModel == null
                || selectedModel.isEmpty()
                || DEFAULT_RENDER_LABEL.equals(selectedModel)
                || player.isSpectator();
    }
}
