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
import net.kyori.adventure.text.serializer.gson.GsonComponentSerializer;
import org.bukkit.configuration.file.YamlConfiguration;
import static dev.dialogmenu.MenuModel.*;

/** 回放导出的资源包，独立检查像素、图层和语义命中目标。 */
final class RowPackTests {
    private record Ink(BufferedImage image, int x, int y, int width, int height, int displayHeight, int top, int advance) {}
    private final Path pack;
    private final Map<String, Map<Integer, Ink>> fonts = new HashMap<>();
    private final Map<Integer, Integer> palette = new HashMap<>();
    private final Map<String, Integer> objects = new HashMap<>();

    private RowPackTests(Path pack) throws Exception {
        this.pack = pack;
        var source = Files.readString(pack.resolve("assets/dialogmenu/shaders/include/rgba_palette.glsl"));
        var colors = java.util.regex.Pattern.compile(
            "case (\\d+): return vec3\\((\\d+)\\.0, (\\d+)\\.0, (\\d+)\\.0\\)").matcher(source);
        while (colors.find()) palette.put(Integer.parseInt(colors.group(1)),
            Integer.parseInt(colors.group(2)) << 16 | Integer.parseInt(colors.group(3)) << 8
                | Integer.parseInt(colors.group(4)));
    }

