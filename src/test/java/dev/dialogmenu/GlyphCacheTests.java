package dev.dialogmenu;

import com.google.gson.JsonParser;
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

/** Compare incremental output with independent cold builds, including persisted font atlases. */
public final class GlyphCacheTests {
    private static int checks;
    private static final Context CONTEXT = new Context() {
        public String expand(String value) { return value.replace("{player}", "Player"); }
        public Icon item(int slot) { return null; }
        public boolean permitted(String permission) { return true; }
    };
    private static final String CONFIG = """
        default-layout: test
        text-scales: [0.75, 1]
        resource-build-threads: 4
        resource-build-cpu-percent: 100
        """;
    private static final String YAML = """
        schema-version: 1
        id: test
        canvas: {width: 300, height: 180}
        frontend:
          label:
            type: text
            content: 文
            offset: {y: 20}
            actions: ['tell: OLD']
            tooltip: Label
          dynamic:
            type: text
            content: '{player}'
            offset: {y: -20}
          stretched:
            type: text
            content: 'XY汉'
            scale: 1.5
            offset: {y: 50}
            actions: ['tell: STRETCH']
          button:
            type: image
            source: button.png
            width: 48
            height: 24
            offset: {x: 60, y: -45}
            actions: ['tell: BUTTON']
        """;

    public static void main(String[] args) throws Exception {
        if (args[0].equals("source")) {
            sourceChange(Path.of(args[1]), Path.of(args[2]));
            System.out.println("PASS glyph source checks=" + checks);
            return;
        }
        Path root = Path.of(args[0]);
        Path data = setup(root.resolve("glyph-cache-" + UUID.randomUUID()), YAML);
        Path menu = data.resolve("layouts/test.yml");
        ReloadEngine.Snapshot old;
        String changed = YAML.replace("tell: OLD", "tell: UPDATED").replace("content: 文", "content: 中")
            .replace("offset: {y: 20}", "offset: {y: 25}");
        try (var engine = new ReloadEngine(data, CONTEXT)) {
            old = build(engine);
            check(!old.fonts().reusedGlyphs(), "first build prepares fonts");
            String original = render(old);
            Files.writeString(menu, YAML + "\n# force input change\n");
            var same = build(engine);
            check(same.fonts().reusedGlyphs(), "unrelated YAML change reuses fonts");
            equivalent(old, same);
            Files.writeString(menu, changed);
            var moved = build(engine);
            check(moved.fonts().reusedGlyphs(), "action, position and already-covered text reuse fonts");
            check(!original.equals(render(moved)) && render(moved).contains("UPDATED"),
                "incremental build applies changed text, position and action");
            compareCold(root, data, moved);
            Files.writeString(data.resolve("config.yml"), CONFIG + "text-colors: ['#123456']\n");
            var colored = build(engine);
            check(colored.fonts().reusedGlyphs(), "palette changes do not invalidate glyph pixels");
            compareCold(root, data, colored);
            image(data, 0xff1155aa);
            var image = engine.prepare(ReloadEngine.Mode.RESOURCE).get(90, TimeUnit.SECONDS).snapshot();
            check(image.fonts().reusedGlyphs(), "image-only resource reload reuses fonts");
            check(!render(image).equals(render(moved)), "new image reaches rendered output");
            compareCold(root, data, image);
            var full = engine.prepare(ReloadEngine.Mode.FULL).get(90, TimeUnit.SECONDS);
            check(full.changed() && full.resourcesChanged() && full.snapshot().pack() != image.pack(),
                "full rebuild cannot return the unchanged snapshot");
            check(!full.snapshot().fonts().reusedGlyphs(), "full rebuild bypasses reusable glyph atlases");
            compareCold(root, data, full.snapshot());
            image = full.snapshot();
            old = image;
        }
        try (var engine = new ReloadEngine(data, CONTEXT)) {
            var restored = engine.restore().get(90, TimeUnit.SECONDS).snapshot();
            equivalent(old, restored);
            Files.writeString(menu, changed + "\n# restored cache\n");
            var reused = build(engine);
            check(reused.fonts().reusedGlyphs(), "disk-restored plain and stretched atlases are reusable");
            equivalent(old, reused);
            // Runtime metrics survive without stretched source images; changed demand regenerates them.
            changed = changed.replace("XY汉", "XY汉Ω");
            Files.writeString(menu, changed);
            var extra = build(engine);
            check(!extra.fonts().reusedGlyphs(), "new stretched character invalidates glyph dependencies");
            compareCold(root, data, extra);
            check(render(restored).equals(render(old)), "new build never mutates restored snapshot");
            changed = changed.replace("x: 1.5", "x: 1.25");
            Files.writeString(menu, changed);
            var stretched = build(engine);
            check(stretched.fonts().reusedGlyphs(), "stretch change preserves source glyph resources");
            compareCold(root, data, stretched);
            Files.writeString(data.resolve("config.yml"), CONFIG.replace("[0.75, 1]", "[1]"));
            var scaled = build(engine);
            check(scaled.fonts().reusedGlyphs(), "text scale change preserves fixed source glyphs");
            compareCold(root, data, scaled);
            Files.writeString(menu, changed.replace("content: 中", "content: Ω"));
            var letter = build(engine);
            check(letter.fonts().reusedGlyphs(), "An already prepared character is shared across old style boundaries");
            compareCold(root, data, letter);
            byte[] persisted = Files.readAllBytes(data.resolve("pack-cache/compiled.json.gz"));
            Files.writeString(menu, changed.replace("button.png", "missing.png"));
            reject(engine.prepare(ReloadEngine.Mode.ALL), "failed resource reload");
            check(Arrays.equals(persisted, Files.readAllBytes(data.resolve("pack-cache/compiled.json.gz"))),
                "failure preserves persisted snapshot");
            check(engine.prepare(ReloadEngine.Mode.RESOURCE).get(90, TimeUnit.SECONDS).snapshot() == letter,
                "failure preserves live snapshot");
        }
        staticToDynamic(root);
        sourceProcess(root);
        System.out.println("PASS glyph cache checks=" + checks);
    }

