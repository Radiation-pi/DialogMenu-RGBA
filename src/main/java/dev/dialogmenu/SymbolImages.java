package dev.dialogmenu;

import java.awt.image.BufferedImage;
import java.io.IOException;
import java.util.List;

final class SymbolImages {
    static final List<String> SYMBOLS = List.of("I", "—", "□", "■", "●", "○", "♢", "♦");
    static boolean source(String source) { return source != null && source.startsWith("symbol:"); }

    static BufferedImage image(String source, VanillaAssets assets) throws IOException {
        String symbol = source.substring("symbol:".length());
        if (!SYMBOLS.contains(symbol)) throw new IOException("未知图形符号：" + symbol);
        var glyph = assets.glyph(symbol.codePointAt(0));
        if (glyph == null) throw new IOException("字库缺少图形：" + symbol);
        // Only discard transparent margins, never resize or split the visible glyph.
        int left = glyph.width(), top = glyph.height(), right = 0, bottom = 0;
        for (int y = 0; y < glyph.height(); y++) for (int x = 0; x < glyph.width(); x++)
            if (glyph.alpha()[y * glyph.width() + x] != 0) {
                left = Math.min(left, x); top = Math.min(top, y);
                right = Math.max(right, x + 1); bottom = Math.max(bottom, y + 1);
            }
        if (right <= left || bottom <= top) throw new IOException("空图形符号：" + symbol);
        BufferedImage image = new BufferedImage(right - left, bottom - top, BufferedImage.TYPE_INT_ARGB);
        for (int y = top; y < bottom; y++) for (int x = left; x < right; x++)
            image.setRGB(x - left, y - top, Byte.toUnsignedInt(glyph.alpha()[y * glyph.width() + x]) << 24 | 0xFFFFFF);
        return image;
    }
}
