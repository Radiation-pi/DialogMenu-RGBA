package dev.dialogmenu;

import java.awt.image.BufferedImage;
import java.lang.management.ManagementFactory;
import java.nio.file.*;
import java.util.*;
import java.util.concurrent.Executors;
import javax.imageio.ImageIO;
import net.kyori.adventure.text.event.ClickEvent;
import net.kyori.adventure.text.serializer.gson.GsonComponentSerializer;
import static dev.dialogmenu.MenuModel.*;

final class PreparedLayoutTests {
    private static int checks;
    private static volatile Object consumed;
    private static final String YAML = """
        schema-version: 3
        id: compiled
        variables:
          value: {type: external, preview: '123', fallback: '0', max-length: 12}
          player: {type: external, preview: 'Alex', fallback: 'Player'}
          visible: {type: external, preview: 'true', fallback: 'true'}
        pages:
          compiled:
            canvas: {width: 452, height: 205, display-width: 452}
            frontend:
              background:
                type: rectangle
                offset: {x: 226, y: 100}
                width: 440
                height: 190
                color: '#303030'
              image:
                type: image
                source: panel.png
                offset: {x: 226, y: 95}
                width: 320
                height: 120
                actions: 'tell: {player}'
                tooltip: ['Player {player}', 'Value {value}']
              fixed:
                type: text
                content: 'Static button'
                offset: {x: 226, y: 60}
                actions: 'tell: fixed'
                tooltip: ['Hello {player}']
              bare:
                type: image
                source: panel.png
                offset: {x: 30, y: 175}
                width: 16
                height: 16
                actions: 'tell: bare'
              hover:
                type: image
                source: panel.png
                offset: {x: 420, y: 175}
                width: 16
                height: 16
                tooltip: ['Hello {player}']
              plain:
                type: text
                content: 'Static title'
                offset: {x: 226, y: 20}
              dynamic:
                type: text
                content: 'Value {value}'
                offset: {x: 226, y: 100}
                line-width: 120
                max-lines: 3
                actions: 'tell: {value}'
              conditional:
                type: text
                content: 'Conditional'
                visible-if: '{visible}'
                offset: {x: 226, y: 160}
        """;

    private static Context context(String player, String value, boolean visible, double scale) {
        return new Context() {
            public String expand(String text) {
                return text.replace("{player}", player).replace("{value}", value)
                    .replace("{visible}", Boolean.toString(visible));
            }
            public Icon item(int slot) { return null; }
            public boolean permitted(String permission) { return permission.isEmpty() || visible; }
            public double textScale() { return scale; }
            public double canvasScale() { return scale; }
        };
    }
    private static synchronized void check(boolean condition, String message) {
        if (!condition) throw new AssertionError(message);
        checks++;
    }
    private static void equal(SpriteCanvas.Output a, SpriteCanvas.Output b, String name) {
        var codec = GsonComponentSerializer.gson();
        check(a.bodyWidth() == b.bodyWidth() && a.rows() == b.rows() && a.sprites() == b.sprites(),
            name + " geometry changed");
        check(codec.serialize(a.component()).equals(codec.serialize(b.component())),
            name + " text, paint order, hit geometry or events changed");
    }

