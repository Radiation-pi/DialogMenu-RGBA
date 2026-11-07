package dev.dialogmenu;

import io.papermc.paper.connection.PlayerGameConnection;
import io.papermc.paper.dialog.Dialog;
import io.papermc.paper.dialog.DialogResponseView;
import io.papermc.paper.event.player.PlayerCustomClickEvent;
import io.papermc.paper.registry.RegistryKey;
import io.papermc.paper.registry.data.dialog.DialogBase;
import io.papermc.paper.registry.data.dialog.body.DialogBody;
import io.papermc.paper.registry.data.dialog.input.DialogInput;
import io.papermc.paper.registry.data.dialog.action.DialogAction;
import io.papermc.paper.registry.data.dialog.ActionButton;
import io.papermc.paper.registry.data.dialog.type.DialogType;
import io.papermc.paper.registry.set.RegistrySet;
import net.kyori.adventure.key.Key;
import net.kyori.adventure.text.event.ClickEvent;
import java.io.*;
import java.nio.file.*;
import java.util.*;
import java.util.concurrent.*;
import net.kyori.adventure.text.Component;
import org.bukkit.*;
import org.bukkit.command.*;
import org.bukkit.entity.Player;
import org.bukkit.event.*;
import org.bukkit.event.inventory.InventoryOpenEvent;
import org.bukkit.event.player.*;
import org.bukkit.event.server.PluginDisableEvent;
import org.bukkit.plugin.Plugin;
import java.util.function.Consumer;
import org.bukkit.plugin.java.JavaPlugin;
import org.bukkit.plugin.ServicePriority;
import org.bukkit.configuration.file.YamlConfiguration;
import static dev.dialogmenu.MenuModel.*;
import static dev.dialogmenu.DialogMenuClosedEvent.Reason;

public final class DialogMenuPlugin extends JavaPlugin implements Listener, TabExecutor, MenuSessions.Host {
    private LayoutStore store;
    private MenuRenderer renderer;
    private FontPack fonts;
    private SpriteCanvas canvas = new SpriteCanvas();
    private MenuSessions application;
    private ReloadEngine reloadEngine;
    private EditorBridge editor;
    private CraftEngineBridge craftEngineBridge;
    private volatile ReloadEngine.Snapshot loaded;
    private boolean reloadRequested;
    private final YamlConfiguration preferences = new YamlConfiguration();
    private final ActionTokens tokens = new ActionTokens();
    private final ClickGestures gestures = new ClickGestures(() -> System.nanoTime() / 1_000_000,
        (action, millis) -> {
            var task = Bukkit.getScheduler().runTaskLater(this, action, (millis + 49) / 50);
            return task::cancel;
        });
    private final Map<UUID, View> views = new HashMap<>();
    private record View(LayoutStore.Document document, int width, List<String> history, UUID token) {}
    private int width, maxPixels;
    private final DialogMenuApi api = new DialogMenuApi(this);

