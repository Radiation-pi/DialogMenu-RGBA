package dev.dialogmenu;

import com.google.gson.*;
import java.awt.image.BufferedImage;
import java.nio.file.*;
import java.util.*;
import java.util.concurrent.TimeUnit;
import javax.imageio.ImageIO;
import net.kyori.adventure.text.event.ClickEvent;
import net.kyori.adventure.text.serializer.gson.GsonComponentSerializer;
import org.bukkit.configuration.file.YamlConfiguration;
import static dev.dialogmenu.MenuModel.*;

/** 通过实际 YAML、资源导出和回调组件验证可选渲染路径。 */
final class ShaderTests {
    private static int checks;
    private static final Context CONTEXT = new Context() {
        public String expand(String text) { return text; }
        public Icon item(int slot) { return null; }
        public boolean permitted(String permission) { return true; }
    };
    private static final String YAML = """
        schema-version: 1
        id: shader-test
        canvas: {width: 300, height: 270}
        frontend:
          image:
            type: image
            source: sample.png
            width: 143
            height: 37
            color: '#55AAFF'
            offset: {x: -50, y: 50}
            actions: ['tell: IMAGE']
            tooltip: Image
          text:
            type: text
            content: 'A中'
            offset: {x: -50, y: 50, z: 1}
            actions: ['tell: TEXT']
          cover:
            type: image
            source: sample.png
            width: 16
            height: 64
            color: '#55AAFF'
            offset: {x: 70, y: 80, z: 2}
            clickable: false
            actions: ['tell: DISABLED']
            tooltip: Disabled
        """;

