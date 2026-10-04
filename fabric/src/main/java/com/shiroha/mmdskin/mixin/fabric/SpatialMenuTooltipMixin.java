package com.shiroha.mmdskin.mixin.fabric;

import com.shiroha.mmdskin.ui.spatial.render.SpatialMenuFontScope;
import com.shiroha.mmdskin.ui.spatial.render.SpatialMenuNativeFont;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.screens.inventory.tooltip.ClientTextTooltip;
import net.minecraft.client.renderer.MultiBufferSource;
import net.minecraft.util.FormattedCharSequence;
import org.joml.Matrix4f;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/** Native tooltips call Font.drawInBatch directly instead of GuiGraphics.drawString. */
@Mixin(ClientTextTooltip.class)
public abstract class SpatialMenuTooltipMixin {
    @Shadow @Final private FormattedCharSequence text;

    @Inject(method = "renderText", at = @At("HEAD"), cancellable = true)
    private void mmdskin$clearTooltip(Font font, int x, int y, Matrix4f pose,
            MultiBufferSource.BufferSource buffers, CallbackInfo ci) {
        if (!SpatialMenuFontScope.active()) return;
        SpatialMenuNativeFont.draw(font, text, x, y, -1, true, pose, buffers);
        ci.cancel();
    }
}
