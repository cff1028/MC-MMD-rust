package com.shiroha.mmdskin.mixin.fabric;

import com.shiroha.mmdskin.compat.vr.pointer.VrOriginalCrosshairControl;
import net.minecraft.client.DeltaTracker;
import net.minecraft.client.gui.Gui;
import net.minecraft.client.gui.GuiGraphics;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/** Vanilla fallback; desktop rendering is unchanged when VR is not active. */
@Mixin(Gui.class)
public abstract class CrosshairMixin {
    @Inject(method = "renderCrosshair", at = @At("HEAD"), cancellable = true)
    private void mmdskin$hideOriginalCrosshair(GuiGraphics graphics, DeltaTracker delta, CallbackInfo ci) {
        if (VrOriginalCrosshairControl.shouldHide()) ci.cancel();
    }
}
