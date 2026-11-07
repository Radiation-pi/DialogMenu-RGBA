package dev.dialogmenu;

import java.io.*;
import java.nio.file.*;
import java.nio.file.attribute.FileTime;
import java.util.*;
import java.util.concurrent.*;
import java.util.zip.*;
import org.bukkit.configuration.file.YamlConfiguration;
import static dev.dialogmenu.MenuModel.*;

/** 文件解析和资源包准备不接触运行中的 Bukkit 或玩家状态。 */
final class ReloadEngine implements AutoCloseable {
    enum Mode {
        YAML, RESOURCE, ALL;
        static Mode command(String[] args) {
            if (args.length == 1) return YAML;
            if (args.length == 2) for (Mode mode : values())
                if (mode.name().equalsIgnoreCase(args[1])) return mode;
            throw new IllegalArgumentException("/dialogmenu reload [yaml|resource|all]");
        }
    }
    record Settings(int width, int maxPixels, String defaultLayout, boolean escape,
                    List<Double> textScales, double defaultTextScale, boolean autoCraftEngine,
                    Path resourceRoot) {}
    record Stamp(long size, FileTime modified) {}
    record Snapshot(Map<String, LayoutStore.Document> documents, VanillaAssets assets, MenuRenderer renderer,
                    FontPack fonts, Settings settings, Path pack, Map<Path, Stamp> inputs,
                    Map<Path, Stamp> resourceInputs, long buildMillis,
                    String buildThread, long generation) {}
    record Result(Snapshot snapshot, boolean changed, boolean resourcesChanged) {
        Result(Snapshot snapshot, boolean changed) { this(snapshot, changed, changed); }
    }
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

    ReloadEngine(Path data, Context preview) {
        this.data = data;
        this.preview = preview;
        this.cache = new PackCache(data.resolve("pack-cache"));
    }
    void retainPack(Object consumer, Snapshot snapshot) { cache.retain(consumer, snapshot.pack()); }

