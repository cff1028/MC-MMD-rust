package com.shiroha.mmdskin.ui.spatial.backend;

import com.mojang.blaze3d.platform.NativeImage;
import com.shiroha.mmdskin.ui.spatial.lumen.model.MenuBackend.ImageData;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ServerData;
import net.minecraft.client.renderer.texture.DynamicTexture;
import net.minecraft.client.resources.DefaultPlayerSkin;
import net.minecraft.client.resources.PlayerSkin;
import net.minecraft.world.level.storage.LevelSummary;
import java.io.ByteArrayInputStream;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.file.*;
import java.util.*;
import java.util.concurrent.CompletableFuture;

/** Decodes world/server icons asynchronously; all rendering reads are in-memory and never issue GL calls. */
final class MenuImageCache implements AutoCloseable {
    private static final int MAX_BYTES = 4 * 1024 * 1024;
    private static final int MAX_EDGE = 2048;
    private static final int MAX_CACHED_ICONS = 128;
    private final Map<String, Source> sources = new HashMap<>();
    private final LinkedHashMap<String, Request> worlds = new LinkedHashMap<>(16, .75f, true);
    private long sourceGeneration;
    private volatile boolean closed;
    private volatile ImageData accountFace;
    private volatile int accountGeneration;
    private int facePriority;
    private UUID accountId;
    private final Minecraft mc = Minecraft.getInstance();
    private record Decoded(String stamp, ImageData image) {}
    private record Source(String key, Path file, byte[] encoded, long generation) {}
    private record Request(String source, long generation, CompletableFuture<Decoded> future, ImageData previous) {}

    void refreshWorlds(List<LevelSummary> local, List<ServerData> servers) {
        refreshWorlds(local, servers, "", null);
    }
    void refreshWorlds(List<LevelSummary> local, List<ServerData> servers, String activeId, Path activeIcon) {
        long generation = ++sourceGeneration;
        Set<String> live = new HashSet<>();
        for (LevelSummary world : local) {
            String id = "local:" + world.getLevelId(); live.add(id);
            Path icon = world.getIcon();
            if (icon != null) sources.put(id, new Source(icon.toAbsolutePath().normalize().toString(), icon, null, generation));
            else sources.remove(id);
        }
        for (int i = 0; i < servers.size(); i++) {
            String id = "server:" + i; live.add(id);
            byte[] icon = servers.get(i).getIconBytes();
            if (icon == null || icon.length > MAX_BYTES) { sources.remove(id); continue; }
            String source = "server:" + servers.get(i).ip + ":" + MenuImagePixels.contentKey(icon);
            sources.put(id, new Source(source, null, icon, generation));
        }
        if (activeId != null && !activeId.isBlank() && activeIcon != null) {
            live.add(activeId);
            sources.put(activeId, new Source(activeIcon.toAbsolutePath().normalize().toString(), activeIcon, null, generation));
        }
        sources.keySet().removeIf(id -> !live.contains(id));
        worlds.keySet().removeIf(id -> !live.contains(id));
    }
    private Request request(String id, Source source, Request previous) {
        Decoded prior = previous != null && previous.source().equals(source.key()) ? completed(previous) : null;
        CompletableFuture<Decoded> job = CompletableFuture.supplyAsync(() -> {
            try {
                if (source.encoded() != null) {
                    if (prior != null && prior.stamp().equals(source.key())) return prior;
                    return decode(source.key(), source.encoded(), false);
                }
                Path path = source.file();
                if (Files.isSymbolicLink(path) && !mc.getLevelSource().getWorldDirValidator().validateSymlink(path).isEmpty())
                    return new Decoded("unapproved-link", null);
                if (!Files.isRegularFile(path)) return new Decoded("missing", null);
                long size = Files.size(path);
                if (size <= 0 || size > MAX_BYTES) return new Decoded("oversized", null);
                String stamp = size + ":" + Files.getLastModifiedTime(path);
                if (prior != null && prior.stamp().equals(stamp)) return prior;
                try (var input = Files.newInputStream(path)) { return decode(stamp, input.readNBytes(MAX_BYTES + 1), false); }
            } catch (Exception failure) { return new Decoded("unreadable", null); }
        });
        var request = new Request(source.key(), source.generation(), job, prior == null ? null : prior.image());
        worlds.put(id, request);
        while (worlds.size() > MAX_CACHED_ICONS) {
            var oldest = worlds.pollFirstEntry(); oldest.getValue().future().cancel(false);
        }
        return request;
    }
    private static Decoded completed(Request request) {
        try { return request.future().getNow(null); }
        catch (RuntimeException failure) { return new Decoded("unreadable", null); }
    }
    ImageData world(String id) {
        Source source = sources.get(id);
        if (closed || source == null) return null;
        var request = worlds.get(id);
        if (request == null || !request.source().equals(source.key())
                || request.generation() != source.generation() && request.future().isDone())
            request = request(id, source, request);
        var decoded = completed(request);
        return decoded == null ? request.previous() : decoded.image();
    }
    ImageData accountFace() { return accountFace; }

