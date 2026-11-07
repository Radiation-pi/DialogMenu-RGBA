package dev.dialogmenu;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.bukkit.configuration.file.YamlConfiguration;
import static dev.dialogmenu.MenuModel.*;

/** 用真实插件 JAR 运行公开 Wiki 中的示例。 */
public final class WikiExampleChecks {
    public static void main(String[] args) throws Exception {
        Path directory = Path.of(args[0]);
        Map<String, LayoutStore.Document> docs = new LayoutStore(directory).load();
        Set<String> expected = Set.of("hello", "text-demo", "image-demo", "gesture",
            "wiki-home", "wiki-page-one", "wiki-page-two", "wiki-catalog-list");
        if (!docs.keySet().equals(expected)) throw new AssertionError("Page IDs: " + docs.keySet());
        VanillaAssets assets = new VanillaAssets(directory.resolve("images"));
        MenuRenderer renderer = new MenuRenderer(assets);
        Context context = new Context() {
            public String expand(String text) {
                return text.replace("{player}", "WikiPlayer").replace("{uuid}", "preview");
            }
            public Icon item(int slot) { return null; }
            public boolean permitted(String permission) { return true; }
        };
        var catalog = MenuTemplate.catalog(docs.values());
        var fonts = FontPack.create(catalog, renderer, context, List.of(300, 360, 420),
            131072, MenuTemplate.characters(docs.values()), List.of(1.0, 1.5, 2.0), List.of(0.75, 1.0));
        int rendered = 0, callbacks = 0;
        for (var doc : catalog) for (double canvasScale : List.of(0.75, 1.0))
        for (double textScale : List.of(1.0, 1.5, 2.0)) for (int width : List.of(300, 360, 420)) {
            Context preview = MenuTemplate.preview(doc, context);
            Context active = new Context() {
                public String expand(String text) { return preview.expand(text); }
                public Icon item(int slot) { return null; }
                public boolean permitted(String permission) { return true; }
                public double canvasScale() { return canvasScale; }
                public double textScale() { return textScale; }
            };
            var frame = renderer.draw(doc.menu(), active, width, 131072);
            int[] called = {0};
            new SpriteCanvas().encode(doc.menu(), frame, active, fonts, region -> {
                called[0]++;
                return net.kyori.adventure.text.event.ClickEvent.runCommand("/wiki-check");
            });
            if (called[0] == 0) throw new AssertionError("No clicks: " + doc.menu().id());
            callbacks += called[0];
            rendered++;
        }
        fonts.write(directory.resolve("generated-pack"));
        if (!Files.isRegularFile(directory.resolve("generated-pack/assets/minecraft/shaders/core/text.vsh")))
            throw new AssertionError("Missing RGBA shader export");
        var template = docs.get("wiki-home").template();
        if (!template.definition.preview("{mode}", Map.of("editing", "true"), "WikiPlayer").equals("编辑"))
            throw new AssertionError("State mapping");
        var hidden = template.document(Map.of(), text -> template.definition.preview(text, Map.of("editing", "false"), "WikiPlayer"));
        var shown = template.document(Map.of(), text -> template.definition.preview(text, Map.of("editing", "true"), "WikiPlayer"));
        if (shown.menu().nodes().size() != hidden.menu().nodes().size() + 1)
            throw new AssertionError("visible-if does not toggle the hint");
        var binding = docs.get("wiki-catalog-list").template();
        var rows = binding.document(Map.of(), Map.of("entries", List.of(
            new DialogMenuEntry("sample:one", "示例一", "", Map.of("size", "10x20")))), text -> text);
        if (rows.flowRows().stream().noneMatch(row -> row.id().equals("sample:one") && row.text().equals("示例一 · 10x20")))
            throw new AssertionError("External collection row");
        if (!binding.definition.forms.get("create").fields().getFirst().key().equals("name"))
            throw new AssertionError("Form schema");
        int rejected = 0;
        for (String invalid : List.of(
            "schema-version: 1\nid: invalid\ncanvas: {width: 300, height: 180}\nbackend: {}\nfrontend: {title: {type: text, content: x}}",
            "schema-version: 1\nid: invalid\ncanvas: {width: 300, height: 180}\ncharacters: 字\nfrontend: {title: {type: text, content: x}}",
            "schema-version: 1\nid: invalid\ncanvas: {width: 300, height: 180}\nfrontend: {title: {type: rectangle, actions: close}}")) {
            YamlConfiguration yaml = new YamlConfiguration();
            yaml.loadFromString(invalid);
            try { LayoutStore.parse("invalid", yaml); }
            catch (IllegalArgumentException expectedError) { rejected++; }
        }
        if (rejected != 3) throw new AssertionError("Documented invalid structures not rejected");
        System.out.println("PASS: 7 complete YAML files / 8 pages; " + rendered + " renders; "
            + callbacks + " click bindings; state/visibility/collection/form; 3 invalid examples rejected; failed=0");
    }
}
