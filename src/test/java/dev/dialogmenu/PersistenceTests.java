package dev.dialogmenu;

import com.google.gson.*;
import java.awt.image.BufferedImage;
import java.io.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.*;
import java.util.concurrent.*;
import java.util.zip.*;
import javax.imageio.ImageIO;
import net.kyori.adventure.text.event.ClickEvent;
import net.kyori.adventure.text.serializer.gson.GsonComponentSerializer;
import static dev.dialogmenu.MenuModel.*;

/** 真正保存/恢复编译结果，比较发给玩家的组件和资源，不替换磁盘或编译器。 */
public final class PersistenceTests {
    private static int checks;
    private static final Context CONTEXT = new Context() {
        public String expand(String text) { return text.replace("{player}", "Player"); }
        public Icon item(int slot) { return null; }
        public boolean permitted(String permission) { return true; }
    };
    private static final String CONFIG = """
        default-layout: test
        text-scales: [1]
        resource-build-cpu-percent: 100
        craftengine-resources: ce
        craftengine-definitions: [demo/configuration/images.yml]
        """;
    private static final String YAML = """
        schema-version: 1
        id: test
        characters: 'ABPlayer'
        pages:
          test:
            canvas: {width: 300, height: 180}
            frontend:
              text:
                type: text
                content: 'A中文'
                offset: {x: 0, y: 20}
                actions: ['tell: TEXT']
                tooltip: Text
              stretched:
                type: text
                content: 'XY汉'
                offset: {x: 0, y: 50}
                scale: {x: 1.5, y: 2}
                actions: ['tell: STRETCH']
              dynamic:
                type: text
                content: '{player}'
                offset: {x: 0, y: -50}
              button:
                type: image
                source: button.png
                width: 48
                height: 24
                offset: {x: 40, y: -20}
                color: '#55AAFF'
                actions: ['tell: BUTTON']
                tooltip: Button
              ce:
                type: image
                source: craftengine:demo/font/sample
                atlas: {columns: 2, rows: 2, column: 0, row: 1}
                width: 16
                height: 16
                offset: {x: -40, y: -20}
              legacy:
                type: image
                source: craftengine:image/example:sample
                width: 16
                height: 16
                offset: {x: -70, y: -20}
          detail:
            canvas: {width: 300, height: 180}
            frontend:
              label: {type: text, content: B, offset: {x: 0, y: 20}}
        """;

