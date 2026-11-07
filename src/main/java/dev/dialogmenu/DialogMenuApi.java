package dev.dialogmenu;

import java.util.Set;
import java.util.UUID;
import java.util.Map;
import java.util.function.Consumer;
import org.bukkit.Bukkit;
import org.bukkit.entity.Player;
import org.bukkit.plugin.Plugin;

/** Main-thread facade for YAML-defined pages; no per-menu business logic. */
public final class DialogMenuApi {
    private final DialogMenuPlugin plugin;
    DialogMenuApi(DialogMenuPlugin plugin) { this.plugin = plugin; }
    private void requireReady() {
        if (!Bukkit.isPrimaryThread()) throw new IllegalStateException("Dialog API requires the server thread");
        if (!plugin.isEnabled()) throw new IllegalStateException("DialogMenu is not enabled");
    }
    public boolean hasLayout(String id) { requireReady(); return plugin.hasLayout(id); }
    public Set<String> layouts() { requireReady(); return plugin.menuIds(); }
    public boolean open(Player player, String id) {
        requireReady();
        return player != null && player.isOnline() && plugin.openFromApi(player, id);
    }
    /**
     * Bind an existing plugin's data/actions to a YAML family. Layouts, permissions and actions
     * remain in YAML; the callback receives only the configured callback action's value.
     */
    public DialogMenuBinding bind(Plugin owner, Player player, String page, Map<String, String> values,
                                  Consumer<String> action, Consumer<DialogMenuClosedEvent.Reason> closed) {
        requireReady();
        if (owner == null || !owner.isEnabled()) throw new IllegalArgumentException("Binding owner is not enabled");
        if (player == null || !player.isOnline()) return null;
        return plugin.bindFromApi(owner, player, page, Map.copyOf(values),
            java.util.Objects.requireNonNull(action), java.util.Objects.requireNonNull(closed));
    }
    boolean bindingActive(DialogMenuBinding binding) {
        requireReady();
        return plugin.bindingActive(binding);
    }
    boolean bindingUpdate(DialogMenuBinding binding, Map<String, String> values) {
        requireReady();
        return plugin.bindingUpdate(binding, Map.copyOf(values));
    }
    boolean bindingUpdateCollection(DialogMenuBinding binding, String collection, java.util.List<DialogMenuEntry> entries) {
        requireReady();
        return plugin.bindingUpdateCollection(binding, collection, java.util.List.copyOf(entries));
    }
    boolean bindingOpenForm(DialogMenuBinding binding, String form,
                            java.util.function.Consumer<Map<String, String>> submitted) {
        requireReady();
        return plugin.bindingOpenForm(binding, form, java.util.Objects.requireNonNull(submitted));
    }
    boolean bindingOpen(DialogMenuBinding binding, String page) {
        requireReady();
        return plugin.bindingOpen(binding, page);
    }
    boolean bindingSuspend(DialogMenuBinding binding, boolean suspended) {
        requireReady();
        return plugin.bindingSuspend(binding, suspended);
    }
    void bindingClose(DialogMenuBinding binding) {
        requireReady();
        if (plugin.bindingActive(binding)) plugin.closeFromApi(binding.player);
    }
    public void close(Player player) {
        requireReady();
        if (player != null) plugin.closeFromApi(player);
    }
    public boolean refresh(Player player) {
        requireReady();
        return player != null && plugin.refreshFromApi(player);
    }
    /** A server-side view identity, not a client render acknowledgement. */
    public Object surface(Player player) {
        requireReady();
        return player == null ? null : plugin.surfaceFromApi(player);
    }
    public String currentId(Player player) {
        requireReady();
        return player == null ? null : plugin.currentIdFromApi(player);
    }
    /** Stable across refreshes, replaced on page changes/new sessions. Not a client acknowledgement. */
    public UUID sessionToken(Player player) {
        requireReady();
        return player == null ? null : plugin.sessionTokenFromApi(player);
    }
}
