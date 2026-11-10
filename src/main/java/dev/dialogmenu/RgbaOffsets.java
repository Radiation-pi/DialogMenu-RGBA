package dev.dialogmenu;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.*;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.TextColor;
import net.kyori.adventure.text.format.NamedTextColor;
import net.kyori.adventure.text.format.TextDecoration;

/** 可见字形以 shadow_color 携带行切片，普通 pass 隐藏；源图集保持共享。 */
final class RgbaOffsets {
    static final int MAGIC = 0x20000000;
    static final int INK = 0xBEEF01;
    static final int TEXTURE_RGB = 0x174783;
    static final int NATIVE_SHIFT = 65536;
    private final Map<Integer, Integer> palette = new LinkedHashMap<>();

    RgbaOffsets() {
        NamedTextColor.NAMES.values().stream().mapToInt(NamedTextColor::value).sorted().forEach(this::add);
    }

    static String commonCharacters() throws IOException {
        try (var input = RgbaOffsets.class.getResourceAsStream("/rgba/characters-3500.txt")) {
            if (input == null) throw new IOException("Missing shader character set");
            return new String(input.readAllBytes(), java.nio.charset.StandardCharsets.UTF_8).strip();
        }
    }

    void prepare(Component text, int fallback) {
        add(fallback);
        for (var glyph : MenuText.glyphs(text)) {
            if (glyph.style().color() != null) add(glyph.style().color().value());
        }
    }

    int add(int color) {
        int rgb = color & 0xFFFFFF;
        if (!palette.containsKey(rgb)) {
            if (palette.size() == 256) throw new IllegalArgumentException("Shader文字和图片共享颜色超过256种");
            palette.put(rgb, palette.size());
        }
        return palette.get(rgb);
    }

    int index(int color) {
        Integer index = palette.get(color & 0xFFFFFF);
        if (index == null) throw new IllegalArgumentException("图片颜色未准备，请执行 /dialogmenu reload all");
        return index;
    }

    Collection<Integer> indices() { return List.copyOf(palette.values()); }

    static Component nativeScope(Component ink) {
        return Component.empty()
            .append(Component.text(Character.toString(0xF1020)).font(SpriteCanvas.SPACE))
            .append(ink)
            .append(Component.text(Character.toString(0xF1021)).font(SpriteCanvas.SPACE));
    }

    static void nativeSpaces(Map<String, Integer> spaces) {
        spaces.put(Character.toString(0xF1020), -NATIVE_SHIFT);
        spaces.put(Character.toString(0xF1021), NATIVE_SHIFT);
    }

    Component encode(Component text, int fallback, int top, int height, int band) {
        if (top < -512 || top > 511 || height < 1 || height > 128)
            throw new IllegalArgumentException("RGBA文字切片超出范围");
        var result = Component.text();
        for (var glyph : MenuText.glyphs(text)) {
            int rgb = glyph.style().color() == null ? fallback & 0xFFFFFF : glyph.style().color().value();
            Integer index = palette.get(rgb);
            if (index == null) throw new IllegalArgumentException("文字颜色未准备，请在text-colors声明#"
                + String.format(java.util.Locale.ROOT, "%06X", rgb) + "后执行 /dialogmenu reload all");
            result.append(glyph.component().color(TextColor.color(INK))
                .shadowColor(net.kyori.adventure.text.format.ShadowColor.shadowColor(
                    MAGIC | (band == 9 ? 1 << 28 : 0) | (height - 1) << 18 | (top & 1023) << 8 | index)));
        }
        return result.build().compact();
    }

    Component bitmap(Component glyph, int fallback, int top, int height, int band) {
        if (height < 1 || height > 1024 || top < -1024 || top > 1023)
            throw new IllegalArgumentException("Bitmap切片超出范围");
        // 首行原点仅为0..8；借用常规切片不会出现的32..40，避免占用额外偏移位。
        int encodedTop = band == 0 ? top + 32 : top;
        return nativeScope(glyph.color(TextColor.color(INK))
            .shadowColor(net.kyori.adventure.text.format.ShadowColor.shadowColor(
                0xC0000000 | (height - 1) << 19 | (encodedTop & 2047) << 8 | index(fallback))));
    }

    void write(Path pack) throws IOException {
        Path include = pack.resolve("assets/dialogmenu/shaders/include/rgba_palette.glsl");
        Files.createDirectories(include.getParent());
        StringBuilder glsl = new StringBuilder("vec3 dialogmenu_color(int index) {\n    switch (index) {\n");
        for (var entry : palette.entrySet()) {
            int rgb = entry.getKey();
            glsl.append("        case ").append(entry.getValue()).append(": return vec3(")
                .append(rgb >> 16 & 255).append(".0, ").append(rgb >> 8 & 255).append(".0, ")
                .append(rgb & 255).append(".0) / 255.0;\n");
        }
        glsl.append("    }\n    return vec3(1.0, 0.0, 1.0);\n}\n");
        Files.writeString(include, glsl);
        RgbaShaders.write(pack);
    }
}
