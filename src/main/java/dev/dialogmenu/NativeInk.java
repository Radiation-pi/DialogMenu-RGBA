package dev.dialogmenu;

import java.io.IOException;
import java.nio.file.*;
import java.util.*;
import java.util.function.Predicate;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.*;
import static dev.dialogmenu.MenuModel.*;

/** 原图不变：末个真实按钮绘制原生栏图文，为层级更高的原生控件开孔。 */
final class NativeInk {
    static final int BASE = 131072;
    static final int STRIDE = 1024;
    static final int HALF_CODES = 16128;
    static final int MAX_PROFILES = 63;
    record Control(String id, boolean button, int width, int y, int height, int z) {}
    record Layout(int bodyRows, int carrier, int contentWidth, int extensionRows, List<Control> controls,
                  List<NativeControls.Button> buttons) {
        int top() { return bodyRows * 9; }
        int canvasOffset(int width) {
            int buttonWidth = controls.getLast().width();
            return (contentWidth - width - 10) / 2 + 4
                - (contentWidth - buttonWidth) / 2 - buttonWidth / 2;
        }
    }
    record Box(int width, int y, int height) {}
    record Profile(int carrier, int rows, int minWidth, List<Box> mask) {
        int codes() { return rows * (1025 - minWidth); }
    }
    private final List<Profile> profiles = new ArrayList<>();

    static Layout layout(NativeControls.Spec spec, int canvasWidth, int canvasHeight, Predicate<String> permitted) {
        var buttons = spec.buttons().stream().filter(b -> permitted.test(b.permission())).toList();
        if (buttons.isEmpty()) throw new IllegalArgumentException("原生栏遮挡需要至少一个当前玩家可用的原生按钮");
        List<Control> controls = new ArrayList<>();
        // 正文 padding=4；布局在正文 widget 高度之后留10px间隔。
        int y = 14;
        for (var field : spec.inputs()) {
            int height = field.type().equals("text")
                ? (field.multiline() ? field.height() : 20) + (field.labelVisible() ? 13 : 0) : 20;
            controls.add(new Control(field.id(), false, field.width(), y, height, 0));
            y += height + 10;
        }
        for (var button : buttons) {
            controls.add(new Control(button.id(), true, button.width(), y, 20, button.z()));
            y += 24;
        }
        int bottom = y - 4;
        // 下延只增加背景绘制容量，不把原生控件推向画布底边。
        int rows = Math.max(spec.bodyRows() == -1 ? 1 : spec.bodyRows(),
            (canvasHeight - spec.backgroundExtension() - bottom + 8) / 9);
        if (rows > 113 || rows * 9 + bottom > 1024)
            throw new IllegalArgumentException("原生栏与正文总高度超过1024原生像素");
        int contentWidth = Math.max(canvasWidth + 10, controls.stream().mapToInt(Control::width).max().orElse(0));
        return new Layout(rows, controls.getLast().y() + 6, contentWidth,
            (spec.backgroundExtension() + 8) / 9, List.copyOf(controls), buttons);
    }

