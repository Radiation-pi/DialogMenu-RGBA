package dev.dialogmenu;

import java.nio.file.*;
import java.util.*;
import java.util.concurrent.TimeUnit;
import org.bukkit.configuration.file.YamlConfiguration;
import static dev.dialogmenu.MenuModel.*;

/** Actual pack rendering and persisted personal zoom, not a replacement renderer. */
final class CanvasScaleTests {
    private static int checks;
    private static final UUID PLAYER = UUID.fromString("00000000-0000-0000-0000-000000000001");
    private static final UUID OTHER = UUID.fromString("00000000-0000-0000-0000-000000000002");
    private static final Context BASE = new Context() {
        public String expand(String text) { return text; }
        public Icon item(int slot) { return null; }
        public boolean permitted(String permission) { return true; }
    };
    static int run(Path temporary) throws Exception {
        checks = 0;
        Path data = temporary.resolve("canvas-scale");
        Files.createDirectories(data.resolve("layouts"));
        Files.writeString(data.resolve("config.yml"), """
            default-layout: scale
            canvas-width: 420
            text-scales: [1]
            default-text-scale: 1
            """);
        Files.writeString(data.resolve("layouts/scale.yml"), """
            schema-version: 1
            id: scale
            canvas: {width: 420, height: 312}
            frontend:
              label:
                type: text
                content: AAA
                alignment: left
                offset: {x: 184}
            """);
        var preferences = new YamlConfiguration();
        preferences.set("players." + PLAYER, 1.5);
        check(CanvasScale.selected(preferences, PLAYER) == 1, "Existing players retain 1x canvas zoom");
        try (var engine = new ReloadEngine(data, BASE)) {
            var snapshot = engine.prepare().get(30, TimeUnit.SECONDS).snapshot();
            var doc = snapshot.documents().get("scale");
            var renderer = snapshot.renderer();
            var original = renderer.draw(doc.menu(), BASE, 420, 131072);
            check(original.image().getWidth() == 420 && original.image().getHeight() == 312,
                "1x keeps the original dimensions");
            var compact = CanvasScale.context(BASE, 0.75);
            var frame = renderer.draw(doc.menu(), compact, 420, 131072);
            check(frame.image().getWidth() == 315 && frame.image().getHeight() == 234,
                "0.75x scales both dimensions, not only text");
            var node = doc.menu().nodes().getFirst();
            check(SpriteCanvas.textSize(doc.menu(), node, 315, 1, 0.75) == 6, "8px text becomes 6px");
            var proportional = new Menu("proportional", "", 420, 312, doc.menu().nodes(), List.of(), null, true);
            check(SpriteCanvas.textSize(proportional, node, 315, 1, 0.75) == 6,
                "scale-text-with-canvas must not apply canvas zoom twice");
            check(SpriteCanvas.textSize(doc.menu(), node, 315, 1.5, 0.75) == 9,
                "Text zoom and canvas zoom remain independent factors");
            var originalOutput = new SpriteCanvas().encode(doc.menu(), original, BASE, snapshot.fonts(), r -> null);
            boolean rejected = false;
            try {
                var tooTight = renderer.draw(doc.menu(), compact, 300, 131072);
                CanvasScale.select(preferences, PLAYER, "0.75", () ->
                    new SpriteCanvas().encode(doc.menu(), tooTight, compact, snapshot.fonts(), r -> null));
            } catch (IllegalArgumentException error) {
                rejected = error.getMessage().contains("原生文字超出画布");
            }
            check(rejected && CanvasScale.selected(preferences, PLAYER) == 1,
                "A rounded edge label that cannot fit rolls back the player's setting");
            check(new SpriteCanvas().encode(doc.menu(), original, BASE, snapshot.fonts(), r -> null)
                .component().equals(originalOutput.component()), "Failed selection leaves the original view renderable");
            check(Files.isRegularFile(snapshot.pack()), "Failed selection retains the working resource pack");
            for (String invalid : List.of("0", "1.5", "NaN", "Infinity", "bad")) {
                boolean invalidRejected = false;
                try { CanvasScale.select(preferences, PLAYER, invalid, () -> { throw new AssertionError("Must not render"); }); }
                catch (IllegalArgumentException error) { invalidRejected = true; }
                check(invalidRejected && CanvasScale.selected(preferences, PLAYER) == 1,
                    "Invalid zoom leaves preferences untouched: " + invalid);
            }
            CanvasScale.select(preferences, PLAYER, "0.75x", () -> {});
            check(CanvasScale.selected(preferences, OTHER) == 1, "Player zoom is isolated");
            check(preferences.getDouble("players." + PLAYER) == 1.5, "Canvas zoom does not change text zoom");
            engine.savePreferences(preferences.saveToString()).get(30, TimeUnit.SECONDS);
            var restored = new YamlConfiguration();
            restored.load(data.resolve("player-text-scales.yml").toFile());
            check(CanvasScale.selected(restored, PLAYER) == 0.75, "Personal zoom survives a disk round trip");
            check(restored.getDouble("players." + PLAYER) == 1.5, "Existing text preferences survive persistence");
            CanvasScale.select(restored, PLAYER, "1", () -> {});
            check(CanvasScale.selected(restored, PLAYER) == 1, "Player can restore the original canvas size");
        }
        return checks;
    }
    private static void check(boolean condition, String message) {
        checks++;
        if (!condition) throw new AssertionError(message);
    }
}
