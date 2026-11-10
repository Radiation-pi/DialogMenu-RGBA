package dev.dialogmenu;

import java.nio.file.*;
import java.util.*;
import net.kyori.adventure.text.serializer.gson.GsonComponentSerializer;
import static dev.dialogmenu.MenuModel.*;

/** Graphic colors remain usable without preparing tiled PNGs; only images may exceed 512. */
final class GraphicLimitTests {
    private static final Context CONTEXT = new Context() {
        public String expand(String value) { return value; }
        public Icon item(int slot) { return null; }
        public boolean permitted(String permission) { return true; }
    };
    private static int checks;
    public static void main(String[] args) throws Exception { run(Path.of(args[0])); }
    private static void check(boolean value, String message) {
        if (!value) throw new AssertionError(message);
        checks++;
    }
    static int run(Path root) throws Exception {
        checks = 0;
        Path data = Files.createDirectories(root.resolve("graphic-limit"));
        var renderer = new MenuRenderer(new VanillaAssets(data));
        List<String> kinds = new ArrayList<>(List.of("rectangle", "frame", "line"));
        kinds.addAll(SymbolImages.SYMBOLS.stream().map(symbol -> "symbol:" + symbol).toList());
        List<LayoutStore.Document> docs = new ArrayList<>();
        for (String kind : kinds) for (String color : List.of("#FF5555", "#55FF55"))
            docs.add(doc(kind, 512, 512, color, 1));
        var fonts = FontPack.create(docs, renderer, CONTEXT, List.of(1014), 1_038_336);
        Path pack = data.resolve("pack");
        fonts.write(pack);
        try (var files = Files.walk(pack)) {
            check(files.noneMatch(p -> p.getFileName().toString().startsWith("tile_")),
                "A graphic-only pack must not contain precompiled image tiles");
        }
        for (String kind : kinds) {
            List<String> encoded = new ArrayList<>();
            for (String color : List.of("#FF5555", "#55FF55")) {
                var doc = doc(kind, 512, 512, color, 1);
                var frame = renderer.drawRuntime(doc.menu(), CONTEXT, 1014, 1_038_336);
                encoded.add(GsonComponentSerializer.gson().serialize(
                    new SpriteCanvas().encode(doc.menu(), frame, CONTEXT, fonts, region -> null).component()));
                check(frame.sprites().getFirst().width() == 512, "512px graphic boundary remains usable");
            }
            check(!encoded.getFirst().equals(encoded.getLast()), "Graphic coloring must not be removed: " + kind);
            for (int[] size : List.of(new int[]{513, 512}, new int[]{512, 513})) {
                var doc = doc(kind, size[0], size[1], "#FF5555", 1);
                rejected(renderer, doc, "Oversized " + kind);
            }
            rejected(renderer, doc(kind, 256, 256, "#FF5555", 2.01), "Scaled " + kind);
        }
        var legacy = LayoutStore.loadText("legacy", """
            schema-version: 1
            id: legacy
            canvas: {width: 300, height: 180}
            frontend:
              icon: {type: image, source: 'minecraft:gui/sprites/container/slot/shield', width: 32, height: 32, color: '#FF5555'}
            """).get("legacy");
        var legacyFonts = FontPack.create(List.of(legacy), renderer, CONTEXT, List.of(300), 1_038_336);
        Path legacyPack = data.resolve("legacy-pack");
        legacyFonts.write(legacyPack);
        String hash = FontPack.hash(renderer.assets.image("minecraft:gui/sprites/container/slot/shield"));
        try (var files = Files.walk(legacyPack)) {
            check(files.filter(p -> p.getFileName().toString().startsWith(hash + "_")).count() == 1,
                "Legacy images must not export unused tint variants");
        }
        System.out.println("PASS image-only graphic limit=" + checks);
        return checks;
    }
    private static void rejected(MenuRenderer renderer, LayoutStore.Document doc, String message) throws Exception {
        boolean rejected = false;
        try { renderer.drawRuntime(doc.menu(), CONTEXT, 1014, 1_038_336); }
        catch (java.io.IOException error) { rejected = error.getMessage().contains("最终显示宽高不得超过512"); }
        check(rejected, message);
    }
    private static LayoutStore.Document doc(String kind, int width, int height, String color, double scale) throws Exception {
        String yaml = """
            schema-version: 1
            id: graphics
            canvas: {width: 1014, height: 1024}
            frontend:
              graphic:
                type: %s
                source: '%s'
                width: %d
                height: %d
                thickness: %d
                color: '%s'
                scale: {x: %s, y: %s}
            """.formatted(kind.startsWith("symbol:") ? "image" : kind,
                kind.startsWith("symbol:") ? kind : "", width, height, height, color, scale, scale);
        return LayoutStore.loadText("graphics", yaml).get("graphics");
    }
}
