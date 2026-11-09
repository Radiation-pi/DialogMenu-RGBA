package dev.dialogmenu;

import java.io.IOException;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;

/**
 * Optional editor data service exposed by DialogMenu.
 *
 * <p>The service never opens a socket and never serves web assets. The
 * companion DialogMenuEditor plugin owns that optional surface.</p>
 */
public interface DialogMenuEditorService {
    record FileEntry(String path, boolean writable) {}
    final class Conflict extends IOException {
        public Conflict(String message) { super(message); }
    }
    /** Blocking file operations for the editor worker, never for the server thread. */
    interface Files {
        List<FileEntry> manifest() throws IOException;
        byte[] read(String path) throws IOException;
        void save(String path, String base, String text) throws Exception;
    }
    Files files() throws IOException;
    /** Server-thread operation. */
    Set<String> layouts();
    /** May be called from the HTTP worker; fails closed when the server is unavailable. */
    boolean permitted(UUID owner);
    CompletableFuture<String> apply(UUID owner, String menu);
}
