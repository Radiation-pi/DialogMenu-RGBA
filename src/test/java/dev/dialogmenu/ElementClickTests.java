package dev.dialogmenu;

import java.nio.file.*;
import java.util.*;
import net.kyori.adventure.text.event.ClickEvent;
import org.bukkit.configuration.file.YamlConfiguration;
import static dev.dialogmenu.MenuModel.*;

/** 元素自身持有交互，不配置第二套命中区域坐标。 */
final class ElementClickTests {
    private static int checks;
    private static final String SOURCE = """
        schema-version: 1
        id: element-clicks
        canvas: {width: 300, height: 180}
        frontend:
          group:
            type: group
            offset: {x: 10, y: 6}
            children:
              label:
                type: text
                content: 'A中'
                clickable: true
                permission: example.label
                tooltip: Label
                offset: {x: -40}
                actions:
                  single: ['callback: select stable-id']
                  double: ['tell: double']
              image:
                type: image
                source: minecraft:item/book
                width: 16
                height: 16
                offset: {x: 40}
                clickable: true
                actions: ['open: next']
              disabled:
                type: text
                content: Disabled
                offset: {y: -30}
                clickable: false
                tooltip: Still visible
                actions: ['console: must-not-run']
        """;

    static int run(Path temporary) throws Exception {
        checks = 0;
        var yaml = yaml(SOURCE);
        var doc = LayoutStore.parse("element-clicks", yaml);
        check(doc.menu().regions().isEmpty(), "No separate hit areas are created");
        var label = doc.menu().nodes().stream().filter(n -> n.type().equals("text")
            && n.interaction().clickable()).findFirst().orElseThrow();
        check(label.x() == -30 && label.y() == 6, "Interaction moves with the authored group");
        check(label.interaction().permission().equals("example.label"), "Element permission is retained");
        check(label.interaction().actions().equals(List.of("callback: select stable-id"))
            && label.interaction().doubleActions().equals(List.of("tell: double")),
            "Single and double actions retain stable callback identity");

        Context context = new Context() {
            public String expand(String text) { return text; }
            public Icon item(int slot) { return null; }
            public boolean permitted(String permission) { return true; }
        };
        var renderer = new MenuRenderer(new VanillaAssets(temporary.resolve("element-images")));
        var fonts = FontPack.create(List.of(doc), renderer, context, List.of(300, 420), 131072,
            "", List.of(1.0), CanvasScale.OPTIONS);
        for (double scale : CanvasScale.OPTIONS) for (int width : List.of(300, 420)) {
            Context active = CanvasScale.context(context, scale);
            List<Region> bound = new ArrayList<>();
            var output = new SpriteCanvas().encode(doc.menu(), renderer.draw(doc.menu(), active, width, 131072),
                active, fonts, region -> {
                    bound.add(region);
                    return ClickEvent.runCommand("/test/" + region.id());
                });
            check(bound.size() == 2, "Only enabled text and image publish click callbacks");
            check(bound.stream().noneMatch(r -> r.actions().contains("console: must-not-run")),
                "Disabled element cannot bind a business action");
            Set<String> clicked = new HashSet<>();
            boolean disabledHover = false;
            for (var glyph : MenuText.glyphs(output.component())) {
                if (glyph.style().clickEvent() != null) clicked.add(glyph.style().clickEvent().toString());
                if (glyph.style().hoverEvent() != null && glyph.style().clickEvent() == null)
                    disabledHover = true;
            }
            check(clicked.equals(Set.of(ClickEvent.runCommand("/test/frontend.group.children.label").toString(),
                ClickEvent.runCommand("/test/frontend.group.children.image").toString())),
                "Actual components carry only the owning elements' targets at both canvas scales");
            check(disabledHover, "Disabled text may retain its hover information");
        }
        yaml.set("backend.old", Map.of("x", 0, "y", 0, "width", 20, "height", 20, "actions", "close"));
        reject(yaml, "独立点击区域已移除");
        yaml = yaml(SOURCE);
        yaml.set("frontend.group.children.label.clickable", "sometimes");
        reject(yaml, "clickable须为true/false");
        yaml = yaml(SOURCE);
        yaml.set("frontend.group.actions", List.of("close"));
        reject(yaml, "直接点击仅用于text/image");

        Path layouts = temporary.resolve("element-layouts");
        var store = new LayoutStore(layouts);
        Files.writeString(layouts.resolve("element-clicks.yml"), SOURCE);
        boolean missingPageRejected = false;
        try { store.load(); }
        catch (IllegalArgumentException expected) { missingPageRejected = expected.getMessage().contains("跳转布局不存在"); }
        check(missingPageRejected, "Image-owned open actions validate the destination");
        Files.writeString(layouts.resolve("next.yml"), """
            schema-version: 1
            id: next
            canvas: {width: 300, height: 180}
            frontend:
              label: {type: text, content: Next}
            """);
        store.publish(store.load());
        var snapshot = store.get("element-clicks");
        Files.writeString(layouts.resolve("element-clicks.yml"), SOURCE + "backend: {}\n");
        boolean reloadRejected = false;
        try { store.publish(store.load()); }
        catch (IllegalArgumentException expected) { reloadRejected = true; }
        check(reloadRejected && store.get("element-clicks") == snapshot,
            "Removed click-area config cannot replace the last valid layout on reload");
        System.out.println("PASS element-owned clicks checks=" + checks);
        return checks;
    }

    private static YamlConfiguration yaml(String text) throws Exception {
        var yaml = new YamlConfiguration();
        yaml.loadFromString(text);
        return yaml;
    }
    private static void reject(YamlConfiguration yaml, String message) {
        try { LayoutStore.parse("element-clicks", yaml); }
        catch (IllegalArgumentException expected) {
            check(expected.getMessage().contains(message), "Configuration failure explains the supported form");
            return;
        }
        throw new AssertionError("Invalid click configuration was accepted");
    }
    private static void check(boolean value, String message) {
        checks++;
        if (!value) throw new AssertionError(message);
    }
}
