package dev.dialogmenu;

import com.google.gson.*;
import dev.dialogmenu.editor.EditorWire;
import java.io.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.*;
import java.util.concurrent.*;
import java.util.zip.*;
import javax.imageio.ImageIO;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.serializer.gson.GsonComponentSerializer;
import org.bukkit.Bukkit;
import org.bukkit.entity.Player;
import org.bukkit.event.*;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.plugin.messaging.PluginMessageListener;
import static dev.dialogmenu.MenuModel.*;

final class EditorBridge implements PluginMessageListener, Listener, AutoCloseable {
    private record Preview(UUID token, ReloadEngine.Snapshot snapshot, String page, Path root, long created) {}
    private final DialogMenuPlugin plugin;
    private final EditorFiles files;
    private final ExecutorService worker = Executors.newSingleThreadExecutor(r -> {
        var thread = new Thread(r, "DialogMenu-editor"); thread.setDaemon(true); return thread;
    });
    private final Map<UUID, EditorWire.Receiver> receivers = new HashMap<>();
    private final Set<UUID> busy = ConcurrentHashMap.newKeySet();
    private final Map<UUID, Preview> previews = new ConcurrentHashMap<>();
    private volatile boolean closed;

    EditorBridge(DialogMenuPlugin plugin) throws IOException {
        this.plugin = plugin;
        files = new EditorFiles(plugin.getDataFolder().toPath());
        var messenger = Bukkit.getMessenger();
        messenger.registerIncomingPluginChannel(plugin, EditorWire.CHANNEL, this);
        messenger.registerOutgoingPluginChannel(plugin, EditorWire.CHANNEL);
        Bukkit.getPluginManager().registerEvents(this, plugin);
    }
    void open(Player player, String menu) {
        if (!permitted(player)) { player.sendMessage("§c没有菜单编辑权限。"); return; }
        var message = new JsonObject(); message.addProperty("event", "open");
        if (menu != null && !menu.isBlank()) message.addProperty("menu", menu);
        send(player, message);
        player.sendMessage("§7编辑器需要安装 DialogMenu Editor Mod；也可按 F8 打开。");
    }
    private static boolean permitted(Player player) { return player.hasPermission("dialogmenu.admin"); }
    @Override public void onPluginMessageReceived(String channel, Player player, byte[] frame) {
        if (closed || !channel.equals(EditorWire.CHANNEL) || !permitted(player)) return;
        UUID owner = player.getUniqueId();
        try {
            byte[] bytes = receivers.computeIfAbsent(owner, unused -> new EditorWire.Receiver())
                .accept(frame, System.currentTimeMillis());
            if (bytes == null) return;
            if (bytes.length > EditorFiles.MAX_YAML * 4) throw new IOException("Editor request is too large");
            JsonObject request = JsonParser.parseString(new String(bytes, StandardCharsets.UTF_8)).getAsJsonObject();
            long requestId = request.get("request").getAsLong();
            String operation = request.get("op").getAsString();
            if (!busy.add(owner)) { error(player, requestId, "An editor operation is still running"); return; }
            worker.execute(() -> {
                try {
                    if (closed) return;
                    JsonObject response = process(owner, operation, request);
                    response.addProperty("request", requestId);
                    main(() -> {
                        if (player.isOnline() && permitted(player)) send(player, response);
                    });
                } catch (Exception failure) {
                    main(() -> error(player, requestId, Objects.toString(failure.getMessage(), "Editor operation failed")));
                } finally { busy.remove(owner); }
            });
        } catch (Exception failure) {
            receivers.remove(owner);
            error(player, -1, Objects.toString(failure.getMessage(), "Invalid editor request"));
        }
    }
    private JsonObject process(UUID owner, String operation, JsonObject request) throws Exception {
        JsonObject response = new JsonObject();
        response.addProperty("op", operation);
        switch (operation) {
            case "list" -> response.add("menus", new Gson().toJsonTree(files.list()));
            case "read", "original" -> document(response,
                files.read(owner, request.get("menu").getAsString(), operation.equals("original")));
            case "save" -> document(response, files.save(owner, value(request, "menu"), value(request, "base"),
                value(request, "revision"), request.getAsJsonObject("tree")));
            case "discard" -> document(response, files.discard(owner, value(request, "menu"),
                value(request, "revision")));
            case "assets" -> {
                var assets = new VanillaAssets(plugin.getDataFolder().toPath().resolve("images"), resourceRoot());
                response.add("assets", new Gson().toJsonTree(assets.sources()));
            }
            case "asset" -> {
                String source = value(request, "source");
                var assets = new VanillaAssets(plugin.getDataFolder().toPath().resolve("images"), resourceRoot());
                var bytes = new ByteArrayOutputStream();
                ImageIO.write(assets.image(source), "png", bytes);
                if (bytes.size() > 1024 * 1024) throw new IOException("Image exceeds 1 MiB");
                response.addProperty("source", source);
                response.addProperty("png", Base64.getEncoder().encodeToString(bytes.toByteArray()));
            }
            case "preview" -> {
                String id = value(request, "menu"), page = value(request, "page");
                var draft = files.read(owner, id, false);
                if (draft.revision().isBlank() || !draft.revision().equals(value(request, "revision")))
                    throw new IOException("Save this draft before previewing");
                Preview old = previews.remove(owner);
                if (old != null) EditorFiles.remove(old.root());
                Path root = files.stage(draft);
                try (var engine = new ReloadEngine(root, DialogMenuPlugin.PREVIEW)) {
                    var snapshot = engine.prepare().get(90, TimeUnit.SECONDS).snapshot();
                    if (!snapshot.documents().containsKey(page)) throw new IOException("Preview page does not exist");
                    if (!belongs(id, page, draft.tree())) throw new IOException("Page is not in this menu");
                    byte[] pack = previewPack(snapshot.pack());
                    UUID token = UUID.randomUUID();
                    previews.put(owner, new Preview(token, snapshot, page, root, System.currentTimeMillis()));
                    response.addProperty("token", token.toString());
                    response.addProperty("pack", Base64.getEncoder().encodeToString(pack));
                } catch (Exception error) { EditorFiles.remove(root); throw error; }
            }
            case "preview-ready" -> {
                Preview preview = previews.get(owner);
                if (preview == null || !preview.token().toString().equals(value(request, "token"))
                    || System.currentTimeMillis() - preview.created() > 300_000)
                    throw new IOException("Preview expired; generate it again");
                main(() -> {
                    Player player = Bukkit.getPlayer(owner);
                    if (player == null || !permitted(player)) return;
                    try {
                        var doc = preview.snapshot().documents().get(preview.page());
                        Context context = MenuTemplate.preview(doc, DialogMenuPlugin.PREVIEW);
                        var frame = preview.snapshot().renderer().draw(doc.menu(), context,
                            preview.snapshot().settings().width(), preview.snapshot().settings().maxPixels());
                        var output = new SpriteCanvas().encode(doc.menu(), frame, context, preview.snapshot().fonts(), r -> null);
                        JsonElement component = JsonParser.parseString(GsonComponentSerializer.gson().serialize(output.component()));
                        previewFonts(component);
                        plugin.showEditorPreview(player, GsonComponentSerializer.gson().deserialize(component.toString()),
                            output.bodyWidth());
                    } catch (Exception error) { throw new CompletionException(error); }
                }).get();
            }
            case "apply" -> {
                String id = value(request, "menu");
                var draft = files.read(owner, id, false);
                Path root = files.stage(draft);
                try (var engine = new ReloadEngine(root, DialogMenuPlugin.PREVIEW)) {
                    engine.prepare().get(90, TimeUnit.SECONDS);
                } finally { EditorFiles.remove(root); }
                var commit = files.commit(owner, id, value(request, "base"), value(request, "revision"));
                try {
                    var result = plugin.prepareAll().get(90, TimeUnit.SECONDS);
                    main(() -> plugin.applyEditorResources(result)).get();
                } catch (Exception error) { files.rollback(commit); throw error; }
                files.applied(owner, id);
                document(response, files.read(owner, id, true));
                response.addProperty("backup", commit.backup().getFileName().toString());
            }
            default -> throw new IOException("Unknown editor operation");
        }
        return response;
    }
    private Path resourceRoot() throws Exception {
        Path data = plugin.getDataFolder().toPath().toAbsolutePath();
        var config = EditorFiles.parse(Files.readString(data.resolve("config.yml")));
        return data.resolve(config.getString("craftengine-resources", "../CraftEngine/resources")).normalize();
    }
    private static boolean belongs(String id, String page, JsonObject tree) {
        return tree.has("pages") ? tree.getAsJsonObject("pages").has(page) : id.equals(page);
    }
    private static String value(JsonObject request, String key) {
        if (!request.has(key) || !request.get(key).isJsonPrimitive()) throw new IllegalArgumentException("Missing " + key);
        return request.get(key).getAsString();
    }
    private static void document(JsonObject response, EditorFiles.Draft draft) {
        response.addProperty("menu", draft.id());
        response.addProperty("base", draft.base());
        response.addProperty("revision", draft.revision());
        response.addProperty("yaml", draft.yaml());
        response.add("tree", draft.tree());
    }
    private static void previewFonts(JsonElement element) {
        if (element.isJsonArray()) element.getAsJsonArray().forEach(EditorBridge::previewFonts);
        if (!element.isJsonObject()) return;
        var object = element.getAsJsonObject();
        if (object.has("font") && object.get("font").getAsString().startsWith("dialogmenu:"))
            object.addProperty("font", object.get("font").getAsString().replace("dialogmenu:", "dialogmenu_preview:"));
        object.remove("clickEvent");
        object.remove("click_event");
        for (var entry : object.entrySet()) previewFonts(entry.getValue());
    }
    private static byte[] previewPack(Path path) throws IOException {
        var bytes = new ByteArrayOutputStream();
        try (var input = new ZipInputStream(Files.newInputStream(path)); var output = new ZipOutputStream(bytes)) {
            for (ZipEntry entry; (entry = input.getNextEntry()) != null;) {
                String name = entry.getName().replace("assets/dialogmenu/", "assets/dialogmenu_preview/");
                output.putNextEntry(new ZipEntry(name));
                byte[] data = input.readAllBytes();
                if (name.endsWith(".json"))
                    data = new String(data, StandardCharsets.UTF_8).replace("dialogmenu:", "dialogmenu_preview:")
                        .getBytes(StandardCharsets.UTF_8);
                output.write(data); output.closeEntry();
                if (bytes.size() > EditorWire.MAX / 2) throw new IOException("Preview pack exceeds 4 MiB");
            }
        }
        if (bytes.size() > EditorWire.MAX / 2) throw new IOException("Preview pack exceeds 4 MiB");
        return bytes.toByteArray();
    }
    private CompletableFuture<Void> main(Runnable action) {
        var result = new CompletableFuture<Void>();
        if (closed || !plugin.isEnabled()) { result.completeExceptionally(new IOException("Plugin disabled")); return result; }
        Bukkit.getScheduler().runTask(plugin, () -> {
            try { action.run(); result.complete(null); }
            catch (Exception error) { result.completeExceptionally(error); }
        });
        return result;
    }
    private void send(Player player, JsonObject message) {
        try {
            for (byte[] frame : EditorWire.frames(message.toString().getBytes(StandardCharsets.UTF_8)))
                player.sendPluginMessage(plugin, EditorWire.CHANNEL, frame);
        } catch (IOException error) { player.sendMessage("§c" + error.getMessage()); }
    }
    private void error(Player player, long request, String text) {
        if (!player.isOnline() || !permitted(player)) return;
        var response = new JsonObject();
        response.addProperty("request", request); response.addProperty("error", text); send(player, response);
    }
    @EventHandler public void quit(PlayerQuitEvent event) {
        UUID owner = event.getPlayer().getUniqueId();
        receivers.remove(owner);
        worker.execute(() -> {
            Preview preview = previews.remove(owner);
            if (preview != null) try { EditorFiles.remove(preview.root()); } catch (IOException ignored) {}
        });
    }
    @Override public void close() {
        closed = true;
        Bukkit.getMessenger().unregisterIncomingPluginChannel(plugin, EditorWire.CHANNEL, this);
        Bukkit.getMessenger().unregisterOutgoingPluginChannel(plugin, EditorWire.CHANNEL);
        receivers.clear();
        worker.execute(() -> {
            for (Preview preview : previews.values())
                try { EditorFiles.remove(preview.root()); } catch (IOException ignored) {}
            previews.clear();
        });
        worker.shutdown();
    }
}
