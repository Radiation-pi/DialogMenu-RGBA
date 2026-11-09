package dev.dialogmenu;

import org.bukkit.entity.Player;

/** Optional command bridge implemented by the separate DialogMenuEditor plugin. */
public interface DialogMenuEditorLauncher {
    void open(Player player, String menu);
}
