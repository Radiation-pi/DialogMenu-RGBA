package dev.dialogmenu;

import com.google.gson.*;
import java.awt.image.BufferedImage;
import java.nio.file.*;
import java.util.*;
import java.util.concurrent.TimeUnit;
import javax.imageio.ImageIO;
import net.kyori.adventure.text.event.ClickEvent;
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
        var renderer = new MenuRenderer(new VanillaAssets(data.resolve("images")), true);
        var fonts = FontPack.create(List.of(doc), renderer, CONTEXT, List.of(300), 131072);
        var frame = renderer.draw(doc.menu(), CONTEXT, 300, 131072);
        List<String> bound = new ArrayList<>();
        var output = new SpriteCanvas().encode(doc.menu(), frame, CONTEXT, fonts, region -> {
            bound.add(region.id());
            return ClickEvent.runCommand("/test/" + region.id());
        });
        check(bound.equals(List.of("frontend.image", "frontend.text")), "Callbacks belong only to enabled elements");
        var glyphs = MenuText.glyphs(output.component());
        var visible = glyphs.stream().filter(g -> g.style().font() != null
            && g.style().font().value().equals("images_rgba")).toList();
        check(visible.size() == 2, "Both image elements use the shared shader font");
        check(visible.stream().allMatch(g -> g.style().clickEvent() == null && g.style().hoverEvent() == null),
            "Visible shader glyphs must not publish a source-size hit box");
        check(((visible.getFirst().style().color().value() >> 14) & 511) + 1 == 143
            && ((visible.getFirst().style().color().value() >> 5) & 511) + 1 == 37,
            "Actual YAML dimensions reach the outgoing payload");
        int imageAt = glyphs.indexOf(visible.getFirst());
        int textAt = -1;
        for (int i = 0; i < glyphs.size(); i++)
            if (glyphs.get(i).style().font() != null && glyphs.get(i).style().font().value().startsWith("text_rgba_")) {
                textAt = i;
                break;
            }
        check(imageAt < textAt && textAt < glyphs.indexOf(visible.getLast()), "Native rows retain mixed image/text z order");
        check(glyphs.stream().anyMatch(g -> g.style().font() != null
            && g.style().font().value().startsWith("image_hit_") && g.style().clickEvent() != null),
            "Resized image has native hit geometry, not only visible stretching");
        check(glyphs.stream().anyMatch(g -> g.style().hoverEvent() != null && g.style().clickEvent() == null),
            "Disabled image keeps hover without executing its stored action");
        Path pack = data.resolve("pack");
        fonts.write(pack);
        List<Path> pngs;
        try (var files = Files.walk(pack)) {
            pngs = files.filter(p -> p.getFileName().toString().startsWith("rgba_image_")).toList();
        }
        check(pngs.size() == 1, "Different sizes and baselines share one image PNG");
        try (var files = Files.list(pack.resolve("assets/dialogmenu/font"))) {
            check(files.filter(p -> p.getFileName().toString().matches("(image|text)_hit_.*\\.json")).count() == 2,
                "Native image and text proxies use two shared font files, not a JSON per offset");
        }
        BufferedImage wrapped = ImageIO.read(pngs.getFirst().toFile());
        for (int y = 0; y < 16; y++) for (int x = 0; x < 16; x++)
            check(wrapped.getRGB(x + 1, y + 1) == image.getRGB(x, y), "Source RGB and transparency are unchanged");
        for (String file : List.of("text.vsh", "text.fsh"))
            check(Files.exists(pack.resolve("assets/minecraft/shaders/core/" + file)), "Shader resources are bundled");

        for (int[] dimensions : List.of(new int[]{1, 512}, new int[]{512, 1}, new int[]{143, 37}, new int[]{512, 512})) {
            int w = dimensions[0], h = dimensions[1];
            boolean[][] covered = new boolean[h][w];
            for (var tile : RgbaImages.tiles(w, h)) {
                check(tile.size() <= 256, "Native hit tiles fit the font limit");
                for (int y = tile.y(); y < tile.y() + tile.size(); y++)
                    for (int x = tile.x(); x < tile.x() + tile.size(); x++) {
                        check(!covered[y][x], "Hit tiles do not overlap");
                        covered[y][x] = true;
                    }
            }
            for (boolean[] row : covered) for (boolean pixel : row) check(pixel, "Hit tiles cover the full stretched image");
            for (int local : List.of(-8, -1, 0, 1, 8)) {
                int payload = RgbaImages.payload(w, h, local);
                int decodedY = payload & 31;
                if (decodedY >= 16) decodedY -= 32;
                check(((payload >> 14) & 511) + 1 == w && ((payload >> 5) & 511) + 1 == h && decodedY == local,
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
            check(!engine.prepare().get(60, TimeUnit.SECONDS).snapshot().fonts().shader(),
                "Existing installations remain on the original backend");
            Files.writeString(data.resolve("config.yml"), Files.readString(data.resolve("config.yml")) + "shader-rendering: true\n");
            var shader = engine.prepare().get(60, TimeUnit.SECONDS).snapshot();
            check(!shader.fonts().shader(), "Legacy config cannot turn the native edition into the RGBA edition");
            check(!engine.prepare().get(60, TimeUnit.SECONDS).changed(), "Unchanged input reuses its snapshot");
            Files.writeString(data.resolve("config.yml"), Files.readString(data.resolve("config.yml"))
                .replace("shader-rendering: true", "shader-rendering: false"));
            check(!engine.prepare().get(60, TimeUnit.SECONDS).snapshot().fonts().shader(),
                "Native edition remains native after another config reload");
            check(!Files.exists(data.resolve("resourcepack/assets/minecraft/shaders/core/text.vsh")),
                "Returning to native rendering removes generated shader overrides");
        }
        System.out.println("PASS optional shader checks=" + checks);
        return checks;
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
