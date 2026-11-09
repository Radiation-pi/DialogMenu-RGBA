package dev.dialogmenu;

import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.TextDecoration;

/** 装饰线也走 RGBA 字形定位，不依赖原版固定基线的装饰四边形。 */
final class RgbaDecorations {
    static int thickness(TextStyle style) { return Math.max(1, style.height() / 8); }

    static Component encode(Component text, int fallback, int top, TextStyle style, FontPack fonts,
                            RgbaOffsets rgba, int band) {
        var result = Component.text();
        for (var glyph : MenuText.glyphs(text)) {
            int advance = fonts.glyphWidth(glyph, style);
            int color = glyph.style().color() == null ? fallback : glyph.style().color().value();
            if (glyph.code() == ' ') result.append(SpriteCanvas.space(advance));
            else {
                Component ink = RgbaText.ink(glyph, rgba.index(color), fonts.glyphPixels(glyph.code(), style),
                    style.height(), top, band);
                if (glyph.style().hasDecoration(TextDecoration.OBFUSCATED)) ink = ink.font(fonts.obfuscatedFont(glyph.code()));
                result.append(ink);
                result.append(SpriteCanvas.space(advance - (glyph.bold() ? 3 : 2)));
            }
            if (glyph.style().hasDecoration(TextDecoration.UNDERLINED))
                result.append(SpriteCanvas.space(-advance)).append(rule(advance, thickness(style),
                    color, top + style.height() - thickness(style), band, rgba));
            if (glyph.style().hasDecoration(TextDecoration.STRIKETHROUGH))
                result.append(SpriteCanvas.space(-advance)).append(rule(advance, thickness(style),
                    color, top + (style.height() - thickness(style)) / 2, band, rgba));
        }
        return result.build().compact();
    }

    private static Component rule(int width, int height, int color, int top, int band, RgbaOffsets rgba) {
        // The parent text is repeated per row; an off-row decoration emits only its advance.
        int start = band == 0 ? 0 : 9;
        if (top + height <= start || top >= start + 9) return SpriteCanvas.space(width);
        var glyph = MenuText.glyphs(Component.text(Character.toString(RgbaText.RULE))).getFirst();
        var result = Component.text();
        while (width > 0) {
            int length = Math.min(512, width);
            result.append(RgbaText.ink(glyph, rgba.index(color), length, height, top, band));
            result.append(SpriteCanvas.space(length - 2));
            width -= length;
        }
        return result.build();
    }
}
