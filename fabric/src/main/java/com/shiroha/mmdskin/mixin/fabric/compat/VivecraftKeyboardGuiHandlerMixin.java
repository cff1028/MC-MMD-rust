package com.shiroha.mmdskin.mixin.fabric.compat;

import com.llamalad7.mixinextras.injector.ModifyExpressionValue;
import com.shiroha.mmdskin.compat.vr.keyboard.VivecraftKeyboardBridge;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Pseudo;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import com.shiroha.mmdskin.compat.vr.VivecraftUiInteraction;

@Pseudo
@Mixin(targets = "org.vivecraft.client_vr.gameplay.screenhandlers.GuiHandler", remap = false)
public abstract class VivecraftKeyboardGuiHandlerMixin {
    @Inject(method = "processGui()V", at = @At("HEAD"), cancellable = true, remap = false)
    private static void mmdskin$twoHandHover(CallbackInfo ci) {
        if (VivecraftUiInteraction.processGui()) ci.cancel();
    }

    @Inject(method = "processBindingsGui()V", at = @At("TAIL"), remap = false)
    private static void mmdskin$twoHandClick(CallbackInfo ci) {
        VivecraftUiInteraction.processGuiButtons();
    }

    @ModifyExpressionValue(method = "processBindingsGui()V", at = {
            @At(value = "INVOKE", target = "Lnet/minecraft/client/KeyMapping;consumeClick()Z", ordinal = 0, remap = true),
            @At(value = "INVOKE", target = "Lnet/minecraft/client/KeyMapping;isDown()Z", ordinal = 0, remap = true)}, remap = false)
    private static boolean mmdskin$oneMouseOwner(boolean original) {
        return original && !VivecraftUiInteraction.controlsGuiClick() && !VivecraftKeyboardBridge.isDragging();
    }

    @ModifyExpressionValue(method = "processBindingsGui()V", at = {
            @At(value = "INVOKE", target = "Lnet/minecraft/client/KeyMapping;consumeClick()Z", ordinal = 2, remap = true),
            @At(value = "INVOKE", target = "Lnet/minecraft/client/KeyMapping;isDown()Z", ordinal = 2, remap = true),
            @At(value = "INVOKE", target = "Lnet/minecraft/client/KeyMapping;consumeClick()Z", ordinal = 3, remap = true),
            @At(value = "INVOKE", target = "Lnet/minecraft/client/KeyMapping;isDown()Z", ordinal = 3, remap = true)}, remap = false)
    private static boolean mmdskin$gripOwnsKeyboard(boolean original) {
        return original && !VivecraftKeyboardBridge.isDragging();
    }

    @ModifyExpressionValue(method = "onScreenChanged",
            at = @At(value = "FIELD", target = "Lorg/vivecraft/client_vr/settings/VRSettings;physicalKeyboard:Z"),
            require = 0, remap = false)
    private static boolean mmdskin$keepGuiLayoutIndependent(boolean original) {
        return original && !VivecraftKeyboardBridge.isActive();
    }
}

