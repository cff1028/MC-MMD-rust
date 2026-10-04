package com.shiroha.mmdskin.mixin.neoforge.compat;

import com.shiroha.mmdskin.compat.vr.VivecraftRadialPages;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Pseudo;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Coerce;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

@Pseudo
@Mixin(targets = "org.vivecraft.client_vr.gameplay.screenhandlers.RadialHandler", remap = false)
public abstract class VivecraftRadialHandlerMixin {
    @Inject(method = "processBindings()V", at = @At(value = "FIELD",
            target = "Lorg/vivecraft/client_vr/settings/VRSettings;radialModeHold:Z"), cancellable = true, remap = false)
    private static void mmdskin$waitAfterNavigation(CallbackInfo ci) {
        if (com.shiroha.mmdskin.compat.vr.VivecraftUiInteraction.processRadialButtons() || VivecraftRadialPages.waitForNextHoldPress()) {
            ci.cancel();
        }
    }

    @Inject(method = "processBindings()V",
            at = @At(value = "INVOKE",
                    target = "Lorg/vivecraft/client_vr/gameplay/screenhandlers/RadialHandler;setOverlayShowing(ZLorg/vivecraft/client_vr/provider/ControllerType;)Z"),
            cancellable = true, remap = false, require = 0)
    private static void mmdskin$keepPageVisible(CallbackInfo ci) {
        if (VivecraftRadialPages.keepOpenAfterNavigation()) {
            ci.cancel();
        }
    }

    @Inject(method = "setOverlayShowing(ZLorg/vivecraft/client_vr/provider/ControllerType;)Z",
            at = @At("HEAD"), cancellable = true, remap = false, require = 0)
    private static void mmdskin$resumePageSelection(boolean showing, @Coerce Object controller,
                                                   CallbackInfoReturnable<Boolean> cir) {
        if (VivecraftRadialPages.onOverlayChange(showing, controller)) {
            cir.setReturnValue(true);
        }
    }

    @Inject(method = "processGui()V", at = @At("TAIL"), remap = false, require = 0)
    private static void mmdskin$radialFocus(CallbackInfo ci) {
        com.shiroha.mmdskin.compat.vr.VivecraftUiInteraction.radialCursorUpdated();
    }
    @Inject(method = "setOverlayShowing(ZLorg/vivecraft/client_vr/provider/ControllerType;)Z", at = @At("RETURN"), remap = false, require = 0)
    private static void mmdskin$radialTriggerSession(boolean showing, @Coerce Object controller, CallbackInfoReturnable<Boolean> cir) {
        com.shiroha.mmdskin.compat.vr.VivecraftUiInteraction.radialOverlayChanged();
    }
}
