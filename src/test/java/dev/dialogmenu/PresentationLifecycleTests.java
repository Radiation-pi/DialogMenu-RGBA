package dev.dialogmenu;

import com.google.gson.GsonBuilder;
import java.lang.reflect.*;
import java.nio.file.*;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.*;
import java.util.logging.Logger;
import org.bukkit.Bukkit;
import org.bukkit.Server;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.entity.Player;
import org.bukkit.plugin.PluginManager;
import org.bukkit.plugin.java.JavaPlugin;
import org.bukkit.scheduler.BukkitScheduler;
import org.bukkit.scheduler.BukkitTask;
import net.kyori.adventure.text.serializer.gson.GsonComponentSerializer;

/** Offline production-method probe. Only platform boundaries and plugin bootstrap are substituted. */
public final class PresentationLifecycleTests {
    static final Queue<Runnable> main = new ConcurrentLinkedQueue<>();
    static final AtomicInteger shows = new AtomicInteger();
    static final List<Map<String, Object>> results = new ArrayList<>();
    static final List<String> messages = new ArrayList<>();
    static final List<UUID> messageRecipients = new ArrayList<>();
    static int checks;
    static ExecutorService previews;
    static Thread owner;
    static Object unsafe;
    static Path root;
    static ReloadEngine.Snapshot snapshot;
    static int tick;

