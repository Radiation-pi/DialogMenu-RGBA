package dev.dialogmenu;

import java.util.ArrayList;
import java.util.List;
import static dev.dialogmenu.MenuModel.*;

/** Fixed-reference preview; adjustments stay in the current view until confirmed. */
final class ScaleCalibration {
    static final int BASE_WIDTH = 452;
    static final int BASE_HEIGHT = 177;
    static final int WIDTH = MenuRenderer.MAX_WIDTH;
    static final int CENTERED_WIDTH = 324;
    static final int HEIGHT = 1024;
    static final int PIXELS = WIDTH * HEIGHT;
    static final String INSTRUCTION = "不要滚动，调整白线到界面底部";
    static final String ACTION = "calibrate-scale";
    static final String VALUE_NODE = "current-scale";
    static final int DISABLED_COLOR = 0xFF555555;
    private static final LayoutStore.Document DOCUMENT = document(1);
    private static final LayoutStore.Document CENTERED_DOCUMENT = document(1, true);

    static LayoutStore.Document document() { return DOCUMENT; }
    static List<LayoutStore.Document> documents() { return List.of(DOCUMENT, CENTERED_DOCUMENT); }
    static boolean is(LayoutStore.Document document) {
        return document == DOCUMENT || document == CENTERED_DOCUMENT;
    }

    static Context reference(Context base) {
        return new Context() {
            public String expand(String text) { return base.expand(text); }
            public Icon item(int slot) { return base.item(slot); }
            public boolean permitted(String permission) { return base.permitted(permission); }
            public double textScale() { return 1; }
            public double canvasScale() { return 1; }
        };
    }

    static double adjust(double current, int steps) {
        if (!CanvasScale.valid(current) || !List.of(-10, -5, -1, 1, 5, 10).contains(steps))
            throw new IllegalArgumentException("校准步数须为±1、±5或±10");
        double value = java.math.BigDecimal.valueOf(current)
            .add(java.math.BigDecimal.valueOf(steps, 2)).doubleValue();
        return Math.clamp(value, CanvasScale.MIN, CanvasScale.MAX);
    }

    static String label(double scale) {
        return java.math.BigDecimal.valueOf(scale).setScale(4, java.math.RoundingMode.HALF_UP)
            .stripTrailingZeros().toPlainString();
    }

    static LayoutStore.Document document(double scale) {
        return document(scale, false);
    }

    static LayoutStore.Document document(double scale, boolean centered) {
        if (!CanvasScale.valid(scale)) throw new IllegalArgumentException("画布倍率须为0.8..2.2");
        int width = centered ? CENTERED_WIDTH : WIDTH;
        // SpriteCanvas gives each native text row width + 2 advance.
        double left = centered ? (width + 2 - 320) / 2.0 : 24;
        var nodes = new ArrayList<Node>();
        nodes.add(text(width, "instruction", left, 24, INSTRUCTION));
        nodes.add(text(width, VALUE_NODE, left, 44, "当前 " + label(scale) + "x"));
        nodes.add(text(width, "range", left + 128, 44, "0.8x - 2.2x"));
        int index = 0;
        for (int step : List.of(-10, -5, -1, 1, 5, 10)) {
            String label = (step > 0 ? "+" : "") + step;
            nodes.add(button(width, "step-" + step, left + index++ * 48, 72, label,
                ACTION + ": " + step, adjust(scale, step) != scale));
        }
        nodes.add(text(width, "step-help", left, 88, "1步 = 0.01x"));
        nodes.add(button(width, "confirm", left, 112, "确定", ACTION + ": confirm", true));
        nodes.add(button(width, "cancel", left + 88, 112, "取消", "close", true));
        nodes.add(button(width, "alignment", left + 192, 112, centered ? "靠左显示" : "水平居中",
            ACTION + ": align", true));
        int bottom = (int) Math.round(BASE_HEIGHT * scale);
        nodes.add(new Node("boundary", "text", left - width / 2.0,
            HEIGHT / 2.0 - bottom + .5, 0, 40, .125, 0, 0, 0xFFFFFFFF,
            Character.toString(RgbaText.RULE), "", "left", 8, 1, 0, 0, 0, List.of()));
        return new LayoutStore.Document(new Menu("dialogmenu-scale-calibration", "", width, HEIGHT,
            List.copyOf(nodes), List.of(), true, false), List.of(), List.of());
    }

    private static Node button(int width, String id, double x, double y, String label, String action, boolean enabled) {
        return new Node(id, "text", x - width / 2.0, HEIGHT / 2.0 - y, 1,
            1, 1, 0, 0, enabled ? 0xFFFFFFFF : DISABLED_COLOR, "[" + label + "]", "", "left",
            8, 1, 0, 0, 0, List.of(), 0,
            new Interaction(enabled, "", List.of(), enabled ? List.of(action) : List.of()), false);
    }

    private static Node text(int width, String id, double x, double y, String value) {
        return new Node(id, "text", x - width / 2.0, HEIGHT / 2.0 - y, 1,
            1, 1, 0, 0, 0xFFFFFFFF, value, "", "left", 8, 1, 0, 0, 0, List.of());
    }
}
