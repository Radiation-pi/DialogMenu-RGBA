package dev.dialogmenu;

import com.google.gson.*;
import java.nio.file.*;
import java.util.*;
import java.util.concurrent.TimeUnit;
import javax.imageio.ImageIO;
import net.kyori.adventure.nbt.*;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.event.ClickEvent;
import net.kyori.adventure.text.format.TextDecoration;
import org.bukkit.configuration.file.YamlConfiguration;
import static dev.dialogmenu.MenuModel.*;

/** 真实布局编码、资源导出、配置恢复及 SNBT 数据的离线检查。 */
public final class DecorationOpenerTests {
    private static int checks;
    private static final Context BASE = new Context() {
        public String expand(String text) { return text; }
        public Icon item(int slot) { return null; }
        public boolean permitted(String permission) { return true; }
    };
    public static void main(String[] args) throws Exception { run(Path.of(args[0])); }

    static void run(Path root) throws Exception {
        checks = 0;
        Path data = Files.createTempDirectory(root, "decorations-openers-");
        Files.createDirectories(data.resolve("layouts"));
        String source = """
            schema-version: 1
            id: styles
            canvas: {width: 300, height: 180}
            frontend:
              label:
                type: text
                content: '&c&o&n&m&kA汉&rb'
                size: 8
                actions: ['tell: styled']
                tooltip: Styled text
            """;
        Files.writeString(data.resolve("layouts/styles.yml"), source);
        String config = """
            default-layout: styles
            canvas-width: 300
            max-canvas-pixels: 1048576
            text-scales: [0.8, 1, 2]
            canvas-scales: [0.8, 1, 2]
            resource-build-cpu-percent: 100
            openers:
              handbook:
                menu: styles
                permission: example.menu
                commands: [handbook, book]
                keys: [shift+f, SPRINT+Q]
                item:
                  material: BOOK
                  name: '&6手册'
                  lore: ['&7右键打开']
                  nbt: '{menu:{id:"handbook",version:1b}}'
            """;
        Files.writeString(data.resolve("config.yml"), config);
        String preferences = "players:\n  keep: 0.75\ncanvas-scales:\n  keep: 0.75\n";
        Files.writeString(data.resolve("player-text-scales.yml"), preferences);
        try (var engine = new ReloadEngine(data, BASE)) {
            var first = engine.prepare().get(60, TimeUnit.SECONDS).snapshot();
            check(first.settings().canvasScales().equals(List.of(.8, 1d, 2d)), "Configured canvas scales are prepared");
            check(CanvasScale.completions(first.settings().canvasScales()).equals(List.of("0.8", "1", "2")),
                "Completion displays configured values");
            check(tabs(first, "ScAlE").equals(List.of("0.8", "1", "2")),
                "Actual command completion reads loaded canvas scales, case-insensitively");
            var doc = first.documents().get("styles");
            var fontPack = data.resolve("export");
            first.fonts().write(fontPack);
            for (double zoom : first.settings().canvasScales())
                for (double text : first.settings().textScales()) {
                    Context active = new Context() {
                        public String expand(String value) { return value; }
                        public Icon item(int slot) { return null; }
                        public boolean permitted(String permission) { return true; }
                        public double canvasScale() { return zoom; }
                        public double textScale() { return text; }
                    };
                    var frame = first.renderer().draw(doc.menu(), active, 300, first.settings().maxPixels());
                    var output = new SpriteCanvas().encode(doc.menu(), frame, active, first.fonts(),
                        region -> ClickEvent.runCommand("/styled"));
                    var glyphs = MenuText.glyphs(output.component());
                    check(glyphs.stream().anyMatch(g -> g.style().hasDecoration(TextDecoration.ITALIC)
                        && g.style().hasDecoration(TextDecoration.OBFUSCATED)), "Italic and obfuscation reach the client");
                    check(glyphs.stream().noneMatch(g -> g.style().hasDecoration(TextDecoration.UNDERLINED)
                        || g.style().hasDecoration(TextDecoration.STRIKETHROUGH)), "Native fixed-baseline rules are suppressed");
                    var rules = glyphs.stream().filter(g -> g.style().font() != null
                        && g.style().font().value().startsWith("rule_")).toList();
                    check(!rules.isEmpty(), "Both decoration lines are encoded as actual RGBA glyphs");
                    TextStyle style = new TextStyle(SpriteCanvas.textSize(doc.menu(), doc.menu().nodes().getFirst(),
                        frame.width(), text, zoom), 1, 1);
                    int top = SpriteCanvas.textTop(doc.menu(), doc.menu().nodes().getFirst(), frame.width(), 0, style);
                    Set<Integer> positions = new HashSet<>();
                    int row = 0;
                    for (var rule : glyphs) {
                        if (rule.code() == '\n') { row++; continue; }
                        if (rule.style().font() == null || !rule.style().font().value().startsWith("rule_")) continue;
                        int payload = rule.style().shadowColor().value();
                        check((payload & 0xE0000000) == RgbaOffsets.MAGIC, "Rules use the row-slice protocol");
                        int local = payload >> 8 & 1023;
                        if (local >= 512) local -= 1024;
                        positions.add(local + row * 9);
                    }
                    check(positions.equals(Set.of(top + style.height() - RgbaDecorations.thickness(style),
                        top + (style.height() - RgbaDecorations.thickness(style)) / 2)), "Underline/strike follow actual scaled height");
                    check(glyphs.stream().anyMatch(g -> g.style().font() != null
                        && g.style().font().equals(RgbaHits.FONT) && g.style().clickEvent() != null
                        && g.style().hoverEvent() != null), "Styled text retains click and hover");
                    int measured = 0;
                    for (var g : MenuText.glyphs(RgbaDecorations.line(37, RgbaDecorations.thickness(style)))) {
                        if (g.style().font().equals(SpriteCanvas.SPACE)) {
                            measured += g.code() - 0xF0000 - 1024;
                        } else {
                            int unit = Integer.parseInt(g.style().font().value().substring(5));
                            measured += (1 << (g.code() - RgbaDecorations.FIRST)) * unit + 1;
                        }
                    }
                    check(measured == 37, "Rule output has exact width with no accumulating gaps");
                }
            var json = JsonParser.parseString(Files.readString(fontPack.resolve(
                "assets/dialogmenu/font/text_rgba_8.json"))).getAsJsonObject();
            Set<Integer> pool = new HashSet<>();
            for (var provider : json.getAsJsonArray("providers")) {
                var chars = provider.getAsJsonObject().getAsJsonArray("chars");
                if (chars != null) for (var row : chars) row.getAsString().codePoints().forEach(pool::add);
            }
            check(pool.contains((int) 'Z') && pool.contains((int) '9') && pool.size() > 3000,
                "Static obfuscation has actual Latin and CJK replacement glyphs");
            for (int power = 0; power <= 8; power++) {
                var image = ImageIO.read(fontPack.resolve("assets/dialogmenu/textures/generated/rule_" + power + ".png").toFile());
                check(image.getWidth() == 1 << power && image.getHeight() == 1, "Rule textures use reusable powers of two");
                check(image.getRGB(0, 0) == (0xFF000000 | RgbaOffsets.TEXTURE_RGB), "Rule pixels carry the RGBA marker");
            }
            var rules = first.settings().openers();
            check(rules.size() == 1 && rules.getFirst().keys().equals(List.of("SHIFT+F", "SPRINT+Q")),
                "Configured aliases, item and combination keys survive full preparation");
            Files.writeString(data.resolve("config.yml"), config.replace("commands: [handbook, book]", "commands: [guide]"));
            var updated = engine.prepare(ReloadEngine.Mode.YAML).get(60, TimeUnit.SECONDS).snapshot();
            check(updated.pack().equals(first.pack()) && updated.settings().openers().getFirst().commands().equals(List.of("guide")),
                "YAML-only updates entry points without rebuilding the pack");
            Files.writeString(data.resolve("config.yml"), config
                .replace("commands: [handbook, book]", "commands: [guide]")
                .replace("canvas-scales: [0.8, 1, 2]",
                    "canvas-scales: [0.6, 1.25, 2]\ndefault-canvas-scale: 1.25"));
            var custom = engine.prepare().get(60, TimeUnit.SECONDS).snapshot();
            check(tabs(custom, "scale").equals(List.of("0.6", "1.25", "2")),
                "Actual scale Tab follows non-default configured values");
            check(tabs(custom, "textscale").equals(List.of("0.8", "1", "2")),
                "Actual textscale Tab independently follows text configuration");
            Files.writeString(data.resolve("config.yml"), config.replace("menu: styles", "menu: missing"));
            rejected(() -> engine.prepare(ReloadEngine.Mode.YAML).get(60, TimeUnit.SECONDS), "Missing entry target is rejected");
        }
        check(Files.readString(data.resolve("player-text-scales.yml")).equals(preferences),
            "Compilation/reload never rewrites player preferences");
        try (var restored = new ReloadEngine(data, BASE)) {
            var snapshot = restored.restore().get(60, TimeUnit.SECONDS).snapshot();
            check(snapshot.settings().openers().getFirst().commands().equals(List.of("guide")),
                "Failed candidate does not replace persisted working entry points");
            snapshot.fonts().write(data.resolve("restored-export"));
            check(Files.isRegularFile(data.resolve("restored-export/assets/dialogmenu/font/rule_1.json")),
                "Decorations survive compiled snapshot restore");
        }
        for (String bad : List.of("SHIFT+A", "SHIFT+SHIFT+F", "ALT+F", "CTRL+", "F+SHIFT"))
            rejected(() -> MenuOpeners.key(bad), "Unsupported key combination is rejected");
        var tag = TagStringIO.get().asCompound("{id:'minecraft:book',count:1,components:{'minecraft:custom_data':"
            + "{craftengine:{id:'example:book'},menu:{version:1b},untouched:9}}}");
        var extra = TagStringIO.get().asCompound("{menu:{id:'guide'},custom:[I;1,2,3]}");
        var merged = MenuOpeners.merge(MenuOpeners.customData(tag), extra);
        var roundTrip = MenuOpeners.read(MenuOpeners.write(tag.put("components",
            tag.getCompound("components").put("minecraft:custom_data", merged))));
        check(MenuOpeners.customData(roundTrip).getCompound("craftengine").getString("id").equals("example:book"),
            "Custom NBT merge preserves CraftEngine identity");
        check(MenuOpeners.customData(roundTrip).getCompound("menu").getByte("version") == 1,
            "Custom NBT merge preserves existing nested keys");
        check(MenuOpeners.contains(MenuOpeners.customData(roundTrip), extra), "SNBT subset match survives binary round trip");
        check(!MenuOpeners.contains(merged, TagStringIO.get().asCompound("{menu:{version:1}}")),
            "NBT matching preserves numeric tag types");
        var changed = new YamlConfiguration();
        changed.loadFromString("canvas-scales: [0.6, 1.25, 3]\n");
        check(CanvasScale.completions(CanvasScale.options(changed, "canvas-scales", List.of(1d)))
            .equals(List.of("0.6", "1.25", "3")), "Completion is not hard-coded to default values");
        var legacy = new YamlConfiguration();
        check(CanvasScale.options(legacy, "canvas-scales", CanvasScale.OPTIONS).equals(List.of(.75, 1d)),
            "Old configurations retain legacy canvas scale choices");
        check(MenuOpeners.sameText(Component.text("Guide"), Component.text("Guide")), "Item names compare formatted content");
        check(!MenuOpeners.sameText(Component.text("Other"), Component.text("Guide")), "Wrong item name does not match");
        System.out.println("PASS decoration/opener checks=" + checks + " (offline; no Bukkit/client events)");
    }

    @FunctionalInterface interface Operation { void run() throws Exception; }
    private static List<String> tabs(ReloadEngine.Snapshot snapshot, String action) throws Exception {
        // 绕过 JavaPlugin 的服务器加载器构造，仅调用真实的无 Bukkit 分支。
        var field = Class.forName("sun.misc.Unsafe").getDeclaredField("theUnsafe");
        field.setAccessible(true);
        Object unsafe = field.get(null);
        var plugin = (DialogMenuPlugin) unsafe.getClass().getMethod("allocateInstance", Class.class)
            .invoke(unsafe, DialogMenuPlugin.class);
        var loaded = DialogMenuPlugin.class.getDeclaredField("loaded");
        loaded.setAccessible(true);
        loaded.set(plugin, snapshot);
        return plugin.onTabComplete(null, null, "dialogmenu", new String[]{action, ""});
    }

    private static void rejected(Operation action, String message) throws Exception {
        try { action.run(); }
        catch (Exception expected) { checks++; return; }
        throw new AssertionError(message);
    }
    private static void check(boolean value, String message) {
        checks++;
        if (!value) throw new AssertionError(message);
    }
}
