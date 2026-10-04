package com.shiroha.mmdskin.mixin.neoforge.compat;

import com.shiroha.mmdskin.compat.vr.mirror.SodiumMirrorCompat;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Pseudo;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/** Optional Sodium hook: mirror-only missing sections join the normal bounded build queues. */
@Pseudo
@Mixin(targets = "net.caffeinemc.mods.sodium.client.render.chunk.RenderSectionManager", remap = false)
public abstract class SodiumMirrorSectionManagerMixin {
    @Inject(method = "updateChunks(Z)V", at = @At("HEAD"), require = 0, remap = false)
    private void mmdskin$queueMirrorSections(boolean updateImmediately, CallbackInfo ci) {
        SodiumMirrorCompat.queueMissing(this);
    }
}
