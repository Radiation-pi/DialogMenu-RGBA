package dev.dialogmenu;

import java.nio.file.*;
import java.util.*;
import net.kyori.adventure.text.event.ClickEvent;
import net.kyori.adventure.text.serializer.gson.GsonComponentSerializer;
import static dev.dialogmenu.MenuModel.*;

/** Compare visible geometry and encoded interactions, not just parsed offsets. */
final class CoordinateTests {
    public static void main(String[] args) throws Exception {
        run(Files.createTempDirectory(Path.of(args[0]), "coordinate-jar-"));
    }
    private static int checks;
    private static final Context CONTEXT = new Context() {
        public String expand(String text) { return text; }
        public Icon item(int slot) { return null; }
        public boolean permitted(String permission) { return true; }
    };
    private static void check(boolean value, String message) {
        if (!value) throw new AssertionError(message);
        checks++;
    }
    private static LayoutStore.Document doc(String text) throws Exception {
        return LayoutStore.loadText("coordinates", text).get("coordinates");
    }
    static int run(Path root) throws Exception {
        checks = 0;
        String old = """
            schema-version: 1
            id: coordinates
            canvas: {width: 300, height: 180}
            close-with-escape: true
            frontend:
              top: {type: text, content: Top, offset: {y: 82}, actions: close, tooltip: Top}
              icon: {type: image, source: 'symbol:■', width: 16, height: 16, offset: {x: -80, y: 64}, actions: 'tell: icon'}
              group:
                type: group
                offset: {x: 10, y: 50}
                scale: 2
                children:
                  label: {type: text, content: Child, offset: {y: -6}, tooltip: Child}
                  frame: {type: frame, width: 12, height: 8, offset: {x: 25, y: -20}}
            """;
        String modern = old.replace("schema-version: 1", "schema-version: 2")
            .replace("y: 82", "y: 8").replace("y: 64", "y: 26").replace("y: 50", "y: 40")
            .replace("y: -6", "y: 6").replace("y: -20", "y: 20");
        var legacy = doc(old);
        var next = doc(modern);
        String left = modern.replace("schema-version: 2", "schema-version: 3")
            .replace("offset: {y: 8}", "offset: {x: 150, y: 8}")
            .replace("x: -80", "x: 70").replace("x: 10,", "x: 160,");
        var topLeft = doc(left);
        check(legacy.menu().equals(next.menu()), "coordinate migration changed geometry/actions");
        check(legacy.menu().equals(topLeft.menu()), "top-left origin changed geometry/actions");
        var renderer = new MenuRenderer(new VanillaAssets(root.resolve("coordinate-images")));
        var fonts = FontPack.create(List.of(legacy), renderer, CONTEXT, List.of(300, 360, 420), 1_048_576,
            "", List.of(1.0), List.of(0.75, 1.0), List.of());
        for (int width : List.of(300, 360, 420)) for (double scale : List.of(0.75, 1.0)) {
            Context context = CanvasScale.context(CONTEXT, scale);
            var a = renderer.draw(legacy.menu(), context, width, 1_048_576);
            var b = renderer.draw(next.menu(), context, width, 1_048_576);
            check(Arrays.equals(a.image().getRGB(0, 0, a.width(), a.height(), null, 0, a.width()),
                b.image().getRGB(0, 0, b.width(), b.height(), null, 0, b.width())), "image moved after conversion");
            var canvas = new SpriteCanvas();
            var bindings = new ArrayList<Region>();
            var first = canvas.encode(legacy.menu(), a, context, fonts, r -> {
                bindings.add(r); return ClickEvent.runCommand("/test " + r.id());
            });
            var second = canvas.encode(next.menu(), b, context, fonts,
                r -> ClickEvent.runCommand("/test " + r.id()));
            check(!bindings.isEmpty() && bindings.stream().anyMatch(r -> r.actions().contains("close")),
                "close action not bound");
            check(GsonComponentSerializer.gson().serialize(first.component())
                .equals(GsonComponentSerializer.gson().serialize(second.component())), "hover/click row encoding moved");
            var c = renderer.draw(topLeft.menu(), context, width, 1_048_576);
            check(Arrays.equals(a.image().getRGB(0, 0, a.width(), a.height(), null, 0, a.width()),
                c.image().getRGB(0, 0, c.width(), c.height(), null, 0, c.width())), "left-origin pixels moved");
            var third = canvas.encode(topLeft.menu(), c, context, fonts,
                r -> ClickEvent.runCommand("/test " + r.id()));
            check(GsonComponentSerializer.gson().serialize(first.component())
                .equals(GsonComponentSerializer.gson().serialize(third.component())), "left-origin interactions moved");
        }
        var label = next.menu().nodes().getFirst();
        check(SpriteCanvas.textTop(next.menu(), label, 300, 0, 8) == 4, "top-center text origin is not at Y=8");
        String family = """
            schema-version: 2
            id: coordinates
            state: {y: '12'}
            variables: {y: {type: state, source: y, preview: '12'}}
            entries: {one: {label: One, image: 'symbol:■'}}
            collections: {items: {external: true}}
            pages:
              coordinates:
                canvas: {width: 300, height: 180}
                frontend:
                  dynamic: {type: text, content: Dynamic, offset: {y: '{y}'}, actions: close}
                grids:
                  items:
                    collection: items
                    items: [one, one]
                    slots: 2
                    columns: 1
                    x: 0
                    y: 40
                    step-x: 40
                    step-y: 30
                    width: 80
                    height: 20
                    background: 'symbol:■'
                    icon-size: 12
                    icon-y: 4
            """;
        var dynamic = doc(family);
        var nodes = dynamic.menu().nodes();
        check(nodes.stream().filter(n -> n.id().endsWith("-base")).map(Node::y).toList().equals(List.of(50.0, 20.0)),
            "version 2 grid rows did not advance downward");
        check(nodes.stream().filter(n -> n.id().endsWith("-icon")).map(Node::y).toList().equals(List.of(46.0, 16.0)),
            "version 2 grid icon Y not converted");
        var changed = dynamic.template().document(Map.of(), text -> text.replace("{y}", "25"));
        check(changed.menu().nodes().stream().filter(n -> n.id().equals("frontend.dynamic")).findFirst()
            .orElseThrow().y() == 65, "dynamic coordinate not converted after expansion");
        var leftFamily = doc(family.replace("schema-version: 2", "schema-version: 3")
            .replace("offset: {y: '{y}'}", "offset: {x: '{y}', y: '{y}'}")
            .replace("x: 0", "x: 150"));
        check(leftFamily.menu().nodes().stream().filter(n -> n.id().endsWith("-base")).map(Node::y).toList()
            .equals(List.of(50.0, 20.0)), "left-origin grid must advance downward");
        check(leftFamily.menu().nodes().stream().filter(n -> n.id().endsWith("-base")).allMatch(n -> n.x() == 0),
            "left-origin grid converted X twice or not at all");
        var moved = leftFamily.template().document(Map.of(), text -> text.replace("{y}", "25"));
        var position = moved.menu().nodes().stream().filter(n -> n.id().equals("frontend.dynamic")).findFirst().orElseThrow();
        check(position.x() == -125 && position.y() == 65, "dynamic XY must convert after expansion");
        var model = doc(modern.replace("type: image, source: 'symbol:■', width: 16, height: 16",
            "type: model, source: minecraft:stone, size: 16"));
        var oldModel = doc(old.replace("type: image, source: 'symbol:■', width: 16, height: 16",
            "type: model, source: minecraft:stone, size: 16"));
        var pack = ModelPack.prepare(root.resolve("coordinate-models"), List.of(model));
        var modernNode = model.menu().nodes().stream().filter(n -> n.type().equals("model")).findFirst().orElseThrow();
        var oldNode = oldModel.menu().nodes().stream().filter(n -> n.type().equals("model")).findFirst().orElseThrow();
        check(pack.area(model.menu(), modernNode, 300, 180).equals(pack.area(oldModel.menu(), oldNode, 300, 180)),
            "model hit projection moved");
        var leftModel = doc(left.replace("type: image, source: 'symbol:■', width: 16, height: 16",
            "type: model, source: minecraft:stone, size: 16"));
        check(leftModel.menu().equals(oldModel.menu()), "left-origin model projection changed");
        ruler(root, renderer);
        System.out.println("PASS versioned coordinates=" + checks);
        return checks;
    }
    private static void ruler(Path root, MenuRenderer renderer) throws Exception {
        String text = Files.readString(Path.of("examples/canvas-limit.yml"));
        var ruler = LayoutStore.loadText("canvas-limit", text).get("canvas-limit");
        var menu = ruler.menu();
        check(menu.width() == 1014 && menu.height() == 1024 && Boolean.TRUE.equals(menu.closeWithEscape()),
            "maximum ruler canvas/escape changed");
        var compressed = LayoutStore.loadText("canvas-limit", text.replace(", display-width: 1014", ""))
            .get("canvas-limit");
        check(renderer.drawRuntime(compressed.menu(), CONTEXT, 300, 1_048_576).width() == 300,
            "layouts without explicit display width must retain their existing size");
        check(menu.nodes().stream().filter(n -> n.id().startsWith("frontend.x-")).allMatch(n -> n.y() == 500),
            "horizontal numbers are not one row");
        check(menu.nodes().stream().filter(n -> n.id().startsWith("frontend.y-")).allMatch(n -> n.x() == -497),
            "vertical numbers are not one column");
        for (String axis : List.of("x", "y")) {
            var ticks = menu.nodes().stream().filter(n -> n.id().startsWith("frontend.tick-" + axis + "-")).toList();
            check(ticks.size() == (axis.equals("x") ? 507 : 512), "ruler must have one tick every 2 units");
            for (int i = 0; i < ticks.size(); i++) {
                var tick = ticks.get(i);
                double at = axis.equals("x") ? tick.x() + 507 : 512 - tick.y();
                check(at == i * 2 + .5, "ruler tick spacing/origin changed");
            }
            var numbers = menu.nodes().stream().filter(n -> n.id().startsWith("frontend." + axis + "-")).toList();
            check(numbers.size() == 51, "ruler needs 20-unit labels");
            for (int i = 0; i < numbers.size(); i++)
                check(numbers.get(i).text().equals(Integer.toString((axis.equals("x") ? i : i + 1) * 20)),
                    "ruler label distance changed");
        }
        var fonts = FontPack.create(List.of(ruler), renderer, CONTEXT, List.of(300, 360, 420), 1_048_576,
            "", List.of(1.0), List.of(0.75, 1.0), List.of());
        for (int width : List.of(300, 360, 420, 452, 1014)) for (double scale : List.of(0.75, 1.0)) {
            var context = CanvasScale.context(CONTEXT, scale);
            var frame = renderer.draw(menu, context, width, 1_048_576);
            if (scale == 1) {
                check(frame.width() == 1014 && frame.height() == 1024,
                    "ruler must not squeeze 1014 logical pixels into the configured open width");
                for (Sprite tick : frame.sprites()) {
                    if (tick.id().startsWith("frontend.tick-x-"))
                        check(tick.x() == Integer.parseInt(tick.id().substring("frontend.tick-x-".length()))
                            && tick.width() == 1, "X ruler tick is not one GUI pixel at its labeled coordinate");
                    if (tick.id().startsWith("frontend.tick-y-"))
                        check(tick.y() == Integer.parseInt(tick.id().substring("frontend.tick-y-".length()))
                            && tick.height() == 1, "Y ruler tick is not one GUI pixel at its labeled coordinate");
                }
            }
            var bound = new ArrayList<Region>();
            var encoded = new SpriteCanvas().encode(menu, frame, context, fonts, r -> {
                bound.add(r); return ClickEvent.runCommand("/test close");
            });
            check(encoded.rows() > 0 && bound.stream().anyMatch(r -> r.actions().contains("close")),
                "ruler did not encode/close at width " + width);
            check(GsonComponentSerializer.gson().serialize(encoded.component()).contains("X=20; Y=0"),
                "ruler coordinate tooltip missing from encoded bindings");
            if (width == 1014 && scale == 1.0)
                javax.imageio.ImageIO.write(frame.image(), "PNG", root.resolve("ruler-render.png").toFile());
        }
        boolean rejected = false;
        try { renderer.draw(menu, CONTEXT, 1014, 1_038_335); }
        catch (IllegalArgumentException expected) { rejected = true; }
        check(rejected, "maximum canvas bypassed pixel budget");
    }
}
