package dev.dialogmenu;

import java.util.*;
import static dev.dialogmenu.MenuModel.*;

/** Native rows must never reorder the menu's painter order, including noninteracting layers. */
final class RgbaRows {
    static Map<String, Integer> plan(Menu menu, Rendered frame) {
        Map<String, Sprite> sprites = new HashMap<>();
        for (Sprite sprite : frame.sprites()) sprites.put(sprite.id(), sprite);
        Map<String, Integer> rows = new HashMap<>();
        double zoom = frame.image().getWidth() / (double) menu.width();
        int next = Integer.MAX_VALUE;
        for (Node node : menu.nodes().reversed()) {
            Sprite sprite = sprites.get(node.id());
            // All supported text scales are <=128px; keep the plan independent of selected scale.
            int top = node.type().equals("text")
                ? Math.max(0, (int) Math.floor((menu.height() / 2.0 - node.y()) * zoom - 64))
                : sprite == null ? 0 : sprite.y();
            int row = Math.min(next, CanvasRows.bodyRow(top));
            rows.put(node.id(), row);
            next = row;
        }
        return rows;
    }

    static Sprite relative(Sprite sprite, int row) {
        return new Sprite(sprite.id(), sprite.x(), sprite.y() - row * CanvasRows.HEIGHT,
            sprite.image(), sprite.texture(), sprite.original(), sprite.textureRegion());
    }
}
