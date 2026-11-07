package dev.dialogmenu;

import java.util.UUID;
import org.bukkit.entity.Player;
import org.bukkit.event.Event;
import org.bukkit.event.HandlerList;

/** Emitted once after the old view is detached. Refresh does not close a session. */
public final class DialogMenuClosedEvent extends Event {
    public enum Reason { CLOSED, REPLACED, RELOAD, DISABLED, PLAYER_QUIT, WORLD_CHANGED, INVENTORY_OPENED }
    private static final HandlerList HANDLERS = new HandlerList();
    private final Player player;
    private final String menuId;
    private final UUID token;
    private final Reason reason;

    DialogMenuClosedEvent(Player player, String menuId, UUID token, Reason reason) {
        this.player = player;
        this.menuId = menuId;
        this.token = token;
        this.reason = reason;
    }
    public Player getPlayer() { return player; }
    public String getMenuId() { return menuId; }
    public UUID getToken() { return token; }
    public Reason getReason() { return reason; }
    @Override public HandlerList getHandlers() { return HANDLERS; }
    public static HandlerList getHandlerList() { return HANDLERS; }
}
