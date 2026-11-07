package dev.dialogmenu;

import com.google.gson.JsonParser;
import java.io.IOException;
import java.nio.file.*;
import java.util.*;
import net.kyori.adventure.text.event.ClickEvent;
import org.bukkit.configuration.file.YamlConfiguration;
import static dev.dialogmenu.MenuModel.*;

/** 导出字体引用原版文件，不重复导出图标像素。 */
final class VanillaReferenceTests {
    private static int checks;

    static int run(Path temporary) throws Exception {
        checks = 0;
        var assets = new VanillaAssets(temporary.resolve("vanilla-images"));
        var renderer = new MenuRenderer(assets);
        Context context = new Context() {
            public String expand(String value) { return value; }
            public Icon item(int slot) { return null; }
            public boolean permitted(String permission) { return true; }
        };
        var yaml = new YamlConfiguration();
        yaml.loadFromString("""
            schema-version: 1
            id: vanilla-reference
            canvas: {width: 300, height: 180}
            frontend:
              icon:
                type: image
                source: minecraft:item/book
                width: 16
                height: 16
                offset: {x: -30, y: 22}
                actions: ['tell: selected']
                tooltip: Book
              repeated:
                type: image
                source: minecraft:item/book
                width: 16
                height: 16
                offset: {x: -30, y: 4}
              shield:
                type: image
                source: minecraft:gui/sprites/container/slot/shield
                width: 24
                height: 24
                offset: {x: 30, y: 22}
            """);
        var doc = LayoutStore.parse("vanilla-reference", yaml);
        var fonts = FontPack.create(List.of(doc), renderer, context, List.of(300, 360, 420), 131072);
        Path output = temporary.resolve("vanilla-reference-pack");
        fonts.write(output);
        try (var paths = Files.walk(output)) {
            check(paths.noneMatch(path -> path.toString().endsWith(".png")),
                "Vanilla item/GUI icons must use original texture paths, not copied PNGs");
        }
        var providers = JsonParser.parseString(Files.readString(output.resolve("assets/dialogmenu/font/sprites.json")))
            .getAsJsonObject().getAsJsonArray("providers");
        Set<String> files = new TreeSet<>();
        for (var value : providers) files.add(value.getAsJsonObject().get("file").getAsString());
        check(files.equals(Set.of("minecraft:item/book.png", "minecraft:gui/sprites/container/slot/shield.png")),
            "JSON points to real vanilla texture paths, including the .png suffix");
        for (int width : List.of(300, 360, 420)) {
            var frame = renderer.draw(doc.menu(), context, width, 131072);
            List<Region> actions = new ArrayList<>();
            var encoded = new SpriteCanvas().encode(doc.menu(), frame, context, fonts, region -> {
                actions.add(region);
                return ClickEvent.runCommand("/selected");
            });
            check(actions.size() == 1 && actions.getFirst().x() == -30 && actions.getFirst().y() == 22,
                "Direct vanilla references preserve the semantic click target");
            check(MenuText.glyphs(encoded.component()).stream().anyMatch(glyph ->
                glyph.style().clickEvent() != null && glyph.style().hoverEvent() != null),
                "Vanilla icon glyph keeps click and hover");
            for (Sprite sprite : frame.sprites()) {
                var glyph = fonts.sprite(sprite);
                var provider = providers.asList().stream().map(value -> value.getAsJsonObject()).filter(value ->
                    value.getAsJsonArray("chars").asList().stream().anyMatch(row ->
                        row.getAsString().codePoints().anyMatch(cp -> cp == glyph.code()))).findFirst().orElseThrow();
                check(provider.get("height").getAsInt() == sprite.image().getHeight()
                    && 7 - provider.get("ascent").getAsInt() == sprite.y(),
                    "Vanilla references preserve each configured scale and vertical offset");
                check(provider.get("file").getAsString().equals(sprite.texture()),
                    "The emitted component glyph resolves to its original vanilla texture");
            }
        }
        yaml.set("frontend.under.type", "text");
        yaml.set("frontend.under.content", "Behind");
        yaml.set("frontend.under.offset.z", -10);
        yaml.set("frontend.long-rule.type", "line");
        yaml.set("frontend.long-rule.width", 260);
        yaml.set("frontend.long-rule.thickness", 1);
        yaml.set("frontend.long-rule.offset.y", 60);
        var mixed = LayoutStore.parse("vanilla-reference", yaml);
        var mixedFonts = FontPack.create(List.of(mixed), renderer, context, List.of(300, 360, 420), 131072);
        var mixedFrame = renderer.draw(mixed.menu(), context, 420, 131072);
        var mixedOutput = new SpriteCanvas().encode(mixed.menu(), mixedFrame, context, mixedFonts, region -> null);
        check(!mixedFonts.rows(mixed.menu(), mixedFrame, context, 420),
            "A vanilla icon above text keeps the original mixed-layer paint order");
        check(MenuText.glyphs(mixedOutput.component()).stream().anyMatch(glyph ->
            glyph.code() == mixedFonts.sprite(mixedFrame.sprites().stream()
                .filter(sprite -> sprite.id().endsWith(".icon")).findFirst().orElseThrow()).code()),
            "Wide single-pixel rules and directly referenced icons render together in the absolute path");
        var hiddenYaml = new YamlConfiguration();
        hiddenYaml.loadFromString(yaml.saveToString());
        for (String key : List.of("icon", "repeated", "shield")) hiddenYaml.set("frontend." + key, null);
        var hidden = LayoutStore.parse("vanilla-reference", hiddenYaml);
        var variableFonts = FontPack.create(List.of(mixed, hidden), renderer, context, List.of(300, 360, 420), 131072);
        for (var state : List.of(hidden, mixed, hidden)) {
            var stateFrame = renderer.draw(state.menu(), context, 420, 131072);
            var stateOutput = new SpriteCanvas().encode(state.menu(), stateFrame, context, variableFonts, region -> null);
            check(!variableFonts.rows(state.menu(), stateFrame, context, 420)
                && MenuText.glyphs(stateOutput.component()).stream().anyMatch(glyph -> glyph.code() == 'B'),
                "Showing and hiding referenced icons preserves a valid precompiled menu and paint order");
        }
        yaml.set("frontend.under", null);
        yaml.set("frontend.long-rule", null);
        yaml.set("frontend.icon.width", 28);
        boolean rejected = false;
        try { renderer.draw(LayoutStore.parse("vanilla-reference", yaml).menu(), context, 300, 131072); }
        catch (IOException expected) { rejected = true; }
        check(rejected, "Vanilla references reject nonuniform stretching instead of changing original pixels");
        yaml.set("frontend.icon.width", 16);
        yaml.set("frontend.icon.opacity", 100);
        rejected = false;
        try { renderer.draw(LayoutStore.parse("vanilla-reference", yaml).menu(), context, 300, 131072); }
        catch (IOException expected) { rejected = true; }
        check(rejected, "Unsupported transforms fail instead of silently copying vanilla pixels");
        yaml.set("frontend.icon.opacity", 255);
        yaml.set("frontend.icon.source", "minecraft:preview/chest");
        rejected = false;
        try { renderer.draw(LayoutStore.parse("vanilla-reference", yaml).menu(), context, 300, 131072); }
        catch (IOException expected) { rejected = expected.getMessage().contains("CE"); }
        check(rejected, "Synthetic chest previews must be replaced by existing CE block icons");
        System.out.println("PASS vanilla texture references checks=" + checks);
        return checks;
    }

    private static void check(boolean condition, String message) {
        checks++;
        if (!condition) throw new AssertionError(message);
    }
}
