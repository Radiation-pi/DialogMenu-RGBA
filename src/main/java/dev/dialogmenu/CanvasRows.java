package dev.dialogmenu;

import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import net.kyori.adventure.text.format.TextDecoration;
import static dev.dialogmenu.MenuModel.*;

/** Dialog 普通消息每次显式换行，基线前进 9 像素。 */
final class CanvasRows {
    static final int HEIGHT = 9;
    // 从前一条基线输出，使用负 ascent 保证短片段的命中边界准确。
    static int offset(int top) { return top < HEIGHT ? top : HEIGHT + top % HEIGHT; }
    static int bodyRow(int top) { return Math.max(0, top / HEIGHT - 1); }

    static boolean enabled(Menu menu, Rendered frame, Context context, int width, Collection<Double> scales) {
        if (!graphics(frame)) {
            // 只有全部图形先绘制时，完整外部字形才能排在分行文字之前。
            boolean text = false;
            for (Node node : menu.nodes()) {
                if (node.type().equals("text")) text = true;
                else if (text) return false;
            }
        }
        for (Node node : frame.textNodes()) {
            for (double scale : scales) {
                int size = SpriteCanvas.textSize(menu, node, width, scale, context.canvasScale());
                for (int sourceHeight : List.of(8, 12, 16))
                    if (sourceHeight * size / gcd(sourceHeight, size) > 256) return false;
            }
            for (var glyph : MenuText.glyphs(MenuText.parse(context.expand(node.text()))))
                for (var decoration : List.of(TextDecoration.ITALIC, TextDecoration.UNDERLINED,
                    TextDecoration.STRIKETHROUGH, TextDecoration.OBFUSCATED))
                    if (glyph.style().hasDecoration(decoration)) return false;
        }
        return true;
    }

    static boolean graphics(Rendered frame) {
        return frame.sprites().stream().noneMatch(sprite -> sprite.texture() != null);
    }

    private static int gcd(int a, int b) { return b == 0 ? a : gcd(b, a % b); }

    static List<Sprite> parts(Sprite sprite, boolean rows) {
        if (sprite.texture() != null) return List.of(sprite);
        if (!rows && Math.max(sprite.image().getWidth(), sprite.image().getHeight())
            / gcd(sprite.image().getWidth(), sprite.image().getHeight()) <= 256) return List.of(sprite);
        List<Sprite> result = new ArrayList<>();
        var image = sprite.image();
        for (int y = 0; y < image.getHeight();) {
            int height = Math.min(rows ? HEIGHT - (sprite.y() + y) % HEIGHT : 256, image.getHeight() - y);
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

    static Sprite local(Sprite sprite, boolean rows) {
        return rows && sprite.texture() == null
            ? new Sprite(sprite.id(), sprite.x(), offset(sprite.y()), sprite.image()) : sprite;
    }
}