    public static void main(String[] args) throws Exception {
        if (args.length > 1 && args[0].equals("restore")) {
            try (var engine = new ReloadEngine(Path.of(args[1]), CONTEXT)) {
                var restored = engine.restore().get(90, TimeUnit.SECONDS);
                Files.writeString(Path.of(args[2]), render(restored.snapshot()));
                System.out.println("RESTORED layouts=" + restored.snapshot().documents().size()
                    + " ms=" + restored.snapshot().buildMillis());
            }
            return;
        }
        Path data = Files.createDirectories(Path.of(args[0]).resolve("persistence-" + UUID.randomUUID()));
        Path layouts = Files.createDirectories(data.resolve("layouts"));
        Path config = data.resolve("config.yml"), menu = layouts.resolve("test.yml");
        Path metadata = data.resolve("pack-cache/compiled.json.gz");
        try (var empty = new ReloadEngine(data, CONTEXT)) {
            rejects(empty.restore(), "missing cache");
            rejects(empty.startup(), "startup does not implicitly compile");
            rejects(empty.prepareForCraftEngine(false), "CE startup does not implicitly compile");
            rejects(empty.prepareForCraftEngine(true), "CE reload requires a prepared cache");
            rejects(empty.prepare(ReloadEngine.Mode.RESOURCE), "CE cannot implicitly build first snapshot");
            check(!Files.exists(metadata) && zips(data).isEmpty(), "cold restore does not compile");
        }
        Files.writeString(config, CONFIG);
        Files.writeString(menu, YAML);
        Files.writeString(layouts.resolve("other.yml"), simple("other", "OLD"));
        images(data, 0xff334455);
        var ce = data.resolve("ce/demo/configuration");
        Files.createDirectories(ce);
        Files.writeString(ce.resolve("images.yml"), """
            images:
              example:sample:
                file: example:font/sample
                height: 16
                ascent: 16
            """);
        ReloadEngine.Snapshot full;
        try (var engine = new ReloadEngine(data, CONTEXT)) {
            full = engine.prepare().get(90, TimeUnit.SECONDS).snapshot();
            check(Files.size(metadata) > 0, "full reload saved on disk");
            check(!engine.prepare().get(90, TimeUnit.SECONDS).changed(), "saved build still reuses stamps");
        }
        String before = render(full);
        var oldTime = Files.getLastModifiedTime(full.pack());
        byte[] saved = Files.readAllBytes(metadata), zip = Files.readAllBytes(full.pack());
        Files.writeString(menu, "[broken pending menu");
        Files.writeString(config, "[broken pending config");
        images(data, 0xffaa5511);
        try (var engine = new ReloadEngine(data, CONTEXT)) {
            var result = engine.restore().get(90, TimeUnit.SECONDS);
            var restored = result.snapshot();
            check(!result.changed() && !result.resourcesChanged(), "startup never requests CE reload");
            check(restored.pack().equals(full.pack()), "restore reuses original ZIP path");
            check(Files.getLastModifiedTime(full.pack()).equals(oldTime)
                && Arrays.equals(zip, Files.readAllBytes(full.pack())), "startup does not rewrite ZIP");
            check(render(restored).equals(before), "restore retains text/stretch/hovers/clicks/custom/CE atlas pixels");
            check(Arrays.equals(saved, Files.readAllBytes(metadata)), "startup does not rewrite compilation");
            var resources = engine.prepare(ReloadEngine.Mode.RESOURCE).get(90, TimeUnit.SECONDS);
            check(resources.resourcesChanged() && !Arrays.equals(zip, Files.readAllBytes(resources.snapshot().pack())),
                "explicit resource reload applies pending image pixels");
            check(resources.snapshot().documents() == restored.documents()
                && resources.snapshot().settings() == restored.settings(), "resource still ignores pending YAML/config");
        }
        try (var engine = new ReloadEngine(data, CONTEXT)) {
            var resource = engine.restore().get(90, TimeUnit.SECONDS).snapshot();
            check(!render(resource).equals(before), "resource reload persisted actual changed images");
            Files.writeString(menu, YAML.replace("tell: TEXT", "tell: UPDATED").replace("x: 40", "x: 30"));
            Files.writeString(layouts.resolve("other.yml"), "[broken unrelated menu");
            var partial = engine.prepare(new ReloadEngine.Request(ReloadEngine.Mode.YAML, "detail"))
                .get(90, TimeUnit.SECONDS).snapshot();
            check(partial.generation() > full.generation(), "restored generation advances");
            Files.writeString(data.resolve("expected-components.json"), render(partial));
            Files.writeString(menu, "[unapplied broken family");
        }
        try (var engine = new ReloadEngine(data, CONTEXT)) {
            var restored = engine.restore().get(90, TimeUnit.SECONDS).snapshot();
            check(render(restored).equals(Files.readString(data.resolve("expected-components.json"))),
                "single-menu family changes survive restart with unrelated broken files");
            check(restored.documents().get("other").sourceYaml().contains("OLD"),
                "save stores applied sources, never current pending YAML");
            Files.writeString(config, CONFIG + "canvas-width: 360\n");
            Files.writeString(menu, YAML);
            Files.writeString(layouts.resolve("other.yml"), simple("other", "GLOBAL"));
            var yaml = engine.prepare(ReloadEngine.Mode.YAML).get(90, TimeUnit.SECONDS).snapshot();
            check(yaml.settings().width() == 360, "global YAML applied");
            before = render(yaml);
        }
        try (var engine = new ReloadEngine(data, CONTEXT)) {
            var restored = engine.restore().get(90, TimeUnit.SECONDS).snapshot();
            check(restored.settings().width() == 360 && render(restored).equals(before), "global YAML persisted");
            var all = engine.prepare().get(90, TimeUnit.SECONDS);
            check(all.resourcesChanged(), "YAML restore retains distinction between applied and compiled inputs");
            saved = Files.readAllBytes(metadata);
            var packs = zips(data);
            Files.writeString(menu, "[broken build");
            rejects(engine.prepare(), "failed compile");
            check(Arrays.equals(saved, Files.readAllBytes(metadata)) && Files.exists(all.snapshot().pack()),
                "failed compile preserves disk snapshot and ZIP");
            Files.writeString(menu, YAML + "\n# force new build\n");
            Path recovery = data.resolve("saved-compilation.gz");
            Files.move(metadata, recovery);
            Files.createDirectory(metadata);
            Files.writeString(metadata.resolve("blocker"), "prevent replacing a directory");
            rejects(engine.prepare(), "failed atomic save");
            check(zips(data).equals(packs), "failed atomic save removes candidate ZIP and retains previous ZIP");
            check(Files.exists(metadata.resolve("blocker")), "failed save leaves previous disk target untouched");
            Files.delete(metadata.resolve("blocker"));
            Files.delete(metadata);
            Files.move(recovery, metadata);
            check(engine.restore().get(90, TimeUnit.SECONDS).snapshot() == all.snapshot(),
                "failed atomic save leaves in-memory snapshot unchanged");
        }
        corruptions(data, metadata);
        Files.writeString(menu, "[broken on next process");
        Files.writeString(config, "[broken on next process");
        Files.writeString(data.resolve("expected-components.json"), before = restoredComponents(data));
        crossProcess(data, before);
        cachePolicy(Path.of(args[0]));
        startupPolicy(Path.of(args[0]));
        System.out.println("PASS persistence checks=" + checks);
    }

