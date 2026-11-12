package dev.dialogmenu;

import com.google.gson.*;
import java.awt.image.BufferedImage;
import java.nio.file.*;
import java.util.*;
import javax.imageio.ImageIO;
import net.kyori.adventure.text.event.ClickEvent;
import net.kyori.adventure.text.serializer.gson.GsonComponentSerializer;
import static dev.dialogmenu.MenuModel.*;

/** Actual pack pixels and proportional image geometry without display tiling. */
final class ProportionalImageTests {
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
    static int run(Path root) throws Exception {
        checks = 0;
        Path data = Files.createDirectories(root.resolve("proportional-images"));
        Path images = Files.createDirectories(data.resolve("images"));
        List<LayoutStore.Document> docs = new ArrayList<>();
        for (int[] size : List.of(new int[]{1, 1}, new int[]{15, 17}, new int[]{256, 256}, new int[]{239, 160})) {
            String name = size[0] + "x" + size[1];
            var image = new BufferedImage(size[0], size[1], BufferedImage.TYPE_INT_ARGB);
            for (int y = 0; y < size[1]; y++) for (int x = 0; x < size[0]; x++)
                image.setRGB(x, y, ((x + y) % 13 == 12 ? 0x80000000 : 0xFF000000)
                    | (x * 67 + y * 13 & 255) << 16 | (x * 11 + y * 71 & 255) << 8 | (x * 43 + y * 29 + 63 & 255));
            ImageIO.write(image, "png", images.resolve(name + ".png").toFile());
            docs.add(doc(name, 1014, 1024));
        }
        var wide = new BufferedImage(256, 16, BufferedImage.TYPE_INT_ARGB);
        ImageIO.write(wide, "png", images.resolve("wide.png").toFile());
        var renderer = new MenuRenderer(new VanillaAssets(images));
        boolean narrowRejected = false;
        try { renderer.drawRuntime(doc("wide", 1, 1024).menu(), CONTEXT, 1014, 1_038_336); }
        catch (IllegalArgumentException error) { narrowRejected = error.getMessage().contains("显示框过窄"); }
        check(narrowRejected, "An unrepresentable subpixel height must not overflow the image's contain box");
        for (String extra : List.of("rotation: {z: 12}", "opacity: 128", "offset: {x: 400}")) {
            String yaml = """
                schema-version: 1
                id: invalid
                canvas: {width: 300, height: 180}
                frontend:
                  image:
                    type: image
                    source: 15x17.png
                    width: 32
                    height: 32
                    %s
                """.formatted(extra);
            var invalid = LayoutStore.loadText("invalid", yaml).get("invalid");
            boolean rejected = false;
            try { renderer.drawRuntime(invalid.menu(), CONTEXT, 300, 1_038_336); }
            catch (java.io.IOException error) { rejected = error.getMessage().contains("运行时不重绘"); }
            check(rejected, "No hidden raster/crop fallback for " + extra);
        }
        // Only one compile-time width. All subsequent dimensions must reuse these exact resources.
        var fonts = FontPack.create(docs, renderer, CONTEXT, List.of(300), 1_038_336);
        Path pack = data.resolve("pack");
        fonts.write(pack);
        Map<String, byte[]> before = files(pack);
        List<Object> fixtures = new ArrayList<>();
        for (var prepared : docs) {
            String name = prepared.menu().id();
            for (int[] size : List.of(new int[]{511, 511}, new int[]{512, 512}, new int[]{513, 512},
                new int[]{512, 513}, new int[]{513, 513}, new int[]{661, 397}, new int[]{1014, 1024},
                new int[]{1013, 1023}, new int[]{1, 1024}, new int[]{1014, 1}, new int[]{512, 1})) {
                var doc = doc(name, size[0], size[1]);
                var frame = renderer.drawRuntime(doc.menu(), CONTEXT, 1014, 1_038_336);
                Sprite sprite = frame.sprites().getFirst();
                check(sprite.image() == null && sprite.original() != null, "No target-sized image rasterization");
                List<String> actions = new ArrayList<>();
                var output = new SpriteCanvas().encode(doc.menu(), frame, CONTEXT, fonts, region -> {
                    actions.add(region.id());
                    return ClickEvent.runCommand("/tile");
                });
                check(actions.equals(List.of("frontend.image")), "One logical action for an image");
                JsonElement encoded = JsonParser.parseString(GsonComponentSerializer.gson().serialize(output.component()));
                List<Object> quads = new ArrayList<>();
                int[] cursor = {0, 0, 0};
                collect(encoded, "", cursor, quads);
                check(cursor[2] == RgbaRows.slices(sprite.y(), sprite.height()).size()
                        * ((sprite.width() + 1) / 2),
                    "Hit coverage follows the proportional display bounds");
                Set<String> sources = new HashSet<>();
                for (Object quad : quads) sources.add((String) ((Map<?, ?>) quad).get("sprite"));
                check(sources.size() == 1 && sources.stream().noneMatch(source -> source.contains("tile_")),
                    "One source texture is used at every display size");
                check(sprite.width() <= size[0] && sprite.height() <= size[1], "Image stays inside its contain box");
                check(Math.abs(sprite.width() * (double) sprite.original().getHeight()
                    - sprite.height() * (double) sprite.original().getWidth()) <= sprite.original().getHeight() / 2.0,
                    "Source aspect ratio is retained to the nearest display pixel");
                fixtures.add(Map.of("source", name + ".png", "boxWidth", size[0], "boxHeight", size[1],
                    "width", sprite.width(), "height", sprite.height(),
                    "x", sprite.x(), "y", sprite.y(), "quads", quads));
            }
            for (int percent = 80; percent <= 220; percent++) {
                var doc = doc(name, 452, 177, 452, 177);
                int width = (int) Math.round(452 * percent / 100.0);
                var frame = renderer.drawRuntime(doc.menu(), CONTEXT, width, 1_038_336);
                Sprite sprite = frame.sprites().getFirst();
                check(sprite.width() <= frame.width() && sprite.height() <= frame.height(),
                    "Personal scale " + percent + " stays inside canvas");
                check(sprite.image() == null && sprite.original() != null, "Personal scale never rasterizes source");
                if (!Set.of(80, 100, 141, 180, 220).contains(percent)) continue;
                var output = new SpriteCanvas().encode(doc.menu(), frame, CONTEXT, fonts,
                    region -> ClickEvent.runCommand("/proportional"));
                List<Object> quads = new ArrayList<>();
                int[] cursor = {0, 0, 0};
                collect(JsonParser.parseString(GsonComponentSerializer.gson().serialize(output.component())), "", cursor, quads);
                fixtures.add(Map.of("source", name + ".png", "boxWidth", width, "boxHeight", frame.height(),
                    "canvasWidth", frame.width(), "canvasHeight", frame.height(),
                    "width", sprite.width(), "height", sprite.height(),
                    "x", sprite.x(), "y", sprite.y(), "quads", quads));
            }
        }
        fonts.write(data.resolve("pack-again"));
        Map<String, byte[]> after = files(data.resolve("pack-again"));
        check(before.keySet().equals(after.keySet()), "Size changes do not add resources");
        for (String file : before.keySet()) check(Arrays.equals(before.get(file), after.get(file)), "Resource unchanged: " + file);
        Files.writeString(data.resolve("fixtures.json"), new Gson().toJson(fixtures));
        System.out.println("PASS proportional image geometry=" + checks + " fixtures=" + data);
        return checks;
    }

