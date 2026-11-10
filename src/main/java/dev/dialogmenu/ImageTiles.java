package dev.dialogmenu;

import java.awt.image.BufferedImage;
import java.util.List;

/** Four reusable source quadrants cover the maximum 1024px canvas axis. */
final class ImageTiles {
    record Part(int index, int x, int y, int width, int height) {}

    static boolean allowed(MenuModel.Node node) {
        return node.type().equals("image") && !SymbolImages.source(node.source());
    }

    static boolean needed(int width, int height) { return width > 512 || height > 512; }

    static List<Part> parts(int width, int height) {
        if (width < 1 || height < 1 || width > 1024 || height > 1024)
            throw new IllegalArgumentException("Image display dimensions must be 1..1024");
        int w = (width + 1) / 2, h = (height + 1) / 2;
        return List.of(new Part(0, 0, 0, w, h), new Part(1, width / 2, 0, w, h),
            new Part(2, 0, height / 2, w, h), new Part(3, width / 2, height / 2, w, h));
    }

    static int payload(int width, int height, int localY, int band) {
        if (width < 1 || width > 1024 || height < 1 || height > 1024 || localY < -512 || localY > 511)
            throw new IllegalArgumentException("Tiled image geometry out of range");
        return 0x80000000 | (band == 9 ? 1 << 30 : 0)
            | (width - 1) << 20 | (height - 1) << 10 | localY & 1023;
    }

    static BufferedImage wrap(BufferedImage source, int tint, int index) {
        int width = source.getWidth(), height = source.getHeight();
        if (width < 1 || width > 256 || height < 1 || height > 256 || index < 0 || index > 3)
            throw new IllegalArgumentException("Image tile source must be 1..256");
        int tx = index % 2, ty = index / 2;
        // Include the preceding texel for the shader's exact-boundary sampling rule.
        int left = Math.max(0, width * tx / 2 - 1), top = Math.max(0, height * ty / 2 - 1);
        int right = (width * (tx + 1) + 1) / 2, bottom = (height * (ty + 1) + 1) / 2;
        BufferedImage crop = source.getSubimage(left, top, right - left, bottom - top);
        BufferedImage image = RegisteredImages.wrap(crop, tint);
        int cw = image.getWidth(), ch = image.getHeight();
        int[][] corners = {{0, 0}, {0, ch - 1}, {cw - 1, ch - 1}, {cw - 1, 0}};
        for (int corner = 0; corner < 4; corner++) {
            int x = corners[corner][0], y = corners[corner][1], step = x == 0 ? 1 : -1;
            image.setRGB(x, y, (252 + corner) << 24 | 0x001D5600 | 149 + corner);
            image.setRGB(x + step, y, tint << 24 | (width - 1) << 16 | (height - 1) << 8 | 177);
            image.setRGB(x + step * 2, y, 0xFF0000B2 | tx << 16 | ty << 8);
        }
        return image;
    }
}
