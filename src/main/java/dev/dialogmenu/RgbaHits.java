package dev.dialogmenu;

import java.awt.image.BufferedImage;
import java.io.IOException;
import java.nio.file.*;
import java.util.*;
import javax.imageio.ImageIO;
import com.google.gson.Gson;
import net.kyori.adventure.key.Key;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.TextDecoration;

/** Native hit geometry stays at the target Y; alpha=1 tiles are discarded by vanilla text.fsh. */
final class RgbaHits {
    private record Location(int top, int size) {}
    private record Cell(int height, int width) {
        String texture() { return "generated/hit_" + height + "_" + width + ".png"; }
    }
    private record Hit(Location location, Cell cell) {}
    private final Set<Location> locations = new LinkedHashSet<>();
    private final Map<Integer, Map<Integer, Cell>> bySize = new HashMap<>();
    private final Map<String, Cell> cells = new LinkedHashMap<>();
    private final Map<Hit, Integer> glyphs = new LinkedHashMap<>();

    void prepare(int top, int size) {
        if (top + size < 7)
            throw new IllegalArgumentException("文字命中超出原生基线范围，请将该小字号元素下移至第7像素后");
        locations.add(new Location(top, size));
    }

    void source(int size, List<FontPack.TextAtlas> atlases) {
        Map<Integer, Cell> characters = bySize.computeIfAbsent(size, key -> new HashMap<>());
        for (var atlas : atlases) {
            int columns = atlas.chars().getFirst().codePointCount(0, atlas.chars().getFirst().length());
            int width = atlas.image().getWidth() / columns, height = atlas.sourceHeight();
            Cell cell = cells.computeIfAbsent(height + "/" + width,
                key -> new Cell(height, width));
            for (String row : atlas.chars())
                row.codePoints().filter(cp -> cp != 0).forEach(cp -> characters.put(cp, cell));
        }
    }

    void finish() {
        for (Location location : locations)
            for (Cell cell : cells.values())
                if (bySize.get(location.size()).containsValue(cell))
                    glyphs.computeIfAbsent(new Hit(location, cell), key -> 0xF0000 + glyphs.size());
    }

    Component text(int top, int size, Component value, FontPack fonts) {
        Location location = new Location(top, size);
        if (!locations.contains(location)) throw new IllegalArgumentException("RGBA hit position changed; rebuild resources");
        var builder = Component.text();
        for (var glyph : MenuText.glyphs(value)) {
            int advance = fonts.glyphWidth(glyph, size);
            if (glyph.code() == ' ') {
                builder.append(Component.text(SpriteCanvas.move(advance)).font(SpriteCanvas.SPACE));
                continue;
            }
            Cell cell = bySize.get(size).get(glyph.code());
            if (cell == null) throw new IllegalArgumentException("RGBA hit character not prepared");
            builder.append(Component.text(Character.toString(glyphs.get(new Hit(location, cell)))));
            int proxyAdvance = (int) (.5 + cell.width() * (size / (float) cell.height())) + 1;
            if (advance != proxyAdvance)
                builder.append(Component.text(SpriteCanvas.move(advance - proxyAdvance)).font(SpriteCanvas.SPACE));
        }
        return builder.build().font(Key.key("dialogmenu", "text_hit_all"))
            .decoration(TextDecoration.BOLD, false).decoration(TextDecoration.ITALIC, false);
    }

    void write(Path pack) throws IOException {
        Path base = pack.resolve("assets/dialogmenu");
        for (Cell cell : cells.values()) {
            BufferedImage image = new BufferedImage(cell.width(), cell.height(), BufferedImage.TYPE_INT_ARGB);
            for (int y = 0; y < cell.height(); y++)
                for (int x = 0; x < cell.width(); x++) image.setRGB(x, y, 0x01FFFFFF);
            ImageIO.write(image, "png", base.resolve("textures/" + cell.texture()).toFile());
        }
        var json = new Gson();
        List<Object> providers = new ArrayList<>();
        for (var entry : glyphs.entrySet()) {
            Location location = entry.getKey().location();
            Cell cell = entry.getKey().cell();
            providers.add(FontPack.bitmap(cell.texture(), location.size(), 7 - location.top(),
                List.of(Character.toString(entry.getValue()))));
        }
        Files.writeString(base.resolve("font/text_hit_all.json"),
            json.toJson(Map.of("providers", providers)) + "\n");
    }
}
