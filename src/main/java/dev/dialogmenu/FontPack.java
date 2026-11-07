package dev.dialogmenu;

import com.google.gson.Gson;
import java.awt.Graphics2D;
import java.awt.image.BufferedImage;
import java.io.IOException;
import java.nio.*;
import java.nio.file.*;
import java.security.*;
import java.util.*;
import javax.imageio.ImageIO;
import static dev.dialogmenu.MenuModel.*;

/** 按行生成稀疏图形和 Unicode 字形，保留绝对定位兼容路径。 */
final class FontPack {
    record Glyph(int code, int top, int height, int advance, String texture, BufferedImage image,
                 TextureRegion textureRegion) {
        Glyph(int code, int top, int height, int advance, String texture, BufferedImage image) {
            this(code, top, height, advance, texture, image, null);
        }
    }
    record TextAtlas(int sourceHeight, List<String> chars, String texture, BufferedImage image) {}
    private record TextCell(int height, int width) {}
    private final Map<String, Glyph> sprites = new LinkedHashMap<>();
    private final Map<Integer, Integer> advances = new TreeMap<>();
    private final Map<Integer, int[]> metrics = new HashMap<>();
    private final Map<String, List<TextAtlas>> text = new TreeMap<>();
    private record TextFont(int top, int size, boolean rows, Set<Integer> characters) {}
    private final Map<String, TextFont> textFonts = new TreeMap<>();
    private final TextSlices slices = new TextSlices();
    private final RgbaOffsets rgba = new RgbaOffsets();
    private final RgbaHits rgbaHits = new RgbaHits();
    private final RgbaImages rgbaImages = new RgbaImages();
    private boolean shader;
    private Collection<Double> textScales;
    private final Set<String> wholeGraphics = new HashSet<>();
    private final Set<String> absoluteRows = new HashSet<>();
    private final Map<BufferedImage, String> imageKeys = new WeakHashMap<>();
    private final Gson json = new Gson();

