package dev.dialogmenu;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.*;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.TextColor;

/** GUI-only text transport: RGB = marker + 10-bit Y + 8-bit palette index; A stays opaque. */
final class RgbaOffsets {
    static final int MAGIC = 0xD40000;
    static final int TEXTURE_RGB = 0x174783;
    private final Map<Integer, Integer> palette = new LinkedHashMap<>();

    static String commonCharacters() throws IOException {
        try (var input = RgbaOffsets.class.getResourceAsStream("/rgba/characters-3500.txt")) {
            if (input == null) throw new IOException("Missing shader character set");
            return new String(input.readAllBytes(), java.nio.charset.StandardCharsets.UTF_8).strip();
        }
    }

    void prepare(Component text, int fallback) {
        add(fallback);
        for (var glyph : MenuText.glyphs(text)) {
            for (var decoration : List.of(net.kyori.adventure.text.format.TextDecoration.ITALIC,
                net.kyori.adventure.text.format.TextDecoration.UNDERLINED,
                net.kyori.adventure.text.format.TextDecoration.STRIKETHROUGH,
                net.kyori.adventure.text.format.TextDecoration.OBFUSCATED))
                if (glyph.style().hasDecoration(decoration))
                    throw new IllegalArgumentException("Shader文字暂不支持" + decoration + "，请改用原生版");
            if (glyph.style().color() != null) add(glyph.style().color().value());
        }
    }

    private void add(int color) {
        int rgb = color & 0xFFFFFF;
        if (!palette.containsKey(rgb)) {
            if (palette.size() == 256) throw new IllegalArgumentException("Shader文字颜色超过256种");
            palette.put(rgb, palette.size());
        }
    }

    Component encode(Component text, int fallback, int top) {
        if (top < 0 || top > 1023) throw new IllegalArgumentException("RGBA Y exceeds 0..1023");
        var result = Component.text();
        for (var glyph : MenuText.glyphs(text)) {
            int rgb = glyph.style().color() == null ? fallback & 0xFFFFFF : glyph.style().color().value();
            Integer index = palette.get(rgb);
            if (index == null) throw new IllegalArgumentException("RGBA color changed; rebuild resources");
            result.append(glyph.component().color(TextColor.color(MAGIC | top << 8 | index)));
        }
        return result.build().compact();
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
        for (String name : List.of("text.vsh", "text.fsh")) {
            Path shader = pack.resolve("assets/minecraft/shaders/core/" + name);
            Files.createDirectories(shader.getParent());
            try (var input = RgbaOffsets.class.getResourceAsStream("/rgba/" + name)) {
                if (input == null) throw new IOException("Missing RGBA shader: " + name);
                Files.copy(input, shader, java.nio.file.StandardCopyOption.REPLACE_EXISTING);
            }
        }
    }
}