    private static void sourceProcess(Path root) throws Exception {
        Path shadow = Files.createDirectories(root.resolve("glyph-source-" + UUID.randomUUID()));
        Path source = shadow.resolve("glyphs.bin.gz");
        try (var input = GlyphCacheTests.class.getResourceAsStream("/glyphs.bin.gz")) {
            Files.copy(Objects.requireNonNull(input), source);
        }
        Path log = shadow.resolve("source-check.log");
        var process = new ProcessBuilder(Path.of(System.getProperty("java.home"), "bin/java").toString(),
            "-Djava.awt.headless=true", "-cp", shadow + File.pathSeparator + System.getProperty("java.class.path"),
            GlyphCacheTests.class.getName(), "source", shadow.resolve("data").toString(), source.toString())
            .redirectErrorStream(true).redirectOutput(log.toFile()).start();
        boolean exited = process.waitFor(90, TimeUnit.SECONDS);
        if (!exited) process.destroyForcibly();
        check(exited && process.exitValue() == 0, "real glyph source changes invalidate cached atlases: " + log);
    }

    private static void sourceChange(Path data, Path source) throws Exception {
        setup(data, YAML);
        try (var engine = new ReloadEngine(data, CONTEXT)) {
            var old = build(engine);
            byte[] changed;
            try (var input = new DataInputStream(new GZIPInputStream(Files.newInputStream(source)));
                 var bytes = new ByteArrayOutputStream()) {
                try (var output = new DataOutputStream(new GZIPOutputStream(bytes))) {
                    output.writeInt(input.readInt());
                    int count = input.readInt();
                    output.writeInt(count);
                    boolean found = false;
                    for (int i = 0; i < count; i++) {
                        int cp = input.readInt(), w = input.readUnsignedShort(), h = input.readUnsignedShort();
                        float advance = input.readFloat();
                        byte[] alpha = input.readNBytes(w * h);
                        if (cp == '文') {
                            for (int p = 0; p < alpha.length; p++) if (alpha[p] != 0) {
                                alpha[p] = 64;
                                found = true;
                            }
                        }
                        output.writeInt(cp);
                        output.writeShort(w);
                        output.writeShort(h);
                        output.writeFloat(advance);
                        output.write(alpha);
                    }
                    check(found, "source mutation changes an actual used glyph");
                }
                changed = bytes.toByteArray();
            }
            Files.write(source, changed);
            Files.writeString(data.resolve("layouts/test.yml"), YAML + "\n# source update\n");
            var next = build(engine);
            check(!next.fonts().reusedGlyphs(), "same styles and characters but different glyph source rebuild");
            var before = files(old.pack());
            var after = files(next.pack());
            check(before.keySet().stream().filter(p -> p.contains("/textures/generated/text_"))
                .anyMatch(p -> !after.containsKey(p) || !Arrays.equals(before.get(p), after.get(p))),
                "changed source pixels reach exported glyph atlases");
            compareCold(data.getParent(), data, next);
        }
    }

    private static void staticToDynamic(Path root) throws Exception {
        String yaml = """
            schema-version: 1
            id: test
            variables:
              amount: {type: external, preview: '1', max-length: 1}
            pages:
              test:
                canvas: {width: 300, height: 180}
                frontend:
                  label: {type: text, content: '0123456789.,-+EMAX', offset: {y: 20}}
            """;
        Path data = setup(root.resolve("glyph-validation-" + UUID.randomUUID()), yaml);
        try (var engine = new ReloadEngine(data, CONTEXT)) { build(engine); }
        try (var engine = new ReloadEngine(data, CONTEXT)) {
            var restored = engine.restore().get(90, TimeUnit.SECONDS).snapshot();
            Files.writeString(data.resolve("layouts/test.yml"), yaml.replace("0123456789.,-+EMAX", "{amount}"));
            var failure = reject(engine.prepare(ReloadEngine.Mode.YAML), "static-to-dynamic needs common glyph coverage");
            check(failure.getCause().getMessage().contains("动态文字字符范围发生变化"),
                "static-to-dynamic is rejected for missing common glyphs, not an unrelated rendering error");
            var full = build(engine);
            check(!full.fonts().reusedGlyphs(), "ALL prepares missing common characters");
            check(!render(restored).equals(render(full)), "old static snapshot remains usable after ALL");
            var yamlReload = engine.prepare(ReloadEngine.Mode.YAML).get(90, TimeUnit.SECONDS);
            check(yamlReload.snapshot().pack().equals(full.pack()), "covered dynamic YAML needs no new ZIP");
        }
    }

