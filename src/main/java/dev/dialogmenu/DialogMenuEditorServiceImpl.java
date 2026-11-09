package dev.dialogmenu;

import java.io.IOException;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import org.bukkit.Bukkit;

final class DialogMenuEditorServiceImpl implements DialogMenuEditorService {
    private final DialogMenuPlugin plugin;
    private DialogMenuEditorFiles files;

    DialogMenuEditorServiceImpl(DialogMenuPlugin plugin) {
        this.plugin = plugin;
    }

    @Override public synchronized Files files() throws IOException {
        if (!plugin.isEnabled()) throw new IllegalStateException("DialogMenu is not enabled");
        if (files == null) {
            var data = plugin.getDataFolder().toPath();
            files = new DialogMenuEditorFiles(data,
                data.resolve(plugin.getConfig().getString("craftengine-resources", "../CraftEngine/resources")).normalize());
        }
        return files;
    }

    @Override public Set<String> layouts() {
        if (!Bukkit.isPrimaryThread()) throw new IllegalStateException("Editor layouts require the server thread");
        return Set.copyOf(plugin.layoutIds());
    }

    @Override public boolean permitted(UUID owner) {
        if (!Bukkit.isPrimaryThread()) {
            if (!plugin.isEnabled()) return false;
            try {
                return Bukkit.getScheduler().callSyncMethod(plugin, () -> permitted(owner)).get(5, TimeUnit.SECONDS);
            } catch (Exception ignored) {
                return false;
            }
        }
        var player = Bukkit.getPlayer(owner);
        return plugin.isEnabled() && player != null && player.isOnline()
            && player.hasPermission("dialogmenu.admin");
    }

    @Override public CompletableFuture<String> apply(UUID owner, String menu) {
        return plugin.applyWebEditor(owner, menu);
    }
}