    private static void cachePolicy(Path root) throws Exception {
        var defaults = new org.bukkit.configuration.file.YamlConfiguration();
        try (var input = PersistenceTests.class.getResourceAsStream("/config.yml")) {
            defaults.loadFromString(new String(Objects.requireNonNull(input).readAllBytes(), StandardCharsets.UTF_8));
        }
        check(defaults.contains("reuse-compiled-cache") && !defaults.getBoolean("reuse-compiled-cache"),
            "distributed configuration defaults to YAML-only startup");
        Path data = Files.createDirectories(root.resolve("cache-policy-" + UUID.randomUUID()));
        Path layouts = Files.createDirectories(data.resolve("layouts"));
        Path menu = layouts.resolve("test.yml"), config = data.resolve("config.yml");
        String yaml = simple("test", "OLD") + """
              button:
                type: image
                source: button.png
                width: 48
                height: 24
                offset: {x: 40, y: -20}
            """;
        String settings = "default-layout: test\ntext-scales: [1]\nresource-build-cpu-percent: 100\n";
        Files.writeString(config, settings + "reuse-compiled-cache: false\n");
        Files.writeString(menu, yaml);
        images(data, 0xff334455);
        String legacy;
        try (var engine = new ReloadEngine(data, CONTEXT, false)) {
            rejects(engine.startup(), "disabled startup with valid sources still requires compiled cache");
            rejects(engine.prepareForCraftEngine(false), "disabled CE initialization cannot build from sources");
            rejects(engine.prepareForCraftEngine(true), "disabled CE reload cannot implicitly compile without cache");
            check(zips(data).isEmpty(), "first startup never creates a resource ZIP");
            var first = engine.prepare().get(90, TimeUnit.SECONDS);
            check(first.resourcesChanged(), "explicit ALL prepares first compiled cache");
            String original = render(first.snapshot());
            Files.writeString(menu, yaml.replace("tell: OLD", "tell: PENDING"));
            images(data, 0xffaa5511);
            var changed = engine.prepareForCraftEngine(true).get(90, TimeUnit.SECONDS);
            check(changed.resourcesChanged() && !original.equals(render(changed.snapshot())),
                "disabled CE preparation applies changed image pixels");
            check(changed.snapshot().documents().get("test").sourceYaml().contains("tell: OLD"),
                "disabled CE retains original resource-only semantics");
            legacy = render(changed.snapshot());
        }
        images(data, 0xff336699);
        Files.writeString(menu, "[broken pending YAML");
        try (var engine = new ReloadEngine(data, CONTEXT, true)) {
            var cached = engine.prepareForCraftEngine(true).get(90, TimeUnit.SECONDS);
            check(!cached.changed() && render(cached.snapshot()).equals(legacy),
                "current enabled policy overrides disabled policy stored in old snapshot");
            var resources = engine.prepare(ReloadEngine.Mode.RESOURCE).get(90, TimeUnit.SECONDS);
            check(resources.resourcesChanged() && !render(resources.snapshot()).equals(legacy),
                "manual resource command still applies changes with cache enabled");
            images(data, 0xff112233);
            check(engine.prepareForCraftEngine(true).get(90, TimeUnit.SECONDS).snapshot() == resources.snapshot(),
                "manual resource reload does not reset current startup cache policy to stored value");
            Files.writeString(menu, yaml);
            Files.writeString(config, settings + "reuse-compiled-cache: true\n");
            engine.prepare(ReloadEngine.Mode.YAML).get(90, TimeUnit.SECONDS);
            var unchanged = engine.prepareForCraftEngine(true).get(90, TimeUnit.SECONDS);
            check(!unchanged.changed(), "YAML reload enables cache without rebuilding pending image");
            Files.writeString(config, settings + "reuse-compiled-cache: false\n");
            engine.prepare(ReloadEngine.Mode.YAML).get(90, TimeUnit.SECONDS);
            var rebuilt = engine.prepareForCraftEngine(true).get(90, TimeUnit.SECONDS);
            check(rebuilt.resourcesChanged(), "YAML reload disables cache and CE applies pending image");
            Files.writeString(config, settings + "reuse-compiled-cache: true\n");
            engine.prepare().get(90, TimeUnit.SECONDS);
            images(data, 0xff445566);
            var all = engine.prepareForCraftEngine(true).get(90, TimeUnit.SECONDS);
            check(!all.changed(), "ALL applies enabled policy for later CE reload");
        }
    }

