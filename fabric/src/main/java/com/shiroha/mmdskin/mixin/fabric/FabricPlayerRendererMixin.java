/* 文件职责：在 Fabric 生物渲染阶段识别玩家并接入 MMD 玩家渲染。 */
package com.shiroha.mmdskin.mixin.fabric;

import com.mojang.blaze3d.vertex.PoseStack;
import com.shiroha.mmdskin.compat.vr.VRArmHider;
import com.shiroha.mmdskin.fabric.YsmCompat;
import com.shiroha.mmdskin.player.runtime.FirstPersonManager;
import com.shiroha.mmdskin.renderer.integration.player.PlayerMixinDelegate;
import com.shiroha.mmdskin.renderer.integration.player.InventoryEntityRenderScope;
import com.shiroha.mmdskin.renderer.integration.player.PlayerMixinDelegate.RenderAction;
import com.shiroha.mmdskin.renderer.integration.state.LivingEntityRenderStateBridge;
import net.minecraft.client.Minecraft;
import net.minecraft.client.player.AbstractClientPlayer;
import net.minecraft.client.renderer.MultiBufferSource;
import net.minecraft.client.renderer.entity.LivingEntityRenderer;
import net.minecraft.client.renderer.entity.state.LivingEntityRenderState;
import net.minecraft.client.renderer.entity.state.PlayerRenderState;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(LivingEntityRenderer.class)
public abstract class FabricPlayerRendererMixin {
    // Vivecraft 1.21.4 的 VRPlayerRenderer 继承这条渲染状态路径，也由此处接管。
    // 不再向 VRPlayerRenderer 注入旧版 render(AbstractClientPlayer, ...) 方法。
    @Inject(
        method = "render(Lnet/minecraft/client/renderer/entity/state/LivingEntityRenderState;Lcom/mojang/blaze3d/vertex/PoseStack;Lnet/minecraft/client/renderer/MultiBufferSource;I)V",
        at = @At("HEAD"),
        cancellable = true
    )
    private void mmdskin$renderPlayer(LivingEntityRenderState state, PoseStack matrixStack,
                                      MultiBufferSource vertexConsumers, int packedLight, CallbackInfo ci) {
        if (!(state instanceof PlayerRenderState playerState)) {
            return;
        }

        if (!(((LivingEntityRenderStateBridge) playerState).mmdskin$getLivingEntity() instanceof AbstractClientPlayer player)) {
            return;
        }

        float tickDelta = ((LivingEntityRenderStateBridge) playerState).mmdskin$getTickDelta();
        Minecraft minecraft = Minecraft.getInstance();
        boolean isLocalPlayer = minecraft.player != null && minecraft.player.getUUID().equals(player.getUUID());
        if (!InventoryEntityRenderScope.isRendering(player) && isLocalPlayer && minecraft.options.getCameraType().isFirstPerson()
                && !FirstPersonManager.shouldRenderFirstPerson() && !VRArmHider.isLocalPlayerInVR()) {
            FirstPersonManager.reset();
            return;
        }

        RenderAction action = PlayerMixinDelegate.handleRender(
                player, playerState.bodyRot, tickDelta, matrixStack, vertexConsumers, packedLight,
                YsmCompat.isYsmActive(player));

        PlayerMixinDelegate.renderSceneModel(player, tickDelta, matrixStack, packedLight);
        if (action == RenderAction.CANCEL) {
            com.shiroha.mmdskin.compat.vr.mirror.VrMirrorNameTags.render(
                    player, playerState, matrixStack, vertexConsumers, packedLight, tickDelta);
            ci.cancel();
        }
    }
}
