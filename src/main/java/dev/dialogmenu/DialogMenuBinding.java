package dev.dialogmenu;

import java.util.Map;
import java.util.List;
import java.util.function.Consumer;
import org.bukkit.entity.Player;

/** A plugin-owned YAML session. Handles become inert after replacement or cleanup. */
public final class DialogMenuBinding {
    private final DialogMenuApi api;
    final Player player;
    final Object identity;

    DialogMenuBinding(DialogMenuApi api, Player player, Object identity) {
        this.api = api;
        this.player = player;
        this.identity = identity;
    }

    public boolean isActive() { return api.bindingActive(this); }
    public boolean update(Map<String, String> values) { return api.bindingUpdate(this, values); }
    /**
     * Replace one YAML-declared {@code external: true} collection. Any number of rows may be supplied;
     * row display is controlled by the page's {@code flow-lists} YAML and a row click reports its ID
     * to the callback passed to {@link DialogMenuApi#bind}.
     */
    public boolean updateCollection(String collection, List<DialogMenuEntry> entries) {
        return api.bindingUpdateCollection(this, collection, entries);
    }
    /**
     * Open a form declared in the template's top-level {@code forms} section. Its fields, labels and
     * Paper input limits come from YAML; the callback receives field-key/value pairs only on submit.
     * Closing or choosing cancel never invokes it.
     */
    public boolean openForm(String form, Consumer<Map<String, String>> submitted) {
        return api.bindingOpenForm(this, form, submitted);
    }
    public boolean open(String page) { return api.bindingOpen(this, page); }
    /** Revoke canvas clicks before presenting a separate text-input dialog. */
    public boolean suspend() { return api.bindingSuspend(this, true); }
    public boolean resume() { return api.bindingSuspend(this, false); }
    public void close() { api.bindingClose(this); }
}