    static BufferedImage preview(Path pack, Component component, int width, int height) throws Exception {
        return new RowPackTests(pack).render(component, width, height);
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
            for (var glyph : replay.glyphs(output.component()))
                if (glyph.style().clickEvent() != null) hits.add(glyph.style().clickEvent().toString());
            if (hits.size() != 2) throw new AssertionError("Clipping must retain two independent semantic click targets");
            checks++;
            Sprite hit = frame.sprites().stream().filter(s -> s.id().equals("frontend.repeated-b"))
                .findFirst().orElseThrow();
            String target = ClickEvent.runCommand("/test/frontend.repeated-b").toString();
            BufferedImage hitImage = hit.image() == null ? hit.original() : hit.image();
            for (int y = 0; y < hit.height(); y++) {
                int visibleX = -1;
                for (int x = 0; x < hit.width(); x++)
                    if (hitImage.getRGB(x * hitImage.getWidth() / hit.width(),
                        y * hitImage.getHeight() / hit.height()) >>> 24 != 0) { visibleX = x; break; }
                if (visibleX >= 0 && !target.equals(replay.hit(output.component(), hit.x() + visibleX, hit.y() + y)))
                    throw new AssertionError("The clickable image has an unclickable visible row at y=" + (hit.y() + y));
            }
            if (target.equals(replay.hit(output.component(), hit.x() + 2, hit.y() - 1))
                || target.equals(replay.hit(output.component(), hit.x() + 2, hit.y() + hit.height())))
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
        for (var glyph : glyphs(component)) {
            if (glyph.code() == '\n') { x = 0; y += 9; continue; }
            String font = glyph.style().font().asString();
            Ink ink = font(font).get(glyph.code());
            if (ink == null) throw new AssertionError("Unregistered output glyph: " + font + "/" + glyph.code());
            int color = glyph.style().color() == null ? 0xFFFFFF : glyph.style().color().value();
            int payload = glyph.style().shadowColor() == null ? 0 : glyph.style().shadowColor().value();
            int band = (payload >>> 28 & 1) * 9;
            BufferedImage layer = new BufferedImage(width, height, BufferedImage.TYPE_INT_ARGB);
            if (font.startsWith("dialogmenu:text_rgba_")) {
                int[] geometry = textGeometry(payload);
                int dh = geometry[0], top = geometry[1];
                band = geometry[2];
                int dw = ((payload >>> 8) & 511) + 1;
                int metadata = ink.image().getRGB(ink.x() + 1, ink.y());
                int sw = metadata >>> 16 & 255, sh = metadata >>> 8 & 255;
                color = palette.get(payload & 255);
                for (int py = 0; py < dh; py++) for (int px = 0; px < dw; px++) {
                    int sx = (int) Math.floor((px + .5) * sw / dw - .0001);
                    int sy = (int) Math.floor((py + .5) * sh / dh - .0001);
                    int alpha = ink.image().getRGB(ink.x() + 1 + sx, ink.y() + 1 + sy) >>> 24;
                    blend(layer, x + px, y + top + py, alpha << 24 | color);
                    if (glyph.bold()) blend(layer, x + px + 1, y + top + py, alpha << 24 | color);
                }
            } else if (font.equals("dialogmenu:images_rgba") || font.equals("dialogmenu:test_object")) {
                boolean proportional = (payload & 0xE0000000) == 0xA0000000
                    || (payload & 0xE0000000) == 0xE0000000;
                int heightPixels = proportional ? ((payload >> 11) & 1023) + 1 : ((payload >> 10) & 511) + 1;
                int localY = payload & (proportional ? 2047 : 1023);
                int yLimit = proportional ? 1024 : 512;
                if (localY >= yLimit) localY -= yLimit * 2;
                boolean object = font.equals("dialogmenu:test_object");
                int metadata = ink.image().getRGB(object ? 1 : ink.x() + 1, object ? 0 : ink.y());
                int sourceWidth = (metadata >> 16 & 255) + (object ? 1 : 0);
                int sourceHeight = (metadata >> 8 & 255) + (object ? 1 : 0);
                int widthPixels = proportional ? Math.round(heightPixels * sourceWidth / (float) sourceHeight)
                    : ((payload >> 19) & 511) + 1;
                if ((ink.image().getRGB(ink.x(), ink.y()) >> 8 & 65535) == 0x1D56) {
                    int geometry = payload >>> 10 & 1048575;
                    heightPixels = (int) Math.floor((Math.sqrt(8 * geometry + 1225) - 33) / 2);
                    int row = geometry - (heightPixels - 1) * (heightPixels + 34) / 2;
                    band = row >= heightPixels + 8 ? 0 : 9;
                    localY = band == 0 ? row - heightPixels - 8 : row - heightPixels + 10;
                    widthPixels = (payload & 1023) + 1;
                }
                int tint = palette.get(metadata >>> 24);
                for (int py = 0; py < heightPixels; py++) for (int px = 0; px < widthPixels; px++) {
                    int sx = (int) Math.floor((px + .5) * sourceWidth / widthPixels - .0001);
                    int sy = (int) Math.floor((py + .5) * sourceHeight / heightPixels - .0001);
                    int pixel = ink.image().getRGB(ink.x() + (object ? 0 : 1) + sx,
                        ink.y() + (object ? 0 : 1) + sy);
                    blend(layer, x + px, y + localY + py, pixel & 0xFF000000
                        | ((pixel >> 16 & 255) * (tint >> 16 & 255) / 255) << 16
                        | ((pixel >> 8 & 255) * (tint >> 8 & 255) / 255) << 8
                        | (pixel & 255) * (tint & 255) / 255);
                }
            } else if (ink.image() != null && !font.equals("dialogmenu:row_hit")) {
                int top = payload >> 8 & 2047;
                if (top >= 1024) top -= 2048;
                band = top >= 32 ? 0 : 9;
                if (band == 0) top -= 32;
                color = palette.get(payload & 255);
                draw(layer, ink, x + 65536, y + top, color);
                if (glyph.bold()) draw(layer, ink, x + 65537, y + top, color);
            }
            for (int py = Math.max(0, y + band); py < Math.min(height, y + band + 9); py++)
                for (int px = 0; px < width; px++) blend(result, px, py, layer.getRGB(px, py));
            x += ink.advance() + (glyph.bold() ? 1 : 0);
        }
        return result;
    }

    static int[] textGeometry(int payload) {
        int row = ((payload >>> 17) & 16383) - 1;
        for (int height = 1; height <= 128; height++) {
            if (row < height + 17) return row >= height + 8
                ? new int[]{height, row - height - 8, 0}
                : new int[]{height, row - height + 10, 9};
            row -= height + 17;
        }
        throw new AssertionError("Invalid text geometry");
    }

