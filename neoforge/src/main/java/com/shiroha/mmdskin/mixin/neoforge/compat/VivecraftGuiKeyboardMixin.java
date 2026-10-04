package com.shiroha.mmdskin.mixin.neoforge.compat;

import com.shiroha.mmdskin.compat.vr.keyboard.VivecraftKeyboardBridge;
import net.minecraft.client.gui.GuiGraphics;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Pseudo;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Pseudo
@Mixin(targets = "org.vivecraft.client_vr.gui.GuiKeyboard", remap = false)
public abstract class VivecraftGuiKeyboardMixin {
    @Inject(method = "init()V", at = @At("HEAD"), cancellable = true, require = 0, remap = false)
    private void mmdskin$customKeyboardLayout(CallbackInfo ci) {
        if (VivecraftKeyboardBridge.isActive()) ci.cancel();
    }

    @Inject(method = "render(Lnet/minecraft/client/gui/GuiGraphics;IIF)V", at = @At("HEAD"),
            cancellable = true, require = 0, remap = false)
    private void mmdskin$customKeyboardRender(GuiGraphics graphics, int mouseX, int mouseY, float partialTick, CallbackInfo ci) {
        if (VivecraftKeyboardBridge.render(this, graphics)) ci.cancel();
    }
}

