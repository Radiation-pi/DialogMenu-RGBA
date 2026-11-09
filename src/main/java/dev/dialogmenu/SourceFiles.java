package dev.dialogmenu;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HashMap;
import java.util.HexFormat;
import java.util.Map;
import java.util.List;
import java.util.Locale;

/** 记录实际解析的输入字节，提交前拒绝已变化的源资源。 */
final class SourceFiles {
    private final Map<Path, String> consumed = new HashMap<>();
    private final Map<Path, List<Path>> imageDirectories = new HashMap<>();

    List<Path> imageDirectory(Path root) throws IOException {
        List<Path> files = imageFiles(root);
        imageDirectories.put(root, files);
        return files.stream().filter(path -> path.toString().toLowerCase(Locale.ROOT).endsWith(".png")).toList();
    }

    private static List<Path> imageFiles(Path root) throws IOException {
        try (var paths = Files.walk(root)) {
            return paths.filter(path -> {
                String name = path.getFileName().toString().toLowerCase(Locale.ROOT);
                return !Files.isDirectory(path) && (name.endsWith(".png") || name.endsWith(".png.mcmeta")
                    || path.equals(root.resolve("registry.json")));
            }).sorted().toList();
        }
    }

    byte[] read(Path path) throws IOException {
        byte[] bytes = Files.readAllBytes(path);
        String hash = digest(bytes);
        String previous = consumed.putIfAbsent(path, hash);
        if (previous != null && !previous.equals(hash)) throw new IOException("构建期间源资源发生变化，请重试");
        return bytes;
    }

    void verify() throws IOException {
        for (var entry : imageDirectories.entrySet())
            if (!entry.getValue().equals(imageFiles(entry.getKey())))
                throw new IOException("构建期间图片目录发生变化，请重试");
        for (var entry : consumed.entrySet())
            if (!entry.getValue().equals(digest(Files.readAllBytes(entry.getKey()))))
                throw new IOException("构建期间源资源发生变化，请重试");
    }

    static String digest(byte[] bytes) {
        try { return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes)); }
        catch (NoSuchAlgorithmException error) { throw new IllegalStateException(error); }
    }
}
