package dev.dialogmenu;

import java.util.*;
import java.util.function.Function;
import net.kyori.adventure.key.Key;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.event.*;
import net.kyori.adventure.text.format.*;
import static dev.dialogmenu.MenuModel.*;

/** 每条正文行内按原绘制顺序输出局部字形。 */
final class SpriteCanvas {
    record Output(Component component, int bodyWidth, int rows, int sprites) {}
    private record TextKey(FontPack fonts, String text, double wrapWidth, int maxLines, int size,
                           double stretchX, double stretchY) {}
    private record TextLayout(List<Component> lines, List<Integer> widths) {}
    private record LabelKey(FontPack fonts, String node, Component text, TextStyle style, int top, int band, int argb) {}
    private final Map<TextKey, TextLayout> staticText = new LinkedHashMap<>(16, .75f, true);
    private final Map<LabelKey, Component> staticLabels = new LinkedHashMap<>(16, .75f, true);
    static final Key SPACE = Key.key("dialogmenu", "space");
    static String move(int pixels) {
        if (pixels < -1024 || pixels > 1024) throw new IllegalArgumentException("Canvas movement out of range");
        return Character.toString(0xF0000 + pixels + 1024);
    }
    static Component space(int pixels) { return Component.text(move(pixels)).font(SPACE); }
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
        return textTop(menu, node, width, line, size, 1);
    }
    static int textTop(Menu menu, Node node, int width, int line, int size, double stretchY) {
        return textTop(menu, node, width, line, new TextStyle(size, 1, stretchY));
    }
    static int textTop(Menu menu, Node node, int width, int line, TextStyle style) {
        return (int) Math.round((menu.height() / 2.0 - node.y()) * width / menu.width()
            - style.height() / 2.0 + line * style.stride());
    }
    Output encode(Menu menu, Rendered frame, Context context, FontPack fonts, Function<Region, ClickEvent> bind) {
        int width = frame.width(), height = frame.height(), rows = (height + 8) / 9;
        List<List<Component>> parts = new ArrayList<>();
        for (int row = 0; row < rows; row++) parts.add(new ArrayList<>());
        Map<Region, ClickEvent> clicks = new HashMap<>();
        Map<String, Sprite> sprites = new HashMap<>();
        for (Sprite sprite : frame.sprites()) sprites.put(sprite.id(), sprite);
        // 文字和图形共用绘制顺序，LayoutStore 已按 Z 排序。
        for (Node node : menu.nodes()) {
            if (node.type().equals("model")) {
                double zoom = width / (double) menu.width();
                int x = (int) Math.round((menu.width() / 2.0 + node.x()) * zoom);
                int center = (int) Math.round((menu.height() / 2.0 - node.y()) * zoom);
                int size = (int) Math.round(node.size() * node.scaleX() * zoom);
                var area = fonts.modelArea(menu, node, width, height);
                Region region = node.interaction() == null ? null : node.interaction().region(node);
                ClickEvent click = region != null && region.hasActions() ? clicks.computeIfAbsent(region, bind) : null;
                var hover = region == null ? null : hover(region, context);
                for (var slice : RgbaRows.slices(area.y, area.height)) {
                    parts.get(slice.row()).add(positioned(fonts.model(node.source(), size,
                        -slice.local(center), slice.band()), x, 0));
                    if (click != null || hover != null)
                        parts.get(slice.row()).add(positioned(RgbaHits.rectangle(slice.local(slice.top()),
                            area.width, slice.height(), click, hover), area.x, 0));
                }
            } else if (node.type().equals("text")) {
                addText(parts, menu, node, context, fonts, clicks, bind, width, height);
            } else {
                Sprite sprite = sprites.get(node.id());
                if (sprite == null) continue;
                Region region = node.interaction() == null ? null : node.interaction().region(node);
                addSprite(parts, sprite, fonts, node.argb(),
                    region != null,
                    region == null || !region.hasActions() ? null : clicks.computeIfAbsent(region, bind),
                    region == null ? null : hover(region, context));
            }
        }
        var body = Component.text();
        for (int row = 0; row < rows; row++) {
            for (Component part : parts.get(row)) body.append(part);
            body.append(space(width + 2));
            if (row + 1 < rows) body.append(Component.newline());
        }
        return new Output(body.build().decoration(TextDecoration.BOLD, false).decoration(TextDecoration.ITALIC, false)
            .shadowColor(ShadowColor.none()), width + 10, rows, frame.sprites().size());
    }
    private void addText(List<List<Component>> parts, Menu menu, Node node, Context context,
                                FontPack fonts, Map<Region, ClickEvent> clicks,
                                Function<Region, ClickEvent> bind, int width, int height) {
        int size = textSize(menu, node, width, context.textScale(), context.canvasScale());
        TextStyle style = new TextStyle(size, node.scaleX(), node.scaleY());
        double zoom = width / (double) menu.width();
        String expanded = context.expand(node.text());
        boolean fixed = expanded.equals(node.text()) && !MenuTemplate.dynamicText(node);
        TextKey key = new TextKey(fonts, expanded, node.width() * node.scaleX() * zoom, node.maxLines(),
            size, node.scaleX(), node.scaleY());
        TextLayout layout = fixed ? staticText.get(key) : null;
        if (layout == null) {
            List<Component> lines = TextFlow.lines(node, MenuText.parse(expanded), zoom,
                glyph -> fonts.glyphWidth(glyph, style));
            layout = new TextLayout(lines,
                lines.stream().map(text -> fonts.textWidth(text, style)).toList());
            if (fixed) {
                staticText.put(key, layout);
                if (staticText.size() > 256) staticText.remove(staticText.keySet().iterator().next());
            }
        }
        List<Component> lines = layout.lines();
        Region region = node.interaction() == null ? null : node.interaction().region(node);
        if (region != null && region.hasActions()) clicks.computeIfAbsent(region, bind);
        for (int line = 0; line < lines.size(); line++) {
            Component text = lines.get(line);
            int advance = layout.widths().get(line);
            double anchor = (menu.width() / 2.0 + node.x()) * zoom;
            int x = (int) Math.round(anchor - switch (node.alignment()) {
                case "left" -> 0;
                case "right" -> advance;
                default -> advance / 2.0;
            });
            int top = textTop(menu, node, width, line, style);
            boolean italic = MenuText.glyphs(text).stream()
                .anyMatch(glyph -> glyph.style().hasDecoration(TextDecoration.ITALIC));
            int leftOverhang = italic ? (int) Math.ceil(style.height() / 4.0) + 2 : 0;
            int rightOverhang = italic ? 2 : 0;
            if (top < 0 || top + style.height() > height || x - leftOverhang < 0
                || x + advance + rightOverhang > width)
                throw new IllegalArgumentException(node.id() + " 原生文字超出画布");
            Key font = fonts.textFont(style, text);
            boolean fixedLabel = fixed && region == null;
            for (var slice : RgbaRows.slices(top, style.height())) {
                int relativeTop = slice.local(top);
                LabelKey labelKey = new LabelKey(fonts, node.id(), text, style, relativeTop, slice.band(), node.argb());
                Component cachedLabel = fixedLabel ? staticLabels.get(labelKey) : null;
                if (cachedLabel == null) {
                    cachedLabel = fonts.rgbaText(text, node.argb(), relativeTop, style, slice.band()).font(font);
                    if (fixedLabel) cache(staticLabels, labelKey, cachedLabel, 2048);
                }
                parts.get(slice.row()).add(positioned(cachedLabel, x, advance));
                if (region != null)
                    parts.get(slice.row()).add(positioned(fonts.rgbaHit(slice.local(slice.top()), slice.height(), style, text,
                        clicks.get(region), hover(region, context)), x, advance));
            }
        }
    }
    private void addSprite(List<List<Component>> parts, Sprite sprite, FontPack fonts, int argb,
                                  boolean interactive, ClickEvent click, HoverEvent<Component> hover) {
        if (fonts.shaderImage(sprite, interactive)) {
            RgbaImages images = fonts.rgbaImages();
            int tint = fonts.imageTint(argb);
            RgbaImages.Glyph glyph = images.glyph(sprite, tint);
            for (var slice : RgbaRows.slices(sprite.y(), sprite.height())) {
                int payload = RgbaImages.payload(sprite.width(), sprite.height(), slice.local(sprite.y()), slice.band());
                Component ink = Component.text(Character.toString(glyph.code()), TextColor.color(RgbaOffsets.INK))
                    .font(Key.key("dialogmenu", "images_rgba")).shadowColor(ShadowColor.shadowColor(payload));
                parts.get(slice.row()).add(positioned(ink, sprite.x(), glyph.advance()));
                if (click != null || hover != null)
                    parts.get(slice.row()).add(positioned(RgbaHits.rectangle(slice.local(slice.top()),
                        sprite.width(), slice.height(), click, hover), sprite.x(), 0));
            }
            return;
        }
        for (Sprite part : fonts.parts(sprite)) {
            FontPack.Glyph glyph = fonts.sprite(RgbaRows.local(part));
            int textColor = sprite.texture() != null && sprite.texture().startsWith("minecraft:")
                ? argb & 0xFFFFFF : 0xFFFFFF;
            Component source = Component.text(Character.toString(glyph.code())).font(Key.key("dialogmenu", "sprites"));
            for (var slice : RgbaRows.slices(part.y(), part.height())) {
                Component ink = fonts.rgbaBitmap(source, textColor, slice.local(part.y()), glyph.height(), slice.band());
                parts.get(slice.row()).add(positioned(ink, part.x(), glyph.advance()));
                if (click != null || hover != null)
                    parts.get(slice.row()).add(positioned(RgbaHits.rectangle(slice.local(slice.top()),
                        part.width(), slice.height(), click, hover), part.x(), 0));
            }
        }
    }
    private static <K, V> void cache(Map<K, V> values, K key, V value, int limit) {
        values.put(key, value);
        if (values.size() > limit) values.remove(values.keySet().iterator().next());
    }
    private static HoverEvent<Component> hover(Region region, Context context) {
        return region.tooltip().isEmpty() ? null
            : HoverEvent.showText(legacy(context.expand(String.join("\n", region.tooltip()))));
    }
    static Component legacy(String text) { return MenuText.parse(text); }
}
