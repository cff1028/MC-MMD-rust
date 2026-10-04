package com.shiroha.mmdskin.mixin.neoforge.compat;

import com.llamalad7.mixinextras.injector.ModifyExpressionValue;
import com.shiroha.mmdskin.compat.vr.keyboard.VivecraftKeyboardBridge;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Pseudo;
import org.spongepowered.asm.mixin.injection.At;

@Pseudo
@Mixin(targets = "org.vivecraft.client_vr.render.helpers.VRPassHelper", remap = false)
public abstract class VivecraftKeyboardPassMixin {
    @ModifyExpressionValue(method = "renderAndSubmit",
            at = @At(value = "FIELD", target = "Lorg/vivecraft/client_vr/settings/VRSettings;physicalKeyboard:Z"),
            require = 0, remap = false)
    private static boolean mmdskin$prepareCustomFramebuffer(boolean original) {
        return original && !VivecraftKeyboardBridge.isActive();
    }
}

