package com.shiroha.mmdskin.mixin.neoforge;

import com.shiroha.mmdskin.ui.spatial.render.SpatialMenuNativeLibrary;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Pseudo;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.ModifyArg;

/** Keep System.load's original NanoVG caller while resolving natives owned by this mod. */
@Pseudo
@Mixin(targets = "org.lwjgl.nanovg.LibNanoVG", remap = false)
public abstract class SpatialMenuNanoVgLibraryMixin {
    @ModifyArg(method = "<clinit>", at = @At(value = "INVOKE", target =
        "Lorg/lwjgl/system/Library;loadSystem(Ljava/util/function/Consumer;Ljava/util/function/Consumer;Ljava/lang/Class;Ljava/lang/String;Ljava/lang/String;)V"),
        index = 4, remap = false)
    private static String mmdskin$ownNativeResource(String original) {
        // Production NeoForge preserves NanoVG's explicit module descriptor. The
        // injected method belongs to that module, so establish this single read
        // edge before resolving our helper (a direct class literal is too early).
        Module nanoVgModule = org.lwjgl.nanovg.NanoVG.class.getModule();
        if (nanoVgModule.isNamed()) {
            try {
                Class<?> helper = Class.forName(
                    "com.shiroha.mmdskin.ui.spatial.render.SpatialMenuNativeLibrary", false,
                    org.lwjgl.nanovg.NanoVG.class.getClassLoader());
                nanoVgModule.addReads(helper.getModule());
            } catch (ClassNotFoundException missingHelper) {
                throw new IllegalStateException("MMD spatial UI native helper is unavailable", missingHelper);
            }
        }
        return SpatialMenuNativeLibrary.absolutePath();
    }
}
