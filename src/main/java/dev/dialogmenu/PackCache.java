package dev.dialogmenu;

import java.io.IOException;
import java.lang.ref.WeakReference;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/** 快照或外部资源包使用方仍持有 ZIP 时，保留对应文件。 */
final class PackCache {
    private record Lease(WeakReference<Object> owner, Path path) {}
    private final Path directory;
    private final List<Lease> leases = new ArrayList<>();

    PackCache(Path directory) { this.directory = directory; }

    synchronized void retain(Object owner, Path path) {
        leases.add(new Lease(new WeakReference<>(owner), path));
    }

    synchronized void prune() throws IOException {
        prune(null);
    }

    synchronized void prune(Path persisted) throws IOException {
        Set<Path> retained = new HashSet<>();
        if (persisted != null) retained.add(persisted);
        leases.removeIf(lease -> lease.owner().get() == null);
        for (Lease lease : leases) retained.add(lease.path());
        if (!Files.isDirectory(directory)) return;
        try (var files = Files.list(directory)) {
            for (Path file : files.toList()) {
                // 只管理自身生成的 ZIP，不遍历目录、符号链接或无关文件。
                if (file.getFileName().toString().matches("dialog-[0-9a-f-]{36}\\.zip")
                    && Files.isRegularFile(file, LinkOption.NOFOLLOW_LINKS) && !retained.contains(file))
                    Files.delete(file);
            }
        }
    }
}
