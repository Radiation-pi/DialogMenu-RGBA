package dev.dialogmenu;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.TextComponent;
import static dev.dialogmenu.MenuModel.*;

/** 从实际编码的字形几何核对字号调整与卡片宽度，包含动态文本失败边界。 */
final class FitWidthCheck {
    private static void heights(Component component, List<Integer> result) {
        if (component instanceof TextComponent text && text.content().equals("苍") && component.shadowColor() != null) {
            int geometry = component.shadowColor().value() >>> 17 & 16383;
            int height = 1;
            while (height < 128 && geometry > height * (height + 35) / 2) height++;
            result.add(height);
        }
        for (var child : component.children()) heights(child, result);
    }
    static void run(Path root) throws Exception {
        Path data = Files.createTempDirectory(root, "fit-width-");
        Files.createDirectories(data.resolve("layouts"));
        String yaml = """
            schema-version: 1
            id: probe
            canvas: {width: 300, height: 180}
            frontend:
              label:
                type: text
                content: '苍白橡树树苗'
                size: 8
                fit-width: 40
            """;
        Files.writeString(data.resolve("layouts/probe.yml"), yaml);
        Files.writeString(data.resolve("config.yml"), "default-layout: probe\nresource-build-cpu-percent: 100\ntext-scales: [1, 2]\n");
        Context context = new Context() {
            public String expand(String value) { return value; }
            public Icon item(int slot) { return null; }
            public boolean permitted(String permission) { return true; }
        };
        try (var engine = new ReloadEngine(data, context)) {
            var snapshot = engine.prepare().get().snapshot();
            var menu = snapshot.documents().get("probe").menu();
            for (double scale : List.of(1d, 2d)) {
                Context scaled = new Context() {
                    public String expand(String value) { return value; }
                    public Icon item(int slot) { return null; }
                    public boolean permitted(String permission) { return true; }
                    public double textScale() { return scale; }
                };
                var frame = snapshot.renderer().drawRuntime(menu, scaled, 300, 655360);
                var output = new SpriteCanvas().encode(menu, frame, scaled, snapshot.fonts(), region -> null);
                List<Integer> heights = new ArrayList<>();
                heights(output.component(), heights);
                if (heights.isEmpty()) throw new AssertionError("fit-width lost visible glyphs");
                for (int height : heights) {
                    if (height >= 8 * scale || snapshot.fonts().textWidth("苍白橡树树苗", height) > 40)
                        throw new AssertionError("encoded text does not fit the requested width");
                }
            }
            // 动态值可能含有换行或极长文本；不能静默溢出、换行或丢字。
            for (String invalid : List.of("苍\n白", "苍".repeat(100))) {
                Context changed = new Context() {
                    public String expand(String value) { return invalid; }
                    public Icon item(int slot) { return null; }
                    public boolean permitted(String permission) { return true; }
                };
                try {
                    var frame = snapshot.renderer().drawRuntime(menu, changed, 300, 655360);
                    new SpriteCanvas().encode(menu, frame, changed, snapshot.fonts(), region -> null);
                    throw new AssertionError("unrenderable single-line value accepted");
                } catch (IllegalArgumentException expected) {
                    String reason = expected.getMessage();
                    if (!reason.contains("fit-width")) throw expected;
                }
            }
        }
        for (String invalid : List.of("fit-width: 0", "fit-width: 40\n    line-width: 40\n    max-lines: 1",
            "fit-width: 40\n    max-lines: 1")) {
            try {
                LayoutStore.loadText("probe", yaml.replace("fit-width: 40", invalid));
                throw new AssertionError("invalid fit-width accepted");
            } catch (IllegalArgumentException expected) {}
        }
        Path edge = Files.createTempDirectory(root, "fit-width-edge-");
        Files.createDirectories(edge.resolve("layouts"));
        Files.writeString(edge.resolve("layouts/probe.yml"), yaml.replace("size: 8", "size: 8\n    offset: {y: 86}"));
        Files.writeString(edge.resolve("config.yml"),
            "default-layout: probe\nresource-build-cpu-percent: 100\ntext-scales: [2]\ndefault-text-scale: 2\n");
        try (var engine = new ReloadEngine(edge, context)) {
            var snapshot = engine.prepare().get().snapshot();
            var menu = snapshot.documents().get("probe").menu();
            Context doubled = new Context() {
                public String expand(String value) { return value; }
                public Icon item(int slot) { return null; }
                public boolean permitted(String permission) { return true; }
                public double textScale() { return 2; }
            };
            var frame = snapshot.renderer().drawRuntime(menu, doubled, 300, 655360);
            new SpriteCanvas().encode(menu, frame, doubled, snapshot.fonts(), region -> null);
        }
        System.out.println("PASS fit-width encoded text and invalid configuration checks (offline)");
    }
}
