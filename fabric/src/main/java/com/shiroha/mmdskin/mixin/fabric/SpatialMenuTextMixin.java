package com.shiroha.mmdskin.mixin.fabric;

import com.shiroha.mmdskin.ui.spatial.render.SpatialMenuFontScope;
import com.shiroha.mmdskin.ui.spatial.render.SpatialMenuNativeFont;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.network.chat.Style;
import net.minecraft.util.FormattedCharSequence;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

@Mixin(GuiGraphics.class)
public abstract class SpatialMenuTextMixin {
    @Inject(method = "drawString(Lnet/minecraft/client/gui/Font;Ljava/lang/String;IIIZ)I", at = @At("HEAD"), cancellable = true)
    private void mmdskin$clearText(Font font, String text, int x, int y, int color, boolean shadow, CallbackInfoReturnable<Integer> cir) {
        if (SpatialMenuFontScope.active()) cir.setReturnValue(SpatialMenuNativeFont.draw((GuiGraphics)(Object)this, font,
                text == null ? FormattedCharSequence.EMPTY : sink -> net.minecraft.util.StringDecomposer.iterateFormatted(text, Style.EMPTY, sink), x, y, color, shadow));
    }
    @Inject(method = "drawString(Lnet/minecraft/client/gui/Font;Lnet/minecraft/util/FormattedCharSequence;IIIZ)I", at = @At("HEAD"), cancellable = true)
    private void mmdskin$clearStyledText(Font font, FormattedCharSequence text, int x, int y, int color, boolean shadow, CallbackInfoReturnable<Integer> cir) {
        if (SpatialMenuFontScope.active()) cir.setReturnValue(SpatialMenuNativeFont.draw((GuiGraphics)(Object)this, font, text, x, y, color, shadow));
    }
}
