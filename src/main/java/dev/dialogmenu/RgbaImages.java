package dev.dialogmenu;

import com.google.gson.Gson;
import java.awt.image.BufferedImage;
import java.io.IOException;
import java.nio.file.*;
import java.util.*;
import static dev.dialogmenu.MenuModel.*;

/** 一份源 PNG 对应一个字形；shadow_color 携带尺寸和当前行的源坐标。 */
final class RgbaImages {
    record Glyph(int code, int advance) {}
    private record Entry(Glyph glyph, String texture, BufferedImage image) {}
    private final Map<String, Entry> images = new LinkedHashMap<>();
    private final transient Map<BufferedImage, String> hashes = new WeakHashMap<>();

    static boolean eligible(Sprite sprite) {
        return sprite.texture() == null && sprite.original() != null
            && sprite.original().getWidth() <= 254 && sprite.original().getHeight() <= 254
            && sprite.width() <= 512 && sprite.height() <= 512;
    }

    static int payload(int width, int height, int localY, int band) {
        if (width < 1 || width > 512 || height < 1 || height > 512 || localY < -512 || localY > 511)
            throw new IllegalArgumentException("Shader图片须宽高1..512、切片源Y=-512..511");
        return 0x20000000 | (band == 9 ? 1 << 28 : 0)
            | (width - 1) << 19 | (height - 1) << 10 | localY & 1023;
    }

    private synchronized String key(Sprite sprite, int tint) {
        return hashes.computeIfAbsent(sprite.original(), FontPack::hash) + ":" + tint;
    }

    void prepare(Sprite sprite, int tint) {
        images.computeIfAbsent(key(sprite, tint), key -> {
            BufferedImage image = wrap(sprite.original(), tint);
            return new Entry(new Glyph(0xE000 + images.size(), 2),
                "generated/rgba_image_" + hashes.get(sprite.original()) + "_" + tint + ".png",
                image);
        });
    }

    Glyph glyph(Sprite sprite, int tint) {
        Entry entry = images.get(key(sprite, tint));
        if (entry == null) throw new IllegalArgumentException("图片已变化，请重新生成资源包");
        return entry.glyph();
    }

    static BufferedImage wrap(BufferedImage source) { return wrap(source, 0); }
    static BufferedImage wrap(BufferedImage source, int tint) {
        int width = Math.max(source.getWidth(), source.getHeight()) + 2, height = width;
        BufferedImage result = new BufferedImage(width, height, BufferedImage.TYPE_INT_ARGB);
        result.setRGB(1, 1, source.getWidth(), source.getHeight(),
            source.getRGB(0, 0, source.getWidth(), source.getHeight(), null, 0, source.getWidth()),
            0, source.getWidth());
        int[][] corners = {{0, 0}, {0, height - 1}, {width - 1, height - 1}, {width - 1, 0}};
        for (int i = 0; i < corners.length; i++) {
            int x = corners[i][0], y = corners[i][1];
            result.setRGB(x, y, (width - 1) << 24 | 0x001D5300 | 149 + i);
            result.setRGB(x == 0 ? 1 : x - 1, y,
                tint << 24 | 0x000000B1 | source.getWidth() << 16 | source.getHeight() << 8);
        }
        return result;
    }

    void write(Path pack) throws IOException {
        Path base = pack.resolve("assets/dialogmenu");
        List<Object> providers = new ArrayList<>();
        Map<Integer, List<Entry>> groups = new LinkedHashMap<>();
        for (Entry entry : images.values()) {
            BuildBudget.checkpoint();
            groups.computeIfAbsent(entry.image().getWidth(), key -> new ArrayList<>()).add(entry);
        }
        int sequence = 0;
        for (var group : groups.entrySet()) {
            int size = group.getKey();
            List<Entry> cells = group.getValue();
            // 每张图集最多16×16格，单格不超过256px，整图不超过4096px。
            for (int start = 0; start < cells.size(); start += 256) {
                BuildBudget.checkpoint();
                var chunk = cells.subList(start, Math.min(start + 256, cells.size()));
                int columns = Math.min(16, chunk.size()), rows = (chunk.size() + columns - 1) / columns;
                BufferedImage atlas = new BufferedImage(columns * size, rows * size, BufferedImage.TYPE_INT_ARGB);
                for (int i = 0; i < chunk.size(); i++) {
                    BufferedImage image = chunk.get(i).image();
                    atlas.setRGB(i % columns * size, i / columns * size, size, size,
                        image.getRGB(0, 0, size, size, null, 0, size), 0, size);
                }
                String texture = "generated/rgba_image_atlas_" + sequence++ + ".png";
                FontPack.writePng(atlas, base.resolve("textures/" + texture));
                List<String> chars = new ArrayList<>();
                for (int row = 0; row < rows; row++) {
                    StringBuilder line = new StringBuilder();
                    for (int column = 0; column < columns; column++) {
                        int index = row * columns + column;
                        line.appendCodePoint(index < chunk.size() ? chunk.get(index).glyph().code() : 0);
                    }
                    chars.add(line.toString());
                }
                providers.add(FontPack.bitmap(texture, 1, 0, chars));
            }
        }
        Gson json = new Gson();
        Files.writeString(base.resolve("font/images_rgba.json"), json.toJson(Map.of("providers", providers)) + "\n");
    }
}
