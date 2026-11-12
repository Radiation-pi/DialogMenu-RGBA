package dev.dialogmenu;

import java.util.ArrayList;
import java.util.List;
import java.util.function.ToIntFunction;
import net.kyori.adventure.text.Component;
import static dev.dialogmenu.MenuModel.*;

/** 对解析后的 Adventure 字形换行，不把样式重新序列化成格式码。 */
final class TextFlow {
    static List<Component> lines(Node node, Component text, double zoom,
                                 ToIntFunction<MenuText.Glyph> measure) {
        List<MenuText.Glyph> glyphs = MenuText.glyphs(text);
        int limit = node.maxLines() == 0 ? Integer.MAX_VALUE
            : Math.max(1, (int) Math.floor(node.width() * node.scale() * zoom));
        List<Component> lines = new ArrayList<>();
        List<MenuText.Glyph> line = new ArrayList<>();
        int used = 0;
        for (int i = 0; i < glyphs.size();) {
            MenuText.Glyph glyph = glyphs.get(i);
            if (glyph.code() == '\n') {
                lines.add(MenuText.component(line));
                line.clear();
                used = 0;
                i++;
                continue;
            }
            int end = i + 1;
            if (asciiWord(glyph.code())) {
                while (end < glyphs.size() && asciiWord(glyphs.get(end).code())) end++;
                if (end < glyphs.size() && glyphs.get(end).code() == '级') end++;
            }
            int advance = 0;
            for (int j = i; j < end; j++) advance += measure.applyAsInt(glyphs.get(j));
            if (advance > limit) {
                end = i + 1;
                advance = measure.applyAsInt(glyph);
            }
            if (advance > limit) throw new IllegalArgumentException(node.id() + " 单字超出line-width");
            if (used > 0 && used + advance > limit) {
                lines.add(MenuText.component(line));
                line.clear();
                used = 0;
            }
            line.addAll(glyphs.subList(i, end));
            used += advance;
            i = end;
        }
        lines.add(MenuText.component(line));
        if (node.maxLines() > 0 && lines.size() > node.maxLines())
            throw new IllegalArgumentException(node.id() + " 文字超过max-lines=" + node.maxLines());
        return List.copyOf(lines);
    }

    private static boolean asciiWord(int code) {
        return code < 128 && Character.isLetterOrDigit(code);
    }
}
