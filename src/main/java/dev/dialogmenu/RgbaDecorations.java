package dev.dialogmenu;

import java.awt.image.BufferedImage;
import java.io.IOException;
import java.nio.file.*;
import java.util.*;
import com.google.gson.Gson;
import net.kyori.adventure.key.Key;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.TextDecoration;

/** 装饰线也走 RGBA 字形定位，不依赖原版固定基线的装饰四边形。 */
final class RgbaDecorations {
    static final int FIRST = 0xE100;

    static int thickness(TextStyle style) { return Math.max(1, style.height() / 8); }

    static Component encode(Component text, int fallback, int top, TextStyle style, FontPack fonts,
                            RgbaOffsets rgba, int band) {
        var result = Component.text();
        for (var glyph : MenuText.glyphs(text)) {
            result.append(rgba.encode(glyph.component()
                .decoration(TextDecoration.UNDERLINED, false)
                .decoration(TextDecoration.STRIKETHROUGH, false), fallback, top, style.height(), band));
            int advance = fonts.glyphWidth(glyph, style);
            int color = glyph.style().color() == null ? fallback : glyph.style().color().value();
            if (glyph.style().hasDecoration(TextDecoration.UNDERLINED))
                result.append(SpriteCanvas.space(-advance)).append(rgba.encode(
                    line(advance, thickness(style)), color, top + style.height() - thickness(style), thickness(style), band));
            if (glyph.style().hasDecoration(TextDecoration.STRIKETHROUGH))
                result.append(SpriteCanvas.space(-advance)).append(rgba.encode(
                    line(advance, thickness(style)), color, top + (style.height() - thickness(style)) / 2, thickness(style), band));
        }
        return result.build().compact();
    }

    static Component line(int width, int thickness) {
        var result = Component.text();
        while (width > 0) {
            int unit = width >= thickness ? thickness : 1;
            int length = Integer.highestOneBit(Math.min(256, width / unit));
            result.append(Component.text(Character.toString(FIRST + Integer.numberOfTrailingZeros(length)))
                .font(Key.key("dialogmenu", "rule_" + unit)));
            result.append(SpriteCanvas.space(-1));
            width -= length * unit;
        }
        Component line = result.build();
        for (TextDecoration decoration : TextDecoration.values()) line = line.decoration(decoration, false);
        return line;
    }

    static void write(Path base, Collection<TextStyle> styles) throws IOException {
        var json = new Gson();
        Set<Integer> thicknesses = new TreeSet<>(Set.of(1));
        for (TextStyle style : styles) thicknesses.add(thickness(style));
        for (int power = 0; power <= 8; power++) {
            BufferedImage image = new BufferedImage(1 << power, 1, BufferedImage.TYPE_INT_ARGB);
            for (int x = 0; x < image.getWidth(); x++) image.setRGB(x, 0, 0xFF000000 | RgbaOffsets.TEXTURE_RGB);
            FontPack.writePng(image, base.resolve("textures/generated/rule_" + power + ".png"));
        }
        for (int thickness : thicknesses) {
            List<Object> providers = new ArrayList<>();
            for (int power = 0; power <= 8; power++)
                providers.add(FontPack.bitmap("generated/rule_" + power + ".png", thickness, 0,
                    List.of(Character.toString(FIRST + power))));
            Files.writeString(base.resolve("font/rule_" + thickness + ".json"),
                json.toJson(Map.of("providers", providers)) + "\n");
        }
    }
}
