package dev.dialogmenu;

import com.google.gson.*;
import java.awt.AlphaComposite;
import java.awt.Color;
import java.awt.image.BufferedImage;
import java.nio.file.*;
import java.util.*;
import javax.imageio.ImageIO;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.event.ClickEvent;
import org.bukkit.configuration.file.YamlConfiguration;
import static dev.dialogmenu.MenuModel.*;

/** 回放导出的资源包，独立检查像素、图层和语义命中目标。 */
final class RowPackTests {
    private record Ink(BufferedImage image, int x, int y, int width, int height, int displayHeight, int top, int advance) {}
    private final Path pack;
    private final Map<String, Map<Integer, Ink>> fonts = new HashMap<>();
    private final Map<Integer, Integer> palette = new HashMap<>();

    private RowPackTests(Path pack) throws Exception {
        this.pack = pack;
        var source = Files.readString(pack.resolve("assets/dialogmenu/shaders/include/rgba_palette.glsl"));
        var colors = java.util.regex.Pattern.compile(
            "case (\\d+): return vec3\\((\\d+)\\.0, (\\d+)\\.0, (\\d+)\\.0\\)").matcher(source);
        while (colors.find()) palette.put(Integer.parseInt(colors.group(1)),
            Integer.parseInt(colors.group(2)) << 16 | Integer.parseInt(colors.group(3)) << 8
                | Integer.parseInt(colors.group(4)));
    }

    static int verify(Path pack, Menu menu, MenuRenderer renderer, Context context, FontPack fonts, int width)
            throws Exception {
        var frame = renderer.draw(menu, context, width, 131072);
        var output = new SpriteCanvas().encode(menu, frame, context, fonts, region -> null);
        var actual = new RowPackTests(pack).render(output.component(), width, frame.image().getHeight());
        var expected = expected(menu, frame, renderer.assets, fonts, context, width);
        ImageIO.write(actual, "png", pack.resolve("updated-base-text.png").toFile());
        for (int y = 0; y < actual.getHeight(); y++) for (int x = 0; x < width; x++)
            if (actual.getRGB(x, y) != expected.getRGB(x, y))
                throw new AssertionError("Unpreviewed base glyph differs from original vanilla pixels: " + x + "," + y);
        return 1;
    }