    private static void startupPolicy(Path root) throws Exception {
        Path data = Files.createDirectories(root.resolve("startup-policy-" + UUID.randomUUID()));
        Path menu = Files.createDirectories(data.resolve("layouts")).resolve("test.yml");
        Path config = data.resolve("config.yml"), metadata = data.resolve("pack-cache/compiled.json.gz");
        String settings = "default-layout: test\ntext-scales: [1]\nresource-build-cpu-percent: 100\n";
        String yaml = simple("test", "OLD") + """
              button:
                type: image
                source: button.png
                width: 48
                height: 24
                offset: {x: 40, y: -20}
            """;
        Files.writeString(config, settings + "reuse-compiled-cache: true\n");
        Files.writeString(menu, yaml);
        images(data, 0xff334455);
        ReloadEngine.Snapshot full;
        try (var engine = new ReloadEngine(data, CONTEXT)) {
            full = engine.prepare().get(90, TimeUnit.SECONDS).snapshot();
        }
        byte[] zip = Files.readAllBytes(full.pack());
        String old = render(full);
        var modified = Files.getLastModifiedTime(full.pack());
        Files.writeString(config, settings + "canvas-width: 360\n");
        Files.writeString(menu, yaml.replace("tell: OLD", "tell: NEW"));
        Files.writeString(data.resolve("images/button.png"), "broken pending image");
        ReloadEngine.Snapshot applied;
        try (var engine = new ReloadEngine(data, CONTEXT)) {
            var startup = engine.startup();
            var ceStartup = engine.prepareForCraftEngine(false);
            var result = startup.get(90, TimeUnit.SECONDS);
            applied = result.result().snapshot();
            check(result.yamlError() == null && !result.result().resourcesChanged(),
                "default false startup loads YAML without resource preparation");
            check(!applied.settings().reuseCompiledCache() && applied.settings().width() == 360,
                "default startup policy and current YAML settings override saved enabled settings");
            check(!render(applied).equals(old) && render(applied).contains("/tell: NEW"),
                "startup applies changed outgoing click actions using frozen image pixels");
            check(ceStartup.get(90, TimeUnit.SECONDS).snapshot() == applied,
                "queued CE initialization reuses YAML startup snapshot even with corrupt source image");
            check(applied.pack().equals(full.pack()) && Arrays.equals(zip, Files.readAllBytes(full.pack()))
                && Files.getLastModifiedTime(full.pack()).equals(modified) && zips(data).equals(Set.of(full.pack())),
                "startup and CE initialization do not rewrite or create ZIPs");
        }
        byte[] saved = Files.readAllBytes(metadata);
        for (String invalid : List.of("[broken YAML", yaml.replace("content: A", "content: 🦄"))) {
            Files.writeString(menu, invalid);
            try (var engine = new ReloadEngine(data, CONTEXT)) {
                var result = engine.startup().get(90, TimeUnit.SECONDS);
                check(result.yamlError() != null, "startup reports invalid or unprepared YAML");
                check(render(result.result().snapshot()).equals(render(applied)),
                    "failed startup YAML retains usable restored menus and components");
                check(!engine.prepareForCraftEngine(false).get(90, TimeUnit.SECONDS).changed(),
                    "CE initialization keeps restored pack after YAML failure");
                check(Arrays.equals(saved, Files.readAllBytes(metadata)) && zips(data).equals(Set.of(full.pack())),
                    "failed startup YAML preserves compiled snapshot and ZIP");
            }
        }
        try (var engine = new ReloadEngine(data, CONTEXT, true)) {
            var result = engine.startup().get(90, TimeUnit.SECONDS);
            check(result.yamlError() == null && render(result.result().snapshot()).equals(render(applied)),
                "explicit cache-only startup skips pending invalid YAML and source image");
        }
        Files.writeString(menu, yaml.replace("tell: OLD", "tell: ALL"));
        images(data, 0xffaa5511);
        try (var engine = new ReloadEngine(data, CONTEXT)) {
            engine.startup().get(90, TimeUnit.SECONDS);
            var result = engine.prepare().get(90, TimeUnit.SECONDS);
            check(result.resourcesChanged() && !result.snapshot().pack().equals(full.pack())
                && !render(result.snapshot()).equals(render(applied)),
                "explicit ALL still rebuilds images after YAML-only startup");
        }
    }

