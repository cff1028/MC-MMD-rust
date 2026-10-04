package com.shiroha.mmdskin.ui.spatial.backend;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import static org.junit.jupiter.api.Assertions.*;

class WorldDeletionTest {
    @TempDir Path temporary;

    @Test void acceptsOnlyAnExistingDirectWorldDirectory() throws Exception {
        Path saves=Files.createDirectory(temporary.resolve("saves"));
        Path world=Files.createDirectory(saves.resolve("测试世界"));
        assertEquals(world.toRealPath(),WorldDeletion.verifiedTarget(saves,"测试世界"));
    }

    @Test void rejectsTraversalRootAndAbsoluteTargets() throws Exception {
        Path saves=Files.createDirectory(temporary.resolve("saves"));
        for(String id:new String[]{"", " ", ".", "..", "../other", "..\\other", "/", "C:\\", "world/child", "world\\child", "C:other"})
            assertThrows(IOException.class,()->WorldDeletion.verifiedTarget(saves,id),id);
        assertThrows(IOException.class,()->WorldDeletion.verifiedTarget(saves,null));
        assertTrue(Files.isDirectory(saves));
    }

    @Test void rejectsMissingDirectoriesAndFilesWithoutCreatingThem() throws Exception {
        Path saves=Files.createDirectory(temporary.resolve("saves"));
        Path file=Files.writeString(saves.resolve("not-a-world"),"preserve");
        assertThrows(IOException.class,()->WorldDeletion.verifiedTarget(saves,"missing"));
        assertThrows(IOException.class,()->WorldDeletion.verifiedTarget(saves,"not-a-world"));
        assertFalse(Files.exists(saves.resolve("missing")));
        assertEquals("preserve",Files.readString(file));
    }
}
