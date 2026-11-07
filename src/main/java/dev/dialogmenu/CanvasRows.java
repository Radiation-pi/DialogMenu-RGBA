package dev.dialogmenu;

import java.util.ArrayList;
import java.util.List;
import static dev.dialogmenu.MenuModel.*;

/** Dialog 普通消息每次显式换行，基线前进 9 像素。 */
final class CanvasRows {
    static final int HEIGHT = 9;
    static int bodyRow(int top) { return Math.max(0, top / HEIGHT - 1); }

    private static int gcd(int a, int b) { return b == 0 ? a : gcd(b, a % b); }

    static List<Sprite> parts(Sprite sprite) {
        if (sprite.texture() != null) return List.of(sprite);
        if (Math.max(sprite.image().getWidth(), sprite.image().getHeight())
            / gcd(sprite.image().getWidth(), sprite.image().getHeight()) <= 256) return List.of(sprite);
        List<Sprite> result = new ArrayList<>();
        var image = sprite.image();
        for (int y = 0; y < image.getHeight();) {
            int height = Math.min(256, image.getHeight() - y);
            for (int x = 0; x < image.getWidth(); x += 256) {
                int width = Math.min(256, image.getWidth() - x);
                var part = image.getSubimage(x, y, width, height);
                boolean visible = false;
                for (int py = 0; py < height && !visible; py++) for (int px = 0; px < width; px++)
                    if (part.getRGB(px, py) >>> 24 != 0) { visible = true; break; }
                if (visible) result.add(new Sprite(sprite.id(), sprite.x() + x, sprite.y() + y, part));
            }
            y += height;
        }
        return result;
    }
}