    static int run(Path root) throws Exception {
        Path data = root.resolve("compiled-layout");
        Files.createDirectories(data.resolve("layouts"));
        Files.createDirectories(data.resolve("images"));
        var image = new BufferedImage(16, 16, BufferedImage.TYPE_INT_ARGB);
        for (int y = 0; y < 16; y++) for (int x = 0; x < 16; x++) image.setRGB(x, y, 0xff778899);
        ImageIO.write(image, "PNG", data.resolve("images/panel.png").toFile());
        Files.writeString(data.resolve("layouts/compiled.yml"), YAML);
        Files.writeString(data.resolve("config.yml"),
            "default-layout: compiled\ncanvas-width: 452\nmax-canvas-pixels: 1048576\nresource-build-cpu-percent: 100\n");
        Context preview = context("Alex", "123", true, 1);
        try (var engine = new ReloadEngine(data, preview)) {
            var snapshot = engine.prepare().get().snapshot();
            compare(snapshot, true);
            // Separate workers share immutable fragments but retain their own mutable renderers/canvases.
            try (var workers = Executors.newFixedThreadPool(4)) {
                var work = new ArrayList<java.util.concurrent.Callable<Void>>();
                for (int i = 0; i < 4; i++) work.add(() -> { compare(snapshot, false); return null; });
                for (var result : workers.invokeAll(work)) result.get();
            }
            var before = output(snapshot, preview, "before", true);
            Files.writeString(data.resolve("layouts/compiled.yml"), YAML.replace("Static title", "Static button"));
            var changed = engine.prepare(ReloadEngine.Mode.YAML).get().snapshot();
            compare(changed, false);
            check(!before.component().equals(output(changed, preview, "before", true).component()),
                "YAML reload reused obsolete static title");
            equal(before, output(snapshot, preview, "before", true), "old snapshot after replacement");
            Files.writeString(data.resolve("layouts/compiled.yml"), YAML.replace("type: rectangle", "type: invalid"));
            boolean rejected = false;
            try { engine.prepare(ReloadEngine.Mode.YAML).get(); }
            catch (java.util.concurrent.ExecutionException expected) { rejected = true; }
            check(rejected, "invalid reload accepted");
            equal(output(changed, preview, "stable", false), output(changed, preview, "stable", true),
                "retained snapshot after failed reload");
        }
        try (var engine = new ReloadEngine(data, preview, true)) {
            var restored = engine.restore().get().snapshot();
            compare(restored, false);
        }
        nativeOverlay(root);
        System.out.println("PASS prepared layout checks=" + checks);
        return checks;
    }
    private static SpriteCanvas.Output output(ReloadEngine.Snapshot snapshot, Context context,
                                              String action, boolean compiled) throws Exception {
        var source = snapshot.documents().get("compiled");
        var doc = source.template().document(Map.of(), context::expand);
        var renderer = new MenuRenderer(snapshot.assets());
        var frame = compiled ? snapshot.presentations().frame(renderer, doc.menu(), context, 452, 1_048_576)
            : renderer.drawRuntime(doc.menu(), context, 452, 1_048_576);
        var canvas = new SpriteCanvas();
        java.util.function.Function<Region, ClickEvent> bind =
            region -> action == null ? null : ClickEvent.runCommand("/" + action + " " + region.id());
        return compiled ? canvas.encode(doc.menu(), frame, context, snapshot.fonts(), bind, snapshot.presentations())
            : canvas.encode(doc.menu(), frame, context, snapshot.fonts(), bind);
    }
    private static void compare(ReloadEngine.Snapshot snapshot, boolean timing) throws Exception {
        for (String value : List.of("9", "100000", "123456789012")) for (boolean visible : List.of(true, false))
            for (double scale : List.of(1.0, 1.01)) for (String actor : Arrays.asList("Alex", "Other", null)) {
                var context = context(actor == null ? "NoClick" : actor, value, visible, scale);
                equal(output(snapshot, context, actor, false), output(snapshot, context, actor, true),
                    value + "/" + visible + "/" + scale + "/" + actor);
            }
        if (timing && Boolean.getBoolean("dialogmenu.benchmark.prepared")) benchmark(snapshot);
    }
    private static void nativeOverlay(Path root) throws Exception {
        var doc = LayoutStore.loadText("overlay-compiled", """
            schema-version: 3
            id: overlay-compiled
            canvas: {width: 452, height: 205, display-width: 452}
            frontend:
              fixed:
                type: text
                content: 'Button'
                offset: {x: 226, y: 100}
                tooltip: ['Hello {player}']
                actions: 'tell: fixed'
              dynamic:
                type: text
                content: '{player}'
                offset: {x: 226, y: 20}
            native-controls:
              overlay: true
              body-rows: 7
              inputs:
                name: {type: text, label: 'Name', initial: ''}
              buttons:
                other: {label: Other, permission: other}
                send: {label: Send, actions: 'tell: send'}
            """).get("overlay-compiled");
        var renderer = new MenuRenderer(new VanillaAssets(root.resolve("compiled-native-images")));
        Context first = context("Alex", "1", true, 1);
        var fonts = FontPack.create(List.of(doc), renderer, first, List.of(452), 1_048_576,
            "", List.of(1.0), List.of(1.0), List.of());
        var prepared = new PreparedLayouts(fonts);
        for (boolean allowed : List.of(true, false)) for (String player : List.of("Alex", "Other")) {
            var active = context(player, "1", allowed, 1);
            var frame = renderer.drawRuntime(doc.menu(), active, 452, 1_048_576);
            var expected = new SpriteCanvas().encodeNative(doc, frame, active, fonts,
                region -> ClickEvent.runCommand("/" + player));
            var actual = new SpriteCanvas().encodeNative(doc, frame, active, fonts,
                region -> ClickEvent.runCommand("/" + player), prepared);
            equal(expected.body(), actual.body(), "native body");
            check(expected.foreground().equals(actual.foreground()), "native foreground changed");
            check(expected.layout().equals(actual.layout()), "native permission layout changed");
        }
    }
    private static void benchmark(ReloadEngine.Snapshot snapshot) throws Exception {
        Context active = context("Alex", "123", true, 1);
        var doc = snapshot.documents().get("compiled").template().document(Map.of(), active::expand);
        var renderer = new MenuRenderer(snapshot.assets());
        var raw = new SpriteCanvas();
        var compiled = new SpriteCanvas();
        var cpu = ManagementFactory.getThreadMXBean();
        var allocation = (com.sun.management.ThreadMXBean) cpu;
        long thread = Thread.currentThread().threadId();
        for (int round = 0; round < 6; round++) for (boolean cached : round % 2 == 0
            ? List.of(false, true) : List.of(true, false)) {
            long bytes = allocation.getThreadAllocatedBytes(thread), start = cpu.getCurrentThreadCpuTime();
            long wall = System.nanoTime();
            for (int i = 0; i < 500; i++) {
                var frame = cached ? snapshot.presentations().frame(renderer, doc.menu(), active, 452, 1_048_576)
                    : renderer.drawRuntime(doc.menu(), active, 452, 1_048_576);
                consumed = cached
                    ? compiled.encode(doc.menu(), frame, active, snapshot.fonts(),
                        region -> ClickEvent.runCommand("/benchmark"), snapshot.presentations())
                    : raw.encode(doc.menu(), frame, active, snapshot.fonts(), region -> ClickEvent.runCommand("/benchmark"));
            }
            System.out.printf(Locale.ROOT, "PREPARED_BENCH round=%d compiled=%s frames=500 cpu_ms=%.3f wall_ms=%.3f bytes=%d%n",
                round, cached, (cpu.getCurrentThreadCpuTime() - start) / 1e6, (System.nanoTime() - wall) / 1e6,
                allocation.getThreadAllocatedBytes(thread) - bytes);
        }
    }
    public static void main(String[] args) throws Exception {
        Path root = Files.createTempDirectory(Path.of(args[0]), "prepared-");
        run(root);
    }
}