    static int run(Path temporary) throws Exception {
        Path images = temporary.resolve("row-images");
        Files.createDirectories(images);
        BufferedImage repeated = new BufferedImage(32, 16, BufferedImage.TYPE_INT_ARGB);
        for (int y = 0; y < 16; y++) for (int x = 0; x < 32; x++) repeated.setRGB(x, y, 0xFFFFFFFF);
        ImageIO.write(repeated, "png", images.resolve("repeated.png").toFile());
        var yaml = new YamlConfiguration();
        yaml.loadFromString("""
            schema-version: 1
            id: rows
            canvas: {width: 300, height: 180}
            frontend:
              base:
                type: rectangle
                width: 300
                height: 180
                color: '#334455'
                offset: {z: -10}
              repeated-a:
                type: image
                source: repeated.png
                width: 32
                height: 8
                offset: {x: -100, y: 40}
              repeated-b:
                type: image
                source: repeated.png
                width: 32
                height: 8
                clickable: true
                actions: ['tell: repeated']
                offset: {x: -100, y: 22}
              label:
                type: text
                content: '&lA中B'
                size: 8
                line-width: 200
                max-lines: 1
                clickable: true
                actions: ['tell: label']
                offset: {x: -25, y: 4, z: 0}
              cover:
                type: rectangle
                width: 6
                height: 20
                color: '#ff0000'
                offset: {x: -25, y: 4, z: 1}
              above:
                type: text
                content: 'XYZ'
                line-width: 200
                max-lines: 1
                offset: {x: 65, y: -11, z: 2}
              fractional:
                type: text
                content: 'QÀ中'
                size: 5
                line-width: 200
                max-lines: 1
                offset: {x: 65, y: 50, z: 3}
            """);
        var doc = LayoutStore.parse("rows", yaml);
        Context context = new Context() {
            public String expand(String text) { return text; }
            public Icon item(int slot) { return null; }
            public boolean permitted(String permission) { return true; }
        };
        var assets = new VanillaAssets(temporary.resolve("row-images"));
        var renderer = new MenuRenderer(assets);
        var fontPack = FontPack.create(List.of(doc), renderer, context, List.of(300, 360, 420), 131072,
            "", List.of(1.0, 1.5, 2.0), CanvasScale.OPTIONS);
        Path exported = temporary.resolve("row-pixels");
        fontPack.write(exported);
        var replay = new RowPackTests(exported);
        int checks = 0;
        for (double canvasScale : List.of(0.75, 1.0))
        for (int requestedWidth : List.of(300, 360, 420)) for (double scale : List.of(1.0, 1.5, 2.0)) {
            Context scaled = new Context() {
                public String expand(String text) { return text; }
                public Icon item(int slot) { return null; }
                public boolean permitted(String permission) { return true; }
                public double textScale() { return scale; }
                public double canvasScale() { return canvasScale; }
            };
            var frame = renderer.draw(doc.menu(), scaled, requestedWidth, 131072);
            int width = frame.image().getWidth();
            if (width != Math.round(requestedWidth * canvasScale))
                throw new AssertionError("Canvas zoom must scale the entire frame");
            checks++;
            var output = new SpriteCanvas().encode(doc.menu(), frame, scaled, fontPack,
                region -> ClickEvent.runCommand("/test/" + region.id()));
            BufferedImage actual = replay.render(output.component(), width, frame.image().getHeight());
            BufferedImage expected = expected(doc.menu(), frame, assets, fontPack, scaled, width);
            for (int y = 0; y < actual.getHeight(); y++) for (int x = 0; x < width; x++)
                if (actual.getRGB(x, y) != expected.getRGB(x, y)) {
                    ImageIO.write(actual, "png", exported.resolve("actual.png").toFile());
                    ImageIO.write(expected, "png", exported.resolve("expected.png").toFile());
                    throw new AssertionError("Row pack pixel/z mismatch at " + x + "," + y
                        + " width=" + width + " scale=" + scale + " canvas=" + canvasScale + " files=" + exported);
                }
            if (width == 300 && scale == 1.0) {
                ImageIO.write(actual, "png", exported.resolve("actual.png").toFile());
                ImageIO.write(expected, "png", exported.resolve("expected.png").toFile());
            }
            if (requestedWidth == 420 && scale == 1.0)
                ImageIO.write(actual, "png", exported.resolve("canvas-" + canvasScale + ".png").toFile());
            checks++;
            Set<String> hits = new HashSet<>();
            for (var glyph : MenuText.glyphs(output.component()))
                if (glyph.style().clickEvent() != null) hits.add(glyph.style().clickEvent().toString());
            if (hits.size() != 2) throw new AssertionError("Clipping must retain two independent semantic click targets");
            checks++;
            Sprite hit = frame.sprites().stream().filter(s -> s.id().equals("frontend.repeated-b"))
                .findFirst().orElseThrow();
            String target = ClickEvent.runCommand("/test/frontend.repeated-b").toString();
            for (int y = 0; y < hit.image().getHeight(); y++) {
                int visibleX = -1;
                for (int x = 0; x < hit.image().getWidth(); x++)
                    if (hit.image().getRGB(x, y) >>> 24 != 0) { visibleX = x; break; }
                if (visibleX >= 0 && !target.equals(replay.hit(output.component(), hit.x() + visibleX, hit.y() + y)))
                    throw new AssertionError("The clickable image has an unclickable visible row at y=" + (hit.y() + y));
            }
            if (target.equals(replay.hit(output.component(), hit.x() + 2, hit.y() - 1))
                || target.equals(replay.hit(output.component(), hit.x() + 2, hit.y() + hit.image().getHeight())))
                throw new AssertionError("Padding creates a ghost click outside the image");
            checks += 2;
        }
        for (int y = -20; y <= 20; y++) {
            var moved = new YamlConfiguration();
            moved.loadFromString(yaml.saveToString());
            moved.set("frontend.label.offset.y", y);
            checks += verify(exported, LayoutStore.parse("rows", moved).menu(), renderer, context, fontPack, 300);
        }
        return checks;
    }

