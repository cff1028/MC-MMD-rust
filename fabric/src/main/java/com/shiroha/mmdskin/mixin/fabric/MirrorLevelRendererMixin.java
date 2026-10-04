package com.shiroha.mmdskin.mixin.fabric;

import com.mojang.blaze3d.pipeline.RenderTarget;
import com.mojang.blaze3d.pipeline.TextureTarget;
import com.shiroha.mmdskin.compat.vr.mirror.MirrorLevelRendererAccess;
import com.shiroha.mmdskin.compat.vr.mirror.VrMirrorScenePass;
import com.shiroha.mmdskin.compat.vr.mirror.VrMirrorCompileBudget;
import com.shiroha.mmdskin.compat.vr.mirror.VrMirrorTerrain;
import com.shiroha.mmdskin.compat.vr.mirror.SodiumMirrorCompat;
import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import it.unimi.dsi.fastutil.objects.ObjectArrayList;
import net.minecraft.client.Minecraft;
import net.minecraft.client.Camera;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.renderer.LevelRenderer;
import net.minecraft.client.renderer.LevelTargetBundle;
import net.minecraft.client.renderer.PostChain;
import net.minecraft.client.renderer.ViewArea;
import net.minecraft.client.renderer.chunk.SectionRenderDispatcher;
import net.minecraft.client.renderer.chunk.SectionRenderDispatcher.RenderSection;
import net.minecraft.client.renderer.culling.Frustum;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.level.lighting.LevelLightEngine;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Mutable;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import org.lwjgl.opengl.GL11;
import org.lwjgl.opengl.GL30;

import java.util.ArrayList;
import java.util.List;

@Mixin(LevelRenderer.class)
public abstract class MirrorLevelRendererMixin implements MirrorLevelRendererAccess {
    @Shadow private Frustum cullingFrustum;
    @Shadow private Frustum capturedFrustum;
    @Shadow private boolean captureFrustum;
    @Shadow @Final private ObjectArrayList<RenderSection> visibleSections;
    @Shadow @Final private ObjectArrayList<RenderSection> nearbyVisibleSections;
    @Shadow @Final private List<Entity> visibleEntities;
    @Shadow private int visibleEntityCount;
    @Shadow @Final @Mutable private LevelTargetBundle targets;
    @Shadow private RenderTarget entityOutlineTarget;
    @Unique private TextureTarget mmdskin$mirrorOutline;
    @Shadow private ViewArea viewArea;
    @Shadow private SectionRenderDispatcher sectionRenderDispatcher;
    @Unique private final VrMirrorCompileBudget mmdskin$compileBudget = new VrMirrorCompileBudget();

    @Inject(method = "setupRender", at = @At("HEAD"), cancellable = true)
    private void mmdskin$mirrorTerrain(Camera camera, Frustum frustum, boolean captured,
                                       boolean spectator, CallbackInfo ci) {
        if (!VrMirrorScenePass.isRendering()) return;
        // Sodium replaces setupRender and reads its own region lists when drawing terrain.
        // Give that renderer an isolated reflected list without running its main-camera update.
        if (SodiumMirrorCompat.prepare(camera, frustum)) {
            ci.cancel();
            return;
        }
        // setupRender would relocate the SAME RenderSections to the reflected camera,
        // cancel builds at the ring edge, and launch a second async occlusion traversal.
        // Restoring only visibleSections cannot undo any of those mutations.
        VrMirrorTerrain.collect(viewArea, camera, frustum, visibleSections, nearbyVisibleSections);
        ci.cancel();
    }

    @Inject(method = "compileSections", at = @At("HEAD"), cancellable = true)
    private void mmdskin$mirrorCompile(Camera camera, CallbackInfo ci) {
        if (!VrMirrorScenePass.isRendering()) return;
        VrMirrorTerrain.submitMissing(visibleSections, sectionRenderDispatcher, mmdskin$compileBudget,
                Minecraft.getInstance().level.getGameTime());
        ci.cancel();
    }