    static int run(Path temporary) throws Exception {
        checks = 0;
        check(RgbaOffsets.commonCharacters().codePoints().distinct().count() == 3500,
            "The full requested common Chinese character set ships with the shader backend");
        Path data = temporary.resolve("shader-test");
        Files.createDirectories(data.resolve("images"));
        Files.createDirectories(data.resolve("layouts"));
        BufferedImage image = new BufferedImage(16, 16, BufferedImage.TYPE_INT_ARGB);
        for (int y = 0; y < 16; y++) for (int x = 0; x < 16; x++)
            image.setRGB(x, y, x < 8 ? 0xFF00DCE7 : 0x80FABE14);
        ImageIO.write(image, "png", data.resolve("images/sample.png").toFile());
        Files.writeString(data.resolve("layouts/shader-test.yml"), YAML);
        var doc = parse(YAML);
        var renderer = new MenuRenderer(new VanillaAssets(data.resolve("images")));
        var fonts = FontPack.create(List.of(doc), renderer, CONTEXT, List.of(300), 131072);
        var frame = renderer.draw(doc.menu(), CONTEXT, 300, 131072);
        List<String> bound = new ArrayList<>();
        var output = new SpriteCanvas().encode(doc.menu(), frame, CONTEXT, fonts, region -> {
            bound.add(region.id());
            return ClickEvent.runCommand("/test/" + region.id());
        });
        check(bound.equals(List.of("frontend.image", "frontend.text")), "Callbacks belong only to enabled elements");
        var glyphs = MenuText.glyphs(ResourceRegressionTests.textOnly(output.component()));
        var parts = new ArrayList<JsonObject>();
        parts(JsonParser.parseString(GsonComponentSerializer.gson().serialize(output.component())), parts);
        var visible = parts.stream().filter(p -> p.has("sprite")).toList();
        check(visible.size() > 2, "Cross-row images are emitted into their physical row bands");
        check(visible.stream().allMatch(g -> !g.has("click_event") && !g.has("hover_event")),
            "Visible shader glyphs must not publish a source-size hit box");
        var sample = visible.stream().filter(g -> ((g.get("shadow_color").getAsInt() >> 19) & 511) + 1 == 143)
            .findFirst().orElseThrow();
        check(((sample.get("shadow_color").getAsInt() >> 19) & 511) + 1 == 143
            && ((sample.get("shadow_color").getAsInt() >> 10) & 511) + 1 == 37,
            "Actual YAML dimensions reach the outgoing payload");
        int imageAt = -1;
        int textAt = -1;
        for (int i = 0; i < parts.size(); i++)
            if (parts.get(i).has("font") && parts.get(i).get("font").getAsString().startsWith("dialogmenu:text_rgba_")) {
                textAt = i;
                break;
            }
        for (int i = textAt - 1; i >= 0; i--) {
            if (parts.get(i).has("text") && parts.get(i).get("text").getAsString().contains("\n")) break;
            if (parts.get(i).has("sprite")) imageAt = i;
        }
        check(imageAt >= 0 && imageAt < textAt, "Within each shared row, text remains above the lower image");
        check(glyphs.stream().anyMatch(g -> g.style().font() != null
            && g.style().font().equals(RgbaHits.FONT) && g.style().clickEvent() != null),
            "Resized image has native hit geometry, not only visible stretching");
        check(glyphs.stream().anyMatch(g -> g.style().hoverEvent() != null && g.style().clickEvent() == null),
            "Disabled image keeps hover without executing its stored action");
        Path pack = data.resolve("pack");
        fonts.write(pack);
        narrowHitBounds(data, renderer);
        stretchedText(data, renderer);
        List<Path> pngs;
        try (var files = Files.walk(pack)) {
            pngs = files.filter(p -> p.toString().contains("/textures/registered/")
                && p.toString().endsWith(".png")).toList();
        }
        check(pngs.size() == 1, "Different sizes and baselines share one image PNG");
        try (var files = Files.list(pack.resolve("assets/dialogmenu/font"))) {
            check(files.anyMatch(p -> p.getFileName().toString().equals("row_hit.json")),
                "Text proxies use dedicated fixed-size fonts with prebuilt offsets");
        }
        BufferedImage wrapped = ImageIO.read(pngs.getFirst().toFile());
        for (int y = 0; y < 16; y++) for (int x = 0; x < 16; x++)
            check(wrapped.getRGB(x + 2, y + 2) == image.getRGB(x, y), "Source RGB and transparency are unchanged");
        check((wrapped.getRGB(1, 0) >>> 24) == fonts.imageTint(0xFFFFFF),
            "Image metadata selects original color unless explicitly registered as tintable");
        for (String file : List.of("rendertype_text.vsh", "rendertype_text.fsh"))
            check(Files.exists(pack.resolve("assets/minecraft/shaders/core/" + file)), "Shader resources are bundled");

        for (int[] dimensions : List.of(new int[]{1, 9}, new int[]{512, 1}, new int[]{143, 7}, new int[]{512, 9})) {
            int w = dimensions[0], h = dimensions[1];
            boolean[][] covered = new boolean[h][w];
            for (var tile : RgbaHits.tiles(w, h)) {
                check(tile.height() <= 9 && tile.width() <= 2, "Native hit tiles use at most two columns and one row");
                for (int y = tile.y(); y < tile.y() + tile.height(); y++)
                    for (int x = tile.x(); x < tile.x() + tile.width(); x++) {
                        check(!covered[y][x], "Hit tiles do not overlap");
                        covered[y][x] = true;
                    }
            }
            for (boolean[] row : covered) for (boolean pixel : row) check(pixel, "Hit tiles cover the full stretched image");
            for (int local : List.of(-8, -1, 0, 1, 8)) {
                int payload = RgbaImages.payload(w, h, local, 9);
                int decodedY = payload & 1023;
                if (decodedY >= 512) decodedY -= 1024;
                check(((payload >> 19) & 511) + 1 == w && ((payload >> 10) & 511) + 1 == h && decodedY == local,
                    "Width, height and signed offset do not corrupt each other");
            }
        }
        // 单图新增绝对偏移时使用原生换行，不增加 PNG 或字体定义。
        Map<String, byte[]> first = null;
        for (int y : List.of(50, 23, -4)) {
            var shifted = parse(YAML.substring(0, YAML.indexOf("  text:"))
                .replace("y: 50", "y: " + y));
            var f = FontPack.create(List.of(shifted), renderer, CONTEXT, List.of(300), 131072);
            Path target = data.resolve("offset-" + y);
            f.write(target);
            Map<String, byte[]> files = resources(target);
            if (first == null) first = files;
            else {
                check(first.keySet().equals(files.keySet()), "Whole-row movement adds no image or hit resources");
                for (String name : first.keySet())
                    check(Arrays.equals(first.get(name), files.get(name)), "Whole-row movement reuses existing bytes");
            }
        }
        Files.writeString(data.resolve("config.yml"), """
            default-layout: shader-test
            canvas-width: 300
            max-canvas-pixels: 393216
            text-scales: [1]
            """);
        try (var engine = new ReloadEngine(data, CONTEXT)) {
            check(engine.prepare().get(60, TimeUnit.SECONDS).snapshot().fonts().textFont(8).value().equals("text_rgba_8"),
                "The installed edition always prepares RGBA fonts");
            Files.writeString(data.resolve("config.yml"), Files.readString(data.resolve("config.yml")) + "shader-rendering: true\n");
            var shader = engine.prepare().get(60, TimeUnit.SECONDS).snapshot();
            check(shader.fonts().textFont(8).value().equals("text_rgba_8"), "Legacy true does not change RGBA fonts");
            check(!engine.prepare().get(60, TimeUnit.SECONDS).changed(), "Unchanged input reuses its snapshot");
            Files.writeString(data.resolve("config.yml"), Files.readString(data.resolve("config.yml"))
                .replace("shader-rendering: true", "shader-rendering: false"));
            var legacyFalse = engine.prepare().get(60, TimeUnit.SECONDS).snapshot();
            check(legacyFalse.fonts().textFont(8).value().equals("text_rgba_8"),
                "Legacy false does not change RGBA fonts");
            try (var zip = new java.util.zip.ZipFile(legacyFalse.pack().toFile())) {
                check(zip.getEntry("assets/minecraft/shaders/core/rendertype_text.vsh") != null
                    && zip.getEntry("dialogmenu_26_3/assets/minecraft/shaders/core/text.vsh") != null,
                    "Published ZIP retains RGBA shader overrides");
            }
        }
        System.out.println("PASS optional shader checks=" + checks);
        return checks;
    }

