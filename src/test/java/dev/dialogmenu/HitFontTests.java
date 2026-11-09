package dev.dialogmenu;

import com.google.gson.*;
import java.io.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.*;
import java.util.concurrent.*;
import java.util.zip.*;
import javax.imageio.ImageIO;
import net.kyori.adventure.text.event.ClickEvent;
import net.kyori.adventure.text.serializer.gson.GsonComponentSerializer;
import org.bukkit.configuration.file.YamlConfiguration;
import static dev.dialogmenu.MenuModel.*;

/** Position-only updates must use the published hit font, including after restart. */
public final class HitFontTests {
    private static int checks;
    private static final Context BASE = new Context() {
        public String expand(String text) { return text; }
        public Icon item(int slot) { return null; }
        public boolean permitted(String permission) { return true; }
    };
    private static final String YAML = """
        schema-version: 1
        id: probe
        canvas: {width: 300, height: 240}
        frontend:
          label:
            type: text
            content: 'Ai 中W'
            size: 8
            offset: {x: -40, y: 20}
            actions: ['tell: HIT']
            tooltip: Label
          narrow:
            type: text
            content: i
            size: 6
            alignment: right
            offset: {x: 150, y: 0}
            actions: ['tell: NARROW']
          small:
            type: text
            content: A
            size: 5
            offset: {y: -30}
          stretched:
            type: text
            content: 'A中'
            size: 8
            scale: {x: 1.5, y: 2}
            offset: {y: -65}
            actions: ['tell: STRETCHED']
        """;

    public static void main(String[] args) throws Exception { run(Path.of(args[0])); }

    static void run(Path root) throws Exception {
        checks = 0;
        Path data = Files.createTempDirectory(root, "hit-font-");
        Path menu = data.resolve("layouts/probe.yml");
        Files.createDirectories(menu.getParent());
        Files.writeString(menu, YAML);
        Files.writeString(data.resolve("config.yml"), """
            default-layout: probe
            max-canvas-pixels: 1048576
            resource-build-cpu-percent: 100
            text-scales: [0.75, 1]
            canvas-scales: [0.75, 1]
            """);
        ReloadEngine.Snapshot last;
        Map<String, byte[]> first;
        try (var engine = new ReloadEngine(data, BASE)) {
            var original = engine.prepare().get(90, TimeUnit.SECONDS).snapshot();
            first = files(original.pack());
            for (int y : List.of(21, 28, -17, 60, 19, 11, -43)) {
                Files.writeString(menu, YAML.replace("x: -40, y: 20", "x: -39, y: " + y));
                var result = engine.prepare(ReloadEngine.Mode.YAML).get(90, TimeUnit.SECONDS);
                check(result.changed() && !result.resourcesChanged(), "Moving text must apply YAML without resources");
                check(original.pack().equals(result.snapshot().pack()), "Moving text must keep the published ZIP");
                same(first, files(result.snapshot().pack()));
                render(result.snapshot());
            }
            String interactive = YAML.replace("size: 5", "size: 5\n    tooltip: Prepared without an action");
            Files.writeString(menu, interactive);
            var added = engine.prepare(new ReloadEngine.Request(ReloadEngine.Mode.YAML, "probe"))
                .get(90, TimeUnit.SECONDS);
            check(!added.resourcesChanged(), "Adding hover to prepared text needs no hit-font generation");
            render(added.snapshot());
            last = added.snapshot();
        }
        try (var engine = new ReloadEngine(data, BASE)) {
            var restored = engine.restore().get(90, TimeUnit.SECONDS).snapshot();
            check(render(last).equals(render(restored)), "Hit-font choices survive persisted snapshot restore");
            Files.writeString(menu, YAML.replace("x: -40, y: 20", "x: -41, y: -43"));
            var moved = engine.prepare(new ReloadEngine.Request(ReloadEngine.Mode.YAML, "probe"))
                .get(90, TimeUnit.SECONDS);
            check(!moved.resourcesChanged(), "Moved text after restart still reuses hit resources");
            same(first, files(moved.snapshot().pack()));
            render(moved.snapshot());
            byte[] saved = Files.readAllBytes(data.resolve("pack-cache/compiled.json.gz"));
            Files.writeString(menu, YAML.replace("Ai 中W", "Ω"));
            try {
                engine.prepare(ReloadEngine.Mode.YAML).get(90, TimeUnit.SECONDS);
                throw new AssertionError("Unprepared visible glyph was accepted");
            } catch (ExecutionException expected) {
                check(expected.getCause().getMessage().contains("文字已变化"),
                    "New visible characters still require resource preparation");
            }
            check(Arrays.equals(saved, Files.readAllBytes(data.resolve("pack-cache/compiled.json.gz"))),
                "Rejected text keeps the previous persisted snapshot");
        }
        Path cold = Files.createTempDirectory(root, "hit-font-cold-");
        Files.createDirectories(cold.resolve("layouts"));
        Files.copy(data.resolve("config.yml"), cold.resolve("config.yml"));
        Files.writeString(cold.resolve("layouts/probe.yml"), YAML.replace("y: 20", "y: 23"));
        try (var engine = new ReloadEngine(cold, BASE)) {
            var built = engine.prepare().get(90, TimeUnit.SECONDS).snapshot();
            same(first, files(built.pack()));
        }
        dimensions(first);
        wideGlyph(root);
        System.out.println("PASS hit-font checks=" + checks + " (offline, no client)");
    }

