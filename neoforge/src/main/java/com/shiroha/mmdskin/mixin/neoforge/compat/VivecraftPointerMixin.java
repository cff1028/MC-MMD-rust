package com.shiroha.mmdskin.mixin.neoforge.compat;

import com.shiroha.mmdskin.compat.vr.pointer.VrPointerRenderer;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Pseudo;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/** Render in Vivecraft's GUI target after it has resolved the actual UI planes. */
@Pseudo
@Mixin(targets = "org.vivecraft.client_vr.render.helpers.VREffectsHelper", remap = false)
public abstract class VivecraftPointerMixin {
    // Both paths still bind the world target here. Their GUI/keyboard surfaces are
    // drawn later (often before LevelRenderer returns), so a TAIL mirror is too late.
    @Inject(method = {"renderVrFast(FZ)V", "renderVRFabulous(FLnet/minecraft/client/renderer/LevelTargetBundle;)V"},
        at = @At(value = "INVOKE", target = "Lnet/minecraft/client/renderer/MultiBufferSource$BufferSource;endBatch()V",
            shift = At.Shift.AFTER, ordinal = 0, remap = true), remap = false)
    private static void mmdskin$mirrorBeforeGui(CallbackInfo ci) {
        com.shiroha.mmdskin.compat.vr.mirror.VrMirrorRenderer.renderBeforeUi();
    }

    // The second SHOWING read starts the keyboard pass, after the base GUI and shadow.
    @Inject(method = "renderGuiAndShadow(FZZ)V",
        at = @At(value = "FIELD", target = "Lorg/vivecraft/client_vr/gameplay/screenhandlers/KeyboardHandler;SHOWING:Z", ordinal = 1), remap = false)
    private static void mmdskin$menuBeforeKeyboard(float partialTick, boolean depthAlways, boolean shadowFirst, CallbackInfo ci) {
        com.shiroha.mmdskin.ui.spatial.SpatialMenuHost.render();
    }

    @Inject(method = "renderGuiAndShadow(FZZ)V", at = @At("TAIL"), remap = false)
    private static void mmdskin$pointerAfterGui(float partialTick, boolean depthAlways, boolean shadowFirst, CallbackInfo ci) {
        com.shiroha.mmdskin.compat.vr.VrFirstPersonUiLayer.render(partialTick);
        VrPointerRenderer.render();
    }

    @Inject(method = "renderMenuRoom(F)V",
        at = @At(value = "FIELD", target = "Lorg/vivecraft/client_vr/gameplay/screenhandlers/KeyboardHandler;SHOWING:Z", ordinal = 0), remap = false)
    private static void mmdskin$menuRoomBeforeKeyboard(float partialTick, CallbackInfo ci) {
        com.shiroha.mmdskin.ui.spatial.SpatialMenuHost.render();
    }

    @Inject(method = "renderMenuRoom(F)V", at = @At("TAIL"), remap = false)
    private static void mmdskin$pointerInMenuRoom(float partialTick, CallbackInfo ci) {
        VrPointerRenderer.render();
    }
}
