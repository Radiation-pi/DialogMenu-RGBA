package dev.dialogmenu;

import java.awt.image.BufferedImage;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.*;

/** Fixed source cells; geometry and palette selection travel with each glyph. */
final class RgbaText {
    static final String FONT = "text_rgba_8";
    static final int RULE = 0xE100;

    static int payload(int width, int height, int top, int band, int color, boolean italic) {
        if (width < 1 || width > 512 || height < 1 || height > 128 || color < 0 || color > 255
            || (band != 0 && band != 9)
            || (band == 0 ? top < 0 || top > 8 : top < 10 - height || top > 17))
            throw new IllegalArgumentException("文字几何超出行带协议");
        int row = band == 0 ? height + 8 + top : top + height - 10;
        int geometry = (height - 1) * (height + 34) / 2 + row + 1;
        return (italic ? 0x80000000 : 0) | geometry << 17 | (width - 1) << 8 | color;
    }

    static Component ink(MenuText.Glyph glyph, int color, int width, int height, int top, int band) {
        return glyph.component().color(TextColor.color(RgbaOffsets.INK))
            .shadowColor(ShadowColor.shadowColor(payload(width, height, top, band, color,
                glyph.style().hasDecoration(TextDecoration.ITALIC))))
            .decoration(TextDecoration.ITALIC, false)
            .decoration(TextDecoration.UNDERLINED, false)
            .decoration(TextDecoration.STRIKETHROUGH, false);
    }

    static BufferedImage wrap(VanillaAssets.Glyph glyph) {
        int width = glyph.width(), height = glyph.height();
        int size = Integer.highestOneBit(Math.max(width, height) + 1) << 1;
        BufferedImage image = new BufferedImage(size, size, BufferedImage.TYPE_INT_ARGB);
        for (int y = 0; y < height; y++) for (int x = 0; x < width; x++)
            image.setRGB(x + 1, y + 1, Byte.toUnsignedInt(glyph.alpha()[y * width + x]) << 24 | 0xFFFFFF);
        int[][] corners = {{0, 0}, {0, size - 1}, {size - 1, size - 1}, {size - 1, 0}};
        for (int i = 0; i < corners.length; i++) {
            int x = corners[i][0], y = corners[i][1];
            image.setRGB(x, y, (252 + i) << 24 | 0x1D5500 | 149 + i);
            image.setRGB(x == 0 ? 1 : x - 1, y, width << 16 | height << 8 | 177);
        }
        return image;
    }
}