    void prepare(NativeControls.Spec spec, Menu menu, int width, int height) {
        if (!spec.overlay() || spec.empty()) return;
        layout(spec, width, height, p -> true);
        var permissions = spec.buttons().stream().map(NativeControls.Button::permission)
            .filter(p -> !p.isEmpty()).distinct().toList();
        var levels = menu.nodes().stream().filter(n -> !n.type().equals("model")).map(Node::z).distinct().toList();
        prepare(spec, levels, permissions, 0, new HashSet<>(), width, height);
    }
    private void prepare(NativeControls.Spec spec, List<Double> levels, List<String> permissions,
                         int index, Set<String> visible, int width, int height) {
        if (index == permissions.size()) {
            if (spec.buttons().stream().noneMatch(b -> b.permission().isEmpty() || visible.contains(b.permission())))
                return;
            var layout = layout(spec, width, height, p -> p.isEmpty() || visible.contains(p));
            for (double z : levels) {
                register(profileOf(layout, z));
            }
            return;
        }
        String permission = permissions.get(index);
        visible.add(permission);
        prepare(spec, levels, permissions, index + 1, visible, width, height);
        visible.remove(permission);
        prepare(spec, levels, permissions, index + 1, visible, width, height);
    }
    private int register(Profile profile) {
        int found = profiles.indexOf(profile);
        if (found >= 0) return found;
        if (profiles.size() == MAX_PROFILES)
            throw new IllegalArgumentException("原生栏排版组合超过63种，请减少不同控件宽高或权限组合");
        if (profiles.stream().mapToInt(Profile::codes).sum() + profile.codes() > HALF_CODES * 2)
            throw new IllegalArgumentException("原生栏倍率排版容量不足，请减少不同原生排版、层级或权限组合");
        profiles.add(profile);
        return profiles.size() - 1;
    }
    private static Profile profileOf(Layout layout, double z) {
        var last = layout.controls().getLast();
        int rows = (last.y() + last.height() + 8) / 9 + 1 + layout.extensionRows();
        int minWidth = Math.max(74, layout.controls().stream().mapToInt(Control::width).max().orElse(0));
        return new Profile(layout.carrier(), rows, minWidth, mask(layout, z));
    }
    private static List<Box> mask(Layout layout, double z) {
        return layout.controls().stream().filter(c -> !c.button() || z <= c.z())
            .map(c -> new Box(c.width(), c.y(), c.height())).toList();
    }
    int code(Layout layout, double z, int row) {
        int index = profiles.indexOf(profileOf(layout, z));
        if (index < 0) throw new IllegalArgumentException("原生栏排版尚未准备，请执行 /dialogmenu reload all");
        var profile = profiles.get(index);
        if (row < -1 || row + 1 >= profile.rows() || layout.contentWidth() > 1024)
            throw new IllegalArgumentException("原生栏绘制行超出协议");
        int start = profiles.subList(0, index).stream().mapToInt(Profile::codes).sum();
        return start + (layout.contentWidth() - profile.minWidth()) * profile.rows() + row + 1;
    }
    static Component scope(Component ink, int id) {
        if (id < 0 || id >= HALF_CODES * 2) throw new IllegalArgumentException("原生栏绘制编号超出协议");
        return Component.empty().append(move(id, false)).append(ink).append(move(id, true));
    }
    private static Component move(int id, boolean restore) {
        if (id >= HALF_CODES) return move(id - HALF_CODES, !restore);
        StringBuilder codes = new StringBuilder(Character.toString(restore ? 0xF201B : 0xF201A));
        for (int bit = 0; bit < 14; bit++)
            if ((id & 1 << bit) != 0) codes.appendCodePoint(0xF2040 + bit + (restore ? 14 : 0));
        return Component.text(codes.toString()).font(SpriteCanvas.SPACE);
    }
    static void spaces(Map<String, Integer> spaces) {
        spaces.put(Character.toString(0xF201A), -BASE);
        spaces.put(Character.toString(0xF201B), BASE);
        for (int bit = 0; bit < 14; bit++) {
            spaces.put(Character.toString(0xF2040 + bit), -(STRIDE << bit));
            spaces.put(Character.toString(0xF204E + bit), STRIDE << bit);
        }
    }
    void write(Path pack) throws IOException {
        var shader = new StringBuilder("ivec3 dialogmenu_native_scope(int code) {\n");
        int start = 0;
        for (int i = 0; i < profiles.size(); i++) {
            var p = profiles.get(i);
            shader.append("    if (code >= ").append(start).append(" && code < ").append(start + p.codes())
                .append(") { int n = code - ").append(start).append("; return ivec3(").append(i)
                .append(", n % ").append(p.rows()).append(" - 1, ").append(p.minWidth())
                .append(" + n / ").append(p.rows()).append("); }\n");
            start += p.codes();
        }
        shader.append("    return ivec3(-1, 0, 0);\n}\n")
            .append("int dialogmenu_native_row(int code) { return dialogmenu_native_scope(code).y; }\n")
            .append("ivec3 dialogmenu_native_profile(int code) {\n")
            .append("    ivec3 scope = dialogmenu_native_scope(code);\n    switch (scope.x) {\n");
        for (int i = 0; i < profiles.size(); i++) {
            var p = profiles.get(i);
            shader.append("        case ").append(i).append(": return ivec3(")
                .append(p.carrier()).append(", ").append(p.mask().size()).append(", ")
                .append("scope.z);\n");
        }
        shader.append("    }\n    return ivec3(0);\n}\n");
        shader.append("vec4 dialogmenu_native_box(int code, int box) {\n")
            .append("    ivec3 scope = dialogmenu_native_scope(code);\n    switch (scope.x) {\n");
        for (int i = 0; i < profiles.size(); i++) {
            if (profiles.get(i).mask().isEmpty()) continue;
            shader.append("        case ").append(i).append(":\n            switch (box) {\n");
            for (int j = 0; j < profiles.get(i).mask().size(); j++) {
                var box = profiles.get(i).mask().get(j);
                shader.append("                case ").append(j).append(": return vec4(")
                    .append("float((scope.z - ").append(box.width()).append(") / 2), ")
                    .append(box.y()).append(".0, ")
                    .append("float((scope.z - ").append(box.width()).append(") / 2 + ").append(box.width()).append("), ")
                    .append(box.y() + box.height()).append(".0);\n");
            }
            shader.append("            }\n            break;\n");
        }
        shader.append("    }\n    return vec4(0.0);\n}\n");
        Path path = pack.resolve("assets/dialogmenu/shaders/include/native_profiles.glsl");
        Files.createDirectories(path.getParent());
        Files.writeString(path, shader);
        if (!profiles.isEmpty()) {
            Files.createDirectories(pack.resolve("assets/dialogmenu/textures/font"));
            var image = new java.awt.image.BufferedImage(2, 2, java.awt.image.BufferedImage.TYPE_INT_ARGB);
            for (int y = 0; y < 2; y++) for (int x = 0; x < 2; x++) image.setRGB(x, y, 0xFF1D5795);
            FontPack.writePng(image, pack.resolve("assets/dialogmenu/textures/font/native_order.png"));
            Files.writeString(pack.resolve("assets/dialogmenu/font/native_order.json"),
                new com.google.gson.Gson().toJson(Map.of("providers",
                    List.of(FontPack.bitmap("font/native_order.png", 1024, 1017, List.of("\uE000"))))));
        }
    }