    static FontPack create(Collection<LayoutStore.Document> docs, MenuRenderer renderer, Context context,
                           Collection<Integer> widths, int maxPixels) throws IOException {
        return create(docs, renderer, context, widths, maxPixels, "");
    }
    static FontPack create(Collection<LayoutStore.Document> docs, MenuRenderer renderer, Context context,
                           Collection<Integer> widths, int maxPixels, String extraCharacters) throws IOException {
        return create(docs, renderer, context, widths, maxPixels, extraCharacters, List.of(1.0));
    }
    static FontPack create(Collection<LayoutStore.Document> docs, MenuRenderer renderer, Context context,
                           Collection<Integer> widths, int maxPixels, String extraCharacters,
                           Collection<Double> textScales) throws IOException {
        return create(docs, renderer, context, widths, maxPixels, extraCharacters, textScales, List.of(context.canvasScale()));
    }
    static FontPack create(Collection<LayoutStore.Document> docs, MenuRenderer renderer, Context context,
                           Collection<Integer> widths, int maxPixels, String extraCharacters,
                           Collection<Double> textScales, Collection<Double> canvasScales) throws IOException {
        FontPack pack = new FontPack();
        pack.shader = renderer.shader;
        String commonCharacters = pack.shader ? RgbaOffsets.commonCharacters() : "";
        Set<Integer> commonCodes = codes(List.of(commonCharacters));
        Set<String> commonFonts = new HashSet<>();
        Map<String, Set<Integer>> dynamicCodes = new HashMap<>();
        pack.textScales = List.copyOf(textScales);
        for (var doc : docs) for (Node node : doc.menu().nodes())
            if (node.reuseTexture() || node.source().startsWith("minecraft:")
                || renderer.assets.craftEngine.atlasReference(node.source()))
                pack.wholeGraphics.add(doc.menu().id());
        for (var doc : docs) if (pack.wholeGraphics.contains(doc.menu().id())) {
            boolean text = false;
            for (Node node : doc.menu().nodes()) {
                if (node.type().equals("text")) text = true;
                else if (text) pack.absoluteRows.add(doc.menu().id());
            }
        }
        for (var doc : docs.stream().sorted(Comparator.comparing(d -> d.menu().id())).toList()) {
            for (double canvasScale : new TreeSet<>(canvasScales))
            for (int requestedWidth : new TreeSet<>(widths)) {
                Context active = CanvasScale.context(MenuTemplate.preview(doc, context), canvasScale);
                Rendered frame = renderer.draw(doc.menu(), active, requestedWidth, maxPixels);
                int width = frame.image().getWidth();
                boolean rows = pack.rows(doc.menu(), frame, active, width);
                boolean graphicRows = pack.graphicRows(doc.menu(), frame, rows);
                Map<String, Integer> nativeRows = pack.shader ? RgbaRows.plan(doc.menu(), frame) : Map.of();
                for (Sprite sprite : frame.sprites()) {
                    try {
                        Sprite local = pack.shader ? RgbaRows.relative(sprite, nativeRows.get(sprite.id())) : sprite;
                        Node node = doc.menu().nodes().stream().filter(n -> n.id().equals(sprite.id())).findFirst().orElseThrow();
                        if (pack.shaderImage(local, node.interaction() != null)) {
                            pack.rgbaImages.prepare(local, node.interaction() != null);
                            continue;
                        }
                        for (Sprite part : CanvasRows.parts(local, graphicRows))
                            pack.register(CanvasRows.local(part, graphicRows));
                    }
                    catch (IOException error) { throw new IOException(doc.menu().id()+"/"+width+": "+error.getMessage(), error); }
                }
                for (Node node : frame.textNodes()) {
                    if (pack.shader) {
                        pack.rgba.prepare(MenuText.parse(active.expand(node.text())), node.argb());
                        for (String value : MenuTemplate.textValues(doc, node, active))
                            pack.rgba.prepare(MenuText.parse(value), node.argb());
                    }
                    boolean dynamic = MenuTemplate.dynamicText(node);
                    Set<Integer> characters = codes(MenuTemplate.textValues(doc, node, active));
                    if (dynamic) characters.addAll(dynamicCodes.computeIfAbsent(
                        MenuTemplate.dynamicCharacters(doc, node, extraCharacters), value -> codes(List.of(value))));
                    for (int cp : characters) pack.measure(cp, renderer.assets);
                    for (double scale : textScales) {
                        int size = SpriteCanvas.textSize(doc.menu(), node, width, scale, canvasScale);
                        List<net.kyori.adventure.text.Component> lines;
                        try {
                            lines = dynamic ? List.of() : TextFlow.lines(node, MenuText.parse(active.expand(node.text())),
                                width / (double) doc.menu().width(), glyph -> pack.glyphWidth(glyph, size));
                        } catch (IllegalArgumentException error) {
                            // 可选倍率可能放不下当前文字，玩家选择时仍会校验。
                            continue;
                        }
                        int count = dynamic ? (node.maxLines() > 0 ? node.maxLines()
                            : frame.image().getHeight() / (size + 1) + 1) : lines.size();
                        boolean registeredShaderCharacters = false;
                        for (int line = 0; line < count; line++) {
                            int top = SpriteCanvas.textTop(doc.menu(), node, width, line, size);
                            if (top >= 0 && top + size <= frame.image().getHeight()) {
                                if (pack.shader && node.interaction() != null)
                                    pack.rgbaHits.prepare(top - nativeRows.get(node.id()) * 9, size);
                                int offset = rows ? CanvasRows.offset(top) : top;
                                String name = pack.preparedName(offset, size, rows);
                                TextFont font = pack.textFonts.computeIfAbsent(name,
                                    key -> new TextFont(pack.shader ? 0 : offset, size, rows, new TreeSet<>()));
                                if (pack.shader && dynamic) {
                                    if (!registeredShaderCharacters) font.characters().addAll(characters);
                                    registeredShaderCharacters = true;
                                    if (commonFonts.add(name)) {
                                        for (int cp : commonCodes) pack.measure(cp, renderer.assets);
                                        font.characters().addAll(commonCodes);
                                    }
                                } else font.characters().addAll(dynamic ? characters : codes(lines.get(line)));
                            }
                        }
                    }
                }
            }
        }
        Map<TextCell, Set<Integer>> used = new LinkedHashMap<>();
        Map<String, List<TextCell>> cells = new HashMap<>();
        for (var entry : pack.textFonts.entrySet()) {
            Map<Integer, Set<Integer>> byHeight = new TreeMap<>();
            for (int cp : entry.getValue().characters())
                byHeight.computeIfAbsent(pack.metrics.get(cp)[1], h -> new TreeSet<>()).add(cp);
            List<TextCell> layout = new ArrayList<>();
            for (var group : byHeight.entrySet()) {
                int width = group.getValue().stream().mapToInt(cp -> renderer.assets.glyph(cp).width()).max().orElseThrow();
                TextCell cell = new TextCell(group.getKey(), width);
                used.computeIfAbsent(cell, key -> new TreeSet<>()).addAll(group.getValue());
                layout.add(cell);
            }
            cells.put(entry.getKey(), layout);
        }
        Map<TextCell, List<TextAtlas>> shared = new HashMap<>();
        for (var entry : used.entrySet())
            shared.put(entry.getKey(), pack.characters(entry.getValue(), renderer.assets));
        for (var entry : pack.textFonts.entrySet()) {
            TextFont font = entry.getValue();
            List<TextAtlas> atlases = new ArrayList<>();
            for (TextCell cell : cells.get(entry.getKey())) for (TextAtlas atlas : shared.get(cell)) {
                List<String> chars = new ArrayList<>();
                boolean visible = false;
                for (String row : atlas.chars()) {
                    StringBuilder masked = new StringBuilder();
                    for (int cp : row.codePoints().toArray()) {
                        boolean present = cp != 0 && font.characters().contains(cp);
                        masked.appendCodePoint(present ? cp : 0);
                        visible |= present;
                    }
                    chars.add(masked.toString());
                }
                if (visible) atlases.add(new TextAtlas(atlas.sourceHeight(), List.copyOf(chars),
                    atlas.texture(), atlas.image()));
            }
            pack.text.put(entry.getKey(), List.copyOf(atlases));
            if (pack.shader) pack.rgbaHits.source(font.size(), atlases);
            if (font.rows()) pack.slices.prepare(entry.getKey(), font.top(), font.size(), atlases);
        }
        if (pack.shader) pack.rgbaHits.finish();
        return pack;
    }
    private static Set<Integer> codes(Collection<String> values) {
        Set<Integer> result = new TreeSet<>();
        for (String value : values) result.addAll(codes(MenuText.parse(value)));
        return result;
    }
    private static Set<Integer> codes(net.kyori.adventure.text.Component value) {
        Set<Integer> result = new TreeSet<>();
        for (var glyph : MenuText.glyphs(value))
            if (glyph.code() != ' ' && glyph.code() != '\n') result.add(glyph.code());
        return result;
    }
    private String key(BufferedImage image) {
        return imageKeys.computeIfAbsent(image, FontPack::hash);
    }
    static String hash(BufferedImage image) {
        try {
            ByteBuffer bytes = ByteBuffer.allocate(8 + image.getWidth() * image.getHeight() * 4);
            bytes.putInt(image.getWidth()).putInt(image.getHeight());
            for (int y = 0; y < image.getHeight(); y++) for (int x = 0; x < image.getWidth(); x++)
                bytes.putInt(image.getRGB(x, y));
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes.array()));
        } catch (NoSuchAlgorithmException error) { throw new IllegalStateException(error); }
    }
    private void register(Sprite sprite) throws IOException {
        if (sprite.texture() != null) {
            String key = spriteKey(sprite);
            if (!sprites.containsKey(key)) {
                int height = sprite.image().getHeight();
                BufferedImage image = sprite.original();
                int advance = bitmapAdvance(image, 0, 0, image.getWidth(), image.getHeight(), height);
                sprites.put(key, new Glyph(0xF0000 + sprites.size(), sprite.y(), height,
                    advance, sprite.texture(), null, sprite.textureRegion()));
            }
            return;
        }
        BufferedImage source = sprite.image();
        String hash = key(source), key = hash + ":" + sprite.y();
        if (sprites.containsKey(key)) return;
        int minimumHeight = Math.max(1, 7 - sprite.y());
        if (source.getHeight() < minimumHeight) {
            BufferedImage padded = new BufferedImage(source.getWidth(), minimumHeight, BufferedImage.TYPE_INT_ARGB);
            Graphics2D padding = padded.createGraphics();
            padding.drawImage(source, 0, 0, null);
            padding.dispose();
            source = padded;
        }
        int divisor = 1;
        while (source.getWidth() / divisor > 256 || source.getHeight() / divisor > 256
            || source.getWidth() % divisor != 0 || source.getHeight() % divisor != 0) {
            divisor++;
            if (divisor > Math.min(source.getWidth(), source.getHeight()))
                throw new IOException(sprite.id() + " 单图无法在256内保持原比例，请调整该元素尺寸");
        }
        BufferedImage image = new BufferedImage(source.getWidth() / divisor, source.getHeight() / divisor,
            BufferedImage.TYPE_INT_ARGB);
        // 整张大图缩到不超过 256 的纹理时，保留细线。
        for (int y = 0; y < image.getHeight(); y++) for (int x = 0; x < image.getWidth(); x++) {
            long a = 0, r = 0, g = 0, b = 0;
            for (int py = 0; py < divisor; py++) for (int px = 0; px < divisor; px++) {
                int color = source.getRGB(x * divisor + px, y * divisor + py), alpha = color >>> 24;
                a += alpha;
                r += (color >> 16 & 255) * alpha;
                g += (color >> 8 & 255) * alpha;
                b += (color & 255) * alpha;
            }
            if (a > 0) image.setRGB(x, y, (int) ((a + divisor * divisor / 2) / (divisor * divisor)) << 24
                | (int) ((r + a / 2) / a) << 16 | (int) ((g + a / 2) / a) << 8 | (int) ((b + a / 2) / a));
        }
        int advance = bitmapAdvance(image, 0, 0, image.getWidth(), image.getHeight(), source.getHeight());
        sprites.put(key, new Glyph(0xF0000 + sprites.size(), sprite.y(), source.getHeight(), advance,
            "generated/sprite_" + hash + ".png", image));
    }
    Glyph sprite(Sprite sprite) {
        Glyph glyph = sprites.get(spriteKey(sprite));
        if (glyph == null) throw new IllegalArgumentException("图形已变化，请重新生成并接收字体资源包");
        return glyph;
    }
    private String spriteKey(Sprite sprite) {
        return (sprite.texture() == null ? key(sprite.image())
            : sprite.texture() + ":" + sprite.textureRegion() + ":" + sprite.height()) + ":" + sprite.y();
    }
    private void measure(int cp, VanillaAssets assets) throws IOException {
        if (metrics.containsKey(cp)) return;
        var glyph = assets.glyph(cp);
        if (glyph == null) throw new IOException("原版字体缺少字符 U+" + Integer.toHexString(cp));
        int used = 0;
        for (int y = 0; y < glyph.height(); y++) for (int x = 0; x < glyph.width(); x++)
            if (glyph.alpha()[y * glyph.width() + x] != 0) used = Math.max(used, x + 1);
        metrics.put(cp, new int[] {used, glyph.height()});
    }
    private List<TextAtlas> characters(Set<Integer> characters, VanillaAssets assets) throws IOException {
        List<TextAtlas> result = new ArrayList<>();
        Map<Integer, List<Integer>> byHeight = new TreeMap<>();
        for (int cp : characters) {
            measure(cp, assets);
            byHeight.computeIfAbsent(metrics.get(cp)[1], h -> new ArrayList<>()).add(cp);
        }
        advances.put(32, 4);
        for (var group : byHeight.entrySet()) {
            int h = group.getKey(), cw = group.getValue().stream().mapToInt(cp -> assets.glyph(cp).width()).max().orElseThrow();
            int columns = Math.min(256 / cw, group.getValue().size()), capacity = columns * (256 / h);
            for (int start = 0; start < group.getValue().size(); start += capacity) {
                List<Integer> chunk = group.getValue().subList(start, Math.min(start + capacity, group.getValue().size()));
                int rowCount = (chunk.size() + columns - 1) / columns;
                BufferedImage image = new BufferedImage(columns * cw, rowCount * h, BufferedImage.TYPE_INT_ARGB);
                List<String> rows = new ArrayList<>();
                for (int y = 0; y < rowCount; y++) {
                    StringBuilder row = new StringBuilder();
                    for (int x = 0; x < columns; x++) {
                        int index = y * columns + x;
                        if (index >= chunk.size()) { row.append('\0'); continue; }
                        int cp = chunk.get(index);
                        var glyph = assets.glyph(cp);
                        for (int py = 0; py < h; py++) for (int px = 0; px < (shader ? cw : glyph.width()); px++) {
                            int alpha = px < glyph.width()
                                ? Byte.toUnsignedInt(glyph.alpha()[py * glyph.width() + px]) : 0;
                            image.setRGB(x * cw + px, y * h + py, alpha << 24
                                | (shader ? RgbaOffsets.TEXTURE_RGB : 0xFFFFFF));
                        }
                        row.appendCodePoint(cp);
                        advances.put(cp, bitmapAdvance(image, x * cw, y * h, cw, h, 8));
                    }
                    rows.add(row.toString());
                }
                result.add(new TextAtlas(h, List.copyOf(rows), "generated/text_" + h + "_" + hash(image) + ".png", image));
            }
        }
        return List.copyOf(result);
    }
    private static int bitmapAdvance(BufferedImage image, int ox, int oy, int width, int height, int displayHeight) {
        int used = 0;
        for (int x = 0; x < width; x++) for (int y = 0; y < height; y++)
            if (image.getRGB(ox + x, oy + y) >>> 24 != 0) used = Math.max(used, x + 1);
        return (int) (.5 + used * (displayHeight / (float) height)) + 1;
    }
    int textWidth(String value) { return textWidth(MenuText.parse(value), 8); }
    int textWidth(String value, int size) { return textWidth(MenuText.parse(value), size); }
    int textWidth(net.kyori.adventure.text.Component value, int size) {
        return MenuText.glyphs(value).stream().mapToInt(glyph -> glyphWidth(glyph, size)).sum();
    }
    int glyphWidth(MenuText.Glyph glyph, int size) {
        int[] metric = metrics.get(glyph.code());
        if (metric == null && glyph.code() != 32)
            throw new IllegalArgumentException("文字已变化，请重新生成字体资源：U+" + Integer.toHexString(glyph.code()));
        int advance = glyph.code() == 32 ? (int) Math.round(size / 2.0)
            : (int) (.5 + metric[0] * (size / (float) metric[1])) + 1;
        return advance + (glyph.bold() ? 1 : 0);
    }
    net.kyori.adventure.key.Key textFont(int top, int size) { return textFont(top, size, true); }
    net.kyori.adventure.key.Key textFont(int top, int size, boolean rows) {
        String name = preparedName(rows ? CanvasRows.offset(top) : top, size, rows);
        if (!textFonts.containsKey(name))
            throw new IllegalArgumentException("文字位置已变化，请重新生成字体资源：" + name);
        return net.kyori.adventure.key.Key.key("dialogmenu", name);
    }
    net.kyori.adventure.key.Key textFont(int top, int size, boolean rows,
                                       net.kyori.adventure.text.Component value) {
        var key = textFont(top, size, rows);
        for (int cp : codes(value)) if (!textFonts.get(key.value()).characters().contains(cp))
            throw new IllegalArgumentException("文字已变化，请重新生成字体资源：U+" + Integer.toHexString(cp));
        return key;
    }
    TextSlices.Part textPart(int top, int size, int part, int code) {
        return slices.part(fontName(CanvasRows.offset(top), size, true), part, code);
    }
    boolean rows(Menu menu, Rendered frame, Context context, int width) {
        return !shader && !absoluteRows.contains(menu.id())
            && CanvasRows.enabled(menu, frame, context, width, textScales);
    }
    net.kyori.adventure.text.Component rgbaText(net.kyori.adventure.text.Component text, int fallback, int top) {
        return rgba.encode(text, fallback, top);
    }
    net.kyori.adventure.text.Component rgbaHit(int top, int size, net.kyori.adventure.text.Component text) {
        return rgbaHits.text(top, size, text, this);
    }
    boolean shader() { return shader; }
    RgbaImages rgbaImages() { return rgbaImages; }
    boolean shaderImage(Sprite sprite, boolean interactive) {
        return shader && RgbaImages.eligible(sprite) && (!interactive || RgbaImages.exactHits(sprite));
    }
    boolean graphicRows(Menu menu, Rendered frame, boolean rows) {
        return rows && !wholeGraphics.contains(menu.id()) && CanvasRows.graphics(frame);
    }
    int originalTextCode(String font, int code) { return slices.original(font, code); }
    void write(Path target) throws IOException {
        Path base = target.resolve("assets/dialogmenu");
        if (Files.exists(base)) try (var paths = Files.walk(base)) {
            for (Path path : paths.sorted(Comparator.reverseOrder()).toList()) Files.delete(path);
        }
        Files.createDirectories(base.resolve("font"));
        Files.createDirectories(base.resolve("textures/generated"));
        Set<String> written = new HashSet<>();
        for (var entry : textFonts.entrySet()) if (!entry.getValue().rows())
            for (TextAtlas atlas : text.get(entry.getKey())) if (written.add(atlas.texture()))
                ImageIO.write(atlas.image(), "png", base.resolve("textures/" + atlas.texture()).toFile());
        Map<String, Integer> spaces = new LinkedHashMap<>();
        for (int x = -1024; x <= 1024; x++) spaces.put(SpriteCanvas.move(x), x);
        writeJson(base.resolve("font/space.json"), Map.of("providers", List.of(Map.of("type", "space", "advances", spaces))));
        List<Object> graphics = SpriteAtlases.write(sprites.values(), base);
        writeJson(base.resolve("font/sprites.json"), Map.of("providers", graphics));
        for (var entry : textFonts.entrySet()) {
            TextFont font = entry.getValue();
            List<Object> characters = new ArrayList<>();
            if (font.rows()) characters.addAll(slices.write(entry.getKey(), base, written));
            else for (TextAtlas atlas : text.get(entry.getKey()))
                characters.add(bitmap(atlas.texture(), font.size(), shader ? 0 : 7 - font.top(), atlas.chars()));
            characters.add(Map.of("type", "space", "advances", Map.of(" ", (int) Math.round(font.size() / 2.0))));
            writeJson(base.resolve("font/" + entry.getKey() + ".json"), Map.of("providers", characters));
        }
        Map<String, Object> metadata = new LinkedHashMap<>();
        metadata.put("pack", Map.of("description", "DialogMenu whole sprites", "pack_format", 64,
            "supported_formats", Map.of("min_inclusive", 64, "max_inclusive", 97),
            "min_format", List.of(64, 0), "max_format", List.of(97, 1)));
        if (shader) metadata.put("overlays", RgbaShaders.metadata());
        writeJson(target.resolve("pack.mcmeta"), metadata);
        if (shader) {
            rgbaImages.write(target);
            rgbaHits.write(target);
            rgba.write(target);
        } else {
            RgbaShaders.remove(target);
        }
    }
    static Object bitmap(String texture, int height, int ascent, List<String> chars) {
        return Map.of("type", "bitmap", "file", texture.contains(":") ? texture : "dialogmenu:" + texture,
            "height", height, "ascent", ascent, "chars", chars);
    }
    static String fontName(int top, int size) {
        return fontName(CanvasRows.offset(top), size, true);
    }
    private static String fontName(int top, int size, boolean rows) {
        return (rows ? (top < CanvasRows.HEIGHT ? "text_head_" : "text_shift_") : "text_absolute_")
            + (rows ? top % CanvasRows.HEIGHT : top) + (size == 8 ? "" : "_" + size);
    }
    private String preparedName(int top, int size, boolean rows) {
        return shader ? "text_rgba_" + size : fontName(top, size, rows);
    }
    private void writeJson(Path path, Object value) throws IOException { Files.writeString(path, json.toJson(value) + "\n"); }
    int spriteCount() { return sprites.size(); }
}