    public DialogMenuApi getMenuApi() { return api; }
    boolean hasLayout(String id) { return store != null && store.get(id) != null; }
    Set<String> menuIds() { return store == null ? Set.of() : Set.copyOf(store.ids()); }
    Object surfaceFromApi(Player player) { return views.get(player.getUniqueId()); }
    UUID sessionTokenFromApi(Player player) {
        View view = views.get(player.getUniqueId());
        return view == null ? null : view.token;
    }
    String currentIdFromApi(Player player) {
        View view = views.get(player.getUniqueId());
        return view == null ? null : view.document.menu().id();
    }
    boolean openFromApi(Player player, String id) {
        try { return open(player, id, width, List.of()); }
        catch (Exception error) { failure(player, error); return false; }
    }
    public void closeFromApi(Player player) {
        if (views.containsKey(player.getUniqueId()) || application.active(player)) close(player);
    }
    boolean refreshFromApi(Player player) {
        View view = views.get(player.getUniqueId());
        if (view == null || application.suspended(player)) return false;
        if (!player.isOnline() || !player.hasPermission("dialogmenu.use")
            || !allowed(player, view.document.menu().permission())) { close(player); return false; }
        try {
            if (application.active(player)) application.refresh(player); else present(player, view);
            return true;
        } catch (Exception error) { failure(player, error); close(player); return false; }
    }
    DialogMenuBinding bindFromApi(Plugin owner, Player player, String id, Map<String, String> values,
                                  Consumer<String> action, Consumer<Reason> closed) {
        try {
            var doc = store.get(id);
            if (loaded == null || doc == null || doc.template() == null)
                throw new IllegalArgumentException("Binding requires a loaded YAML family page: " + id);
            if (!player.hasPermission("dialogmenu.use") || !allowed(player, doc.menu().permission())) return null;
            return application.bind(owner, player, doc, width, values, action, closed);
        } catch (Exception error) { failure(player, error); return null; }
    }
    boolean bindingActive(DialogMenuBinding binding) { return application.active(binding); }
    boolean bindingUpdate(DialogMenuBinding binding, Map<String, String> values) {
        try { return application.update(binding, values); }
        catch (Exception error) { failure(binding.player, error); return false; }
    }
    boolean bindingUpdateCollection(DialogMenuBinding binding, String collection, List<DialogMenuEntry> entries) {
        try { return application.updateCollection(binding, collection, entries); }
        catch (Exception error) { failure(binding.player, error); return false; }
    }
    boolean bindingOpenForm(DialogMenuBinding binding, String form, Consumer<Map<String, String>> submitted) {
        try { return application.openForm(binding, form, submitted); }
        catch (Exception error) { failure(binding.player, error); return false; }
    }
    public void showForm(Player player, MenuTemplate.FormSpec form, Consumer<Map<String, String>> submitted, Runnable cancelled) {
        gestures.cancel(player.getUniqueId());
        ActionTokens.Page page = new ActionTokens.Page();
        ClickEvent submit = page.bind(response -> {
            if (response == null) return;
            Map<String, String> values = new LinkedHashMap<>();
            for (var field : form.fields()) {
                String value = response.getText(field.key());
                if (value != null) values.put(field.key(), value);
            }
            submitted.accept(Map.copyOf(values));
        });
        ClickEvent cancel = page.bind(cancelled);
        List<DialogInput> inputs = form.fields().stream().map(field ->
            DialogInput.text(field.key(), Component.text(field.label()))
                .initial(field.initial()).maxLength(field.maxLength()).build()).map(DialogInput.class::cast).toList();
        var yes = ActionButton.builder(Component.text(form.submit()))
            .action(formAction(submit)).build();
        var no = ActionButton.builder(Component.text(form.cancel()))
            .action(formAction(cancel)).build();
        Dialog dialog = Dialog.create(factory -> factory.empty()
            .base(DialogBase.builder(Component.text(form.title())).pause(false)
                .canCloseWithEscape(true).afterAction(DialogBase.DialogAfterAction.NONE)
                .inputs(inputs).build())
            .type(DialogType.multiAction(List.of(yes)).exitAction(no).build()));
        player.showDialog(dialog);
        tokens.publish(player.getUniqueId(), page);
    }
    private static DialogAction formAction(ClickEvent event) {
        var payload = (ClickEvent.Payload.Custom) event.payload();
        return DialogAction.customClick(payload.key(), payload.nbt());
    }
    boolean bindingOpen(DialogMenuBinding binding, String id) {
        try {
            var doc = store.get(id);
            if (doc == null) throw new IllegalArgumentException("布局不存在：" + id);
            return application.navigate(binding, doc);
        } catch (Exception error) { failure(binding.player, error); return false; }
    }
    boolean bindingSuspend(DialogMenuBinding binding, boolean value) {
        try { return application.suspend(binding, value); }
        catch (Exception error) { failure(binding.player, error); return false; }
    }
    public void suspendApplication(Player player) {
        gestures.cancel(player.getUniqueId());
        tokens.remove(player.getUniqueId());
        DialogCompat.close(player);
    }