    static Component orderBounds() {
        // GUI text has no depth test in 26.3: its CPU bounds must intersect the preceding widgets.
        return Component.empty().append(SpriteCanvas.space(-512))
            .append(Component.text("\uE000").font(net.kyori.adventure.key.Key.key("dialogmenu:native_order")))
            .append(SpriteCanvas.space(-513));
    }

    static void validate(LayoutStore.Document doc, Rendered frame, Context context, FontPack fonts, Layout layout) {
        if (!doc.flowRows().isEmpty())
            throw new IllegalArgumentException("原生栏遮挡模式不能同时使用flow-lists正文");
        for (var field : doc.controls().inputs()) {
            String label = context.expand(field.label());
            if (field.type().equals("text") && field.labelVisible()
                && (label.contains("\n") || fonts.textWidth(Component.text(label), 8) > field.width()))
                throw new IllegalArgumentException(field.id() + "：原生标签须单行且不宽于输入框");
        }
        Map<String, Sprite> sprites = new HashMap<>();
        for (var sprite : frame.sprites()) sprites.put(sprite.id(), sprite);
        for (var node : doc.menu().nodes()) {
            var interaction = node.interaction();
            boolean interactive = interaction != null && (interaction.region(node).hasActions()
                || !interaction.tooltip().isEmpty());
            double bottom;
            if (node.type().equals("model")) {
                var area = fonts.modelArea(doc.menu(), node, frame.width(), frame.height());
                bottom = area.getMaxY();
                if (bottom > layout.top())
                    throw new IllegalArgumentException(node.id() + "：原生栏暂不支持模型，请放在正文区");
            } else if (node.type().equals("text")) {
                int size = SpriteCanvas.textSize(doc.menu(), node, frame.width(), context.textScale(), context.canvasScale());
                TextStyle style = new TextStyle(size, node.scale());
                var lines = TextFlow.lines(node, MenuText.parse(context.expand(node.text())),
                    frame.width() / (double) doc.menu().width(), glyph -> fonts.glyphWidth(glyph, style));
                bottom = SpriteCanvas.textTop(doc.menu(), node, frame.width(), Math.max(0, lines.size() - 1), style)
                    + style.height();
            } else {
                var sprite = sprites.get(node.id());
                if (sprite == null) continue;
                bottom = sprite.y() + sprite.height();
            }
            if (interactive && bottom > layout.top())
                throw new IllegalArgumentException(node.id() + "：无法在原生栏内放点击或悬停区域（含两侧、间隙和下方）");
        }
    }
}
