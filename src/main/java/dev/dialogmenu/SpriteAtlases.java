package dev.dialogmenu;

import java.awt.image.BufferedImage;
import java.io.IOException;
import java.nio.file.Path;
import java.util.*;

/** 合并同尺寸精灵单元，不改变像素、字形度量或外部纹理引用。 */
final class SpriteAtlases {
    private record Size(int width, int height, int displayHeight) {}

    static List<Object> write(Collection<FontPack.Glyph> glyphs, Path base) throws IOException {
        Map<Size, Map<String, List<FontPack.Glyph>>> groups = new LinkedHashMap<>();
        List<Object> providers = new ArrayList<>();
        for (var glyph : glyphs) {
            BuildBudget.checkpoint();
            if (glyph.image() == null) {
                providers.add(FontPack.bitmap(glyph.texture(), glyph.height(), 7 - glyph.top(),
                    glyph.textureRegion() == null ? List.of(Character.toString(glyph.code()))
                        : glyph.textureRegion().chars(glyph.code())));
            } else {
                var size = new Size(glyph.image().getWidth(), glyph.image().getHeight(), glyph.height());
                groups.computeIfAbsent(size, ignored -> new LinkedHashMap<>())
                    .computeIfAbsent(glyph.texture(), ignored -> new ArrayList<>()).add(glyph);
            }
        }
        int sequence = 0;
        for (var group : groups.entrySet()) {
            Size size = group.getKey();
            List<List<FontPack.Glyph>> cells = new ArrayList<>(group.getValue().values());
            int columns = 256 / size.width(), capacity = columns * (256 / size.height());
            for (int start = 0; start < cells.size(); start += capacity) {
                BuildBudget.checkpoint();
                var chunk = cells.subList(start, Math.min(start + capacity, cells.size()));
                int rows = (chunk.size() + columns - 1) / columns;
                var image = new BufferedImage(columns * size.width(), rows * size.height(), BufferedImage.TYPE_INT_ARGB);
                Set<Integer> tops = new TreeSet<>();
                for (int i = 0; i < chunk.size(); i++) {
                    var pixels = chunk.get(i).getFirst().image();
                    image.setRGB(i % columns * size.width(), i / columns * size.height(), size.width(), size.height(),
                        pixels.getRGB(0, 0, size.width(), size.height(), null, 0, size.width()), 0, size.width());
                    for (var glyph : chunk.get(i)) tops.add(glyph.top());
                }
                String texture = "generated/sprites_" + sequence++ + ".png";
                FontPack.writePng(image, base.resolve("textures/" + texture));
                for (int top : tops) {
                    List<String> chars = new ArrayList<>();
                    for (int row = 0; row < rows; row++) {
                        StringBuilder line = new StringBuilder();
                        for (int column = 0; column < columns; column++) {
                            int i = row * columns + column, code = 0;
                            if (i < chunk.size())
                                for (var glyph : chunk.get(i)) if (glyph.top() == top) code = glyph.code();
                            line.appendCodePoint(code);
                        }
                        chars.add(line.toString());
                    }
                    providers.add(FontPack.bitmap(texture, size.displayHeight(), 7 - top, chars));
                }
            }
        }
        return providers;
    }
}
