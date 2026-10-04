package com.shiroha.mmdskin.mixin.neoforge.compat;

import com.llamalad7.mixinextras.injector.ModifyExpressionValue;
import com.mojang.blaze3d.pipeline.RenderTarget;
import com.shiroha.mmdskin.compat.vr.keyboard.VivecraftKeyboardBridge;
import org.joml.Matrix4f;
import org.joml.Vector3fc;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Pseudo;
import org.spongepowered.asm.mixin.injection.At;

@Pseudo
@Mixin(targets = "org.vivecraft.client_vr.render.helpers.VREffectsHelper", remap = false)
public abstract class VivecraftKeyboardEffectsMixin {
    @ModifyExpressionValue(method = {"renderMenuRoom", "renderGuiAndShadow"},
            at = @At(value = "FIELD", target = "Lorg/vivecraft/client_vr/settings/VRSettings;physicalKeyboard:Z"),
            require = 0, remap = false)
    private static boolean mmdskin$renderCustomPlane(boolean original) {
        return original && !VivecraftKeyboardBridge.isActive();
    }

    @ModifyExpressionValue(method = "render2D",
            at = @At(value = "FIELD", target = "Lorg/vivecraft/client_vr/gameplay/screenhandlers/GuiHandler;GUI_SCALE:F"),
            require = 0, remap = false)
    private static float mmdskin$keyboardPhysicalWidth(float original, float partialTick, RenderTarget framebuffer,
                                                       Vector3fc pos, Matrix4f rot, boolean depthAlways) {
        return VivecraftKeyboardBridge.isKeyboardFramebuffer(framebuffer) ? VivecraftKeyboardBridge.KEYBOARD_SCALE : original;
    }
}