    private static void wideGlyph(Path root) throws Exception {
        Path data = Files.createTempDirectory(root, "hit-font-wide-");
        var yaml = new YamlConfiguration();
        yaml.loadFromString("""
            schema-version: 1
            id: wide
            canvas: {width: 300, height: 240}
            frontend:
              label:
                type: text
                content: '&l中'
                size: 8
                scale: {x: 32, y: 1}
                actions: ['tell: WIDE']
            """);
        var doc = LayoutStore.parse("wide", yaml);
        var renderer = new MenuRenderer(new VanillaAssets(data.resolve("images")));
        var fonts = FontPack.create(List.of(doc), renderer, BASE, List.of(300), 1048576);
        Path pack = data.resolve("pack");
        fonts.write(pack);
        var body = new SpriteCanvas().encode(doc.menu(), renderer.draw(doc.menu(), BASE, 300, 1048576),
            BASE, fonts, r -> ClickEvent.runCommand("/WIDE")).component();
        check(GsonComponentSerializer.gson().serialize(body).contains("/WIDE"),
            "Maximum-width bold glyph keeps its click proxy");
        try (var paths = Files.list(pack.resolve("assets/dialogmenu/textures/generated"))) {
            for (Path path : paths.filter(p -> p.getFileName().toString().startsWith("hit_")).toList())
                check(ImageIO.read(path.toFile()).getWidth() <= 256,
                    "Bold advance compensation never creates an oversized native glyph");
        }
    }

    private static String render(ReloadEngine.Snapshot snapshot) throws Exception {
        StringBuilder encoded = new StringBuilder();
        for (double scale : snapshot.settings().canvasScales())
            for (double textScale : snapshot.settings().textScales())
                for (int width : List.of(300, 360, 420, 1014)) {
                    Context context = new Context() {
                        public String expand(String text) { return text; }
                        public Icon item(int slot) { return null; }
                        public boolean permitted(String permission) { return true; }
                        public double canvasScale() { return scale; }
                        public double textScale() { return textScale; }
                    };
                    var menu = snapshot.documents().get("probe").menu();
                    var frame = snapshot.renderer().drawRuntime(menu, context, width, snapshot.settings().maxPixels());
                    var body = new SpriteCanvas().encode(menu, frame, context, snapshot.fonts(),
                        region -> ClickEvent.runCommand("/" + region.id())).component();
                    String json = GsonComponentSerializer.gson().serialize(body);
                    check(MenuText.glyphs(body).stream().noneMatch(g -> SpriteCanvas.SPACE.equals(g.style().font())
                        && (g.style().clickEvent() != null || g.style().hoverEvent() != null)),
                        "Horizontal positioning spaces must not create native ghost hit/hover areas");
                    check(json.contains("/frontend.label") && json.contains("/frontend.narrow")
                        && json.contains("/frontend.stretched") && json.contains("Label"),
                        "Moved text retains click and hover in every width/scale combination");
                    encoded.append(json);
                    try { narrow(body, snapshot.fonts(), files(snapshot.pack())); }
                    catch (AssertionError error) {
                        throw new AssertionError("width=" + width + " canvas=" + scale + " text=" + textScale
                            + ": " + error.getMessage(), error);
                    }
                }
        return encoded.toString();
    }

