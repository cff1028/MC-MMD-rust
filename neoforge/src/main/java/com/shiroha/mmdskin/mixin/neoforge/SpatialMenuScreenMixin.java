package com.shiroha.mmdskin.mixin.neoforge;

import com.llamalad7.mixinextras.injector.wrapmethod.WrapMethod;
import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.shiroha.mmdskin.ui.spatial.SpatialMenuHost;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.screens.Screen;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/** Native specialist editors opened by Lumen retain their controls but use the same clear system font. */
@Mixin(Screen.class)
public abstract class SpatialMenuScreenMixin {
    @Inject(method = "onClose", at = @At("HEAD"), cancellable = true)
    private void mmdskin$returnToSpatialMenu(CallbackInfo ci) {
        if (SpatialMenuHost.returnFromNativeRoot((Screen) (Object) this)) ci.cancel();
    }

    @WrapMethod(method = "renderWithTooltip")
    private void mmdskin$nativeFontScope(GuiGraphics graphics, int mouseX, int mouseY, float dt, Operation<Void> original) {
        try (var scope = com.shiroha.mmdskin.ui.spatial.render.SpatialMenuFontScope.forScreen((Screen)(Object)this)) {
            original.call(graphics, mouseX, mouseY, dt);
        }
    }
}
