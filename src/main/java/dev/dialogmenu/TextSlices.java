package dev.dialogmenu;

import java.awt.image.BufferedImage;
import java.awt.image.IndexColorModel;
import java.io.IOException;
import java.nio.file.Path;
import java.util.*;
import javax.imageio.ImageIO;

/** Share identical row fragments without widening a font's declared character range. */
final class TextSlices {
    private static final IndexColorModel ALPHA = alphaPalette();
    private static final IndexColorModel BINARY_ALPHA = new IndexColorModel(1, 2,
        new byte[] {-1, -1}, new byte[] {-1, -1}, new byte[] {-1, -1}, new byte[] {0, -1});
    record Part(int code, int advance) {}
    private record Lookup(String font, int part, int code) {}
    private record Original(String font, int code) {}
    private record Geometry(int sourceHeight, int sourceWidth, int size, int start, int inkHeight, int height) {}
    private record Cell(int code, BufferedImage image, int x, int y) {}
    private record Page(String texture, Geometry geometry, List<Cell> cells, int columns) {
        int rows() { return (cells.size() + columns - 1) / columns; }
        BufferedImage image() {
            int expand = geometry.size() / gcd(geometry.sourceHeight(), geometry.size());
            int divisor = geometry.sourceHeight() / gcd(geometry.sourceHeight(), geometry.size());
            int cw = geometry.sourceWidth() * expand, ch = geometry.height() * divisor;
            IndexColorModel palette = palette(cells, geometry);
            BufferedImage image = new BufferedImage(columns * cw, rows() * ch,
                palette == BINARY_ALPHA ? BufferedImage.TYPE_BYTE_BINARY : BufferedImage.TYPE_BYTE_INDEXED, palette);
            for (int i = 0; i < cells.size(); i++) {
                Cell cell = cells.get(i);
                for (int y = 0; y < geometry.inkHeight() * divisor; y++) for (int x = 0; x < cw; x++)
                    image.getRaster().setSample(i % columns * cw + x, i / columns * ch + y, 0,
                        (cell.image().getRGB(cell.x() + x / expand,
                            cell.y() + (geometry.start() * divisor + y) / expand) >>> 24)
                            / (palette == BINARY_ALPHA ? 255 : 1));
            }
            return image;
        }
    }
    private static final class Pool {
        final Geometry geometry;
        final int id;
        final Map<Integer, Cell> cells = new TreeMap<>();
        Pool(Geometry geometry, int id) { this.geometry = geometry; this.id = id; }
        List<Page> pages() {
            int gcd = gcd(geometry.sourceHeight(), geometry.size());
            int cw = geometry.sourceWidth() * (geometry.size() / gcd);
            int ch = geometry.height() * (geometry.sourceHeight() / gcd);
            int columns = Math.min(4096 / cw, Math.min(16, cells.size()));
            int capacity = columns * (4096 / ch);
            List<Cell> ordered = new ArrayList<>(cells.values());
            List<Page> result = new ArrayList<>();
            for (int start = 0; start < ordered.size(); start += capacity) {
                String texture = "generated/text_slice_" + geometry.size() + "_" + geometry.start()
                    + "_" + geometry.inkHeight() + "_" + geometry.height() + "_"
                    + geometry.sourceHeight() + "_pool_" + id + "_" + start / capacity + ".png";
                result.add(new Page(texture, geometry,
                    List.copyOf(ordered.subList(start, Math.min(start + capacity, ordered.size()))), columns));
            }
            return result;
        }
    }
    private final Map<Geometry, Pool> pools = new LinkedHashMap<>();
    private final Map<String, Map<Geometry, Map<Integer, Integer>>> mappings = new HashMap<>();
    private final Map<String, Map<String, Integer>> spaces = new HashMap<>();
    private final Map<Lookup, Part> parts = new HashMap<>();
    private final Map<Original, Integer> originals = new HashMap<>();
    private final Map<String, Integer> offsets = new HashMap<>();

