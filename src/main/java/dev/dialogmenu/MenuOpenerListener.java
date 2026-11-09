package dev.dialogmenu;

import java.util.*;
import org.bukkit.Bukkit;
import org.bukkit.command.*;
import org.bukkit.entity.Player;
import org.bukkit.event.*;
import org.bukkit.event.player.*;
import org.bukkit.inventory.EquipmentSlot;

/** 所有入口复用插件原有权限、会话和菜单打开流程。 */
final class MenuOpenerListener implements Listener, AutoCloseable {
    private final DialogMenuPlugin plugin;
    private final List<Command> commands = new ArrayList<>();

    MenuOpenerListener(DialogMenuPlugin plugin) {
        this.plugin = plugin;
        Bukkit.getPluginManager().registerEvents(this, plugin);
    }

    List<MenuOpeners.Rule> rules() {
        var loaded = plugin.loadedResources();
        return loaded == null ? List.of() : loaded.settings().openers();
    }

    void sync() {
        removeCommands();
        for (var rule : rules()) for (String label : rule.commands()) {
            Command command = new Command(label) {
                @Override public boolean execute(CommandSender sender, String alias, String[] args) {
                    if (!(sender instanceof Player player)) {
                        sender.sendMessage("此命令需要玩家");
                        return true;
                    }
                    if (args.length != 0) { sender.sendMessage("/" + alias); return true; }
                    open(player, rule);
                    return true;
                }
                @Override public List<String> tabComplete(CommandSender sender, String alias, String[] args) {
                    return List.of();
                }
            };
            command.setPermission("dialogmenu.use");
            if (!Bukkit.getCommandMap().register("dialogmenu", command))
                plugin.getLogger().warning("入口指令/" + label + "已被占用，使用/dialogmenu:" + label);
            commands.add(command);
        }
        for (Player player : Bukkit.getOnlinePlayers()) player.updateCommands();
    }

    private boolean open(Player player, MenuOpeners.Rule rule) {
        return (rule.permission().isBlank() || player.hasPermission(rule.permission()))
            && plugin.openFromApi(player, rule.menu());
    }

    private boolean trigger(Player player, String action, org.bukkit.inventory.ItemStack item) {
        String key = MenuOpeners.key(action, player.isSneaking(), player.isSprinting());
        for (var rule : rules()) {
            boolean matches = rule.keys().contains(key);
            try {
                if (!matches && rule.item() != null && rule.item().action().equals(key))
                    matches = MenuOpeners.matches(rule.item(), item);
                if (matches && open(player, rule)) return true;
            } catch (Exception error) {
                plugin.getLogger().warning("入口" + rule.id() + "失败：" + error.getMessage());
            }
        }
        return false;
    }

    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void swap(PlayerSwapHandItemsEvent event) {
        if (trigger(event.getPlayer(), "F", event.getPlayer().getInventory().getItemInMainHand()))
            event.setCancelled(true);
    }

    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void drop(PlayerDropItemEvent event) {
        if (trigger(event.getPlayer(), "Q", event.getItemDrop().getItemStack())) event.setCancelled(true);
    }

    @EventHandler(priority = EventPriority.HIGH)
    public void interact(PlayerInteractEvent event) {
        if (event.getHand() != EquipmentSlot.HAND || event.useItemInHand() == Event.Result.DENY) return;
        String action = switch (event.getAction()) {
            case RIGHT_CLICK_AIR, RIGHT_CLICK_BLOCK -> "RIGHT_CLICK";
            case LEFT_CLICK_AIR, LEFT_CLICK_BLOCK -> "LEFT_CLICK";
            default -> null;
        };
        if (action != null && trigger(event.getPlayer(), action, event.getItem())) event.setCancelled(true);
    }

    private void removeCommands() {
        var map = Bukkit.getCommandMap();
        map.getKnownCommands().entrySet().removeIf(entry -> commands.contains(entry.getValue()));
        for (Command command : commands) command.unregister(map);
        commands.clear();
    }

    @Override public void close() {
        HandlerList.unregisterAll(this);
        removeCommands();
    }
}
