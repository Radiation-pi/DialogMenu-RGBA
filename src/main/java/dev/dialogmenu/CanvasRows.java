package dev.dialogmenu;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.WeakHashMap;
import java.awt.image.BufferedImage;
import static dev.dialogmenu.MenuModel.*;

/** Dialog 普通消息每次显式换行，基线前进 9 像素。 */
final class CanvasRows {
    static final int HEIGHT = 9;

    private static int gcd(int a, int b) { return b == 0 ? a : gcd(b, a % b); }

    private record Part(int x, int y, BufferedImage image) {}
    static final class Cache {
        private final Map<BufferedImage, List<Part>> images = new WeakHashMap<>();
        synchronized List<Sprite> parts(Sprite sprite) {
            if (!split(sprite)) return List.of(sprite);
            return located(sprite, images.computeIfAbsent(sprite.image(), CanvasRows::splitImage));
        }
    }
    static List<Sprite> parts(Sprite sprite) {
        return split(sprite) ? located(sprite, splitImage(sprite.image())) : List.of(sprite);
    }
    private static boolean split(Sprite sprite) {
        return sprite.texture() == null && Math.max(sprite.image().getWidth(), sprite.image().getHeight())
            / gcd(sprite.image().getWidth(), sprite.image().getHeight()) > 256;
    }
    private static List<Sprite> located(Sprite sprite, List<Part> parts) {
        List<Sprite> result = new ArrayList<>();
        for (Part part : parts)
            result.add(new Sprite(sprite.id(), sprite.x() + part.x(), sprite.y() + part.y(), part.image()));
        return result;
    }
    private static List<Part> splitImage(BufferedImage image) {
        List<Part> result = new ArrayList<>();
        for (int y = 0; y < image.getHeight();) {
            int height = Math.min(256, image.getHeight() - y);
            for (int x = 0; x < image.getWidth(); x += 256) {
                int width = Math.min(256, image.getWidth() - x);
                var part = image.getSubimage(x, y, width, height);
                boolean visible = false;
                for (int py = 0; py < height && !visible; py++) for (int px = 0; px < width; px++)
                    if (part.getRGB(px, py) >>> 24 != 0) { visible = true; break; }
                if (visible) result.add(new Part(x, y, part));
            }
            y += height;
        }
        return result;
    }
}