    CompletableFuture<Result> prepare() { return prepare(Mode.ALL); }
    CompletableFuture<Result> prepare(Mode mode) {
        return CompletableFuture.supplyAsync(() -> {
            try { return mode == Mode.YAML ? layouts() : build(mode); }
            catch (Exception error) { throw new CompletionException(error); }
        }, worker);
    }
    private Result layouts() throws Exception {
        long start = System.nanoTime();
        if (latest == null) throw new IllegalStateException("尚无资源，请执行 /dialogmenu reload all");
        Map<Path, Stamp> current = yamlInputs();
        if (latest.inputs().equals(current)) return new Result(latest, false, false);
        Settings settings = settings();
        if (!settings.resourceRoot().equals(latest.settings().resourceRoot())
            || !latest.settings().textScales().containsAll(settings.textScales()))
            throw new IllegalArgumentException("资源目录或字体倍率范围已变化，请执行 /dialogmenu reload all；旧布局保留");
        var documents = new LayoutStore(data.resolve("layouts")).load();
        if (!documents.containsKey(settings.defaultLayout()))
            throw new IllegalArgumentException("default-layout不存在");
        try {
            validate(MenuTemplate.catalog(documents.values()), latest.renderer(), latest.fonts(), settings);
        } catch (IllegalArgumentException error) {
            throw new IllegalArgumentException("布局不能复用现有资源：" + error.getMessage()
                + "；旧布局保留，需要新字形时执行 /dialogmenu reload all", error);
        }
        Snapshot next = new Snapshot(documents, latest.assets(), latest.renderer(), latest.fonts(), settings,
            latest.pack(), current, latest.resourceInputs(),
            TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - start), Thread.currentThread().getName(), ++generation);
        cache.retain(next, next.pack());
        latest = next;
        return new Result(next, true, false);
    }
    private Settings settings() throws Exception {
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
        return new Settings(width, maxPixels, yaml.getString("default-layout", "layout-demo"),
            yaml.getBoolean("close-with-escape", false), List.copyOf(scales), defaultScale,
            yaml.getBoolean("craftengine-auto-reload", true),
            data.resolve(yaml.getString("craftengine-resources", "../CraftEngine/resources")).normalize());
    }
    private Result build(Mode mode) throws Exception {
        long start = System.nanoTime();
        cache.prune();
        // 仅资源重载不读取磁盘上的待应用布局或配置。
        boolean all = mode == Mode.ALL || latest == null;
        Map<Path, Stamp> yamlInputs = all ? yamlInputs() : latest.inputs();
        Settings settings = all ? settings() : latest.settings();
        if (latest != null && latest.inputs().equals(yamlInputs)
            && latest.resourceInputs().equals(resourceInputs(latest.assets(), settings)))
            return new Result(latest, false, false);
        var documents = all ? new LayoutStore(data.resolve("layouts")).load() : latest.documents();
        if (!documents.containsKey(settings.defaultLayout())) throw new IllegalArgumentException("default-layout不存在");
        var assets = new VanillaAssets(data.resolve("images"), settings.resourceRoot());
        var renderer = new MenuRenderer(assets);
        List<LayoutStore.Document> catalog = MenuTemplate.catalog(documents.values());
        var widths = new TreeSet<>(List.of(300, 360, 420, settings.width()));
        var fonts = FontPack.create(catalog, renderer, preview, widths, settings.maxPixels(),
            MenuTemplate.characters(documents.values()), settings.textScales(), CanvasScale.OPTIONS);
        validate(catalog, renderer, fonts, settings);
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
        Snapshot next = new Snapshot(documents, assets, renderer, fonts, settings, zip, yamlInputs,
            resourceInputs(assets, settings), TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - start),
            Thread.currentThread().getName(), ++generation);
        cache.retain(next, zip);
        latest = next;
        return new Result(next, true);
    }
    private void validate(List<LayoutStore.Document> catalog, MenuRenderer renderer, FontPack fonts,
                          Settings settings) throws Exception {
        var widths = new TreeSet<>(List.of(300, 360, 420, settings.width()));
        // 可选倍率在玩家选择时校验，不要求每个布局加载时适配所有倍率。
        for (var doc : catalog) for (int size : widths) {
            var base = MenuTemplate.preview(doc, preview);
            Context active = new Context() {
                public String expand(String text) { return base.expand(text); }
                public Icon item(int slot) { return base.item(slot); }
                public boolean permitted(String permission) { return base.permitted(permission); }
                public double textScale() { return settings.defaultTextScale(); }
            };
            var frame = renderer.draw(doc.menu(), active, size, settings.maxPixels());
            new SpriteCanvas().encode(doc.menu(), frame, active, fonts, region -> null);
        }
    }
    private Map<Path, Stamp> yamlInputs() throws IOException {
        Set<Path> files = new HashSet<>();
        files.add(data.resolve("config.yml"));
        scan(data.resolve("layouts"), files);
        return stamps(files);
    }
    private static void scan(Path root, Set<Path> files) throws IOException {
        if (Files.exists(root)) try (var paths = Files.walk(root.toRealPath())) {
            paths.filter(Files::isRegularFile).forEach(files::add);
        }
    }
    private Map<Path, Stamp> resourceInputs(VanillaAssets assets, Settings settings) throws IOException {
        Set<Path> files = new HashSet<>();
        scan(data.resolve("images"), files);
        Path resources = settings.resourceRoot();
        if (Files.isDirectory(resources)) try (var paths = Files.walk(resources)) {
            paths.filter(p -> p.toString().endsWith(".yml") && Files.isRegularFile(p)).forEach(files::add);
        }
        files.addAll(assets.craftEngine.dependencies());
        return stamps(files);
    }
    private static Map<Path, Stamp> stamps(Set<Path> files) throws IOException {
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
