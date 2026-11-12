package dev.dialogmenu;

import java.awt.Rectangle;
import java.awt.image.BufferedImage;
import java.util.List;
import static dev.dialogmenu.MenuModel.*;

/** Dedicated solid source; borders are four non-overlapping rectangles. */
final class PrimitiveShapes {
    static final String SOURCE = "primitive:solid";
    private static final BufferedImage SOLID = new BufferedImage(1, 1, BufferedImage.TYPE_INT_ARGB);
    static { SOLID.setRGB(0, 0, 0xFFFFFFFF); }

    static boolean type(String type) {
        return type.equals("rectangle") || type.equals("frame") || type.equals("line");
    }
    static BufferedImage source() { return SOLID; }
    static boolean sprite(Sprite sprite) { return SOURCE.equals(sprite.texture()); }

    static List<Rectangle> rectangles(Node node, int width, int height, double zoom) {
        if (!node.type().equals("frame")) return List.of(new Rectangle(0, 0, width, height));
        int edge = Math.max(1, (int) Math.round(node.thickness() * node.scale() * zoom));
        if (2L * edge >= Math.min(width, height)) return List.of(new Rectangle(0, 0, width, height));
        return List.of(new Rectangle(0, 0, width, edge), new Rectangle(0, height - edge, width, edge),
            new Rectangle(0, edge, edge, height - 2 * edge),
            new Rectangle(width - edge, edge, edge, height - 2 * edge));
    }

    static int payload(int width, int height, int top, int band) {
        if (width < 1 || width > 1024 || height < 1 || height > 1024
            || (band != 0 && band != 9)
            || (band == 0 ? top < 0 || top > 8 : top < 10 - height || top > 17))
            throw new IllegalArgumentException("基础形状尺寸或行带超出范围");
        int row = band == 0 ? height + 8 + top : top + height - 10;
        int geometry = (height - 1) * (height + 34) / 2 + row;
        return 0x40000000 | geometry << 10 | (width - 1);
    }
}
