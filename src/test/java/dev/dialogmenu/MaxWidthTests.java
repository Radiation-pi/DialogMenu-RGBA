package dev.dialogmenu;

import java.nio.file.*;
import java.util.List;
import net.kyori.adventure.text.event.ClickEvent;
import net.kyori.adventure.text.serializer.gson.GsonComponentSerializer;
import static dev.dialogmenu.MenuModel.*;

public final class MaxWidthTests {
    private static final Context BASE = new Context() {
        public String expand(String text) { return text; }
        public Icon item(int slot) { return null; }
        public boolean permitted(String permission) { return true; }
    };
    private static int checks;
    public static void main(String[] args) throws Exception { run(Path.of(args[0])); }
    static void run(Path root) throws Exception {
        Path data = Files.createTempDirectory(root, "max-width-");
        Files.createDirectories(data.resolve("layouts"));
        Files.writeString(data.resolve("config.yml"), """
            default-layout: probe
            max-canvas-pixels: 1048576
            resource-build-cpu-percent: 100
            """);
        Files.writeString(data.resolve("layouts/probe.yml"), """
            schema-version: 1
            id: probe
            canvas: {width: 1014, height: 1024}
            close-with-escape: true
            frontend:
              panel: {type: rectangle, width: 1014, height: 1024}
              origin:
                type: text
                content: '(0,0)'
                tooltip: X=0 Y=0
              close:
                type: text
                content: X
                offset: {x: 480, y: 464}
                tooltip: Close
                actions: close
            """);
        Files.writeString(data.resolve("layouts/tall.yml"), """
            schema-version: 1
            id: tall
            canvas: {width: 300, height: 400}
            frontend:
              text: {type: text, content: A}
            """);
        try (var engine = new ReloadEngine(data, BASE)) {
            var snapshot = engine.prepare().get().snapshot();
            verify(snapshot);
            var tall = snapshot.documents().get("tall").menu();
            try {
                snapshot.renderer().drawRuntime(tall, BASE, 1014, snapshot.settings().maxPixels());
                throw new AssertionError("Oversized height silently accepted");
            } catch (IllegalArgumentException expected) { checks++; }
            try {
                snapshot.renderer().drawRuntime(snapshot.documents().get("probe").menu(), BASE, 1015,
                    snapshot.settings().maxPixels());
                throw new AssertionError("Width beyond protocol maximum accepted");
            } catch (IllegalArgumentException expected) { checks++; }
        }
        try (var engine = new ReloadEngine(data, BASE)) { verify(engine.restore().get().snapshot()); }
        var field = Class.forName("sun.misc.Unsafe").getDeclaredField("theUnsafe");
        field.setAccessible(true);
        Object unsafe = field.get(null);
        var plugin = (DialogMenuPlugin) unsafe.getClass().getMethod("allocateInstance", Class.class)
            .invoke(unsafe, DialogMenuPlugin.class);
        check(plugin.onTabComplete(null, null, "dialogmenu", new String[]{"open", "probe", ""})
            .equals(List.of("small", "medium", "large", "max")), "Maximum size missing from command completion");
        System.out.println("PASS maximum-width checks=" + checks + " (offline; no client)");
    }
    private static void verify(ReloadEngine.Snapshot snapshot) throws Exception {
        var menu = snapshot.documents().get("probe").menu();
        for (double scale : snapshot.settings().canvasScales()) {
            Context active = CanvasScale.context(BASE, scale);
            var frame = snapshot.renderer().drawRuntime(menu, active, 1014, snapshot.settings().maxPixels());
            check(frame.width() == Math.round(1014 * scale), "Maximum width was capped to an old preset");
            check(frame.height() == Math.round(1024 * frame.width() / 1014.0), "Maximum height changed");
            var encoded = new SpriteCanvas().encode(menu, frame, active, snapshot.fonts(),
                region -> ClickEvent.runCommand("/probe " + region.actions()));
            String json = GsonComponentSerializer.gson().serialize(encoded.component());
            check(json.contains("X=0 Y=0") && json.contains("/probe [close]"),
                "Maximum-size encoding lost hover or close action");
        }
    }
    private static void check(boolean value, String message) {
        if (!value) throw new AssertionError(message);
        checks++;
    }
}