    static void set(Object object, Class<?> type, String name, Object value) throws Exception {
        Field field = type.getDeclaredField(name);
        field.setAccessible(true);
        field.set(object, value);
    }
    static void set(Object object, String name, Object value) throws Exception {
        set(object, object.getClass(), name, value);
    }
    static Object get(Object object, String name) throws Exception {
        Field field = object.getClass().getDeclaredField(name);
        field.setAccessible(true);
        return field.get(object);
    }
    static Object empty(Class<?> type) {
        if (!type.isPrimitive() || type == void.class) return null;
        if (type == boolean.class) return false;
        if (type == int.class) return 0;
        if (type == long.class) return 0L;
        if (type == double.class) return 0d;
        if (type == float.class) return 0f;
        throw new AssertionError(type);
    }
    interface Call { Object run(Method method, Object[] args) throws Throwable; }
    @SuppressWarnings("unchecked")
    static <T> T proxy(Class<T> type, Call call) {
        return (T) Proxy.newProxyInstance(type.getClassLoader(), new Class<?>[]{type}, (p, m, a) -> {
            if (m.getName().equals("toString")) return "offline-" + type.getSimpleName();
            return call.run(m, a);
        });
    }
    static void platform() throws Exception {
        BukkitTask task = proxy(BukkitTask.class, (m, a) -> empty(m.getReturnType()));
        BukkitScheduler scheduler = proxy(BukkitScheduler.class, (m, a) -> {
            if (m.getName().equals("runTask")) {
                main.add((Runnable) a[1]);
                return task;
            }
            throw new AssertionError("Unexpected scheduler: " + m);
        });
        PluginManager manager = proxy(PluginManager.class, (m, a) -> {
            if (m.getName().equals("callEvent") || m.getName().equals("registerEvents")) return null;
            if (m.getName().equals("getPlugin")) return null;
            if (m.getName().equals("isPluginEnabled")) return false;
            throw new AssertionError("Unexpected manager: " + m);
        });
        Map<String, org.bukkit.command.Command> commands = new HashMap<>();
        var commandMap = proxy(org.bukkit.command.CommandMap.class, (m, a) -> {
            if (m.getName().equals("getKnownCommands")) return commands;
            throw new AssertionError("Unexpected command map: " + m);
        });
        Field server = Bukkit.class.getDeclaredField("server");
        server.setAccessible(true);
        server.set(null, proxy(Server.class, (m, a) -> switch (m.getName()) {
            case "isPrimaryThread" -> Thread.currentThread() == owner;
            case "getCurrentTick" -> tick;
            case "getScheduler" -> scheduler;
            case "getPluginManager" -> manager;
            case "getCommandMap" -> commandMap;
            case "getOnlinePlayers" -> List.of();
            case "getLogger" -> Logger.getLogger("offline-platform");
            case "getName", "getVersion", "getBukkitVersion" -> "offline-boundary";
            default -> throw new AssertionError("Unexpected server: " + m);
        }));
    }
    static Player player() {
        UUID id = UUID.randomUUID();
        return proxy(Player.class, (m, a) -> switch (m.getName()) {
            case "getUniqueId" -> id;
            case "getName" -> "QueueProbe";
            case "isOnline", "hasPermission" -> true;
            case "showDialog" -> { shows.incrementAndGet(); yield null; }
            case "sendMessage" -> {
                messages.add(String.valueOf(a[0]));
                messageRecipients.add(id);
                yield null;
            }
            case "closeInventory", "closeDialog" -> null;
            default -> throw new AssertionError("Unexpected player: " + m);
        });
    }
    static DialogMenuPlugin plugin(ExecutorService executor) throws Exception {
        DialogMenuPlugin plugin = (DialogMenuPlugin) unsafe.getClass()
            .getMethod("allocateInstance", Class.class).invoke(unsafe, DialogMenuPlugin.class);
        set(plugin, JavaPlugin.class, "isEnabled", true);
        set(plugin, JavaPlugin.class, "dataFolder", root.resolve("data/plugins/DialogMenu").toFile());
        set(plugin, JavaPlugin.class, "logger", Logger.getLogger("offline-plugin"));
        set(plugin, "loaded", snapshot);
        set(plugin, "width", 452);
        set(plugin, "maxPixels", 1048576);
        set(plugin, "presentationWorker", executor);
        set(plugin, "pendingPresentations", new LatestTasks<UUID>(executor));
        set(plugin, "previewWorker", previews);
        set(plugin, "presentationCaches", new WeakHashMap<>());
        set(plugin, "immediateCaches", new WeakHashMap<>());
        set(plugin, "previewCaches", new WeakHashMap<>());
        set(plugin, "presentationTicks", new HashMap<>());
        set(plugin, "readyPresentations", new ConcurrentHashMap<>());
        set(plugin, "presentationSequence", new AtomicLong());
        set(plugin, "presentationRequests", new ConcurrentHashMap<>());
        set(plugin, "preferences", new YamlConfiguration());
        set(plugin, "tokens", new ActionTokens());
        set(plugin, "gestures", new ClickGestures(() -> 0L, (action, millis) -> () -> {}));
        set(plugin, "views", new HashMap<>());
        set(plugin, "application", new MenuSessions(plugin));
        return plugin;
    }
    @SuppressWarnings("unchecked")
    static Player refreshingPlayer(DialogMenuPlugin plugin) throws Exception {
        Player player = player();
        // These existing races exercise an already refreshing page, not Paper's first-dialog factory.
        ((Map<UUID, Integer>) get(plugin, "presentationTicks")).put(player.getUniqueId(), tick);
        return player;
    }
    static ExecutorService worker() {
        return Executors.newSingleThreadExecutor(task -> {
            Thread thread = new Thread(task, "offline-DialogMenu-present");
            thread.setDaemon(true);
            return thread;
        });
    }
    static void drain(ExecutorService executor) throws Exception {
        executor.submit(() -> {}).get(90, TimeUnit.SECONDS);
    }
    static void check(boolean condition, String message) {
        if (!condition) throw new AssertionError(message);
        checks++;
    }
    static void record(String test, Object... pairs) {
        Map<String, Object> row = new LinkedHashMap<>();
        row.put("test", test);
        for (int i = 0; i < pairs.length; i += 2) row.put(pairs[i].toString(), pairs[i + 1]);
        results.add(row);
        System.out.println(new com.google.gson.Gson().toJson(row));
    }
    static void publicationRace(String operation) throws Exception {
        try (var executor = worker()) {
            DialogMenuPlugin plugin = plugin(executor);
            Player player = refreshingPlayer(plugin);
            CountDownLatch entered = new CountDownLatch(1), release = new CountDownLatch(1);
            executor.execute(() -> {
                entered.countDown();
                try { release.await(10, TimeUnit.SECONDS); } catch (InterruptedException error) { Thread.currentThread().interrupt(); }
            });
            check(entered.await(5, TimeUnit.SECONDS), "worker gate");
            plugin.showApplication(player, snapshot.documents().get("dense"), 452, List.of());
            switch (operation) {
                case "close" -> plugin.closeFromApi(player);
                case "suspend" -> plugin.suspendApplication(player);
                case "replace" -> plugin.showApplication(player, snapshot.documents().get("other"), 452, List.of());
                default -> throw new AssertionError(operation);
            }
            release.countDown();
            drain(executor);
            if (operation.equals("replace")) {
                // Discard the replacement too, so the probe does not emulate Paper's dialog factory.
                plugin.closeFromApi(player);
            }
            int callbacks = main.size();
            while (!main.isEmpty()) main.remove().run();
            check(shows.get() == 0, operation + " resurrected a stale dialog");
            record("stale-result-" + operation, "publishCallbacks", callbacks, "staleDisplays", shows.get());
        }
    }
    static void tokenGap(boolean changedActions) throws Exception {
        try (var executor = worker()) {
            DialogMenuPlugin plugin = plugin(executor);
            Player player = refreshingPlayer(plugin);
            plugin.showApplication(player, snapshot.documents().get("dense"), 452, List.of());
            drain(executor);
            main.clear();
            ActionTokens tokens = (ActionTokens) get(plugin, "tokens");
            ActionTokens.Page displayed = new ActionTokens.Page();
            var doc = snapshot.documents().get("dense");
            var target = ClickGestures.Target.of(plugin.sessionTokenFromApi(player), doc.menu(),
                LayoutStore.interactiveRegions(doc.menu()).getFirst(), DialogMenuPlugin.PREVIEW);
            Method activate = DialogMenuPlugin.class.getDeclaredMethod("activate", Player.class, ClickGestures.Target.class);
            activate.setAccessible(true);
            var action = displayed.bind(() -> {
                try { activate.invoke(plugin, player, target); }
                catch (ReflectiveOperationException error) { throw new AssertionError(error); }
            });
            tokens.publish(player.getUniqueId(), displayed);
            String identifier = ((net.kyori.adventure.text.event.ClickEvent.Payload.Custom) action.payload()).key().value();
            CountDownLatch entered = new CountDownLatch(1), release = new CountDownLatch(1);
            executor.execute(() -> {
                entered.countDown();
                try { release.await(10, TimeUnit.SECONDS); } catch (InterruptedException error) { Thread.currentThread().interrupt(); }
            });
            check(entered.await(5, TimeUnit.SECONDS), "worker gate");
            var current = changedActions ? LayoutStore.loadText("dense",
                Files.readString(root.resolve("data/layouts/dense.yml")).replace("tell: clicked", "tell: changed"))
                .get("dense") : doc;
            plugin.showApplication(player, current, 452, List.of());
            messages.clear();
            var response = tokens.takeResponse(player.getUniqueId(), identifier);
            boolean oldClickAccepted = response != null;
            if (response != null) response.accept(null);
            check(tokens.takeResponse(player.getUniqueId(), identifier) == null, "click accepted twice");
            check(changedActions ? messages.isEmpty()
                : messages.size() == 1 && messages.getFirst().contains("clicked"),
                "displayed click did not dispatch once or dispatched changed actions");
            record(changedActions ? "changed-actions-rejected" : "same-page-refresh-keeps-action",
                "oldClickAcceptedWhileReplacementPending", oldClickAccepted,
                "replacementPublished", false, "messages", List.copyOf(messages));
            check(oldClickAccepted, "same-page refresh discarded the displayed action before replacing its frame");
            plugin.closeFromApi(player);
            release.countDown();
            drain(executor);
            main.clear();
        }
    }
    static void burst() throws Exception {
        try (var executor = worker()) {
            DialogMenuPlugin plugin = plugin(executor);
            Player player = refreshingPlayer(plugin);
            CountDownLatch entered = new CountDownLatch(1), release = new CountDownLatch(1);
            executor.execute(() -> {
                entered.countDown();
                try { release.await(10, TimeUnit.SECONDS); } catch (InterruptedException error) { Thread.currentThread().interrupt(); }
            });
            check(entered.await(5, TimeUnit.SECONDS), "worker gate");
            long begin = System.nanoTime();
            int count = 2000;
            for (int i = 0; i < count; i++)
                plugin.showApplication(player, snapshot.documents().get("dense"), 452, List.of());
            long enqueue = System.nanoTime() - begin;
            var queue = get(plugin, "pendingPresentations");
            int queued = ((Map<?, ?>) get(queue, "pending")).size();
            release.countDown();
            drain(executor);
            int prepared = main.size();
            record("same-player-burst", "requests", count, "queuedTasks", queued,
                "superseded", count - queued, "enqueueMillis", enqueue / 1e6,
                "preparedPublishCallbacks", prepared);
            check(queued == 1 && prepared == 1, "same-player burst retained obsolete tasks or lost the latest frame");
            plugin.closeFromApi(player);
            while (!main.isEmpty()) main.remove().run();
        }
    }
    static void refreshFairness() throws Exception {
        try (var executor = worker()) {
            DialogMenuPlugin plugin = plugin(executor);
            Player first = refreshingPlayer(plugin), second = refreshingPlayer(plugin);
            CountDownLatch entered = new CountDownLatch(1), release = new CountDownLatch(1);
            executor.execute(() -> {
                entered.countDown();
                try { release.await(10, TimeUnit.SECONDS); }
                catch (InterruptedException error) { Thread.currentThread().interrupt(); }
            });
            check(entered.await(5, TimeUnit.SECONDS), "fairness gate");
            var doc = LayoutStore.loadText("dense", Files.readString(root.resolve("data/layouts/dense.yml"))
                .replace(", display-width: 452", "")).get("dense");
            plugin.showApplication(first, doc, 1, List.of());
            plugin.showApplication(second, doc, 1, List.of());
            plugin.showApplication(first, doc, 1, List.of());
            release.countDown();
            drain(executor);
            messageRecipients.clear();
            while (!main.isEmpty()) main.remove().run();
            check(messageRecipients.equals(List.of(first.getUniqueId(), second.getUniqueId())),
                "a refresh moved the waiting player behind later arrivals");
            record("refresh-keeps-player-turn", "players", 2, "firstPlayerRetainsTurn", true);
        }
    }
    static void throughput() throws Exception {
        try (var executor = worker()) {
            DialogMenuPlugin plugin = plugin(executor);
            var doc = snapshot.documents().get("dense");
            var serializer = GsonComponentSerializer.gson();
            var renderer = new MenuRenderer(snapshot.assets());
            var frame = renderer.drawRuntime(doc.menu(), DialogMenuPlugin.PREVIEW, 452, 1048576);
            String expected = serializer.serialize(new SpriteCanvas().encode(
                doc.menu(), frame, DialogMenuPlugin.PREVIEW, snapshot.fonts(), region -> null).component());
            for (int i = 0; i < 32; i++)
                plugin.renderPreview(snapshot, doc, DialogMenuPlugin.PREVIEW).get();
            int count = 256;
            CountDownLatch entered = new CountDownLatch(1), release = new CountDownLatch(1);
            previews.execute(() -> {
                entered.countDown();
                try { release.await(10, TimeUnit.SECONDS); } catch (InterruptedException error) { Thread.currentThread().interrupt(); }
            });
            check(entered.await(5, TimeUnit.SECONDS), "preview gate");
            long begin = System.nanoTime();
            List<CompletableFuture<SpriteCanvas.Output>> futures = new ArrayList<>();
            for (int i = 0; i < count; i++)
                futures.add(plugin.renderPreview(snapshot, doc, DialogMenuPlugin.PREVIEW));
            double submitMillis = (System.nanoTime() - begin) / 1e6;
            AtomicInteger completed = new AtomicInteger();
            for (var future : futures) future.thenRun(completed::incrementAndGet);
            Player player = refreshingPlayer(plugin);
            long interactiveBegin = System.nanoTime();
            plugin.showApplication(player, doc, 452, List.of());
            drain(executor);
            double delay = (System.nanoTime() - interactiveBegin) / 1e6;
            check(main.size() == 1, "blocked previews prevented interactive rendering");
            check(completed.get() == 0, "preview gate did not isolate interactive rendering");
            release.countDown();
            int parity = 0;
            for (var future : futures)
                if (expected.equals(serializer.serialize(future.get().component()))) parity++;
            check(parity == count && completed.get() == count, "render completion/parity");
            check(main.size() == 1, "interactive presentation did not complete");
            record("isolated-preview-render-pressure", "requests", count, "completed", completed.get(),
                "componentParity", parity, "submitMillis", submitMillis,
                "interactivePreparedAfterMillis", delay,
                "interactiveFinishedWhilePreviewBlocked", true,
                "note", "offline rendering, not MSPT or network/client latency");
            plugin.closeFromApi(player);
            while (!main.isEmpty()) main.remove().run();
        }
    }
    static void unrelatedReload(ReloadEngine engine) throws Exception {
        try (var executor = worker()) {
            DialogMenuPlugin plugin = plugin(executor);
            var store = new LayoutStore(root.resolve("data/layouts"));
            store.publish(snapshot.documents());
            set(plugin, "store", store);
            set(plugin, "openers", new MenuOpenerListener(plugin));
            Player player = refreshingPlayer(plugin);
            plugin.showApplication(player, snapshot.documents().get("dense"), 452, List.of());
            drain(executor);
            check(main.size() == 1, "missing prepared render before unrelated reload");
            Files.writeString(root.resolve("data/layouts/other.yml"), "\n# unrelated menu reload\n",
                StandardOpenOption.APPEND);
            ReloadEngine.Result result = engine.prepare(
                new ReloadEngine.Request(ReloadEngine.Mode.YAML, "other")).get(30, TimeUnit.SECONDS);
            check(result.menus().equals(Set.of("other")), "not a single unrelated-menu reload");
            check(result.snapshot().renderer() == snapshot.renderer()
                && result.snapshot().fonts() == snapshot.fonts(), "resource identity unexpectedly changed");
            plugin.publishResources(result);
            main.remove().run();
            drain(executor);
            boolean active = "dense".equals(plugin.currentIdFromApi(player));
            int outstanding = ((Map<?, ?>) get(plugin, "presentationRequests")).size();
            record("unrelated-single-menu-reload", "affectedMenus", result.menus(),
                "denseViewStillActive", active, "displayCalls", shows.get(),
                "unfulfilledPresentationRequests", outstanding,
                "retryQueued", !main.isEmpty());
            check(active && shows.get() == 0 && outstanding == 1 && main.size() == 1,
                "unrelated menu reload silently discarded the pending frame instead of rendering with current resources");
            plugin.closeFromApi(player);
            while (!main.isEmpty()) main.remove().run();
        }
    }
    static void nativeRefresh(boolean changedControls) throws Exception {
        try (var executor = worker()) {
            DialogMenuPlugin plugin = plugin(executor);
            Player player = refreshingPlayer(plugin);
            var doc = snapshot.documents().get("native");
            plugin.showApplication(player, doc, 452, List.of());
            drain(executor);
            main.clear();
            Object displayedView = plugin.surfaceFromApi(player);
            var button = doc.controls().buttons().getFirst();
            Method submit = DialogMenuPlugin.class.getDeclaredMethod("submitNative", Player.class,
                displayedView.getClass(), NativeControls.Button.class, io.papermc.paper.dialog.DialogResponseView.class);
            submit.setAccessible(true);
            ActionTokens tokens = (ActionTokens) get(plugin, "tokens");
            var displayed = new ActionTokens.Page();
            var event = displayed.bind(response -> {
                try { submit.invoke(plugin, player, displayedView, button, response); }
                catch (ReflectiveOperationException error) { throw new AssertionError(error); }
            });
            tokens.publish(player.getUniqueId(), displayed);
            String identifier = ((net.kyori.adventure.text.event.ClickEvent.Payload.Custom) event.payload()).key().value();
            var current = changedControls ? LayoutStore.loadText("native",
                Files.readString(root.resolve("data/layouts/native.yml")).replace("tell: native", "tell: changed"))
                .get("native") : doc;
            CountDownLatch entered = new CountDownLatch(1), release = new CountDownLatch(1);
            executor.execute(() -> {
                entered.countDown();
                try { release.await(10, TimeUnit.SECONDS); } catch (InterruptedException error) { Thread.currentThread().interrupt(); }
            });
            check(entered.await(5, TimeUnit.SECONDS), "native gate");
            plugin.showApplication(player, current, 452, List.of());
            var response = proxy(io.papermc.paper.dialog.DialogResponseView.class, (m, a) -> {
                if (m.getName().equals("getText")) return "Alex";
                throw new AssertionError("Unexpected response read " + m);
            });
            messages.clear();
            var callback = tokens.takeResponse(player.getUniqueId(), identifier);
            check(callback != null, "native token lost during same-page refresh");
            callback.accept(response);
            check(tokens.takeResponse(player.getUniqueId(), identifier) == null, "native response accepted twice");
            check(changedControls ? messages.isEmpty()
                : messages.size() == 1 && messages.getFirst().contains("native Alex"),
                "native refresh did not submit once with unchanged controls or accepted changed controls");
            check(changedControls == (plugin.currentIdFromApi(player) != null),
                "native close policy changed while rendering");
            record(changedControls ? "native-changed-controls-rejected" : "native-refresh-submits-once",
                "messages", List.copyOf(messages));
            plugin.closeFromApi(player);
            release.countDown();
            drain(executor);
            while (!main.isEmpty()) main.remove().run();
        }
    }
    public static void main(String[] args) throws Exception {
        System.out.println("PASS presentation lifecycle checks=" + run(Path.of(args[0])));
    }
    static void hybridFailures() throws Exception {
        try (var executor = worker()) {
            DialogMenuPlugin plugin = plugin(executor);
            Player player = player();
            var invalid = LayoutStore.loadText("dense", Files.readString(root.resolve("data/layouts/dense.yml"))
                .replace(", display-width: 452", "")).get("dense");
            for (int step = 0; step < 3; step++) {
                if (step == 2) tick += 2;
                boolean failedBeforeReturn = false;
                try { plugin.showApplication(player, invalid, 1, List.of()); }
                catch (IllegalArgumentException expected) { failedBeforeReturn = true; }
                check(failedBeforeReturn == (step != 1),
                    "first/low-frequency errors must be synchronous; a rapid refresh must be queued");
                drain(executor);
                if (step == 1) {
                    check(main.size() == 1, "rapid render failure lost its main-thread completion");
                    plugin.closeFromApi(player);
                    while (!main.isEmpty()) main.remove().run();
                } else check(main.isEmpty(), "synchronous render unexpectedly scheduled publication");
            }
            record("hybrid-error-delivery", "firstSynchronous", true,
                "rapidRefreshAsynchronous", true, "afterTwoTicksSynchronous", true);
        }
    }
    @SuppressWarnings("unchecked")
    static void publicationDelivery() throws Exception {
        try (var executor = worker()) {
            DialogMenuPlugin plugin = plugin(executor);
            var requests = (Map<UUID, Long>) get(plugin, "presentationRequests");
            Method publish = DialogMenuPlugin.class.getDeclaredMethod(
                "publishPresentationLater", UUID.class, long.class, Runnable.class);
            publish.setAccessible(true);
            UUID player = UUID.randomUUID();
            requests.put(player, 1L);
            AtomicInteger delivered = new AtomicInteger();
            publish.invoke(plugin, player, 1L, (Runnable) delivered::incrementAndGet);
            plugin.tickEnd(null);
            check(delivered.get() == 1, "tick-end did not publish ready work before scheduler fallback");
            while (!main.isEmpty()) main.remove().run();
            check(delivered.get() == 1, "scheduler repeated tick-end publication");
            publish.invoke(plugin, player, 1L, (Runnable) delivered::incrementAndGet);
            while (!main.isEmpty()) main.remove().run();
            plugin.tickEnd(null);
            check(delivered.get() == 2, "fallback lost or duplicated late work");
            publish.invoke(plugin, player, 0L, (Runnable) delivered::incrementAndGet);
            plugin.tickEnd(null);
            check(delivered.get() == 2 && main.isEmpty(), "stale work entered publication");
            Player closing = player();
            requests.put(closing.getUniqueId(), 2L);
            publish.invoke(plugin, closing.getUniqueId(), 2L, (Runnable) delivered::incrementAndGet);
            plugin.closeApplication(closing);
            plugin.tickEnd(null);
            while (!main.isEmpty()) main.remove().run();
            check(delivered.get() == 2, "close left a ready callback active");
            requests.put(closing.getUniqueId(), 3L);
            publish.invoke(plugin, closing.getUniqueId(), 3L, (Runnable) delivered::incrementAndGet);
            plugin.replaceApplication(closing);
            plugin.tickEnd(null);
            while (!main.isEmpty()) main.remove().run();
            check(delivered.get() == 2, "replacement left an old ready callback active");
            record("publication-delivery", "tickEndAndFallbackExactlyOnce", true, "closeAndStaleRejected", true);
        }
    }
    static void latestTasks() {
        List<Runnable> scheduled = new ArrayList<>();
        List<String> executed = new ArrayList<>();
        var tasks = new LatestTasks<String>(scheduled::add);
        for (int i = 0; i < 5000; i++) {
            int value = i;
            tasks.submit("first", () -> executed.add("first:" + value));
        }
        tasks.submit("second", () -> executed.add("second"));
        tasks.submit("cancelled", () -> executed.add("cancelled"));
        tasks.cancel("cancelled");
        check(scheduled.size() == 1, "refresh bursts grew the executor queue");
        scheduled.removeFirst().run();
        check(executed.equals(List.of("first:4999", "second")), "latest frame or another player was lost");
        tasks.submit("clear", () -> executed.add("clear"));
        tasks.clear();
        scheduled.removeFirst().run();
        check(executed.size() == 2, "cleared work still executed");
        tasks.submit("running", () -> {
            tasks.submit("running", () -> executed.add("stale"));
            tasks.submit("running", () -> executed.add("latest"));
        });
        scheduled.removeFirst().run();
        check(executed.getLast().equals("latest") && !executed.contains("stale"),
            "updates received during rendering were not coalesced");
        tasks.submit("failure", () -> { throw new IllegalStateException("expected-task-failure"); });
        tasks.submit("after-failure", () -> executed.add("recovered"));
        try {
            scheduled.removeFirst().run();
            throw new AssertionError("task failure swallowed");
        } catch (IllegalStateException expected) {
            check(expected.getMessage().equals("expected-task-failure"), "unexpected task failure");
        }
        check(scheduled.size() == 1, "failure stranded another player's frame");
        scheduled.removeFirst().run();
        check(executed.getLast().equals("recovered"), "queue did not recover from a failed task");
        record("latest-task-queue", "burstRequests", 5000, "latestFrame", "first:4999",
            "anotherPlayerCompleted", true, "cancelAndClear", true, "failureRecovery", true);
    }
    static int run(Path directory) throws Exception {
        root = Files.createTempDirectory(directory, "presentation-lifecycle-");
        checks = 0;
        results.clear();
        latestTasks();
        owner = Thread.currentThread();
        Files.createDirectories(root.resolve("data/layouts"));
        Files.createDirectories(root.resolve("data/images"));
        Field field = Class.forName("sun.misc.Unsafe").getDeclaredField("theUnsafe");
        field.setAccessible(true);
        unsafe = field.get(null);
        Files.writeString(root.resolve("data/config.yml"), """
            default-layout: dense
            max-canvas-pixels: 1048576
            text-scales: [1]
            canvas-scales: [1]
            resource-build-cpu-percent: 100
            """);
        StringBuilder yaml = new StringBuilder("""
            schema-version: 3
            id: dense
            canvas: {width: 452, height: 205, display-width: 452}
            frontend:
            """);
        for (int i = 0; i < 64; i++) yaml.append("""
              text-%d:
                type: text
                content: 'Row %d'
                size: 6
                offset: {x: %d, y: %d}
                tooltip: ['Pressure test', 'Selected']
                actions: 'tell: clicked'
            """.formatted(i, i, 28 + i % 8 * 56, 12 + i / 8 * 22));
        Files.writeString(root.resolve("data/layouts/dense.yml"), yaml);
        Files.writeString(root.resolve("data/layouts/other.yml"), yaml.toString().replace("id: dense", "id: other"));
        Files.writeString(root.resolve("data/layouts/native.yml"), """
            schema-version: 3
            id: native
            canvas: {width: 452, height: 205, display-width: 452}
            frontend:
              text: {type: text, content: Native, offset: {x: 226, y: 20}}
            native-controls:
              inputs:
                name: {type: text, label: Name}
              buttons:
                submit: {label: Submit, actions: 'tell: native {input.name}', close: true}
            """);
        Field server = Bukkit.class.getDeclaredField("server");
        server.setAccessible(true);
        Object previousServer = server.get(null);
        try (var previewExecutor = worker();
             var engine = new ReloadEngine(root.resolve("data"), DialogMenuPlugin.PREVIEW)) {
            previews = previewExecutor;
            snapshot = engine.prepare().get(120, TimeUnit.SECONDS).snapshot();
            platform();
            hybridFailures();
            publicationDelivery();
            tokenGap(false);
            tokenGap(true);
            publicationRace("close");
            publicationRace("suspend");
            publicationRace("replace");
            burst();
            refreshFairness();
            throughput();
            nativeRefresh(false);
            nativeRefresh(true);
            unrelatedReload(engine);
        } finally {
            server.set(null, previousServer);
            Files.writeString(root.resolve("results.json"), new GsonBuilder().setPrettyPrinting().create().toJson(results));
        }
        return checks;
    }
}
