package dev.dialogmenu;

import com.google.gson.*;
import java.awt.image.BufferedImage;
import java.io.IOException;
import java.nio.file.*;
import java.util.*;
import javax.imageio.ImageIO;
import net.kyori.adventure.key.Key;
import net.kyori.adventure.text.event.ClickEvent;
import org.bukkit.configuration.file.YamlConfiguration;
import static dev.dialogmenu.MenuModel.*;

/** 无需服务器，验证真实 CE 源映射和导出的字体 provider 网格。 */
final class AtlasReferenceTests {
    private static int checks;

    static int run(Path temporary) throws Exception {
        checks = 0;
        Path resources = temporary.resolve("ce-resources");
        Path pack = resources.resolve("sample");
        Files.createDirectories(pack.resolve("configuration"));
        Path texture = pack.resolve("resourcepack/assets/example/textures/icons.png");
        Files.createDirectories(texture.getParent());
        BufferedImage atlas = new BufferedImage(48, 32, BufferedImage.TYPE_INT_ARGB);
        for (int y = 0; y < 32; y++) for (int x = 0; x < 48; x++)
            if (x % 16 < 12) atlas.setRGB(x, y, 0xff000000 | ((y / 16 * 3 + x / 16 + 1) * 0x203040));
        ImageIO.write(atlas, "png", texture.toFile());
        Files.writeString(pack.resolve("configuration/images.yml"), """
            images:
              example:selected:
                ref: example:atlas:1:2
              example:first:
                ref: example:atlas
                row: 0
                col: 0
              example:row:
                ref: example:atlas:1
              example:bad:
                ref: example:atlas:2:0
              example:atlas:
                file: example:icons.png
                chars: ['\\ue001\\ue002\\ue003', 'XYZ']
            """);
        var assets = new VanillaAssets(temporary.resolve("atlas-images"), resources);
        String selected = "craftengine:image/example:selected";
        var image = assets.image(selected);
        check(image.getWidth() == 16 && image.getHeight() == 16, "A mapped image resolves only its atlas cell");
        check(image.getRGB(0, 0) == atlas.getRGB(32, 16) && image.getRGB(15, 0) == 0,
            "Cell coordinates and alpha are preserved, not the whole atlas");
        check(assets.image("craftengine:image/example:first").getRGB(0, 0) == atlas.getRGB(0, 0),
            "Separate CE row/col mapping and forward references resolve");
        check(assets.image("craftengine:image/example:row").getRGB(0, 0) == atlas.getRGB(0, 16),
            "A row-only CE reference defaults to column zero");
        check(assets.sources().contains(selected), "Mapped cells are available in the asset catalog");
        check(assets.craftEngine.dependencies().contains(texture.toAbsolutePath()), "The original atlas is a reload dependency");
        boolean rejected = false;
        try { assets.image("craftengine:image/example:bad"); }
        catch (IOException expected) { rejected = true; }
        check(rejected, "An out-of-range CE mapping must fail rather than draw another icon");

        var yaml = new YamlConfiguration();
        yaml.loadFromString("""
            schema-version: 1
            id: atlas-test
            canvas: {width: 300, height: 180}
            frontend:
              selected:
                type: image
                source: craftengine:image/example:selected
                width: 16
                height: 16
                offset: {x: -30, y: 22}
                actions: ['tell: selected']
                tooltip: Selected
              repeated:
                type: image
                source: craftengine:image/example:selected
                width: 16
                height: 16
                offset: {x: -30, y: 4}
              first:
                type: image
                source: craftengine:image/example:first
                width: 16
                height: 16
                offset: {x: 30, y: 22}
            """);
        var doc = LayoutStore.parse("atlas-test", yaml);
        Context context = new Context() {
            public String expand(String value) { return value; }
            public Icon item(int slot) { return null; }
            public boolean permitted(String permission) { return true; }
        };
        var renderer = new MenuRenderer(assets);
        var fonts = FontPack.create(List.of(doc), renderer, context, List.of(300), 131072);
        Path output = temporary.resolve("atlas-reference-pack");
        fonts.write(output);
        try (var paths = Files.walk(output)) {
            check(paths.noneMatch(path -> path.toString().endsWith(".png")), "No atlas or cell PNG is copied into the Dialog pack");
        }
        var frame = renderer.draw(doc.menu(), context, 300, 131072);
        List<Region> actions = new ArrayList<>();
        var encoded = new SpriteCanvas().encode(doc.menu(), frame, context, fonts, region -> {
            actions.add(region);
            return ClickEvent.runCommand("/selected");
        });
        check(actions.size() == 1 && actions.getFirst().x() == -30 && actions.getFirst().y() == 22,
            "Atlas reuse retains the original click target and coordinates");
        check(MenuText.glyphs(encoded.component()).stream().anyMatch(glyph ->
            glyph.style().clickEvent() != null && glyph.style().hoverEvent() != null),
            "Atlas glyph retains click and hover events");
        var providers = JsonParser.parseString(Files.readString(output.resolve("assets/dialogmenu/font/sprites.json")))
            .getAsJsonObject().getAsJsonArray("providers");
        for (Sprite sprite : frame.sprites()) {
            var glyph = fonts.sprite(sprite);
            JsonObject provider = null;
            for (var value : providers) for (var row : value.getAsJsonObject().getAsJsonArray("chars"))
                if (row.getAsString().codePoints().anyMatch(cp -> cp == glyph.code())) provider = value.getAsJsonObject();
            check(provider != null && provider.get("file").getAsString().equals("example:icons.png"),
                "Provider references the existing CE atlas, not a generated copy");
            var rows = provider.getAsJsonArray("chars");
            check(rows.size() == 2 && rows.get(0).getAsString().codePointCount(0, rows.get(0).getAsString().length()) == 3,
                "Provider retains the complete atlas grid dimensions");
            int row = sprite.x() > 150 ? 0 : 1, column = sprite.x() > 150 ? 0 : 2;
            check(rows.get(row).getAsString().codePoints().toArray()[column] == glyph.code(),
                "Only the mapped atlas cell receives the glyph");
            check(provider.get("height").getAsInt() == 16
                && 7 - provider.get("ascent").getAsInt() == sprite.y() && glyph.advance() == 13,
                "Direct atlas reference preserves display size, top and real alpha advance");
        }
        yaml.set("frontend.selected.width", 14);
        yaml.set("frontend.selected.height", 14);
        yaml.set("frontend.selected.offset.x", -153);
        yaml.set("canvas.width", 360);
        var rounded = LayoutStore.parse("atlas-test", yaml);
        var roundedFrame = renderer.draw(rounded.menu(), context, 300, 131072);
        check(roundedFrame.sprites().getFirst().texture() != null,
            "Equal logical dimensions still reuse the atlas when rounded bounds differ by a pixel");
        yaml.set("frontend.selected.width", 28);
        rejected = false;
        try { renderer.draw(LayoutStore.parse("atlas-test", yaml).menu(), context, 300, 131072); }
        catch (IOException expected) { rejected = true; }
        check(rejected, "A genuinely stretched atlas cell remains unsupported");
        yaml.set("frontend.selected.width", 14);
        yaml.set("frontend.selected.opacity", 100);
        rejected = false;
        try { renderer.draw(LayoutStore.parse("atlas-test", yaml).menu(), context, 300, 131072); }
        catch (IOException expected) { rejected = true; }
        check(rejected, "An unsupported atlas transform fails instead of silently duplicating the texture");
        yaml.set("frontend.selected.opacity", 255);
        yaml.set("frontend.label.type", "text");
        yaml.set("frontend.label.content", "Label");
        yaml.set("frontend.label.offset.z", 10);
        var layered = LayoutStore.parse("atlas-test", yaml);
        var layeredFrame = renderer.draw(layered.menu(), context, 300, 131072);
        var layeredFonts = FontPack.create(List.of(layered), renderer, context, List.of(300), 131072);
        var layeredOutput = new SpriteCanvas().encode(layered.menu(), layeredFrame, context, layeredFonts, region -> null);
        check(MenuText.glyphs(layeredOutput.component()).stream().anyMatch(glyph ->
            glyph.style().font() != null && glyph.style().font().value().startsWith("text_shift_")),
            "An external atlas below text must not expand row text into absolute-position fonts");
        yaml.set("frontend.selected.offset.z", 20);
        var overlap = LayoutStore.parse("atlas-test", yaml);
        check(!CanvasRows.enabled(overlap.menu(), renderer.draw(overlap.menu(), context, 300, 131072),
            context, 300, List.of(1.0)), "A graphic above text retains the paint-order compatibility path");
        System.out.println("PASS CE atlas references checks=" + checks);
        return checks;
    }

    private static void check(boolean condition, String message) {
        checks++;
        if (!condition) throw new AssertionError(message);
    }
}
