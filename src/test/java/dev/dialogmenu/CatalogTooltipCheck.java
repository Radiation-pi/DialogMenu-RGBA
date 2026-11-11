package dev.dialogmenu;

import java.util.List;
import java.util.Map;
import org.bukkit.configuration.file.YamlConfiguration;

/** 目录说明的兼容行为及输入边界。 */
public final class CatalogTooltipCheck {
    private static String yaml(String tooltip) {
        return """
            schema-version: 1
            id: probe
            state: {editing: 'false'}
            collections:
              choices:
                load: loadChoices
                list: 'list::{uuid}::*'
                status: 'status::{uuid}'
                ready: 'ready::{uuid}'
                write: writeChoices
                token: 'token::{uuid}'
                result: 'result::{uuid}'
                edit-state: editing
            entries:
              default: {label: Name, image: 'minecraft:item/book'}
              custom:
                label: Custom
                image: minecraft:item/book
            """ + tooltip + """
            pages:
              probe:
                canvas: {width: 300, height: 180}
                frontend:
                  title: {type: text, content: Title}
                grids:
                  options:
                    collection: choices
                    slots: 2
                    columns: 2
                    width: 50
                    height: 20
                    x: 0
                    y: 0
                    step-x: 60
                    step-y: 25
                    background: minecraft:item/book
                    icon-size: 8
            """;
    }
    private static MenuTemplate load(String tooltip) throws Exception {
        var config = new YamlConfiguration();
        config.loadFromString(yaml(tooltip));
        return MenuTemplate.load("probe", config).get("probe").template();
    }
    private static void check(boolean ok, String message) { if (!ok) throw new AssertionError(message); }
    public static void main(String[] args) throws Exception {
        var template = load("    tooltip: ['Hello {player}', 'More']\n");
        var menu = template.document(Map.of("choices", List.of("default", "custom"))).menu();
        var defaultNodes = menu.nodes().stream().filter(n -> n.id().startsWith("grid-0-0")).toList();
        var customNodes = menu.nodes().stream().filter(n -> n.id().startsWith("grid-0-1")).toList();
        check(defaultNodes.size() == 3 && defaultNodes.stream().allMatch(n -> n.interaction().tooltip().equals(List.of("Name"))), "old entry fallback");
        check(customNodes.size() == 3 && customNodes.stream().allMatch(n -> n.interaction().tooltip().equals(List.of("Hello {player}", "More"))), "custom tooltip consistency");
        check(load("    tooltip: []\n").definition.entries.get("custom").tooltip().isEmpty(), "explicit empty tooltip");
        check(load("    tooltip: 'Single'\n").definition.entries.get("custom").tooltip().equals(List.of("Single")), "scalar tooltip");
        var context = new MenuModel.Context() {
            public String expand(String text) { return text.replace("{player}", "Alex"); }
            public MenuModel.Icon item(int slot) { return null; }
            public boolean permitted(String permission) { return true; }
        };
        var hover = SpriteCanvas.class.getDeclaredMethod("hover", MenuModel.Region.class, MenuModel.Context.class);
        hover.setAccessible(true);
        var region = customNodes.getFirst().interaction().region(customNodes.getFirst());
        var event = (net.kyori.adventure.text.event.HoverEvent<?>) hover.invoke(null, region, context);
        var plain = net.kyori.adventure.text.serializer.plain.PlainTextComponentSerializer.plainText()
            .serialize((net.kyori.adventure.text.Component) event.value());
        check(plain.equals("Hello Alex\nMore"), "variables and multiple lines in final hover");
        for (String invalid : List.of("    tooltip: 5\n", "    tooltip: [valid, 5]\n", "    tooltip: {invalid: true}\n")) {
            boolean rejected = false;
            try { load(invalid); } catch (IllegalArgumentException expected) { rejected = true; }
            check(rejected, "invalid tooltip must fail: " + invalid);
        }
        System.out.println("CatalogTooltipCheck passed");
    }
}
