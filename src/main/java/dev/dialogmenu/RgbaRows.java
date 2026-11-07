package dev.dialogmenu;

import java.util.*;
import static dev.dialogmenu.MenuModel.*;

/** 原生行不能改变菜单的绘制顺序，无交互图层也必须保序。 */
final class RgbaRows {
    static Map<String, Integer> plan(Menu menu, Rendered frame) {
        Map<String, Sprite> sprites = new HashMap<>();
        for (Sprite sprite : frame.sprites()) sprites.put(sprite.id(), sprite);
        Map<String, Integer> rows = new HashMap<>();
        double zoom = frame.image().getWidth() / (double) menu.width();
        int next = Integer.MAX_VALUE;
        for (Node node : menu.nodes().reversed()) {
            Sprite sprite = sprites.get(node.id());
            // 支持的实际字号均不超过 128px，行计划不依赖玩家当前选择的倍率。
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
