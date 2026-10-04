package com.shiroha.mmdskin.ui.spatial.backend;

import net.minecraft.world.level.storage.LevelResource;
import net.minecraft.world.level.storage.LevelStorageSource;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

/** Uses Minecraft's directory lock and deletion routine, only for a direct save under this instance. */
final class WorldDeletion {
    private WorldDeletion() {}

    static Path verifiedTarget(Path saves, String id) throws IOException {
        if (id == null || id.isBlank() || id.equals(".") || id.equals("..")
                || id.contains("/") || id.contains("\\") || id.contains(":"))
            throw new IOException("无效的存档目录");
        Path base = saves.toRealPath(), target = base.resolve(id).normalize();
        if (!base.equals(target.getParent()) || !Files.isDirectory(target)
                || !target.toRealPath().equals(target))
            throw new IOException("存档路径已改变，或不是当前实例中的独立目录");
        return target;
    }

    static void delete(LevelStorageSource source, String id) throws IOException, net.minecraft.world.level.validation.ContentValidationException {
        Path target = verifiedTarget(source.getBaseDir(), id);
        try (var access = source.validateAndCreateAccess(id)) {
            if (!target.equals(access.getLevelPath(LevelResource.ROOT).toRealPath()))
                throw new IOException("存档路径已改变，请刷新后重试");
            access.deleteLevel();
        }
    }
}