    private static void parts(JsonElement value, List<JsonObject> result) {
        if (!value.isJsonObject()) return;
        JsonObject object = value.getAsJsonObject();
        result.add(object);
        if (object.has("extra")) for (JsonElement child : object.getAsJsonArray("extra")) parts(child, result);
    }

    private static void narrowHitBounds(Path data, MenuRenderer renderer) throws Exception {
        var doc = parse("""
            schema-version: 1
            id: shader-test
            canvas: {width: 300, height: 72}
            frontend:
              arrow: {type: text, content: i, size: 6, alignment: right,
                offset: {x: 150}, actions: ['tell: NARROW_OK']}
              wide: {type: text, content: W, size: 6, offset: {x: -80}}
            """);
        var fonts = FontPack.create(List.of(doc), renderer, CONTEXT, List.of(300), 131072);
        Path pack = data.resolve("narrow-pack");
        fonts.write(pack);
        var body = new SpriteCanvas().encode(doc.menu(), renderer.draw(doc.menu(), CONTEXT, 300, 131072),
            CONTEXT, fonts, region -> ClickEvent.runCommand("/NARROW_OK")).component();
        var hit = MenuText.glyphs(body).stream().filter(g -> g.style().font() != null
            && g.style().font().equals(RgbaHits.FONT)).findFirst().orElseThrow();
        check(hit.style().clickEvent() != null, "Narrow right-edge text retains its click action");
        var providers = JsonParser.parseString(Files.readString(
            pack.resolve("assets/dialogmenu/font/" + hit.style().font().value() + ".json")))
            .getAsJsonObject().getAsJsonArray("providers");
        for (var value : providers) {
            var provider = value.getAsJsonObject();
            if (provider.getAsJsonArray("chars").get(0).getAsString().codePointAt(0) != hit.code()) continue;
            String file = provider.get("file").getAsString().replace("dialogmenu:", "assets/dialogmenu/textures/");
            var image = ImageIO.read(pack.resolve(file).toFile());
            int advance = 1;
            check(advance <= fonts.textWidth("i", 6),
                "A narrow click proxy must not wrap past the visible text's right edge");
            return;
        }
        throw new AssertionError("Narrow hit font provider missing");
    }

    private static void stretchedText(Path data, MenuRenderer renderer) throws Exception {
        var doc = parse("""
            schema-version: 1
            id: shader-test
            canvas: {width: 300, height: 270}
            frontend:
              label: {type: text, content: 'A中', size: 8, width: 120,
                offset: {x: -40, y: 50}, scale: {x: 1.5, y: 2},
                actions: ['tell: STRETCHED']}
            """);
        var fonts = FontPack.create(List.of(doc), renderer, CONTEXT, List.of(300), 131072);
        Path pack = data.resolve("stretched-text-pack");
        fonts.write(pack);
        var frame = renderer.draw(doc.menu(), CONTEXT, 300, 131072);
        var output = new SpriteCanvas().encode(doc.menu(), frame, CONTEXT, fonts,
            region -> ClickEvent.runCommand("/" + region.id()));
        String variant = RgbaText.FONT;
        var ink = MenuText.glyphs(output.component()).stream()
            .filter(g -> g.style().font() != null && g.style().font().value().equals(variant))
            .findFirst().orElseThrow();
        check(ink.style().color() != null, "Stretched text still uses the RGBA palette protocol");
        var providers = JsonParser.parseString(Files.readString(pack.resolve(
            "assets/dialogmenu/font/" + variant + ".json"))).getAsJsonObject()
            .getAsJsonArray("providers");
        var bitmap = providers.get(0).getAsJsonObject();
        check(bitmap.get("height").getAsInt() == 1,
            "Y stretch keeps a fixed bitmap-font height");
        Path texture = pack.resolve(bitmap.get("file").getAsString()
            .replace("dialogmenu:", "assets/dialogmenu/textures/"));
        BufferedImage atlas = ImageIO.read(texture.toFile());
        check(atlas.getHeight() % 2 == 0 && atlas.getWidth() > 0,
            "Stretched text exports a transformed glyph atlas");
        check(MenuText.glyphs(output.component()).stream().anyMatch(g -> g.style().font() != null
            && g.style().font().equals(RgbaHits.FONT) && g.style().clickEvent() != null),
            "Stretched text retains an interactive hit proxy");
    }

    private static LayoutStore.Document parse(String source) throws Exception {
        var yaml = new YamlConfiguration();
        yaml.loadFromString(source);
        return LayoutStore.parse("shader-test", yaml);
    }
    private static Map<String, byte[]> resources(Path root) throws Exception {
        Map<String, byte[]> result = new TreeMap<>();
        try (var files = Files.walk(root)) {
            for (Path path : files.filter(Files::isRegularFile).toList())
                result.put(root.relativize(path).toString(), Files.readAllBytes(path));
        }
        return result;
    }
    private static void check(boolean condition, String message) {
        checks++;
        if (!condition) throw new AssertionError(message);
    }
}