    private static void corruptions(Path data, Path metadata) throws Exception {
        byte[] good = Files.readAllBytes(metadata);
        new CompiledSnapshot(data).prune(new PackCache(data.resolve("pack-cache")));
        Path pack;
        try (var engine = new ReloadEngine(data, CONTEXT)) {
            pack = engine.restore().get(90, TimeUnit.SECONDS).snapshot().pack();
        }
        Files.move(pack, pack.resolveSibling("temporarily-missing.zip"));
        try (var engine = new ReloadEngine(data, CONTEXT)) {
            rejects(engine.restore(), "missing ZIP");
        }
        Files.move(pack.resolveSibling("temporarily-missing.zip"), pack);
        for (String kind : List.of("gzip", "engine", "format", "zip", "missing")) {
            if (kind.equals("gzip")) Files.writeString(metadata, "broken gzip");
            else if (kind.equals("missing")) Files.delete(metadata);
            else {
                JsonObject value;
                try (var input = new InputStreamReader(new GZIPInputStream(new ByteArrayInputStream(good)),
                    StandardCharsets.UTF_8)) { value = JsonParser.parseReader(input).getAsJsonObject(); }
                if (kind.equals("zip")) value.getAsJsonObject("state").addProperty("packDigest", "bad");
                else value.addProperty(kind, kind.equals("format") ? "999" : "incompatible");
                try (var output = new OutputStreamWriter(new GZIPOutputStream(Files.newOutputStream(metadata)),
                    StandardCharsets.UTF_8)) { new Gson().toJson(value, output); }
            }
            var packs = zips(data);
            try (var engine = new ReloadEngine(data, CONTEXT)) {
                rejects(engine.restore(), "reject " + kind);
                rejects(engine.startup(), "startup rejects " + kind + " without compiling");
                rejects(engine.prepareForCraftEngine(false), "CE startup rejects " + kind + " without compiling");
                check(zips(data).equals(packs), "invalid cache never builds/deletes ZIP: " + kind);
                if (!kind.equals("missing")) {
                    Files.writeString(data.resolve("layouts/test.yml"), "[broken rebuild");
                    rejects(engine.prepare(), "manual rebuild with broken layout");
                    check(zips(data).equals(packs), "failed rebuild retains indexed ZIP: " + kind);
                }
            }
            Files.write(metadata, good);
        }
    }
    private static String restoredComponents(Path data) throws Exception {
        try (var engine = new ReloadEngine(data, CONTEXT)) {
            return render(engine.restore().get(90, TimeUnit.SECONDS).snapshot());
        }
    }
    private static void crossProcess(Path data, String expected) throws Exception {
        Path output = data.resolve("child-components.json"), log = data.resolve("child.log");
        var process = new ProcessBuilder(Path.of(System.getProperty("java.home"), "bin/java").toString(),
            "-Djava.awt.headless=true", "-cp", System.getProperty("java.class.path"),
            PersistenceTests.class.getName(), "restore", data.toString(), output.toString())
            .redirectErrorStream(true).redirectOutput(log.toFile()).start();
        check(process.waitFor(90, TimeUnit.SECONDS) && process.exitValue() == 0,
            "fresh JVM restores cache: " + log);
        check(expected.equals(Files.readString(output)), "fresh JVM emits identical outgoing components");
    }
    private static String render(ReloadEngine.Snapshot snapshot) throws Exception {
        StringBuilder result = new StringBuilder();
        for (var doc : snapshot.documents().values()) {
            for (int width : List.of(300, 360, 420)) {
                var frame = snapshot.renderer().draw(doc.menu(), CONTEXT, width, 131072);
                var component = new SpriteCanvas().encode(doc.menu(), frame, CONTEXT, snapshot.fonts(),
                    region -> ClickEvent.runCommand("/" + String.join("-", region.actions()))).component();
                result.append(doc.menu().id()).append(':').append(width).append(':')
                    .append(FontPack.hash(frame.image())).append(':')
                    .append(GsonComponentSerializer.gson().serialize(component)).append('\n');
            }
        }
        return result.toString();
    }
    private static String simple(String id, String action) {
        return "schema-version: 1\nid: " + id + "\ncanvas: {width: 300, height: 180}\nfrontend:\n"
            + "  label: {type: text, content: A, offset: {y: 20}, actions: ['tell: " + action + "']}\n";
    }
    private static void images(Path data, int color) throws Exception {
        var image = new BufferedImage(32, 32, BufferedImage.TYPE_INT_ARGB);
        for (int y = 0; y < 32; y++) for (int x = 0; x < 32; x++)
            image.setRGB(x, y, color ^ x * 0x10100 ^ y * 0x10);
        for (String name : List.of("images/button.png", "ce/demo/resourcepack/assets/example/textures/font/sample.png")) {
            Path path = data.resolve(name);
            Files.createDirectories(path.getParent());
            ImageIO.write(image, "png", path.toFile());
        }
    }
    private static Set<Path> zips(Path data) throws Exception {
        Path directory = data.resolve("pack-cache");
        if (!Files.isDirectory(directory)) return Set.of();
        try (var paths = Files.list(directory)) {
            return new HashSet<>(paths.filter(p -> p.toString().endsWith(".zip")).toList());
        }
    }
    private static void rejects(CompletableFuture<?> future, String message) throws Exception {
        try { future.get(90, TimeUnit.SECONDS); throw new AssertionError(message + " accepted"); }
        catch (ExecutionException expected) { checks++; }
    }
    private static void check(boolean value, String message) {
        checks++;
        if (!value) throw new AssertionError(message);
    }
}