    private String hit(Component component, int testX, int testY) throws Exception {
        int x = 0, y = 0;
        String result = null;
        for (var glyph : glyphs(component)) {
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

    private List<MenuText.Glyph> glyphs(Component component) throws Exception {
        var serializer = GsonComponentSerializer.gson();
        JsonElement tree = JsonParser.parseString(serializer.serialize(component));
        replaceObjects(tree);
        return MenuText.glyphs(serializer.deserialize(tree.toString()));
    }

    private void replaceObjects(JsonElement value) throws Exception {
        if (!value.isJsonObject()) return;
        JsonObject object = value.getAsJsonObject();
        if (object.has("sprite")) {
            String sprite = object.get("sprite").getAsString();
            if (!objects.containsKey(sprite)) {
                int code = 0xE000 + objects.size();
                String[] key = sprite.split(":", 2);
                BufferedImage image = ImageIO.read(pack.resolve(
                    "assets/" + key[0] + "/textures/" + key[1] + ".png").toFile());
                objects.put(sprite, code);
                fonts.computeIfAbsent("dialogmenu:test_object", ignored -> new HashMap<>())
                    .put(code, new Ink(image, 2, 2, 0, 0, 8, 0, 8));
            }
            object.remove("type");
            object.remove("sprite");
            object.remove("atlas");
            object.addProperty("text", new String(Character.toChars(objects.get(sprite))));
            object.addProperty("font", "dialogmenu:test_object");
        }
        if (object.has("extra"))
            for (JsonElement child : object.getAsJsonArray("extra")) replaceObjects(child);
    }

    private static BufferedImage expected(Menu menu, Rendered frame, VanillaAssets assets, FontPack fonts,
                                           Context context, int width) {
        BufferedImage result = new BufferedImage(width, frame.image().getHeight(), BufferedImage.TYPE_INT_ARGB);
        for (Node node : menu.nodes()) {
            if (!node.type().equals("text")) {
                for (Sprite sprite : frame.sprites()) if (sprite.id().equals(node.id())) {
                    if (sprite.image() != null) overlay(result, sprite.image(), sprite.x(), sprite.y());
                    else {
                        var graphics = result.createGraphics();
                        BufferedImage source = sprite.original();
                        int rgb = node.type().equals("image") ? assets.imageColor(node.source(), node.argb()) : node.argb();
                        BufferedImage colored = new BufferedImage(source.getWidth(), source.getHeight(), BufferedImage.TYPE_INT_ARGB);
                        for (int sy = 0; sy < source.getHeight(); sy++) for (int sx = 0; sx < source.getWidth(); sx++) {
                            int pixel = source.getRGB(sx, sy);
                            colored.setRGB(sx, sy, pixel & 0xFF000000
                                | ((pixel >> 16 & 255) * (rgb >> 16 & 255) / 255) << 16
                                | ((pixel >> 8 & 255) * (rgb >> 8 & 255) / 255) << 8
                                | (pixel & 255) * (rgb & 255) / 255);
                        }
                        if (node.type().equals("frame")) {
                            int edge = Math.max(1, (int) Math.round(node.thickness() * node.scale() * width / menu.width()));
                            graphics.setColor(new java.awt.Color(rgb, true));
                            if (2 * edge >= Math.min(sprite.width(), sprite.height()))
                                graphics.fillRect(sprite.x(), sprite.y(), sprite.width(), sprite.height());
                            else for (int py=0; py<sprite.height(); py++) for (int px=0; px<sprite.width(); px++)
                                if (px<edge || py<edge || px>=sprite.width()-edge || py>=sprite.height()-edge)
                                    graphics.fillRect(sprite.x()+px, sprite.y()+py, 1, 1);
                        } else graphics.drawImage(colored, sprite.x(), sprite.y(), sprite.width(), sprite.height(), null);
                        graphics.dispose();
                    }
                }
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
                    int color = glyph.style().color() == null ? node.argb() & 0xFFFFFF : glyph.style().color().value();
                    int targetWidth = Math.max(1, (int) Math.round(source.width() * size / (double) source.height()));
                    for (int py = 0; py < size; py++) for (int px = 0; px < targetWidth; px++) {
                        int sx = (int) Math.floor((px + .5) * source.width() / targetWidth - .0001);
                        int sy = (int) Math.floor((py + .5) * source.height() / size - .0001);
                        int pixel = image.getRGB(sx, sy) & 0xFF000000 | color;
                        blend(result, x + px, y + py, pixel);
                        if (glyph.bold()) blend(result, x + px + 1, y + py, pixel);
                    }
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
