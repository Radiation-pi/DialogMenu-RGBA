package dev.dialogmenu;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HashMap;
import java.util.HexFormat;
import java.util.Map;

/** 记录实际解析的输入字节，提交前拒绝已变化的源资源。 */
final class SourceFiles {
    private final Map<Path, String> consumed = new HashMap<>();

    byte[] read(Path path) throws IOException {
        byte[] bytes = Files.readAllBytes(path);
        String hash = digest(bytes);
        String previous = consumed.putIfAbsent(path, hash);
        if (previous != null && !previous.equals(hash)) throw new IOException("构建期间源资源发生变化，请重试");
        return bytes;
    }

    void verify() throws IOException {
        for (var entry : consumed.entrySet())
            if (!entry.getValue().equals(digest(Files.readAllBytes(entry.getKey()))))
                throw new IOException("构建期间源资源发生变化，请重试");
    }

    static String digest(byte[] bytes) {
        try { return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes)); }
        catch (NoSuchAlgorithmException error) { throw new IllegalStateException(error); }
    }
}
