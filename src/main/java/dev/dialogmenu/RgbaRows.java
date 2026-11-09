package dev.dialogmenu;

import java.util.*;
import static dev.dialogmenu.MenuModel.*;

/** 每个物理行带独立按 Z 绘制；前移一个原生基线，给命中字体保留 ascent 空间。 */
final class RgbaRows {
    record Slice(int row, int top, int height, int band) {
        int local(int origin) { return origin - row * CanvasRows.HEIGHT; }
    }

    static List<Slice> slices(int top, int height) {
        List<Slice> result = new ArrayList<>();
        for (int y = top, end = top + height; y < end;) {
            int band = y / CanvasRows.HEIGHT;
            int bottom = Math.min(end, (band + 1) * CanvasRows.HEIGHT);
            result.add(new Slice(Math.max(0, band - 1), y, bottom - y, band == 0 ? 0 : 9));
            y = bottom;
        }
        return result;
    }

    static Sprite local(Sprite sprite) {
        return new Sprite(sprite.id(), sprite.x(), 7, sprite.image(), sprite.texture(), sprite.original(),
            sprite.textureRegion(), sprite.width(), sprite.height());
    }

}