    private Map<Integer, Ink> font(String name) throws Exception {
        if (fonts.containsKey(name)) return fonts.get(name);
        String[] key = name.split(":", 2);
        JsonArray providers = JsonParser.parseString(Files.readString(pack.resolve(
            "assets/" + key[0] + "/font/" + key[1] + ".json"))).getAsJsonObject().getAsJsonArray("providers");
        Map<Integer, Ink> result = new HashMap<>();
        for (var element : providers) {
            var provider = element.getAsJsonObject();
            if (provider.get("type").getAsString().equals("space")) {
                for (var entry : provider.getAsJsonObject("advances").entrySet())
                    result.putIfAbsent(entry.getKey().codePointAt(0), new Ink(null, 0, 0, 0, 0, 0, 0, entry.getValue().getAsInt()));
                continue;
            }
            String[] texture = provider.get("file").getAsString().split(":", 2);
            BufferedImage image = ImageIO.read(pack.resolve("assets/" + texture[0] + "/textures/" + texture[1]).toFile());
            if (name.startsWith("dialogmenu:text_rgba_") || name.startsWith("dialogmenu:rule_")) {
                BufferedImage white = new BufferedImage(image.getWidth(), image.getHeight(), BufferedImage.TYPE_INT_ARGB);
                for (int py = 0; py < image.getHeight(); py++) for (int px = 0; px < image.getWidth(); px++)
                    white.setRGB(px, py, image.getRGB(px, py) & 0xFF000000 | 0xFFFFFF);
                image = white;
            }
            JsonArray rows = provider.getAsJsonArray("chars");
            int columns = rows.get(0).getAsString().codePoints().toArray().length;
            int cw = image.getWidth() / columns, ch = image.getHeight() / rows.size();
            int height = provider.get("height").getAsInt();
            for (int y = 0; y < rows.size(); y++) {
                int[] codes = rows.get(y).getAsString().codePoints().toArray();
                for (int x = 0; x < codes.length; x++) if (codes[x] != 0) {
                    int used = 0;
                    for (int py = 0; py < ch; py++) for (int px = 0; px < cw; px++)
                        if (image.getRGB(x * cw + px, y * ch + py) >>> 24 != 0) used = Math.max(used, px + 1);
                    result.putIfAbsent(codes[x], new Ink(image, x * cw, y * ch, cw, ch, height,
                        7 - provider.get("ascent").getAsInt(), (int) (.5 + used * height / (float) ch) + 1));
                }
            }
        }
        fonts.put(name, result);
        return result;
    }

    private BufferedImage render(Component component, int width, int height) throws Exception {
        BufferedImage result = new BufferedImage(width, height, BufferedImage.TYPE_INT_ARGB);
        int x = 0, y = 0;
        for (var glyph : MenuText.glyphs(component)) {
            if (glyph.code() == '\n') { x = 0; y += 9; continue; }
            String font = glyph.style().font().asString();
            Ink ink = font(font).get(glyph.code());
            if (ink == null) throw new AssertionError("Unregistered output glyph: " + font + "/" + glyph.code());
            int color = glyph.style().color() == null ? 0xFFFFFF : glyph.style().color().value();
            int payload = glyph.style().shadowColor() == null ? 0 : glyph.style().shadowColor().value();
            int band = (payload >>> 28 & 1) * 9;
            BufferedImage layer = new BufferedImage(width, height, BufferedImage.TYPE_INT_ARGB);
            if (font.equals("dialogmenu:images_rgba")) {
                int widthPixels = ((payload >> 19) & 511) + 1, heightPixels = ((payload >> 10) & 511) + 1;
                int localY = payload & 1023;
                if (localY >= 512) localY -= 1024;
                int metadata = ink.image().getRGB(ink.x() + 1, ink.y());
                int sourceWidth = metadata >> 16 & 255, sourceHeight = metadata >> 8 & 255;
                for (int py = 0; py < heightPixels; py++) for (int px = 0; px < widthPixels; px++) {
                    int sx = (int) Math.floor((px + .5) * sourceWidth / widthPixels - .0001);
                    int sy = (int) Math.floor((py + .5) * sourceHeight / heightPixels - .0001);
                    blend(layer, x + px, y + localY + py,
                        ink.image().getRGB(ink.x() + 1 + sx, ink.y() + 1 + sy));
                }
            } else if (ink.image() != null && !font.equals("dialogmenu:row_hit")) {
                int top;
                if (font.startsWith("dialogmenu:text_rgba_") || font.startsWith("dialogmenu:rule_")) {
                    top = payload >> 8 & 1023;
                    if (top >= 512) top -= 1024;
                } else {
                    top = payload >> 8 & 2047;
                    if (top >= 1024) top -= 2048;
                    band = top >= 32 ? 0 : 9;
                    if (band == 0) top -= 32;
                }
                color = palette.get(payload & 255);
                draw(layer, ink, x, y + top, color);
                if (glyph.bold()) draw(layer, ink, x + 1, y + top, color);
            }
            for (int py = Math.max(0, y + band); py < Math.min(height, y + band + 9); py++)
                for (int px = 0; px < width; px++) blend(result, px, py, layer.getRGB(px, py));
            x += ink.advance() + (glyph.bold() ? 1 : 0);
        }
        return result;
    }

