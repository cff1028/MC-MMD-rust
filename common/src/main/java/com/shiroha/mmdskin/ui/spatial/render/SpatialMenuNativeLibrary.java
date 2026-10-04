package com.shiroha.mmdskin.ui.spatial.render;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.FileAlreadyExistsException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.Locale;

/**
 * Supplies only NanoVG's JNI library, without changing LWJGL's global search path.
 * LibNanoVG's initializer mixin passes this absolute path to its original loader,
 * retaining the native library's correct defining classloader.
 */
public final class SpatialMenuNativeLibrary {
    private static String extracted;
    private SpatialMenuNativeLibrary() {}

    public static synchronized String absolutePath() {
        if (extracted != null) return extracted;
        String platform = platform();
        String filename = platform.equals("windows") ? "lwjgl_nanovg.dll" :
            platform.equals("macos") ? "liblwjgl_nanovg.dylib" : "liblwjgl_nanovg.so";
        String relative = platform + "/" + architecture() + "/org/lwjgl/nanovg/" + filename;
        String resource = "/assets/mmdskin/lumen/natives/" + relative;
        try {
            byte[] bytes;
            InputStream found = SpatialMenuNativeLibrary.class.getResourceAsStream(resource);
            // The normal LWJGL resource path also supports standalone tests and plain classpaths.
            if (found == null) found = SpatialMenuNativeLibrary.class.getResourceAsStream("/" + relative);
            if (found == null) throw new IOException("Missing packaged NanoVG native: " + resource);
            try (InputStream input = found) { bytes = input.readAllBytes(); }
            String digest = digest(bytes);
            Path directory = Path.of(System.getProperty("java.io.tmpdir"), "mmdskin-nanovg", "3.3.3", digest);
            Files.createDirectories(directory);
            Path library = directory.resolve(filename);
            if (!Files.isRegularFile(library) || !digest(Files.readAllBytes(library)).equals(digest)) {
                Path temporary = Files.createTempFile(directory, filename, ".part");
                try {
                    Files.write(temporary, bytes);
                    try { Files.move(temporary, library, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING); }
                    catch (AtomicMoveNotSupportedException unsupported) {
                        Files.move(temporary, library, StandardCopyOption.REPLACE_EXISTING);
                    } catch (FileAlreadyExistsException raced) {
                        if (!digest(Files.readAllBytes(library)).equals(digest)) throw raced;
                    }
                } finally { Files.deleteIfExists(temporary); }
            }
            extracted = library.toAbsolutePath().toString();
            return extracted;
        } catch (IOException failure) {
            throw new IllegalStateException("Cannot prepare NanoVG for the spatial menu", failure);
        }
    }

    private static String platform() {
        String os = System.getProperty("os.name", "").toLowerCase(Locale.ROOT);
        if (os.contains("mac") || os.contains("darwin")) return "macos";
        if (os.contains("win")) return "windows";
        if (os.contains("linux") || os.contains("freebsd")) return "linux";
        throw new IllegalStateException("Unsupported NanoVG operating system: " + os);
    }

    private static String architecture() {
        String arch = System.getProperty("os.arch", "").toLowerCase(Locale.ROOT);
        if (arch.equals("aarch64") || arch.equals("arm64")) return "arm64";
        if (arch.startsWith("arm")) return "arm32";
        if (arch.equals("amd64") || arch.equals("x86_64") || arch.equals("x64")) return "x64";
        if (arch.equals("x86") || arch.matches("i[3-6]86")) return "x86";
        throw new IllegalStateException("Unsupported NanoVG architecture: " + arch);
    }

    private static String digest(byte[] bytes) {
        try { return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes)); }
        catch (NoSuchAlgorithmException impossible) { throw new AssertionError(impossible); }
    }
}