    private static void narrow(net.kyori.adventure.text.Component body, FontPack fonts,
                               Map<String, byte[]> resources) throws Exception {
        for (var glyph : MenuText.glyphs(body)) {
            if (glyph.style().clickEvent() == null || !glyph.style().clickEvent().value().equals("/frontend.narrow")
                || glyph.style().font() == null || !glyph.style().font().equals(RgbaHits.FONT)) continue;
            var providers = JsonParser.parseString(new String(resources.get(
                "assets/dialogmenu/font/" + glyph.style().font().value() + ".json"), StandardCharsets.UTF_8))
                .getAsJsonObject().getAsJsonArray("providers");
            for (var value : providers) {
                var provider = value.getAsJsonObject();
                if (!provider.getAsJsonArray("chars").get(0).getAsString().contains(Character.toString(glyph.code())))
                    continue;
                var png = ImageIO.read(new ByteArrayInputStream(resources.get(
                    provider.get("file").getAsString().replace("dialogmenu:", "assets/dialogmenu/textures/"))));
                check(png.getHeight() == provider.get("height").getAsInt(),
                    "Narrow proxy uses an exact-width blank without vertical scaling");
                int used = 0;
                for (int x = 0; x < png.getWidth(); x++) for (int y = 0; y < png.getHeight(); y++)
                    if (png.getRGB(x, y) >>> 24 != 0) used = x + 1;
                int advance = used + 1;
                check(advance == png.getWidth() && advance <= 2,
                    "Narrow row proxy cannot wrap past the visible text's right edge");
                return;
            }
            throw new AssertionError("Narrow hit font provider missing");
        }
        throw new AssertionError("Narrow text lost its native click proxy: " + MenuText.glyphs(body).stream()
            .filter(g -> g.style().clickEvent() != null && g.style().clickEvent().value().equals("/frontend.narrow"))
            .toList());
    }

    private static void dimensions(Map<String, byte[]> resources) throws Exception {
        long pngBytes = 0, jsonBytes = 0, packedBytes;
        int pngs = 0, variants = 0;
        Set<Integer> heights = new TreeSet<>();
        var compressed = new ByteArrayOutputStream();
        try (var zip = new ZipOutputStream(compressed)) {
            for (var entry : resources.entrySet()) {
                String name = entry.getKey();
                if (!name.contains("/generated/row_hit_") && !name.contains("/font/row_hit")) continue;
                zip.putNextEntry(new ZipEntry(name));
                zip.write(entry.getValue());
                zip.closeEntry();
                if (name.endsWith(".png")) {
                    var image = ImageIO.read(new ByteArrayInputStream(entry.getValue()));
                    heights.add(image.getHeight());
                    int used = 0;
                    for (int y = 0; y < image.getHeight(); y++) for (int x = 0; x < image.getWidth(); x++) {
                        if ((image.getRGB(x, y) >>> 24) / 255.0 >= 0.1)
                            throw new AssertionError("Hit pixels exceed the native fragment discard threshold");
                        if (image.getRGB(x, y) >>> 24 != 0) used = Math.max(used, x + 1);
                    }
                    check(used + 1 == image.getWidth(), "Native active width must fill each hit tile without gaps");
                    pngBytes += entry.getValue().length;
                    pngs++;
                } else {
                    var providers = JsonParser.parseString(new String(entry.getValue(), StandardCharsets.UTF_8))
                        .getAsJsonObject().getAsJsonArray("providers");
                    variants += providers.size();
                    for (var value : providers) {
                        var provider = value.getAsJsonObject();
                        int top = 7 - provider.get("ascent").getAsInt(), h = provider.get("height").getAsInt();
                        check(top >= 0 && top <= 17 && h <= 9
                            && provider.get("ascent").getAsInt() <= h, "Only current-row hit offsets are prepared");
                    }
                    jsonBytes += entry.getValue().length;
                }
            }
        }
        packedBytes = compressed.size();
        check(heights.equals(Set.of(1,2,3,4,5,6,7,8,9)),
            "Fixed hit heights cover one native row without depending on visible font size");
        check(pngBytes < 65536, "Blank hit PNGs remain small");
        check(pngBytes + jsonBytes < 102400 && packedBytes < 102400, "All hit assets fit the 100KiB budget");
        System.out.println("Hit assets PNGs=" + pngs + " PNG-bytes=" + pngBytes + " providers=" + variants
            + " JSON-bytes=" + jsonBytes + " ZIP-bytes=" + packedBytes);
    }

    private static Map<String, byte[]> files(Path pack) throws IOException {
        Map<String, byte[]> result = new TreeMap<>();
        try (var zip = new ZipFile(pack.toFile())) {
            for (var entry : zip.stream().filter(e -> !e.isDirectory()).toList())
                try (var input = zip.getInputStream(entry)) { result.put(entry.getName(), input.readAllBytes()); }
        }
        return result;
    }

    private static void same(Map<String, byte[]> expected, Map<String, byte[]> actual) {
        check(expected.keySet().equals(actual.keySet()), "Coordinate updates add no resource entries");
        for (String name : expected.keySet())
            check(Arrays.equals(expected.get(name), actual.get(name)), "Coordinate updates change no resource bytes: " + name);
    }

    private static void check(boolean value, String message) {
        if (!value) throw new AssertionError(message);
        checks++;
    }
}
