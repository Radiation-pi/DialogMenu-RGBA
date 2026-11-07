package dev.dialogmenu;

import org.bukkit.Bukkit;
import org.bukkit.event.*;
import org.bukkit.plugin.EventExecutor;
import java.lang.reflect.InvocationTargetException;
import java.nio.file.Path;

/** CE owns generation/upload; Dialog supplies one prepared, immutable external ZIP. */
final class CraftEngineBridge implements Listener, AutoCloseable {
    private final DialogMenuPlugin plugin;
    private static final String EVENT =
        "net.momirealms.craftengine.bukkit.api.event.AsyncResourcePackCacheEvent";
    private final Class<? extends Event> eventType;

    @SuppressWarnings("unchecked")
    CraftEngineBridge(DialogMenuPlugin plugin) throws ReflectiveOperationException {
        this.plugin = plugin;
        eventType = (Class<? extends Event>) Class.forName(EVENT, true,
            plugin.getClass().getClassLoader()).asSubclass(Event.class);
        EventExecutor executor = (listener, event) -> {
            if (eventType.isInstance(event)) cache(event);
        };
        Bukkit.getPluginManager().registerEvent(eventType, this, EventPriority.HIGH,
            executor, plugin, false);
    }

    @Override public void close() {
        HandlerList.unregisterAll(this);
    }

    private void cache(Event event) {
        try {
            var result = plugin.prepareResources().get();
            // Avoid waiting on the main thread while CE is preparing its resource cache.
            // Snapshot generations prevent delayed publication from replacing newer resources.
            Bukkit.getScheduler().runTask(plugin, () -> plugin.publishResources(result));
            plugin.retainPack(eventType.getMethod("cacheData").invoke(event), result.snapshot());
            eventType.getMethod("registerExternalResourcePack", Path.class)
                .invoke(event, result.snapshot().pack());
            plugin.getLogger().info("CraftEngine registered Dialog pack ("
                + (result.changed() ? "rebuilt" : "cached") + ")");
        } catch (Exception error) {
            if (error instanceof InvocationTargetException invocation && invocation.getCause() != null)
                error = new IllegalStateException(invocation.getCause());
            plugin.getLogger().log(java.util.logging.Level.SEVERE, "Dialog重载失败，保留上一版资源", error);
            var previous = plugin.loadedResources();
            if (previous != null) {
                try {
                    plugin.retainPack(eventType.getMethod("cacheData").invoke(event), previous);
                    eventType.getMethod("registerExternalResourcePack", Path.class)
                        .invoke(event, previous.pack());
                } catch (ReflectiveOperationException reflectionError) {
                    throw new IllegalStateException("Unable to register previous Dialog resource pack",
                        reflectionError);
                }
            }
            else throw new IllegalStateException("Dialog尚无可用资源包", error);
        }
    }
}