    /** Uses the same profile/skin provider as Minecraft; never fetches skin URLs or handles account credentials itself. */
    void refreshAccount() {
        int generation = ++accountGeneration;
        var profile = mc.getGameProfile();
        if (!Objects.equals(accountId, profile.getId())) { accountId = profile.getId(); accountFace = null; }
        facePriority = 0;
        requestFace(mc.player == null ? DefaultPlayerSkin.get(profile) : mc.player.getSkin(), generation, false);
        mc.getSkinManager().getOrLoad(profile).thenAcceptAsync(skin -> {
            if (!closed && generation == accountGeneration) skin.ifPresent(value -> requestFace(value, generation, true));
        }, mc).exceptionally(failure -> null);
    }
    private void requestFace(PlayerSkin skin, int generation, boolean downloaded) {
        // Also support offline skin providers that register a DynamicTexture without a download URL.
        // Both this refresh and skin completion run on Minecraft's executor, outside NanoVG's frame.
        try {
            var texture = mc.getTextureManager().getTexture(skin.texture());
            if (texture instanceof DynamicTexture dynamic && dynamic.getPixels() != null) {
                var pixels = dynamic.getPixels();
                ImageData composed = MenuImagePixels.face(pixels.getWidth(), pixels.getHeight(), pixels::getPixel);
                publishFace(composed, generation, downloaded ? 2 : 3);
                return;
            }
        } catch (RuntimeException ignored) { /* Resource-backed default skins use the asynchronous path below. */ }
        CompletableFuture.supplyAsync(() -> {
            try (var input = mc.getResourceManager().open(skin.texture())) {
                byte[] bytes = input.readNBytes(MAX_BYTES + 1);
                return decode(skin.texture().toString(), bytes, true).image();
            } catch (Exception failure) { return null; }
        }).thenAccept(image -> {
            publishFace(image, generation, downloaded ? 2 : 1);
        });
    }
    private synchronized void publishFace(ImageData image, int generation, int priority) {
        if (image != null && !closed && generation == accountGeneration && priority >= facePriority) {
            facePriority = priority; accountFace = image;
        }
    }
    private static Decoded decode(String stamp, byte[] bytes, boolean face) {
        if (!validPng(bytes)) return new Decoded(stamp, null);
        // Native world/server icons are 64x64; reject malformed large covers before allocating pixels.
        var header = ByteBuffer.wrap(bytes).order(ByteOrder.BIG_ENDIAN);
        if (!face && (header.getInt(16) != 64 || header.getInt(20) != 64)) return new Decoded(stamp, null);
        try (NativeImage pixels = NativeImage.read(new ByteArrayInputStream(bytes))) {
            ImageData image = face ? MenuImagePixels.face(pixels.getWidth(), pixels.getHeight(), pixels::getPixel)
                    : MenuImagePixels.image(pixels.getWidth(), pixels.getHeight(), pixels::getPixel);
            return new Decoded(stamp, image);
        } catch (Exception | LinkageError failure) { return new Decoded(stamp, null); }
    }
    private static boolean validPng(byte[] bytes) {
        if (bytes.length < 24 || bytes.length > MAX_BYTES) return false;
        var header = ByteBuffer.wrap(bytes).order(ByteOrder.BIG_ENDIAN);
        if (header.getLong(0) != 0x89504E470D0A1A0AL || header.getInt(12) != 0x49484452) return false;
        int width = header.getInt(16), height = header.getInt(20);
        return width > 0 && height > 0 && width <= MAX_EDGE && height <= MAX_EDGE;
    }
    @Override public void close() {
        closed = true; accountGeneration++;
        for (var request : worlds.values()) request.future().cancel(false);
        worlds.clear(); sources.clear(); accountFace = null;
    }
}
