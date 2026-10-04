package com.shiroha.mmdskin.mixin.fabric;

import com.llamalad7.mixinextras.injector.wrapmethod.WrapMethod;
import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.shiroha.mmdskin.renderer.integration.player.InventoryEntityRenderScope;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.screens.inventory.InventoryScreen;
import net.minecraft.world.entity.LivingEntity;
import org.joml.Quaternionf;
import org.joml.Vector3f;
import org.spongepowered.asm.mixin.Mixin;

@Mixin(InventoryScreen.class)
public abstract class InventoryScreenMixin {
    @WrapMethod(method = "renderEntityInInventory")
    private static void mmdskin$entityPreview(GuiGraphics graphics, float x, float y, float scale,
            Vector3f translation, Quaternionf rotation, Quaternionf cameraRotation,
            LivingEntity entity, Operation<Void> original) {
        try (var ignored = InventoryEntityRenderScope.enter(entity)) {
            original.call(graphics, x, y, scale, translation, rotation, cameraRotation, entity);
        }
    }
}
