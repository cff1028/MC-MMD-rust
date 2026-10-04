package com.shiroha.mmdskin.mixin.neoforge;

import com.shiroha.mmdskin.compat.vr.mirror.VrMirrorRenderer;
import net.minecraft.client.renderer.RenderStateShard;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/** Keep held-item render types inside the mirror's framebuffer during capture. */
@Mixin(RenderStateShard.class)
public abstract class RenderStateShardMixin {
    @Inject(method = {"setupRenderState()V", "clearRenderState()V"},
            at = @At("HEAD"), cancellable = true)
    private void mmdskin$keepMirrorOutputTarget(CallbackInfo ci) {
        // MAIN_TARGET and ITEM_ENTITY_TARGET normally rebind the world framebuffer.
        // Shader, texture, lighting, blending and other render states still run.
        if (VrMirrorRenderer.isDrawingAvatar()
                && (Object) this instanceof RenderStateShard.OutputStateShard) {
            ci.cancel();
        }
    }
}
