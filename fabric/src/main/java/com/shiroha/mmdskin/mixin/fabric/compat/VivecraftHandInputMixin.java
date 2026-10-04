package com.shiroha.mmdskin.mixin.fabric.compat;

import com.shiroha.mmdskin.compat.vr.hand.SteamVrHandManifest;
import com.shiroha.mmdskin.compat.vr.hand.SteamVrHandProvider;
import com.shiroha.mmdskin.compat.vr.hand.SteamVrFingerTracking;
import com.shiroha.mmdskin.compat.vr.SteamVrControllerInput;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Pseudo;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.ModifyArg;
import org.spongepowered.asm.mixin.injection.ModifyArgs;
import org.spongepowered.asm.mixin.injection.invoke.arg.Args;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/** Adds optional skeletal input without changing Vivecraft's action polling or user bindings. */
@Pseudo
@Mixin(targets = "org.vivecraft.client_vr.provider.openvr_lwjgl.MCOpenVR", remap = false)
public abstract class VivecraftHandInputMixin {
    @ModifyArg(method = "loadActionManifest()V", at = @At(value = "INVOKE",
            target = "Lorg/lwjgl/openvr/VRInput;VRInput_SetActionManifestPath(Ljava/lang/CharSequence;)I",
            remap = false), index = 0, remap = false, require = 0)
    private CharSequence mmdskin$addOptionalSkeletalActions(CharSequence original) {
        return SteamVrHandManifest.prepareManifest(original);
    }

    @ModifyArgs(method = "updatePose()V", at = @At(value = "INVOKE",
            target = "Lorg/lwjgl/openvr/VRInput;VRInput_UpdateActionState(Lorg/lwjgl/openvr/VRActiveActionSet$Buffer;I)I",
            remap = false), remap = false, require = 0)
    private void mmdskin$includeControllerDiagnostics(Args args) {
        args.set(0, com.shiroha.mmdskin.compat.vr.SteamVrMenuInput.appendActionSet(
                com.shiroha.mmdskin.compat.vr.SteamVrUiInput.appendActionSet(SteamVrControllerInput.appendActionSet(args.get(0)))));
    }

    @Inject(method = "updatePose()V", at = @At("RETURN"), remap = false, require = 0)
    private void mmdskin$readSkeletalHands(CallbackInfo ci) {
        com.shiroha.mmdskin.compat.vr.SteamVrUiInput.poll();
        com.shiroha.mmdskin.compat.vr.SteamVrMenuInput.poll();
        SteamVrHandProvider.poll(this);
        SteamVrFingerTracking.poll(this);
    }

    @Inject(method = "destroy()V", at = @At("HEAD"), remap = false, require = 0)
    private void mmdskin$releaseSkeletalHands(CallbackInfo ci) {
        SteamVrHandProvider.reset();
        SteamVrFingerTracking.reset();
        SteamVrControllerInput.reset();
        com.shiroha.mmdskin.compat.vr.SteamVrUiInput.reset();
        com.shiroha.mmdskin.compat.vr.SteamVrMenuInput.reset();
        com.shiroha.mmdskin.ui.spatial.SpatialMenuVrBridge.reset();
    }
}
