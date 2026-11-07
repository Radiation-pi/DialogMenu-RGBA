package dev.dialogmenu;

import java.util.ArrayList;
import java.util.List;
import java.util.regex.Pattern;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.TextComponent;
import net.kyori.adventure.text.format.Style;
import net.kyori.adventure.text.format.TextDecoration;
import net.kyori.adventure.text.serializer.legacy.LegacyComponentSerializer;

/** 由 Adventure 解析格式，布局和输出共用解析后的字形样式。 */
final class MenuText {
    private static final LegacyComponentSerializer LEGACY = LegacyComponentSerializer.builder()
        .character('&').hexColors().useUnusualXRepeatedCharacterHexFormat().build();

    private static final Pattern SECTION_PREFIX = Pattern.compile(
        "§(?=[0-9a-fk-or]|#[0-9a-f]{6}|x(?:§[0-9a-f]){6})", Pattern.CASE_INSENSITIVE);

    record Glyph(int code, Style style) {
        Component component() { return Component.text(Character.toString(code)).style(style); }
        boolean bold() { return style.hasDecoration(TextDecoration.BOLD); }
    }

    static Component parse(String text) {
        return LEGACY.deserialize(SECTION_PREFIX.matcher(text).replaceAll("&"));
    }

    static List<Glyph> glyphs(Component component) {
        List<Glyph> result = new ArrayList<>();
        collect(component, Style.empty(), result);
        return List.copyOf(result);
    }

    private static void collect(Component component, Style inherited, List<Glyph> result) {
        Style style = component.style().merge(inherited, Style.Merge.Strategy.IF_ABSENT_ON_TARGET);
        if (!(component instanceof TextComponent text))
            throw new IllegalArgumentException("Canvas text must contain literal text components");
        text.content().codePoints().forEach(cp -> result.add(new Glyph(cp, style)));
        for (Component child : component.children()) collect(child, style, result);
    }

    static Component component(List<Glyph> glyphs) {
        var result = Component.text();
        for (Glyph glyph : glyphs) result.append(glyph.component());
        return result.build().compact();
    }
}
