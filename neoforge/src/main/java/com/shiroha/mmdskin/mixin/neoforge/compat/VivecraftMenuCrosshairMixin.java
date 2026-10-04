package com.shiroha.mmdskin.mixin.neoforge.compat;

import com.shiroha.mmdskin.compat.vr.pointer.VrOriginalCrosshairControl;
import net.minecraft.client.gui.GuiGraphics;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Pseudo;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/** Covers the regular screen mouse cursor and both TwoHandedScreen cursors. */
@Pseudo
@Mixin(targets = "org.vivecraft.client_vr.render.helpers.RenderHelper", remap = false)
public abstract class VivecraftMenuCrosshairMixin {
    // Match the unique Vivecraft method name; its GuiGraphics descriptor is
    // remapped differently in Fabric production and must not be hard-coded.
    @Inject(method = "drawMouseMenuQuad", at = @At("HEAD"), cancellable = true, require = 0, remap = false)
    private static void mmdskin$hideOriginalMenuCrosshair(GuiGraphics graphics, int mouseX, int mouseY, CallbackInfo ci) {
        // The spatial menu has its own physical panel and pointer; its surrogate Screen is blank.
        if (com.shiroha.mmdskin.ui.spatial.SpatialMenuHost.isOpen() || VrOriginalCrosshairControl.shouldHide()) ci.cancel();
    }
}
