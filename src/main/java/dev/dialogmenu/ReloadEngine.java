package dev.dialogmenu;

import java.io.*;
import java.nio.file.*;
import java.nio.file.attribute.FileTime;
import java.util.*;
import java.util.concurrent.*;
import java.util.zip.*;
import org.bukkit.configuration.file.YamlConfiguration;
import static dev.dialogmenu.MenuModel.*;

/** File parsing and pack preparation never touch live Bukkit/player state. */
final class ReloadEngine implements AutoCloseable {
    record Settings(int width, int maxPixels, String defaultLayout, boolean escape,
                    List<Double> textScales, double defaultTextScale, boolean autoCraftEngine) {}
    record Stamp(long size, FileTime modified) {}
    record Snapshot(Map<String, LayoutStore.Document> documents, VanillaAssets assets, MenuRenderer renderer,
                    FontPack fonts, Settings settings, Path pack, Map<Path, Stamp> inputs, long buildMillis,
                    String buildThread, long generation) {}
    record Result(Snapshot snapshot, boolean changed) {}
    private final Path data;
    private final Context preview;
    private final PackCache cache;
    private final ExecutorService worker = Executors.newSingleThreadExecutor(r -> {
        Thread thread = new Thread(r, "DialogMenu-reload");
        thread.setDaemon(true);
        return thread;
    });
    private Snapshot latest;
    private long generation;
    private CompletableFuture<Result> pending;

    ReloadEngine(Path data, Context preview) {
        this.data = data;
        this.preview = preview;
        this.cache = new PackCache(data.resolve("pack-cache"));
    }
    void retainPack(Object consumer, Snapshot snapshot) { cache.retain(consumer, snapshot.pack()); }

    synchronized CompletableFuture<Result> prepare() {
        if (pending != null && !pending.isDone()) return pending;
        pending = CompletableFuture.supplyAsync(() -> {
            try { return build(); }
            catch (Exception error) { throw new CompletionException(error); }
        }, worker);
        return pending;
    }
    private Result build() throws Exception {
        long start = System.nanoTime();
        cache.prune();
        if (latest != null && latest.inputs().equals(inputs(latest.assets()))) return new Result(latest, false);
        var yaml = new YamlConfiguration();
        yaml.loadFromString(Files.readString(data.resolve("config.yml")));
        int width = LayoutStore.integer(yaml, "canvas-width", 300, 64, 512);
        int maxPixels = LayoutStore.integer(yaml, "max-canvas-pixels", 131072, 4096, 524288);
        List<Double> scales = new ArrayList<>();
        for (Object value : yaml.getList("text-scales", List.of(1.0))) {
            if (!(value instanceof Number n) || !Double.isFinite(n.doubleValue())
                || n.doubleValue() < .5 || n.doubleValue() > 4)
                throw new IllegalArgumentException("text-scales须为0.5..4的倍率列表");
            if (!scales.contains(n.doubleValue())) scales.add(n.doubleValue());
        }
        double defaultScale = LayoutStore.number(yaml, "default-text-scale", 1);
        if (!scales.contains(defaultScale)) throw new IllegalArgumentException("default-text-scale须在text-scales中");
        Settings settings = new Settings(width, maxPixels, yaml.getString("default-layout", "layout-demo"),
            yaml.getBoolean("close-with-escape", false), List.copyOf(scales), defaultScale,
            yaml.getBoolean("craftengine-auto-reload", true));
        var documents = new LayoutStore(data.resolve("layouts")).load();
        if (!documents.containsKey(settings.defaultLayout())) throw new IllegalArgumentException("default-layout不存在");
        Path resources = data.resolve(yaml.getString("craftengine-resources", "../CraftEngine/resources")).normalize();
        var assets = new VanillaAssets(data.resolve("images"), resources);
        var renderer = new MenuRenderer(assets);
        List<LayoutStore.Document> catalog = MenuTemplate.catalog(documents.values());
        var widths = new TreeSet<>(List.of(300, 360, 420, width));
        var fonts = FontPack.create(catalog, renderer, preview, widths, maxPixels,
            MenuTemplate.characters(documents.values()), scales, CanvasScale.OPTIONS);
        // Optional scales are validated when selected, not required for every layout to load.
        for (var doc : catalog) for (int size : widths) {
            var base = MenuTemplate.preview(doc, preview);
            Context active = new Context() {
                public String expand(String text) { return base.expand(text); }
                public Icon item(int slot) { return base.item(slot); }
                public boolean permitted(String permission) { return base.permitted(permission); }
                public double textScale() { return settings.defaultTextScale(); }
            };
            var frame = renderer.draw(doc.menu(), active, size, maxPixels);
            new SpriteCanvas().encode(doc.menu(), frame, active, fonts, region -> null);
        }
        Path exported = data.resolve("resourcepack");
        fonts.write(exported);
        Path packDirectory = data.resolve("pack-cache");
        Files.createDirectories(packDirectory);
        Path zip = packDirectory.resolve("dialog-" + UUID.randomUUID() + ".zip");
        try (var output = new ZipOutputStream(Files.newOutputStream(zip));
             var files = Files.walk(exported)) {
            for (Path path : files.filter(Files::isRegularFile).sorted().toList()) {
                output.putNextEntry(new ZipEntry(exported.relativize(path).toString().replace('\\', '/')));
                Files.copy(path, output);
                output.closeEntry();
            }
        } catch (Exception error) {
            Files.deleteIfExists(zip);
            throw error;
        }
        Snapshot next = new Snapshot(documents, assets, renderer, fonts, settings, zip, inputs(assets),
            TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - start), Thread.currentThread().getName(), ++generation);
        cache.retain(next, zip);
        latest = next;
        return new Result(next, true);
    }
    private Map<Path, Stamp> inputs(VanillaAssets assets) throws IOException {
        Set<Path> files = new HashSet<>();
        files.add(data.resolve("config.yml"));
        for (String directory : List.of("layouts", "images")) {
            Path root = data.resolve(directory);
            if (Files.exists(root)) try (var paths = Files.walk(root)) {
                paths.filter(Files::isRegularFile).forEach(files::add);
            }
        }
        Path resources;
        var config = new YamlConfiguration();
        try { config.loadFromString(Files.readString(data.resolve("config.yml"))); }
        catch (Exception error) { throw new IOException("无法读取config.yml", error); }
        resources = data.resolve(config.getString("craftengine-resources", "../CraftEngine/resources")).normalize();
        if (Files.isDirectory(resources)) try (var paths = Files.walk(resources)) {
            paths.filter(p -> p.toString().endsWith(".yml") && Files.isRegularFile(p)).forEach(files::add);
        }
        files.addAll(assets.craftEngine.dependencies());
        Map<Path, Stamp> result = new HashMap<>();
        for (Path file : files) if (Files.isRegularFile(file))
            result.put(file, new Stamp(Files.size(file), Files.getLastModifiedTime(file)));
        return Map.copyOf(result);
    }
    CompletableFuture<Void> export(Snapshot snapshot) {
        return CompletableFuture.runAsync(() -> {
            try { snapshot.fonts().write(data.resolve("resourcepack")); }
            catch (IOException error) { throw new CompletionException(error); }
        }, worker);
    }
    CompletableFuture<Void> savePreferences(String yaml) {
        return CompletableFuture.runAsync(() -> {
            try { Files.writeString(data.resolve("player-text-scales.yml"), yaml); }
            catch (IOException error) { throw new CompletionException(error); }
        }, worker);
    }
    @Override public void close() { worker.shutdownNow(); }
}