    @Override public void onEnable() {
        try {
            getLogger().info("Renderer edition: " + RenderingEdition.ID);
            saveDefaultConfig();
            getConfig().addDefault("close-with-escape", false);
            getConfig().addDefault("text-scales", List.of(1, 1.5, 2));
            getConfig().addDefault("default-text-scale", 1);
            getConfig().addDefault("craftengine-auto-reload", true);
            getConfig().options().copyDefaults(true);
            saveConfig();
            try (var jar = new java.util.jar.JarFile(getFile())) {
                for (var entry : jar.stream().filter(e -> !e.isDirectory() && (e.getName().startsWith("layouts/")
                    || e.getName().startsWith("images/"))).toList())
                    if (!new File(getDataFolder(), entry.getName()).exists()) saveResource(entry.getName(), false);
            }
            application = new MenuSessions(this);
            store = new LayoutStore(getDataFolder().toPath().resolve("layouts"));
            File preferenceFile = new File(getDataFolder(), "player-text-scales.yml");
            if (preferenceFile.isFile()) preferences.load(preferenceFile);
            reloadEngine = new ReloadEngine(getDataFolder().toPath(), PREVIEW);
            Objects.requireNonNull(getCommand("dialogmenu")).setExecutor(this);
            getCommand("dialogmenu").setTabCompleter(this);
            Bukkit.getPluginManager().registerEvents(this, this);
            editor = new EditorBridge(this);
            if (Bukkit.getPluginManager().isPluginEnabled("CraftEngine"))
                craftEngineBridge = new CraftEngineBridge(this);
            Bukkit.getServicesManager().register(DialogMenuApi.class, api, this, ServicePriority.Normal);
            requestReload(Bukkit.getConsoleSender(), new ReloadEngine.Request(ReloadEngine.Mode.ALL, null));
        } catch (Exception error) {
            getLogger().log(java.util.logging.Level.SEVERE, "Dialog initialization failed", error);
            Bukkit.getPluginManager().disablePlugin(this);
        }
    }
    @Override public void onDisable() {
        Bukkit.getServicesManager().unregisterAll(this);
        if (editor != null) editor.close();
        if (craftEngineBridge != null) craftEngineBridge.close();
        if (reloadEngine != null) reloadEngine.close();
        for (UUID id : List.copyOf(views.keySet())) {
            Player player = Bukkit.getPlayer(id);
            if (player != null) close(player, Reason.DISABLED);
        }
        views.clear();
        gestures.clear();
        tokens.clear();
        if (application != null) application.clear(Reason.DISABLED);
    }
    private Context context(Player player) {
        return new Context() {
            public String expand(String text) {
                return application.expand(player, text).replace("{player}", player.getName())
                    .replace("{uuid}", player.getUniqueId().toString());
            }
            public Icon item(int slot) { return null; }
            public boolean permitted(String permission) { return allowed(player, permission); }
            public double textScale() {
                double chosen = preferences.getDouble("players." + player.getUniqueId(), loaded.settings().defaultTextScale());
                return loaded.settings().textScales().contains(chosen) ? chosen : loaded.settings().defaultTextScale();
            }
            public double canvasScale() { return CanvasScale.selected(preferences, player.getUniqueId()); }
        };
    }
    static final Context PREVIEW = new Context() {
        public String expand(String text) { return text.replace("{player}", "Player")
            .replace("{server}", "Preview").replace("{uuid}", "preview"); }
        public Icon item(int slot) { return null; }
        public boolean permitted(String permission) { return true; }
    };
    CompletableFuture<ReloadEngine.Result> prepareResources() { return reloadEngine.prepare(ReloadEngine.Mode.RESOURCE); }
    CompletableFuture<ReloadEngine.Result> prepareAll() { return reloadEngine.prepare(); }
    ReloadEngine.Snapshot loadedResources() { return loaded; }
    void retainPack(Object consumer, ReloadEngine.Snapshot snapshot) { reloadEngine.retainPack(consumer, snapshot); }
    void applyEditorResources(ReloadEngine.Result result) {
        if (publishResources(result) && result.resourcesChanged() && loaded.settings().autoCraftEngine()
            && Bukkit.getPluginManager().isPluginEnabled("CraftEngine"))
            Bukkit.dispatchCommand(Bukkit.getConsoleSender(), "ce reload all");
    }
    void showEditorPreview(Player player, Component component, int bodyWidth) {
        closeFromApi(player);
        player.showDialog(Dialog.create(factory -> factory.empty()
            .base(DialogBase.builder(Component.text("DialogMenu preview · F8 to edit")).pause(false)
                .canCloseWithEscape(true).afterAction(DialogBase.DialogAfterAction.NONE)
                .body(List.of(DialogBody.plainMessage(component, bodyWidth))).build())
            .type(DialogType.dialogList(RegistrySet.keySet(RegistryKey.DIALOG, List.of())).build())));
    }
    boolean publishResources(ReloadEngine.Result result) {
        if (loaded != null && loaded.generation() >= result.snapshot().generation()) return false;
        long start = System.nanoTime();
        for (UUID id : List.copyOf(views.keySet())) {
            View view = views.get(id);
            if (view == null || !result.menus().isEmpty() && !result.menus().contains(view.document().menu().id())) continue;
            Player player = Bukkit.getPlayer(id);
            if (player != null) close(player, Reason.RELOAD);
            else { views.remove(id); gestures.cancel(id); tokens.remove(id); }
        }
        if (result.menus().isEmpty()) application.clear();
        else application.clear(result.menus());
        loaded = result.snapshot();
        renderer = loaded.renderer();
        fonts = loaded.fonts();
        canvas = new SpriteCanvas();
        width = loaded.settings().width();
        maxPixels = loaded.settings().maxPixels();
        store.publish(loaded.documents());
        getLogger().info("Dialog prepared " + store.ids().size() + " layouts in " + loaded.buildMillis()
            + "ms off-thread; main-thread publish=" + (System.nanoTime() - start) / 1_000_000.0 + "ms");
        return true;
    }
    private void requestReload(CommandSender sender, ReloadEngine.Request request) {
        if (reloadRequested) { sender.sendMessage("Dialog正在重载。"); return; }
        reloadRequested = true;
        reloadEngine.prepare(request).whenComplete((result, error) -> {
            if (!isEnabled()) return;
            Bukkit.getScheduler().runTask(this, () -> {
                reloadRequested = false;
                if (error != null) { failure(sender, unwrap(error)); return; }
                boolean changed = publishResources(result);
                sender.sendMessage(!result.changed() ? "Dialog未变化，复用现有资源。"
                    : request.menu() != null ? "Dialog菜单 " + request.menu() + " 已重载，材质包未修改。"
                    : result.resourcesChanged() ? "Dialog资源已异步重载。"
                    : "Dialog布局已重载，材质包未修改。");
                if (changed && result.resourcesChanged() && loaded.settings().autoCraftEngine()
                    && Bukkit.getPluginManager().isPluginEnabled("CraftEngine"))
                    Bukkit.dispatchCommand(Bukkit.getConsoleSender(), "ce reload all");
            });
        });
    }
    private static Exception unwrap(Throwable error) {
        while (error instanceof CompletionException || error instanceof ExecutionException) error = error.getCause();
        return error instanceof Exception e ? e : new RuntimeException(error);
    }
    private static boolean allowed(Player player, String permission) {
        return permission.isBlank() || player.hasPermission(permission);
    }
    private boolean open(Player player, String id, int targetWidth, List<String> history) throws Exception {
        if (loaded == null) throw new IllegalStateException("Dialog正在准备资源，请稍后重试");
        if (!player.hasPermission("dialogmenu.use")) {
            player.sendMessage("§c没有打开此界面的权限。");
            return false;
        }
        var doc = store.get(id);
        if (doc == null) throw new IllegalArgumentException("布局不存在：" + id);
        if (!player.hasPermission("dialogmenu.use") || !allowed(player, doc.menu().permission())) {
            player.sendMessage("§c没有打开此界面的权限。");
            return false;
        }
        if (doc.template() != null) return application.open(player, doc, targetWidth, history);
        View next = new View(doc, targetWidth, List.copyOf(history), UUID.randomUUID());
        present(player, next);
        application.remove(player);
        View previous = views.put(player.getUniqueId(), next);
        if (previous != null) closed(player, previous, Reason.REPLACED);
        lifecycle(player, doc.open());
        return true;
    }
    public void showApplication(Player player, LayoutStore.Document document, int targetWidth, List<String> history) throws Exception {
        if (!player.hasPermission("dialogmenu.use") || !allowed(player, document.menu().permission())) { close(player); return; }
        View previous = views.get(player.getUniqueId());
        boolean refresh = previous != null && previous.document.menu().id().equals(document.menu().id());
        View next = new View(document, targetWidth, List.copyOf(history),
            refresh ? previous.token : UUID.randomUUID());
        present(player, next);
        views.put(player.getUniqueId(), next);
        if (!refresh) {
            if (previous != null) closed(player, previous, Reason.REPLACED);
            lifecycle(player, document.open());
        }
    }
    public void closeApplication(Player player) { close(player); }
    public void later(Runnable action) { Bukkit.getScheduler().runTaskLater(this, action, 1); }
    private void present(Player player, View view) throws Exception {
        try (var ignored = application.frame(player)) { presentFrame(player, view); }
    }
    private void presentFrame(Player player, View view) throws Exception {
        UUID id = player.getUniqueId();
        Context context = context(player);
        var pending = gestures.pending(id);
        if (pending != null) {
            // 数据可以刷新，但此处替换对话框会吞掉尚在途中的第二次点击。
            if (player.hasPermission("dialogmenu.use")
                && gestures.hold(id, view.token, view.document.menu(), context)) return;
            gestures.cancel(id);
            tokens.remove(id);
        }
        var frame = renderer.drawRuntime(view.document.menu(), context, view.width, maxPixels);
        var page = new ActionTokens.Page();
        var output = canvas.encode(view.document.menu(), frame, context, fonts,
            region -> {
                var target = ClickGestures.Target.of(view.token, view.document.menu(), region, context);
                return page.bind(() -> activate(player, target), target.twice().isEmpty() ? null : target);
            });
        Component body = output.component();
        for (LayoutStore.FlowRow row : view.document.flowRows()) {
            body = body.append(Component.newline());
            if (row.id().isBlank()) body = body.append(Component.text(row.text()));
            else body = body.append(Component.text(row.text()).clickEvent(page.bind(() -> {
                try { application.entry(player, row.collection() + " " + row.id()); }
                catch (Exception error) { failure(player, error); close(player); }
            })));
        }
        Component content = body;
        boolean escape = view.document.menu().closeWithEscape() == null ? loaded.settings().escape()
            : view.document.menu().closeWithEscape();
        var dialogType = DialogType.dialogList(RegistrySet.keySet(RegistryKey.DIALOG, List.of()));
        if (escape) dialogType.exitAction(ActionButton.create(Component.text("关闭"), null, 150,
            DialogAction.staticAction(page.bind(() -> close(player)))));
        Dialog dialog = Dialog.create(factory -> factory.empty()
            .base(DialogBase.builder(Component.empty()).pause(false).canCloseWithEscape(escape)
                .afterAction(DialogBase.DialogAfterAction.NONE)
                .body(List.of(DialogBody.plainMessage(content, output.bodyWidth()))).build())
            .type(dialogType.build()));
        player.showDialog(dialog);
        tokens.publish(player.getUniqueId(), page);
    }
    private void activate(Player player, ClickGestures.Target target) {
        UUID id = player.getUniqueId();
        if (target.twice().isEmpty()) {
            gestures.cancel(id);
            dispatch(player, target, target.single());
        } else gestures.click(id, target,
            () -> dispatch(player, target, target.single()), () -> dispatch(player, target, target.twice()));
    }
    private void dispatch(Player player, ClickGestures.Target target, List<String> actions) {
        UUID id = player.getUniqueId();
        tokens.remove(id);
        View view = views.get(id);
        if (view == null || !player.isOnline() || application.suspended(player)) return;
        try {
            if (!player.hasPermission("dialogmenu.use") || !allowed(player, view.document.menu().permission())) {
                close(player);
                return;
            }
            boolean present;
            try (var ignored = application.frame(player)) {
                present = target.present(view.token, view.document.menu(), context(player));
            }
            if (present) executeActions(player, actions, false);
            else if (!allowed(player, target.permission())) player.sendMessage("§c没有操作权限。");
            if (views.get(id) == view && !application.suspended(player)) {
                if (application.active(player)) application.refresh(player);
                else present(player, view);
            }
        } catch (Exception error) { failure(player, error); close(player); }
    }
    public void executeActions(Player player, List<String> actions) {
        executeActions(player, actions, true);
    }
    private void executeActions(Player player, List<String> actions, boolean expand) {
        View view = views.get(player.getUniqueId());
        if (view == null) return;
        for (String action : actions) {
            if (views.get(player.getUniqueId()) != view || application.suspended(player)) break;
            String[] parts = action.split(":", 2);
            String kind = parts[0].strip();
            String value = parts.length == 2 ? parts[1].strip() : "";
            if (expand) value = context(player).expand(value);
            try {
                switch (kind) {
                    case "toggle", "set", "notice" -> application.state(player, kind, value);
                    case "entry" -> application.entry(player, value);
                    case "callback" -> application.callback(player, value);
                    case "tell" -> player.sendMessage(SpriteCanvas.legacy(value));
                    case "sound" -> play(player, value);
                    case "close" -> close(player);
                    case "return" -> { return; }
                    case "open" -> {
                        var history = new ArrayList<>(view.history);
                        if (history.size() >= 64) history.removeFirst();
                        history.add(view.document.menu().id());
                        open(player, value, view.width, history);
                    }
                    case "back" -> {
                        if (view.history.isEmpty()) close(player);
                        else {
                            var history = new ArrayList<>(view.history);
                            String id = history.removeLast();
                            open(player, id, view.width, history);
                        }
                    }
                    case "command" -> { close(player); player.chat("/" + value.replaceFirst("^/", "")); }
                    case "command-in-place" -> player.performCommand(value.replaceFirst("^/", ""));
                    case "console" -> {
                        close(player);
                        Bukkit.dispatchCommand(Bukkit.getConsoleSender(), value.replaceFirst("^/", ""));
                    }
                    default -> throw new IllegalArgumentException("动作尚不支持：" + kind);
                }
            } catch (Exception error) {
                failure(player, error);
                return;
            }
        }
    }
    private void lifecycle(Player player, List<String> actions) {
        for (String action : actions) {
            String[] parts = action.split(":", 2);
            String value = context(player).expand(parts[1].strip());
            if (parts[0].strip().equals("tell")) player.sendMessage(SpriteCanvas.legacy(value));
            else play(player, value);
        }
    }
    private void play(Player player, String value) {
        var sound = LayoutStore.sound(value);
        player.playSound(player.getLocation(), sound.key(), sound.volume(), sound.pitch());
    }
    private void closed(Player player, View view, Reason reason) {
        lifecycle(player, view.document.close());
        Bukkit.getPluginManager().callEvent(new DialogMenuClosedEvent(
            player, view.document.menu().id(), view.token, reason));
    }
    private void close(Player player) { close(player, Reason.CLOSED); }
    private void close(Player player, Reason reason) {
        View view = views.remove(player.getUniqueId());
        gestures.cancel(player.getUniqueId());
        tokens.remove(player.getUniqueId());
        DialogCompat.close(player);
        application.remove(player, reason);
        if (view != null) closed(player, view, reason);
    }
    @EventHandler public void click(PlayerCustomClickEvent event) {
        if (!event.getIdentifier().namespace().equals("dialogmenu")
            || !(event.getCommonConnection() instanceof PlayerGameConnection connection)) return;
        Runnable execute = () -> {
            Player player = connection.getPlayer();
            if (!player.isOnline()) return;
            Consumer<DialogResponseView> action = tokens.takeResponse(player.getUniqueId(), event.getIdentifier().value());
            if (action == null) return;
            try {
                action.accept(event.getDialogResponseView());
            } catch (Exception error) { failure(player, error); close(player); }
        };
        if (Bukkit.isPrimaryThread()) execute.run(); else Bukkit.getScheduler().runTask(this, execute);
    }
    @EventHandler public void quit(PlayerQuitEvent event) {
        close(event.getPlayer(), Reason.PLAYER_QUIT);
    }
    @EventHandler public void ownerDisabled(PluginDisableEvent event) {
        if (event.getPlugin() != this) application.ownerDisabled(event.getPlugin());
    }
    @EventHandler public void inventory(InventoryOpenEvent event) {
        if (event.getPlayer() instanceof Player player
            && (views.containsKey(player.getUniqueId()) || application.active(player))) close(player, Reason.INVENTORY_OPENED);
    }
    @EventHandler public void world(PlayerChangedWorldEvent event) {
        Player player = event.getPlayer();
        if (views.containsKey(player.getUniqueId()) || application.active(player)) close(player, Reason.WORLD_CHANGED);
    }
    private void exportResources(CommandSender sender) {
        if (loaded == null) throw new IllegalStateException("Dialog正在准备资源");
        reloadEngine.export(loaded).whenComplete((ignored, error) -> {
            if (isEnabled()) Bukkit.getScheduler().runTask(this, () -> {
                if (error != null) failure(sender, unwrap(error));
                else sender.sendMessage("已导出resourcepack；CE合包会自动读取，无需手工复制。");
            });
        });
    }
    private void textScale(Player player, String value) throws Exception {
        if (loaded == null) throw new IllegalStateException("Dialog正在准备资源");
        double scale = Double.parseDouble(value.replaceFirst("[xX]$", ""));
        if (!loaded.settings().textScales().contains(scale))
            throw new IllegalArgumentException("可选文字倍率：" + loaded.settings().textScales());
        String key = "players." + player.getUniqueId();
        Object previous = preferences.get(key);
        preferences.set(key, scale);
        try {
            View view = views.get(player.getUniqueId());
            if (view != null && !application.suspended(player)) present(player, view);
        } catch (Exception error) { preferences.set(key, previous); throw error; }
        String text = preferences.saveToString();
        reloadEngine.savePreferences(text).whenComplete((ignored, error) -> {
            if (isEnabled()) Bukkit.getScheduler().runTask(this, () -> {
                if (error != null) failure(player, unwrap(error));
                else player.sendMessage("文字倍率：" + scale);
            });
        });
    }
    private void canvasScale(Player player, String value) throws Exception {
        if (loaded == null) throw new IllegalStateException("Dialog正在准备资源");
        double scale = CanvasScale.select(preferences, player.getUniqueId(), value, () -> {
            View view = views.get(player.getUniqueId());
            if (view == null) return;
            Context active = context(player);
            var frame = renderer.draw(view.document.menu(), active, view.width, maxPixels);
            new SpriteCanvas().encode(view.document.menu(), frame, active, fonts, region -> null);
            if (!application.suspended(player)) present(player, view);
        });
        reloadEngine.savePreferences(preferences.saveToString()).whenComplete((ignored, error) -> {
            if (isEnabled()) Bukkit.getScheduler().runTask(this, () -> {
                if (error != null) failure(player, unwrap(error));
                else player.sendMessage("画布倍率：" + scale);
            });
        });
    }
    @Override public boolean onCommand(CommandSender sender, Command command, String label, String[] args) {
        try {
            String action = args.length == 0 ? "open" : args[0].toLowerCase(Locale.ROOT);
            if (Set.of("reload", "resources", "status", "edit").contains(action)
                && !sender.hasPermission("dialogmenu.admin")) {
                sender.sendMessage("§c仅管理员可执行此操作。");
                return true;
            }
            switch (action) {
                case "edit" -> {
                    if (!(sender instanceof Player player)) throw new IllegalArgumentException("此命令需要玩家");
                    if (args.length > 2) throw new IllegalArgumentException("/dialogmenu edit <menu>");
                    editor.open(player, args.length == 2 ? args[1] : "");
                }
                case "list" -> sender.sendMessage("Dialog: " + String.join(", ", layoutIds()));
                case "status" -> sender.sendMessage("Dialog standalone layouts=" + store.ids().size()
                    + " active=" + views.size() + " renderer=" + RenderingEdition.ID + "");
                case "reload" -> requestReload(sender, ReloadEngine.Request.command(args));
                case "resources" -> exportResources(sender);
                case "textscale" -> {
                    if (!(sender instanceof Player player)) throw new IllegalArgumentException("此命令需要玩家");
                    if (loaded == null) throw new IllegalStateException("Dialog正在准备资源");
                    if (args.length < 2) player.sendMessage("可选文字倍率：" + loaded.settings().textScales());
                    else textScale(player, args[1]);
                }
                case "scale" -> {
                    if (!(sender instanceof Player player)) throw new IllegalArgumentException("此命令需要玩家");
                    if (args.length > 2) throw new IllegalArgumentException("/dialogmenu scale <0.75|1>");
                    if (args.length == 1) player.sendMessage("画布倍率："
                        + CanvasScale.selected(preferences, player.getUniqueId()) + "；可选：0.75、1");
                    else canvasScale(player, args[1]);
                }
                case "open" -> {
                    if (!(sender instanceof Player player)) throw new IllegalArgumentException("此命令需要玩家");
                    if (args.length > 3) throw new IllegalArgumentException("/dialogmenu open <id> [small|medium|large]");
                    int size = args.length > 2 ? switch (args[2].toLowerCase(Locale.ROOT)) {
                        case "small", "小" -> 300;
                        case "medium", "中" -> 360;
                        case "large", "大" -> 420;
                        default -> throw new IllegalArgumentException("尺寸请选择small/medium/large");
                    } : width;
                    open(player, args.length > 1 ? args[1] : loaded == null ? "layout-demo" : loaded.settings().defaultLayout(),
                        size, List.of());
                }
                case "close" -> {
                    if (!(sender instanceof Player player)) throw new IllegalArgumentException("此命令需要玩家");
                    close(player);
                }
                default -> sender.sendMessage("/dialogmenu open <id> [small|medium|large] | edit [menu] | scale <0.75|1> | textscale <倍率> | list | reload [yaml|resource|all|菜单名] | resources | close");
            }
        } catch (Exception error) { failure(sender, error); }
        return true;
    }
    @Override public List<String> onTabComplete(CommandSender sender, Command command, String alias, String[] args) {
        List<String> choices = args.length == 1 ? List.of("open", "list", "close", "reload", "resources", "status", "textscale", "scale", "edit")
            : args.length == 2 && args[0].equalsIgnoreCase("reload")
                ? java.util.stream.Stream.concat(java.util.stream.Stream.of("yaml", "resource", "all"),
                    store.documents().values().stream().flatMap(doc ->
                        java.util.stream.Stream.of(doc.menu().id(), LayoutStore.source(doc)))).distinct().toList()
            : args.length == 2 && args[0].equals("edit") ? layoutIds()
            : args.length == 2 && args[0].equals("textscale") && loaded != null
                ? loaded.settings().textScales().stream().map(String::valueOf).toList()
            : args.length == 2 && args[0].equals("scale") ? List.of("0.75", "1")
            : args.length == 2 && args[0].equals("open") ? layoutIds()
            : args.length == 3 && args[0].equals("open") ? List.of("small", "medium", "large") : List.of();
        String prefix = args.length == 0 ? "" : args[args.length - 1].toLowerCase(Locale.ROOT);
        return choices.stream().filter(v -> v.startsWith(prefix)).toList();
    }
    private List<String> layoutIds() {
        return new ArrayList<>(store.ids());
    }
    private void failure(CommandSender sender, Exception error) {
        sender.sendMessage("§cDialog：" + Objects.toString(error.getMessage(), error.getClass().getSimpleName()));
        getLogger().log(java.util.logging.Level.WARNING, "Dialog operation failed", error);
    }
}
