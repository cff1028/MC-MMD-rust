package com.shiroha.mmdskin.mixin.neoforge;

import com.mojang.blaze3d.pipeline.RenderTarget;
import com.shiroha.mmdskin.compat.vr.mirror.MirrorMinecraftAccess;
import net.minecraft.client.Minecraft;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;

@Mixin(Minecraft.class)
public abstract class MirrorMinecraftMixin implements MirrorMinecraftAccess {
    @Shadow private RenderTarget mainRenderTarget;
    @Override public RenderTarget mmdskin$replaceRenderTarget(RenderTarget target) {
        RenderTarget old = mainRenderTarget;
        mainRenderTarget = target;
        return old;
    }
}

