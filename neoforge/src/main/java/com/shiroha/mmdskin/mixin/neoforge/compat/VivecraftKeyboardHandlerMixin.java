package com.shiroha.mmdskin.mixin.neoforge.compat;

import com.llamalad7.mixinextras.injector.ModifyExpressionValue;
import com.shiroha.mmdskin.compat.vr.keyboard.VivecraftKeyboardBridge;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Pseudo;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/** Routes a mod-owned keyboard through Vivecraft's normal overlay lifecycle. */
@Pseudo
@Mixin(targets = "org.vivecraft.client_vr.gameplay.screenhandlers.KeyboardHandler", remap = false)
public abstract class VivecraftKeyboardHandlerMixin {
    @ModifyExpressionValue(method = {"setOverlayShowing(Z)Z", "reinitKeyboard()V", "orientOverlay(Z)V",
            "processGui()V", "processBindings()V"},
            at = @At(value = "FIELD", target = "Lorg/vivecraft/client_vr/settings/VRSettings;physicalKeyboard:Z"),
            require = 0, remap = false)
    private static boolean mmdskin$useCustomPlane(boolean original) {
        return original && !VivecraftKeyboardBridge.isActive();
    }

    @Inject(method = "setOverlayShowing(Z)Z", at = @At("RETURN"), require = 0, remap = false)
    private static void mmdskin$keyboardLifecycle(boolean showing, CallbackInfoReturnable<Boolean> cir) {
        VivecraftKeyboardBridge.overlayChanged();
    }

    @Inject(method = "orientOverlay(Z)V", at = @At("HEAD"), cancellable = true, require = 0, remap = false)
    private static void mmdskin$reachableKeyboard(boolean guiRelative, CallbackInfo ci) {
        if (VivecraftKeyboardBridge.orientOverlay(guiRelative)) ci.cancel();
    }

    @Inject(method = "processGui()V", at = @At("HEAD"), cancellable = true, require = 0, remap = false)
    private static void mmdskin$keyboardContacts(CallbackInfo ci) {
        if (VivecraftKeyboardBridge.processGui()) ci.cancel();
    }

    @Inject(method = "processBindings()V", at = @At("HEAD"), cancellable = true, require = 0, remap = false)
    private static void mmdskin$singleInputDispatcher(CallbackInfo ci) {
        if (VivecraftKeyboardBridge.isActive()) {
            VivecraftKeyboardBridge.discardOriginalBindings();
            ci.cancel();
        }
    }
}

