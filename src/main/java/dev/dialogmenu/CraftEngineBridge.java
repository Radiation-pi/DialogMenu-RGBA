package dev.dialogmenu;

import org.bukkit.Bukkit;
import org.bukkit.event.*;
import org.bukkit.plugin.EventExecutor;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.nio.file.Path;

/** CE 负责生成和上传，Dialog 提供已准备好的不可变外部 ZIP。 */
final class CraftEngineBridge implements Listener, AutoCloseable {
    private final DialogMenuPlugin plugin;
    private static final String EVENT =
        "net.momirealms.craftengine.bukkit.api.event.AsyncResourcePackCacheEvent";
    private final Class<? extends Event> eventType;
    private final Object craftEngine;
    private final Method isReloading;
    private final Method isEnabling;

    @SuppressWarnings("unchecked")
    CraftEngineBridge(DialogMenuPlugin plugin) throws ReflectiveOperationException {
        this.plugin = plugin;
        eventType = (Class<? extends Event>) Class.forName(EVENT, true,
            plugin.getClass().getClassLoader()).asSubclass(Event.class);
        Class<?> engineType = Class.forName("net.momirealms.craftengine.core.plugin.CraftEngine",
            true, plugin.getClass().getClassLoader());
        craftEngine = engineType.getMethod("instance").invoke(null);
        isReloading = engineType.getMethod("isReloading");
        isEnabling = engineType.getMethod("isEnabling");
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
            // CE 的启动缓存任务可能晚于 onEnable 结束，不能仅靠 isEnabling 判断。
            boolean reloading = Boolean.TRUE.equals(isReloading.invoke(craftEngine))
                && !Boolean.TRUE.equals(isEnabling.invoke(craftEngine));
            var result = plugin.prepareResources(reloading).get();
            // CE 准备资源缓存时，不阻塞主线程。
            // 使用快照代次，避免延迟发布覆盖较新的资源。
            Bukkit.getScheduler().runTask(plugin, () -> plugin.preparedByCraftEngine(result));
            plugin.retainPack(eventType.getMethod("cacheData").invoke(event), result.snapshot());
            eventType.getMethod("registerExternalResourcePack", Path.class)
                .invoke(event, result.snapshot().pack());
            plugin.getLogger().info("CraftEngine registered Dialog pack ("
                + (result.changed() ? "rebuilt" : "cached") + ")");
        } catch (Exception error) {
            if (error instanceof InvocationTargetException invocation && invocation.getCause() != null)
                error = new IllegalStateException(invocation.getCause());
            var previous = plugin.loadedResources();
            if (previous == null) {
                plugin.getLogger().warning("Dialog无可用资源，跳过CE注册；请执行 /dialogmenu reload all。");
                return;
            }
            plugin.getLogger().log(java.util.logging.Level.SEVERE, "Dialog重载失败，保留上一版资源", error);
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
        }
    }
}
