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

/** Keep ZIPs while snapshots or external pack consumers still own them. */
final class PackCache {
    private record Lease(WeakReference<Object> owner, Path path) {}
    private final Path directory;
    private final List<Lease> leases = new ArrayList<>();

    PackCache(Path directory) { this.directory = directory; }

    synchronized void retain(Object owner, Path path) {
        leases.add(new Lease(new WeakReference<>(owner), path));
    }

    synchronized void prune() throws IOException {
        Set<Path> retained = new HashSet<>();
        leases.removeIf(lease -> lease.owner().get() == null);
        for (Lease lease : leases) retained.add(lease.path());
        if (!Files.isDirectory(directory)) return;
        try (var files = Files.list(directory)) {
            for (Path file : files.toList()) {
                // Own generated ZIPs only; never traverse folders, symlinks or unrelated files.
                if (file.getFileName().toString().matches("dialog-[0-9a-f-]{36}\\.zip")
                    && Files.isRegularFile(file, LinkOption.NOFOLLOW_LINKS) && !retained.contains(file))
                    Files.delete(file);
            }
        }
    }
}
