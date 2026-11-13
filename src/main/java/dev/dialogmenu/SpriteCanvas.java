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
    record NativeOutput(Output body, Component foreground, NativeInk.Layout layout) {}
    private record TextKey(FontPack fonts, String text, double wrapWidth, int maxLines, int size, double scale) {}
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
    static int textSize(Menu menu, Node node, int width, double scale, double canvasScale) {
        int size = (int) Math.round(node.size() * scale
            * (menu.scaleTextWithCanvas() ? width / (double) menu.width() : canvasScale));
        if (size < 1 || size > 128) throw new IllegalArgumentException(node.id() + " 实际字号须为1..128");
        return size;
    }
    static int textTop(Menu menu, Node node, int width, int line, int size) {
        return textTop(menu, node, width, line, new TextStyle(size, 1));
    }
    static int textTop(Menu menu, Node node, int width, int line, TextStyle style) {
        return (int) Math.round((menu.height() / 2.0 - node.y()) * width / menu.width()
            - style.height() / 2.0 + line * style.stride());
    }
    Output encode(Menu menu, Rendered frame, Context context, FontPack fonts, Function<Region, ClickEvent> bind) {
        return encode(menu, frame, context, fonts, bind, null, null).body();
    }
    Output encode(Menu menu, Rendered frame, Context context, FontPack fonts, Function<Region, ClickEvent> bind,
                  PreparedLayouts prepared) {
        return encode(menu, frame, context, fonts, bind, null, prepared).body();
    }
    NativeOutput encodeNative(LayoutStore.Document doc, Rendered frame, Context context, FontPack fonts,
                              Function<Region, ClickEvent> bind) {
        var layout = NativeInk.layout(doc.controls(), frame.width(), frame.height(), context::permitted);
        NativeInk.validate(doc, frame, context, fonts, layout);
        return encode(doc.menu(), frame, context, fonts, bind, layout, null);
    }
    NativeOutput encodeNative(LayoutStore.Document doc, Rendered frame, Context context, FontPack fonts,
                              Function<Region, ClickEvent> bind, PreparedLayouts prepared) {
        var layout = NativeInk.layout(doc.controls(), frame.width(), frame.height(), context::permitted);
        NativeInk.validate(doc, frame, context, fonts, layout);
        return encode(doc.menu(), frame, context, fonts, bind, layout, prepared);
    }
    private static List<List<Component>> rows(int count) {
        List<List<Component>> result = new ArrayList<>();
        for (int row = 0; row < count; row++) result.add(new ArrayList<>());
        return result;
    }
    private NativeOutput encode(Menu menu, Rendered frame, Context context, FontPack fonts,
                                Function<Region, ClickEvent> bind, NativeInk.Layout nativeLayout,
                                PreparedLayouts prepared) {
        int width = frame.width(), height = frame.height(), rows = (height + 8) / 9;
        int bodyRows = nativeLayout == null ? rows : nativeLayout.bodyRows();
        List<List<Component>> bodyParts = rows(Math.max(rows, bodyRows));
        var foreground = Component.text();
        Map<Region, ClickEvent> clicks = new HashMap<>();
        Map<String, Sprite> sprites = new HashMap<>();
        for (Sprite sprite : frame.sprites()) sprites.put(sprite.id(), sprite);
        // 文字和图形共用绘制顺序，LayoutStore 已按 Z 排序。
        for (Node node : menu.nodes()) {
            List<List<Component>> parts = nativeLayout == null ? bodyParts : rows(rows);
            var fragment = prepared == null ? null : prepared.fragment(menu, node, frame, context, this);
            if (fragment != null) {
                Region region = node.interaction() == null ? null : node.interaction().region(node);
                ClickEvent click = region != null && region.hasActions() ? clicks.computeIfAbsent(region, bind) : null;
                fragment.append(parts, click, region == null ? null : hover(region, context));
            } else appendNode(parts, menu, node, frame, context, fonts, clicks, bind,
                region -> hover(region, context), sprites.get(node.id()));
            if (nativeLayout != null) {
                boolean interactive = node.interaction() != null
                    && (node.interaction().region(node).hasActions() || !node.interaction().tooltip().isEmpty());
                for (int row = 0; row < rows; row++) for (var part : parts.get(row)) {
                    // 行带相对原生基线前移一行；边界行由按钮承载，避免正文裁剪。
                    if (row < bodyRows - 1 || row < bodyRows && interactive) bodyParts.get(row).add(part);
                    else {
                        var ink = fonts.nativeInk();
                        foreground.append(NativeInk.scope(part,
                            ink.code(nativeLayout, node.z(), row - bodyRows)));
                    }
                }
            }
        }
        var body = Component.text();
        for (int row = 0; row < bodyRows; row++) {
            for (Component part : bodyParts.get(row)) body.append(part);
            body.append(space(width + 2));
            if (row + 1 < bodyRows) body.append(Component.newline());
        }
        return new NativeOutput(new Output(clean(body.build()), width + 10, bodyRows, frame.sprites().size()),
            clean(foreground.build()), nativeLayout);
    }
    PreparedLayouts.Fragment compileNode(Menu menu, Node node, Rendered frame, Context context, FontPack fonts) {
        var parts = rows((frame.height() + 8) / 9);
        Sprite sprite = frame.sprites().stream().filter(value -> value.id().equals(node.id())).findFirst().orElse(null);
        appendNode(parts, menu, node, frame, context, fonts, new HashMap<>(),
            region -> PreparedLayouts.CLICK_SLOT,
            region -> region.tooltip().isEmpty() ? null : PreparedLayouts.HOVER_SLOT, sprite);
        return new PreparedLayouts.Fragment(parts, !node.type().equals("text"));
    }
    private void appendNode(List<List<Component>> parts, Menu menu, Node node, Rendered frame, Context context,
                            FontPack fonts, Map<Region, ClickEvent> clicks, Function<Region, ClickEvent> bind,
                            Function<Region, HoverEvent<Component>> hover, Sprite sprite) {
        int width = frame.width(), height = frame.height();
        if (node.type().equals("model")) {
            double zoom = width / (double) menu.width();
            int x = (int) Math.round((menu.width() / 2.0 + node.x()) * zoom);
            int center = (int) Math.round((menu.height() / 2.0 - node.y()) * zoom);
            int size = (int) Math.round(node.size() * node.scale() * zoom);
            var area = fonts.modelArea(menu, node, width, height);
            Region region = node.interaction() == null ? null : node.interaction().region(node);
            ClickEvent click = region != null && region.hasActions() ? clicks.computeIfAbsent(region, bind) : null;
            var tooltip = region == null ? null : hover.apply(region);
            for (var slice : RgbaRows.slices(area.y, area.height)) {
                parts.get(slice.row()).add(positioned(fonts.model(node.source(), size,
                    -slice.local(center), slice.band()), x, 0));
                if (click != null || tooltip != null)
                    parts.get(slice.row()).add(positioned(RgbaHits.rectangle(slice.local(slice.top()),
                        area.width, slice.height(), click, tooltip), area.x, 0));
            }
        } else if (node.type().equals("text")) {
            addText(parts, menu, node, context, fonts, clicks, bind, hover, width, height);
        } else {
            if (sprite == null) return;
            Region region = node.interaction() == null ? null : node.interaction().region(node);
            int argb = node.type().equals("image")
                ? fonts.registeredImages().color(node.source(), node.argb()) : node.argb();
            if (PrimitiveShapes.sprite(sprite)) {
                for (var rect : PrimitiveShapes.rectangles(node, sprite.width(), sprite.height(),
                    width / (double) menu.width()))
                    addSprite(parts, new Sprite(sprite.id(), sprite.x() + rect.x, sprite.y() + rect.y,
                        null, sprite.texture(), sprite.original(), null, rect.width, rect.height),
                        fonts, argb, null, null);
            } else addSprite(parts, sprite, fonts, argb,
                region == null || !region.hasActions() ? null : clicks.computeIfAbsent(region, bind),
                region == null ? null : hover.apply(region));
        }
    }
    private static Component clean(Component value) {
        return value.decoration(TextDecoration.BOLD, false).decoration(TextDecoration.ITALIC, false)
            .shadowColor(ShadowColor.none());
    }
    static Component nativeLabel(String value, FontPack fonts, Rendered frame, NativeOutput overlay) {
        var text = Component.text(value);
        int width = fonts.textWidth(text, 8);
        if (value.contains("\n") || width > 1024)
            throw new IllegalArgumentException("原生按钮标签须单行且不超过1024像素");
        Component label = positioned(fonts.rgbaText(text, 0xFFFFFF, 0, new TextStyle(8, 1), 0)
            .font(fonts.textFont(8, text)), -width / 2, width);
        return clean(NativeInk.orderBounds().append(label)
            .append(positioned(overlay.foreground(), overlay.layout().canvasOffset(frame.width()), 0)));
    }
    private void addText(List<List<Component>> parts, Menu menu, Node node, Context context,
                         FontPack fonts, Map<Region, ClickEvent> clicks, Function<Region, ClickEvent> bind,
                         Function<Region, HoverEvent<Component>> hover, int width, int height) {
        int size = textSize(menu, node, width, context.textScale(), context.canvasScale());
        TextStyle style = new TextStyle(size, node.scale());
        double zoom = width / (double) menu.width();
        String expanded = context.expand(node.text());
        boolean fixed = expanded.equals(node.text()) && !MenuTemplate.dynamicText(node);
        TextKey key = new TextKey(fonts, expanded, node.width() * node.scale() * zoom, node.maxLines(),
            size, node.scale());
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
                        clicks.get(region), hover.apply(region)), x, advance));
            }
        }
    }
    private void addSprite(List<List<Component>> parts, Sprite sprite, FontPack fonts, int argb,
                           ClickEvent click, HoverEvent<Component> hover) {
        if (ImageRegistry.source(sprite.texture())) {
            int tint = fonts.imageTint(argb);
            for (var slice : RgbaRows.slices(sprite.y(), sprite.height())) {
                Component ink = fonts.registeredImages().component(sprite.texture(), tint, sprite.height(),
                    slice.local(sprite.y()), slice.band());
                parts.get(slice.row()).add(positioned(ink, sprite.x(), 8));
                if (click != null || hover != null)
                    parts.get(slice.row()).add(positioned(RgbaHits.rectangle(slice.local(slice.top()),
                        sprite.width(), slice.height(), click, hover), sprite.x(), 0));
            }
            return;
        }
        if (fonts.shaderImage(sprite)) {
            RgbaImages images = fonts.rgbaImages();
            int tint = fonts.imageTint(argb);
            RgbaImages.Glyph glyph = images.glyph(sprite, tint);
            for (var slice : RgbaRows.slices(sprite.y(), sprite.height())) {
                int payload = PrimitiveShapes.sprite(sprite)
                    ? PrimitiveShapes.payload(sprite.width(), sprite.height(), slice.local(sprite.y()), slice.band())
                    : RgbaImages.imagePayload(sprite.height(), slice.local(sprite.y()), slice.band());
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