    private static LayoutStore.Document doc(String name, int width, int height) throws Exception {
        return doc(name, width, height, 1014, 1024);
    }
    private static LayoutStore.Document doc(String name, int width, int height, int canvasWidth, int canvasHeight) throws Exception {
        String yaml = """
            schema-version: 1
            id: %s
            canvas: {width: %d, height: %d}
            frontend:
              image:
                type: image
                source: %s.png
                width: %d
                height: %d
                actions: 'tell: TILE'
                tooltip: TILE
            """.formatted(name, canvasWidth, canvasHeight, name, width, height);
        return LayoutStore.loadText(name, yaml).get(name);
    }

    private static void collect(JsonElement value, String font, int[] cursor, List<Object> quads) {
        if (value.isJsonPrimitive() && value.getAsJsonPrimitive().isString()) {
            JsonObject text = new JsonObject();
            text.addProperty("text", value.getAsString());
            collect(text, font, cursor, quads);
            return;
        }
        if (value.isJsonArray()) {
            for (var child : value.getAsJsonArray()) collect(child, font, cursor, quads);
            return;
        }
        if (!value.isJsonObject()) return;
        JsonObject object = value.getAsJsonObject();
        if (object.has("font")) font = object.get("font").getAsString();
        if (object.has("sprite")) {
            quads.add(Map.of("sprite", object.get("sprite").getAsString(),
                "payload", object.get("shadow_color").getAsInt(), "x", cursor[0], "row", cursor[1]));
            cursor[0] += 8;
        }
        if (object.has("text")) for (int cp : object.get("text").getAsString().codePoints().toArray()) {
            if (cp == '\n') { cursor[0] = 0; cursor[1]++; }
            else if (font.equals("dialogmenu:space")) cursor[0] += cp - 0xF0000 - 1024;
            else if (font.equals("dialogmenu:row_hit")) {
                check(object.has("click_event"), "Actual hit glyph retains action");
                check(object.has("hover_event"), "Actual hit glyph retains hover");
                cursor[0] += (cp - 0xF0000) / 18 % 2 + 1;
                cursor[2]++;
            }
        }
        if (object.has("extra")) collect(object.get("extra"), font, cursor, quads);
    }
    private static Map<String, byte[]> files(Path root) throws Exception {
        Map<String, byte[]> files = new TreeMap<>();
        try (var paths = Files.walk(root)) {
            for (Path file : paths.filter(Files::isRegularFile).toList())
                files.put(root.relativize(file).toString(), Files.readAllBytes(file));
        }
        return files;
    }
    public static void main(String[] args) throws Exception { run(Path.of(args[0])); }
}