    private String hit(Component component, int testX, int testY) throws Exception {
        int x = 0, y = 0;
        String result = null;
        for (var glyph : MenuText.glyphs(component)) {
            if (glyph.code() == '\n') { x = 0; y += 9; continue; }
            Ink ink = font(glyph.style().font().asString()).get(glyph.code());
            double width = ink.image() == null ? 0 : ink.advance() + (glyph.bold() ? 1 : 0);
            if (glyph.style().clickEvent() != null && testX >= x && testX < x + width
                && testY >= y + ink.top() && testY < y + ink.top() + ink.displayHeight())
                result = glyph.style().clickEvent().toString();
            x += ink.advance() + (glyph.bold() ? 1 : 0);
        }
        return result;
    }

    private static BufferedImage expected(Menu menu, Rendered frame, VanillaAssets assets, FontPack fonts,
                                           Context context, int width) {
        BufferedImage result = new BufferedImage(width, frame.image().getHeight(), BufferedImage.TYPE_INT_ARGB);
        for (Node node : menu.nodes()) {
            if (!node.type().equals("text")) {
                for (Sprite sprite : frame.sprites()) if (sprite.id().equals(node.id()))
                    overlay(result, sprite.image(), sprite.x(), sprite.y());
            } else {
                int size = (int) Math.round(node.size() * context.textScale()
                    * (menu.scaleTextWithCanvas() ? width / (double) menu.width() : context.canvasScale()));
                var text = MenuText.parse(context.expand(node.text()));
                int advance = fonts.textWidth(text, size);
                int x = (int) Math.round((menu.width() / 2.0 + node.x()) * width / menu.width() - advance / 2.0);
                int y = SpriteCanvas.textTop(menu, node, width, 0, size);
                for (var glyph : MenuText.glyphs(text)) {
                    var source = assets.glyph(glyph.code());
                    var image = new BufferedImage(source.width(), source.height(), BufferedImage.TYPE_INT_ARGB);
                    for (int py = 0; py < source.height(); py++) for (int px = 0; px < source.width(); px++)
                        image.setRGB(px, py, Byte.toUnsignedInt(source.alpha()[py * source.width() + px]) << 24 | 0xFFFFFF);
                    var ink = new Ink(image, 0, 0, source.width(), source.height(), size, 0, 0);
                    int color = glyph.style().color() == null ? node.argb() & 0xFFFFFF : glyph.style().color().value();
                    draw(result, ink, x, y, color);
                    if (glyph.bold()) draw(result, ink, x + 1, y, color);
                    x += fonts.glyphWidth(glyph, size);
                }
            }
        }
        return result;
    }

    private static void draw(BufferedImage target, Ink ink, int ox, int oy, int tint) {
        double scale = ink.displayHeight() / (double) ink.height();
        int width = (int) Math.ceil(ink.width() * scale);
        for (int y = 0; y < ink.displayHeight(); y++) for (int x = 0; x < width; x++) {
            int sx = (int) Math.floor((x + .5) / scale), sy = (int) Math.floor((y + .5) / scale);
            if (sx >= ink.width() || sy >= ink.height()) continue;
            int color = ink.image().getRGB(ink.x() + sx, ink.y() + sy);
            int rgb = ((color >> 16 & 255) * (tint >> 16 & 255) / 255) << 16
                | ((color >> 8 & 255) * (tint >> 8 & 255) / 255) << 8 | (color & 255) * (tint & 255) / 255;
            blend(target, ox + x, oy + y, color & 0xFF000000 | rgb);
        }
    }

    private static void overlay(BufferedImage target, BufferedImage image, int x, int y) {
        for (int py = 0; py < image.getHeight(); py++) for (int px = 0; px < image.getWidth(); px++)
            blend(target, x + px, y + py, image.getRGB(px, py));
    }

    private static void blend(BufferedImage target, int x, int y, int argb) {
        if (x < 0 || x >= target.getWidth() || y < 0 || y >= target.getHeight() || argb >>> 24 == 0) return;
        var g = target.createGraphics();
        g.setComposite(AlphaComposite.SrcOver);
        g.setColor(new Color(argb, true));
        g.fillRect(x, y, 1, 1);
        g.dispose();
    }
}
