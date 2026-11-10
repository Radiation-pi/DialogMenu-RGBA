package dev.dialogmenu;

import com.google.gson.Gson;
import java.nio.file.*;
import java.util.*;
import java.util.concurrent.TimeUnit;
import javax.imageio.ImageIO;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.event.ClickEvent;
import static dev.dialogmenu.MenuModel.*;

/** Geometry changes reuse real exported resources and persisted source glyphs. */
final class UnifiedGeometryTests {
    private static final Context CONTEXT = new Context() {
        public String expand(String text) { return text; }
        public Icon item(int slot) { return null; }
        public boolean permitted(String permission) { return true; }
    };
    private static int checks;
    private static void check(boolean value, String message) {
        if (!value) throw new AssertionError(message);
        checks++;
    }
    static void run(Path root) throws Exception {
        checks = 0;
        for (int height = 1; height <= 128; height++)
            for (int band : List.of(0, 9))
                for (int top = band == 0 ? 0 : 10 - height; top <= (band == 0 ? 8 : 17); top++)
                    for (int width : List.of(1, 257, 512)) for (boolean italic : List.of(false, true)) {
                        int encoded = RgbaText.payload(width, height, top, band, 255, italic);
                        int[] decoded = RowPackTests.textGeometry(encoded);
                        check(decoded[0] == height && decoded[1] == top && decoded[2] == band
                            && ((encoded >>> 8) & 511) + 1 == width && (encoded < 0) == italic,
                            "Independent geometry decoder preserves all row/height/width/style combinations");
                        check(encoded != 0 && encoded != 0xFFBEEF01, "Glyph shadow never vanishes or collides with the hidden pass");
                    }
        Path data = Files.createTempDirectory(root, "unified-");
        Files.createDirectories(data.resolve("layouts"));
        Files.writeString(data.resolve("config.yml"), """
            default-layout: probe
            canvas-width: 512
            max-canvas-pixels: 1048576
            text-scales: [1]
            canvas-scales: [1]
            resource-build-cpu-percent: 100
            text-colors: ['#55AAFF']
            """);
        Path yaml = data.resolve("layouts/probe.yml");
        Files.writeString(yaml, menu(1, 1, 0, "□", 80, 40));
        Path pack;
        byte[] bytes;
        try (var engine = new ReloadEngine(data, CONTEXT)) {
            var first = engine.prepare().get(90, TimeUnit.SECONDS).snapshot();
            pack = first.pack(); bytes = Files.readAllBytes(pack);
            var stamp = Files.getLastModifiedTime(pack);
            for (double x : List.of(.75, 1.25, 2.0, 16.0))
                for (double y : List.of(.5, 1.5, 4.0, 8.0)) {
                    Files.writeString(yaml, menu(x, y, 13, "♦", 257, 73));
                    var result = engine.prepare(ReloadEngine.Mode.YAML).get(90, TimeUnit.SECONDS);
                    check(!result.resourcesChanged() && result.snapshot().pack().equals(pack)
                        && stamp.equals(Files.getLastModifiedTime(pack)) && Arrays.equals(bytes, Files.readAllBytes(pack)),
                        "Text stretch and previously unused symbol geometry keep the exact ZIP");
                    var doc = result.snapshot().documents().get("probe");
                    var output = new SpriteCanvas().encode(doc.menu(),
                        result.snapshot().renderer().drawRuntime(doc.menu(), CONTEXT, 640, 1048576),
                        CONTEXT, result.snapshot().fonts(), region -> ClickEvent.runCommand("/unified"));
                    check(MenuText.glyphs(ResourceRegressionTests.textOnly(output.component())).stream().anyMatch(g -> g.style().clickEvent() != null),
                        "Changed geometry retains outgoing clicks");
                }
        }
        try (var engine = new ReloadEngine(data, CONTEXT)) {
            var restored = engine.restore().get(90, TimeUnit.SECONDS);
            Files.writeString(yaml, menu(3, 3, -17, "○", 300, 50));
            var updated = engine.prepare(ReloadEngine.Mode.YAML).get(90, TimeUnit.SECONDS);
            check(updated.snapshot().pack().equals(pack) && Arrays.equals(bytes, Files.readAllBytes(pack)),
                "New text geometry after restart still uses the frozen source atlas");
        }
        var model = LayoutStore.loadText("model", """
            schema-version: 1
            id: model
            canvas: {width: 300, height: 180}
            frontend:
              block: {type: image, source: 'model:minecraft:stone', size: 24, actions: close}
            """).get("model").menu().nodes().getFirst();
        check(model.type().equals("model") && model.source().equals("minecraft:stone") && !model.interaction().actions().isEmpty(),
            "Unified image entry reaches the real model dispatcher and retains interaction");
        System.out.println("PASS unified geometry checks=" + checks);
    }

    private static String menu(double x, double y, int offset, String symbol, int width, int height) {
        return """
            schema-version: 1
            id: probe
            canvas: {width: 640, height: 640}
            frontend:
              label:
                type: text
                content: '&l&o&nA'
                size: 8
                scale: {x: %s, y: %s}
                offset: {y: %s}
                color: '#55AAFF'
                actions: close
              shape:
                type: image
                source: 'symbol:%s'
                width: %s
                height: %s
                offset: {y: -150}
                color: '#55AAFF'
                actions: close
            """.formatted(x, y, offset, symbol, width, height);
    }

    public static void main(String[] args) throws Exception {
        Path root = Files.createDirectories(Path.of(args[0]));
        if (args.length == 1) { run(root); return; }
        var assets = new VanillaAssets(root.resolve("images"));
        var glyph = assets.glyph('A');
        ImageIO.write(RgbaText.wrap(glyph), "png", root.resolve("glyph.png").toFile());
        for (int cp : "A与工常科".codePoints().toArray()) {
            var source = assets.glyph(cp);
            ImageIO.write(RgbaText.wrap(source), "png", root.resolve("glyph-" + cp + ".png").toFile());
            var pixels = new java.awt.image.BufferedImage(source.width(), source.height(),
                java.awt.image.BufferedImage.TYPE_INT_ARGB);
            for (int y = 0; y < source.height(); y++) for (int x = 0; x < source.width(); x++)
                pixels.setRGB(x, y, Byte.toUnsignedInt(source.alpha()[y * source.width() + x]) << 24 | 0xFFFFFF);
            ImageIO.write(pixels, "png", root.resolve("source-" + cp + ".png").toFile());
        }
        List<Map<String, Object>> cases = new ArrayList<>();
        for (int width : List.of(1, 8, 31, 257, 512))
            for (int height : List.of(1, 8, 37, 128))
                for (int top : List.of(0, 8, 17, 35)) for (boolean italic : List.of(false, true)) {
                    List<Map<String, Integer>> rows = new ArrayList<>();
                    for (var row : RgbaRows.slices(top, height))
                        rows.add(Map.of("row", row.row(), "payload",
                            RgbaText.payload(width, height, row.local(top), row.band(), 0, italic)));
                    cases.add(Map.of("width", width, "height", height, "top", top, "italic", italic, "rows", rows));
                }
        Files.writeString(root.resolve("cases.json"), new Gson().toJson(cases));
    }
}