    void prepare(String font, int offset, int size, List<FontPack.TextAtlas> source) throws IOException {
        Map<Geometry, Map<Integer, Integer>> output = new LinkedHashMap<>();
        Map<String, Integer> blank = new LinkedHashMap<>();
        int alias = 0xF0000;
        for (int start = 0, part = 0; start < size; part++) {
            int top = part == 0 ? offset : CanvasRows.HEIGHT;
            int height = Math.min(CanvasRows.HEIGHT - top % CanvasRows.HEIGHT, size - start);
            for (var atlas : source) {
                int h = atlas.sourceHeight();
                int gcd = gcd(h, size), expand = size / gcd, divisor = h / gcd;
                int columns = atlas.chars().getFirst().codePointCount(0, atlas.chars().getFirst().length());
                int cw = atlas.image().getWidth() / columns;
                Geometry geometry = new Geometry(h, cw, size, start, height, Math.max(Math.max(1, 7 - top), height));
                for (int row = 0; row < atlas.chars().size(); row++) {
                    int[] codes = atlas.chars().get(row).codePoints().toArray();
                    for (int column = 0; column < codes.length; column++) {
                        int cp = codes[column];
                        if (cp == 0) continue;
                        int code = part == 0 ? cp : alias++;
                        if (code > 0xFFFFD) throw new IOException("单个字体切片超过私有码点容量");
                        int used = 0;
                        int firstY = start * divisor / expand;
                        int lastY = (start * divisor + height * divisor - 1) / expand;
                        for (int y = firstY; y <= lastY; y++) for (int x = 0; x < cw; x++)
                            if (atlas.image().getRGB(column * cw + x, row * h + y) >>> 24 != 0)
                                used = Math.max(used, (x + 1) * expand);
                        int advance = (int) (.5 + used / (float) divisor) + 1;
                        if (used == 0) blank.put(Character.toString(code), advance);
                        else {
                            output.computeIfAbsent(geometry, key -> new TreeMap<>()).put(cp, code);
                            Pool pool = pools.computeIfAbsent(geometry, key -> new Pool(key, pools.size()));
                            pool.cells.putIfAbsent(cp, new Cell(cp, atlas.image(), column * cw, row * h));
                        }
                        parts.put(new Lookup(font, part, cp), new Part(code, advance));
                        originals.put(new Original(font, code), cp);
                    }
                }
            }
            start += height;
        }
        mappings.put(font, output);
        offsets.put(font, offset);
        spaces.put(font, blank);
    }

    Part part(String font, int part, int code) {
        Part found = parts.get(new Lookup(font, part, code));
        if (found == null) throw new IllegalArgumentException("文字或行内偏移已变化，请重新生成字体资源");
        return found;
    }

    int original(String font, int code) { return originals.getOrDefault(new Original(font, code), code); }

    List<Object> write(String font, Path base, Set<String> written) throws IOException {
        List<Object> providers = new ArrayList<>();
        for (var entry : mappings.get(font).entrySet()) {
            Geometry geometry = entry.getKey();
            int top = geometry.start() == 0 ? offsets.get(font) : CanvasRows.HEIGHT;
            for (Page page : pools.get(geometry).pages()) {
                List<String> chars = new ArrayList<>();
                boolean visible = false;
                for (int row = 0; row < page.rows(); row++) {
                    StringBuilder line = new StringBuilder();
                    for (int column = 0; column < page.columns(); column++) {
                        int index = row * page.columns() + column;
                        int code = index < page.cells().size()
                            ? entry.getValue().getOrDefault(page.cells().get(index).code(), 0) : 0;
                        line.appendCodePoint(code);
                        visible |= code != 0;
                    }
                    chars.add(line.toString());
                }
                if (!visible) continue;
                if (written.add(page.texture()))
                    ImageIO.write(page.image(), "png", base.resolve("textures/" + page.texture()).toFile());
                providers.add(FontPack.bitmap(page.texture(), geometry.height(), 7 - top, chars));
            }
        }
        if (!spaces.get(font).isEmpty()) providers.add(Map.of("type", "space", "advances", spaces.get(font)));
        return providers;
    }

    private static int gcd(int a, int b) { return b == 0 ? a : gcd(b, a % b); }

    private static IndexColorModel palette(List<Cell> cells, Geometry geometry) {
        for (Cell cell : cells) for (int y = 0; y < geometry.sourceHeight(); y++)
            for (int x = 0; x < geometry.sourceWidth(); x++) {
                int alpha = cell.image().getRGB(cell.x() + x, cell.y() + y) >>> 24;
                if (alpha != 0 && alpha != 255) return ALPHA;
            }
        return BINARY_ALPHA;
    }

    private static IndexColorModel alphaPalette() {
        byte[] white = new byte[256], alpha = new byte[256];
        Arrays.fill(white, (byte) 255);
        for (int i = 0; i < 256; i++) alpha[i] = (byte) i;
        return new IndexColorModel(8, 256, white, white, white, alpha);
    }
}
