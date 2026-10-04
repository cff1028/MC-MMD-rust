package com.shiroha.mmdskin.mixin.neoforge.compat;

import com.shiroha.mmdskin.compat.vr.VivecraftRadialPages;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Pseudo;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Pseudo
@Mixin(targets = "org.vivecraft.client_vr.gui.GuiRadial", remap = false)
public abstract class VivecraftGuiRadialMixin extends Screen {
    @Unique
    private final VivecraftRadialPages mmdskin$radialPages = new VivecraftRadialPages();

    protected VivecraftGuiRadialMixin(Component title) {
        super(title);
    }

    // GuiRadial overrides Minecraft methods, which use intermediary names in
    // packaged Fabric Vivecraft. Both aliases also keep the optional target usable in dev.
    @Inject(method = {"init()V", "method_25426()V"}, at = @At("TAIL"), remap = false, require = 0)
    private void mmdskin$addRadialPages(CallbackInfo ci) {
        mmdskin$radialPages.decorate(this, this::addRenderableWidget, this::clearWidgets, this::init);
    }

    @Inject(method = {"render", "method_25394"}, at = @At("TAIL"), remap = false, require = 0)
    private void mmdskin$renderRadialPageLabel(GuiGraphics graphics, int mouseX, int mouseY,
                                              float partialTick, CallbackInfo ci) {
        mmdskin$radialPages.renderLabels(this, graphics);
    }
}
