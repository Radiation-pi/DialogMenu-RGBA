package dev.dialogmenu;

import java.awt.image.BufferedImage;
import java.io.IOException;
import java.nio.file.*;
import java.util.*;
import com.google.gson.Gson;
import net.kyori.adventure.key.Key;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.event.*;
import net.kyori.adventure.text.format.*;

/** 固定小空白覆盖一行；不扫描字形、不登记画布位置。 */
final class RgbaHits {
    static final Key FONT = Key.key("dialogmenu", "row_hit");
    record Tile(int x, int y, int width, int height) {}
    private record Cell(int width, int height) {
        String texture() { return "generated/row_hit_" + width + "_" + height + ".png"; }
    }
    private static final List<Cell> CELLS = cells();

    private static List<Cell> cells() {
        List<Cell> cells = new ArrayList<>();
        for (int height = 1; height <= 9; height++)
            for (int width = 1; width <= 2; width++) cells.add(new Cell(width, height));
        return List.copyOf(cells);
    }

    static List<Tile> tiles(int width, int height) {
        List<Tile> result = new ArrayList<>();
        for (int y = 0; y < height; y += 9)
            for (int x = 0; x < width; x += 2)
                result.add(new Tile(x, y, Math.min(2, width - x), Math.min(9, height - y)));
        return result;
    }

    static Component rectangle(int top, int width, int height, ClickEvent click, HoverEvent<Component> hover) {
        var result = Component.text();
        for (Tile tile : tiles(width, height)) {
            int y = top + tile.y();
            Cell cell = new Cell(tile.width(), Math.max(tile.height(), 7 - y));
            int index = CELLS.indexOf(cell);
            if (index < 0 || y < 0 || y > 17)
                throw new IllegalArgumentException("命中切片须位于当前原生行内");
            int code = 0xF0000 + index * 18 + y;
            result.append(SpriteCanvas.space(tile.x()))
                .append(Component.text(Character.toString(code)).font(FONT).clickEvent(click).hoverEvent(hover))
                .append(SpriteCanvas.space(-tile.x() - tile.width()));
        }
        return result.build().decoration(TextDecoration.BOLD, false).decoration(TextDecoration.ITALIC, false)
            .shadowColor(ShadowColor.none());
    }

    Component text(int top, int height, TextStyle style, Component value, FontPack fonts,
                   ClickEvent click, HoverEvent<Component> hover) {
        var builder = Component.text();
        for (var glyph : MenuText.glyphs(value)) {
            int advance = fonts.glyphWidth(glyph, style);
            if (glyph.code() != ' ') builder.append(rectangle(top, Math.max(1, advance), height, click, hover));
            builder.append(SpriteCanvas.space(advance));
        }
        return builder.build().decoration(TextDecoration.BOLD, false).decoration(TextDecoration.ITALIC, false);
    }

    void write(Path pack) throws IOException {
        Path base = pack.resolve("assets/dialogmenu");
        List<Object> providers = new ArrayList<>();
        for (int index = 0; index < CELLS.size(); index++) {
            BuildBudget.checkpoint();
            Cell cell = CELLS.get(index);
            BufferedImage image = new BufferedImage(cell.width(), cell.height(), BufferedImage.TYPE_INT_ARGB);
            // 原生命中宽度是advance，不是贴图宽度。alpha=1可计宽，仍低于原生0.1丢弃阈值。
            if (cell.width() == 2) image.setRGB(0, 0, 0x01000000);
            FontPack.writePng(image, base.resolve("textures/" + cell.texture()));
            for (int top = Math.max(0, 7 - cell.height()); top <= 17; top++) {
                boolean needed = top + cell.height() <= (top < 9 ? 9 : 18);
                if (needed) providers.add(FontPack.bitmap(cell.texture(), cell.height(), 7 - top,
                    List.of(Character.toString(0xF0000 + index * 18 + top))));
            }
        }
        Files.writeString(base.resolve("font/row_hit.json"), new Gson().toJson(Map.of("providers", providers)) + "\n");
    }
}
