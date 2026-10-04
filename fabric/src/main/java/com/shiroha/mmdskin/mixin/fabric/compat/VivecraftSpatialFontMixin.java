package com.shiroha.mmdskin.mixin.fabric.compat;

import com.llamalad7.mixinextras.injector.wrapmethod.WrapMethod;
import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.shiroha.mmdskin.ui.spatial.SpatialMenuHost;
import net.minecraft.client.DeltaTracker;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.screens.Screen;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Pseudo;

/** Vivecraft's overlay path invokes Screen.render directly, bypassing renderWithTooltip. */
@Pseudo
@Mixin(targets = "org.vivecraft.client_vr.render.helpers.RenderHelper", remap = false)
public abstract class VivecraftSpatialFontMixin {
    @WrapMethod(method = "drawScreen", remap = false)
    private static void mmdskin$nativeOverlayFontScope(GuiGraphics graphics, DeltaTracker tracker,
            Screen screen, boolean useMaximumScale, Operation<Void> original) {
        try (var scope = com.shiroha.mmdskin.ui.spatial.render.SpatialMenuFontScope.forScreen(screen)) {
            original.call(graphics, tracker, screen, useMaximumScale);
        }
    }
}
