package dev.dialogmenu;

import java.util.*;
import java.util.function.Function;
import net.kyori.adventure.key.Key;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.event.*;
import net.kyori.adventure.text.format.*;
import static dev.dialogmenu.MenuModel.*;

/** Row-local glyphs, in the original paint order within every body row. */
final class SpriteCanvas {
    record Output(Component component, int bodyWidth, int rows, int sprites) {}
    static final Key SPACE = Key.key("dialogmenu", "space");
    static String move(int pixels) {
        if (pixels < -1024 || pixels > 1024) throw new IllegalArgumentException("Canvas movement out of range");
        return Character.toString(0xF0000 + pixels + 1024);
    }
    private static Component space(int pixels) { return Component.text(move(pixels)).font(SPACE); }
    private static Component positioned(Component ink, int x, int advance) {
        return Component.empty().append(space(x)).append(ink).append(space(-x - advance));
    }
    static int textTop(Menu menu, Node node, int width, int line) {
        return textTop(menu, node, width, line, node.size());
    }
    static int textSize(Menu menu, Node node, int width, double scale) {
        return textSize(menu, node, width, scale, 1);
    }
    static int textSize(Menu menu, Node node, int width, double scale, double canvasScale) {
        int size = (int) Math.round(node.size() * scale
            * (menu.scaleTextWithCanvas() ? width / (double) menu.width() : canvasScale));
        if (size < 1 || size > 128) throw new IllegalArgumentException(node.id() + " 实际字号须为1..128");
        return size;
    }
    static int textTop(Menu menu, Node node, int width, int line, int size) {
        return (int) Math.round((menu.height() / 2.0 - node.y()) * width / menu.width() - size / 2.0 + line * (size + 1));
    }
    Output encode(Menu menu, Rendered frame, Context context, FontPack fonts, Function<Region, ClickEvent> bind) {
        int width = frame.image().getWidth(), height = frame.image().getHeight(), rows = (height + 8) / 9;
        boolean rowCanvas = fonts.rows(menu, frame, context, width);
        boolean graphicRows = fonts.graphicRows(menu, frame, rowCanvas);
        Map<String, Integer> nativeRows = fonts.shader() ? RgbaRows.plan(menu, frame) : Map.of();
        List<List<Component>> parts = new ArrayList<>();
        for (int row = 0; row < rows; row++) parts.add(new ArrayList<>());
        Map<Region, ClickEvent> clicks = new HashMap<>();
        Map<String, Sprite> sprites = new HashMap<>();
        for (Sprite sprite : frame.sprites()) sprites.put(sprite.id(), sprite);
        // Keep a single paint order for text and graphics, already sorted by z in LayoutStore.
        for (Node node : menu.nodes()) {
            if (node.type().equals("text")) {
                addText(parts, menu, node, context, fonts, clicks, bind, width, height, rowCanvas,
                    fonts.shader() ? nativeRows.get(node.id()) : 0);
            } else {
                Sprite sprite = sprites.get(node.id());
                if (sprite == null) continue;
                Region region = node.interaction() == null ? null : node.interaction().region(node);
                addSprite(parts, sprite, fonts, graphicRows, fonts.shader() ? nativeRows.get(node.id()) : 0,
                    region != null,
                    region == null || !region.hasActions() ? null : clicks.computeIfAbsent(region, bind),
                    region == null ? null : hover(region, context));
            }
        }
        Component body = Component.empty();
        for (int row = 0; row < rows; row++) {
            for (Component part : parts.get(row)) body = body.append(part);
            body = body.append(space(width + 2));
            if (row + 1 < rows) body = body.append(Component.newline());
        }
        return new Output(body.decoration(TextDecoration.BOLD, false).decoration(TextDecoration.ITALIC, false)
            .shadowColor(ShadowColor.none()), width + 10, rows, frame.sprites().size());
    }
    private static void addText(List<List<Component>> parts, Menu menu, Node node, Context context,
                                FontPack fonts, Map<Region, ClickEvent> clicks,
                                Function<Region, ClickEvent> bind, int width, int height, boolean rows, int nativeRow) {
        int size = textSize(menu, node, width, context.textScale(), context.canvasScale());
        double zoom = width / (double) menu.width();
        Component parsed = MenuText.parse(context.expand(node.text()));
        List<Component> lines = TextFlow.lines(node, parsed, zoom, glyph -> fonts.glyphWidth(glyph, size));
        Region region = node.interaction() == null ? null : node.interaction().region(node);
        if (region != null && region.hasActions()) clicks.computeIfAbsent(region, bind);
        for (int line = 0; line < lines.size(); line++) {
            Component text = lines.get(line);
            int advance = fonts.textWidth(text, size);
            double anchor = (menu.width() / 2.0 + node.x()) * zoom;
            int x = (int) Math.round(anchor - switch (node.alignment()) {
                case "left" -> 0;
                case "right" -> advance;
                default -> advance / 2.0;
            });
            int top = textTop(menu, node, width, line, size);
            if (top < 0 || top + size > height || x < 0 || x + advance > width)
                throw new IllegalArgumentException(node.id() + " 原生文字超出画布");
            Key font = fonts.textFont(top, size, rows, text);
            for (int start = 0, part = 0; start < size; part++) {
                Component label;
                if (rows) {
                    var builder = Component.text();
                    for (var glyph : MenuText.glyphs(text)) {
                        if (glyph.code() == ' ') {
                            builder.append(glyph.component().decoration(TextDecoration.BOLD, false));
                            if (glyph.bold()) builder.append(space(1));
                        } else {
                            var slice = fonts.textPart(top, size, part, glyph.code());
                            builder.append(Component.text(Character.toString(slice.code())).style(glyph.style()));
                            int correction = fonts.glyphWidth(glyph, size) - slice.advance() - (glyph.bold() ? 1 : 0);
                            if (correction != 0) builder.append(space(correction).decoration(TextDecoration.BOLD, false));
                        }
                    }
                    label = builder.build();
                } else label = text;
                int relativeTop = top - nativeRow * 9;
                label = fonts.shader() ? fonts.rgbaText(label, node.argb(), relativeTop).font(font)
                    : label.font(font).colorIfAbsent(TextColor.color(node.argb()));
                if (region != null && !fonts.shader())
                    label = label.clickEvent(clicks.get(region)).hoverEvent(hover(region, context));
                int row = rows ? CanvasRows.bodyRow(top + start) : nativeRow;
                parts.get(row).add(positioned(label, x, advance));
                if (fonts.shader() && region != null)
                    parts.get(nativeRow).add(positioned(fonts.rgbaHit(relativeTop, size, text)
                        .clickEvent(clicks.get(region)).hoverEvent(hover(region, context)), x, advance));
                start += rows ? Math.min(CanvasRows.HEIGHT - (top + start) % CanvasRows.HEIGHT, size - start) : size;
            }
        }
    }
    private static void addSprite(List<List<Component>> parts, Sprite sprite, FontPack fonts, boolean rows,
                                  int nativeRow, boolean interactive, ClickEvent click, HoverEvent<Component> hover) {
        if (fonts.shader()) sprite = RgbaRows.relative(sprite, nativeRow);
        if (fonts.shaderImage(sprite, interactive)) {
            RgbaImages images = fonts.rgbaImages();
            parts.get(nativeRow).add(positioned(images.image(sprite), sprite.x(), images.glyph(sprite).advance()));
            if (click != null || hover != null)
                for (var tile : RgbaImages.tiles(sprite.image().getWidth(), sprite.image().getHeight()))
                    parts.get(nativeRow).add(positioned(images.hit(sprite.y() + tile.y(), tile.size(), click, hover),
                        sprite.x() + tile.x(), tile.size() + 1));
            return;
        }
        for (Sprite part : CanvasRows.parts(sprite, rows)) {
            FontPack.Glyph glyph = fonts.sprite(CanvasRows.local(part, rows));
            Component ink = Component.text(Character.toString(glyph.code()), TextColor.color(0xFFFFFF))
                .font(Key.key("dialogmenu", "sprites")).clickEvent(click).hoverEvent(hover);
            parts.get(rows ? CanvasRows.bodyRow(part.y()) : nativeRow).add(positioned(ink, part.x(), glyph.advance()));
        }
    }
    private static HoverEvent<Component> hover(Region region, Context context) {
        return region.tooltip().isEmpty() ? null
            : HoverEvent.showText(legacy(context.expand(String.join("\n", region.tooltip()))));
    }
    static Component legacy(String text) { return MenuText.parse(text); }
}