    private static Path setup(Path data, String yaml) throws Exception {
        Files.createDirectories(data.resolve("layouts"));
        Files.writeString(data.resolve("layouts/test.yml"), yaml);
        Files.writeString(data.resolve("config.yml"), CONFIG);
        image(data, 0xffaa5511);
        return data;
    }
    private static void image(Path data, int color) throws Exception {
        Files.createDirectories(data.resolve("images"));
        var image = new BufferedImage(16, 16, BufferedImage.TYPE_INT_ARGB);
        for (int y = 0; y < 16; y++) for (int x = 0; x < 16; x++) image.setRGB(x, y, color ^ x * 257 ^ y);
        ImageIO.write(image, "png", data.resolve("images/button.png").toFile());
    }
    private static ReloadEngine.Snapshot build(ReloadEngine engine) throws Exception {
        return engine.prepare(ReloadEngine.Mode.ALL).get(90, TimeUnit.SECONDS).snapshot();
    }
    private static void compareCold(Path root, Path source, ReloadEngine.Snapshot incremental) throws Exception {
        Path cold = setup(root.resolve("glyph-cold-" + UUID.randomUUID()),
            Files.readString(source.resolve("layouts/test.yml")));
        Files.copy(source.resolve("config.yml"), cold.resolve("config.yml"), StandardCopyOption.REPLACE_EXISTING);
        Files.copy(source.resolve("images/button.png"), cold.resolve("images/button.png"), StandardCopyOption.REPLACE_EXISTING);
        try (var engine = new ReloadEngine(cold, CONTEXT)) { equivalent(incremental, build(engine)); }
    }
    private static void equivalent(ReloadEngine.Snapshot expected, ReloadEngine.Snapshot actual) throws Exception {
        check(render(expected).equals(render(actual)), "pixels and outgoing text/image/hover/click components match cold build");
        Map<String, byte[]> left = files(expected.pack()), right = files(actual.pack());
        check(left.keySet().equals(right.keySet()), "resource entry sets match cold build");
        for (String path : left.keySet()) {
            boolean json = path.endsWith(".json") || path.endsWith(".mcmeta");
            if (!(json ? JsonParser.parseString(new String(left.get(path), StandardCharsets.UTF_8)).equals(
                JsonParser.parseString(new String(right.get(path), StandardCharsets.UTF_8)))
                : Arrays.equals(left.get(path), right.get(path))))
                throw new AssertionError("incremental resource differs from cold build: " + path);
        }
        checks++;
    }
    private static Map<String, byte[]> files(Path pack) throws Exception {
        Map<String, byte[]> result = new TreeMap<>();
        try (var zip = new ZipFile(pack.toFile())) {
            for (var entry : zip.stream().filter(e -> !e.isDirectory()).toList())
                try (var input = zip.getInputStream(entry)) { result.put(entry.getName(), input.readAllBytes()); }
        }
        return result;
    }
    private static String render(ReloadEngine.Snapshot snapshot) throws Exception {
        StringBuilder result = new StringBuilder();
        for (var doc : MenuTemplate.catalog(snapshot.documents().values()))
            for (int width : List.of(300, 360, 420)) for (double scale : snapshot.settings().textScales()) {
                Context base = MenuTemplate.preview(doc, CONTEXT);
                Context context = new Context() {
                    public String expand(String value) { return base.expand(value); }
                    public Icon item(int slot) { return null; }
                    public boolean permitted(String permission) { return true; }
                    public double textScale() { return scale; }
                };
                var frame = snapshot.renderer().draw(doc.menu(), context, width, 131072);
                var component = new SpriteCanvas().encode(doc.menu(), frame, context, snapshot.fonts(),
                    region -> ClickEvent.runCommand("/" + String.join("-", region.actions()))).component();
                result.append(FontPack.hash(frame.image())).append(GsonComponentSerializer.gson().serialize(component));
            }
        return result.toString();
    }
    private static ExecutionException reject(CompletableFuture<?> future, String message) throws Exception {
        try { future.get(90, TimeUnit.SECONDS); throw new AssertionError(message + " accepted"); }
        catch (ExecutionException expected) { checks++; return expected; }
    }
    private static void check(boolean condition, String message) {
        checks++;
        if (!condition) throw new AssertionError(message);
    }
}