    @WrapOperation(method = "renderLevel", at = @At(value = "INVOKE",
            target = "Lnet/minecraft/client/multiplayer/ClientLevel;pollLightUpdates()V"))
    private void mmdskin$mirrorLightQueue(ClientLevel level, Operation<Void> original) {
        if (!VrMirrorScenePass.isRendering()) original.call(level);
    }

    @WrapOperation(method = "renderLevel", at = @At(value = "INVOKE",
            target = "Lnet/minecraft/world/level/lighting/LevelLightEngine;runLightUpdates()I"))
    private int mmdskin$mirrorLightUpdates(LevelLightEngine engine, Operation<Integer> original) {
        return VrMirrorScenePass.isRendering() ? 0 : original.call(engine);
    }

    @Override public Runnable mmdskin$saveMirrorState(RenderTarget target) {
        // Clearing the desktop outline would switch the viewport before entities draw.
        if (mmdskin$mirrorOutline == null || mmdskin$mirrorOutline.width != target.width
                || mmdskin$mirrorOutline.height != target.height) {
            mmdskin$releaseMirrorTargets();
            mmdskin$mirrorOutline = new TextureTarget(target.width, target.height, true);
            mmdskin$mirrorOutline.setClearColor(0, 0, 0, 0);
        }
        Frustum oldFrustum = cullingFrustum;
        Frustum oldCaptured = capturedFrustum;
        boolean oldCapture = captureFrustum;
        var oldVisible = new ObjectArrayList<>(visibleSections);
        var oldNearby = new ObjectArrayList<>(nearbyVisibleSections);
        var oldEntities = new ArrayList<>(visibleEntities);
        int oldEntityCount = visibleEntityCount;
        LevelTargetBundle oldTargets = targets;
        RenderTarget oldOutline = entityOutlineTarget;
        // Keep every target, including fields added by Vivecraft, scoped to this pass.
        targets = new LevelTargetBundle();
        entityOutlineTarget = mmdskin$mirrorOutline;
        visibleEntities.clear();
        visibleEntityCount = 0;
        capturedFrustum = null;
        captureFrustum = false;
        return () -> {
            cullingFrustum = oldFrustum;
            capturedFrustum = oldCaptured;
            captureFrustum = oldCapture;
            visibleSections.clear();
            visibleSections.addAll(oldVisible);
            nearbyVisibleSections.clear();
            nearbyVisibleSections.addAll(oldNearby);
            visibleEntities.clear();
            visibleEntities.addAll(oldEntities);
            visibleEntityCount = oldEntityCount;
            targets = oldTargets;
            entityOutlineTarget = oldOutline;
            // The eye's section-ring position, camera history and occlusion graph
            // were never changed. Do not invalidate/rebuild them on every mirror pass.
        };
    }

    @Override public void mmdskin$releaseMirrorTargets() {
        if (mmdskin$mirrorOutline == null) return;
        int draw = GL11.glGetInteger(GL30.GL_DRAW_FRAMEBUFFER_BINDING);
        int read = GL11.glGetInteger(GL30.GL_READ_FRAMEBUFFER_BINDING);
        int deleted = mmdskin$mirrorOutline.frameBufferId;
        try {
            mmdskin$mirrorOutline.destroyBuffers();
        } finally {
            mmdskin$mirrorOutline = null;
            GL30.glBindFramebuffer(GL30.GL_DRAW_FRAMEBUFFER, draw == deleted ? 0 : draw);
            GL30.glBindFramebuffer(GL30.GL_READ_FRAMEBUFFER, read == deleted ? 0 : read);
        }
    }

    @Inject(method = "getTransparencyChain", at = @At("HEAD"), cancellable = true)
    private void mmdskin$mirrorTransparency(CallbackInfoReturnable<PostChain> cir) {
        if (VrMirrorScenePass.isRendering() && Minecraft.useShaderTransparency()) {
            // The vanilla mirror pass does not draw Vivecraft's three extra layers.
            cir.setReturnValue(Minecraft.getInstance().getShaderManager().getPostChain(
                    ResourceLocation.withDefaultNamespace("transparency"), LevelTargetBundle.SORTING_TARGETS));
        }
    }
}
