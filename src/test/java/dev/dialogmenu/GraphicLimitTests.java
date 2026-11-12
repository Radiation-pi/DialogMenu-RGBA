package dev.dialogmenu;

import java.nio.file.*;
import java.util.*;
import net.kyori.adventure.text.serializer.gson.GsonComponentSerializer;
import static dev.dialogmenu.MenuModel.*;

/** Shapes have dedicated geometry; symbols and images keep source-proportional geometry. */
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
                var sprite = frame.sprites().getFirst();
                check(PrimitiveShapes.type(kind) ? sprite.width() == 512 && sprite.height() == 512
                    : sprite.width() == Math.max(1, Math.round(sprite.height()
                    * sprite.original().getWidth() / (float) sprite.original().getHeight())),
                    "Shape dimensions and proportional image geometry");
            }
            check(!encoded.getFirst().equals(encoded.getLast()), "Graphic coloring must not be removed: " + kind);
            for (int[] size : List.of(new int[]{513, 512}, new int[]{512, 513})) {
                var doc = doc(kind, size[0], size[1], "#FF5555", 1);
                var frame = renderer.drawRuntime(doc.menu(), CONTEXT, 1014, 1_038_336);
                check(frame.sprites().size() == 1, "Former 512 boundary does not split " + kind);
            }
            var scaled = renderer.drawRuntime(doc(kind, 256, 256, "#FF5555", 2.01).menu(), CONTEXT, 1014, 1_038_336);
            check(scaled.sprites().size() == 1, "Proportional graphic scale does not require new tiles");
        }
        for (String kind : List.of("rectangle", "frame", "line")) {
            for (int[] size : List.of(new int[]{900, 2}, new int[]{2, 900}, new int[]{800, 600})) {
                var doc = doc(kind, size[0], size[1], "#FF5555", 1);
                var frame = renderer.drawRuntime(doc.menu(), CONTEXT, 1014, 1_038_336);
                var sprite = frame.sprites().getFirst();
                check(sprite.width() == size[0] && sprite.height() == size[1], "Independent primitive dimensions");
                check(sprite.original().getWidth() == 1 && sprite.original().getRGB(0, 0) == -1,
                    "Dedicated white source rather than a text glyph");
                new SpriteCanvas().encode(doc.menu(), frame, CONTEXT, fonts, region -> null);
                check(true, "New primitive dimensions encode with already prepared resources");
            }
        }
        for (int h = 1; h <= 1024; h++) for (int y : List.of(0, 1, 8, 9, 17)) {
            for (var slice : RgbaRows.slices(y, h)) {
                int payload = PrimitiveShapes.payload(1014, h, slice.local(y), slice.band());
                int geometry = payload >>> 10 & 1048575;
                int decoded = (int) Math.floor(((float) Math.sqrt(8 * geometry + 1225) - 33) / 2);
                check(decoded == h && (payload & 1023) + 1 == 1014, "Shader primitive dimension decode");
            }
        }
        List<Object> fixtures = new ArrayList<>();
        for (String kind : List.of("rectangle", "frame", "line")) {
            for (int[] size : List.of(new int[]{900, 2}, new int[]{2, 900}, new int[]{800, 600},
                new int[]{1014, 1024}, new int[]{1, 1}, new int[]{513, 511})) {
                for (String color : List.of("#FF5555", "#55FF55")) {
                    var base = doc(kind, size[0], size[1], color, 1);
                    String yaml = base.sourceYaml().replace("thickness: " + size[1], "thickness: 3");
                    var doc = LayoutStore.loadText("graphics", yaml).get("graphics");
                    var frame = renderer.drawRuntime(doc.menu(), CONTEXT, 1014, 1_038_336);
                    var sprite = frame.sprites().getFirst();
                    var output = new SpriteCanvas().encode(doc.menu(), frame, CONTEXT, fonts, region -> null);
                    var preview = renderer.draw(doc.menu(), CONTEXT, 1014, 1_038_336).image();
                    boolean hollow = kind.equals("frame") && Math.min(size[0], size[1]) > 6;
                    check(preview.getRGB(sprite.x(), sprite.y()) == (0xFF000000 | Integer.parseInt(color.substring(1),16)),
                        "Primitive preview corner has exact tint");
                    check((preview.getRGB(sprite.x()+size[0]/2, sprite.y()+size[1]/2) >>> 24 == 0) == hollow,
                        "Frame interior is transparent and filled primitives are solid");
                    fixtures.add(Map.of("type", kind, "width", size[0], "height", size[1], "thickness", 3,
                        "x", sprite.x(), "y", sprite.y(), "color", color, "component",
                        com.google.gson.JsonParser.parseString(GsonComponentSerializer.gson().serialize(output.component()))));
                }
            }
        }
        Files.writeString(data.resolve("primitives.json"), new com.google.gson.Gson().toJson(fixtures));
        Path again = data.resolve("pack-again");
        fonts.write(again);
        try (var files = Files.walk(pack)) {
            for (Path file : files.filter(Files::isRegularFile).toList())
                check(Arrays.equals(Files.readAllBytes(file), Files.readAllBytes(again.resolve(pack.relativize(file)))),
                    "Changing primitive sizes does not create new resources");
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
        System.out.println("PASS proportional graphics=" + checks);
        return checks;
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
                scale: %s
            """.formatted(kind.startsWith("symbol:") ? "image" : kind,
                kind.startsWith("symbol:") ? kind : "", width, height, height, color, scale);
        return LayoutStore.loadText("graphics", yaml).get("graphics");
    }
}
