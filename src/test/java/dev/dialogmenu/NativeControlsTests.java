package dev.dialogmenu;

import java.nio.file.Path;
import java.nio.file.Files;
import java.util.*;
import net.kyori.adventure.text.event.ClickEvent;
import net.kyori.adventure.text.serializer.gson.GsonComponentSerializer;
import static dev.dialogmenu.MenuModel.*;

final class NativeControlsTests {
    private static int checks;
    private static final String BASE = """
        schema-version: 3
        id: native-test
        canvas: {width: 452, height: 205, display-width: 452}
        frontend:
          top: {type: text, content: Top, offset: {x: 80, y: 20}, actions: 'tell: top'}
          bottom: {type: text, content: Bottom, offset: {x: 330, y: 180}, actions: 'tell: bottom'}
        """;
    private static final String CONTROLS = """
        native-controls:
          inputs:
            name: {type: text, label: Name, initial: Original}
            lines: {type: text, multiline: true, height: 40, max-lines: 4, initial: 'line'}
            amount: {type: slider, min: -10, max: 100, initial: 25, step: 0.5}
          buttons:
            send: {label: Send, close: false, actions: 'tell: {input.name}'}
            done: {label: Done, close: true}
        """;
    private static void check(boolean value, String message) {
        if (!value) throw new AssertionError(message);
        checks++;
    }
    private static LayoutStore.Document parse(String text) throws Exception {
        return LayoutStore.loadText("native-test", text).get("native-test");
    }
    private static void reject(String text) throws Exception {
        try { parse(text); }
        catch (IllegalArgumentException expected) { checks++; return; }
        throw new AssertionError("invalid native controls accepted: " + text);
    }
    static int run(Path root) throws Exception {
        checks = 0;
        var plain = parse(BASE);
        var nativeDoc = parse(BASE + CONTROLS);
        check(nativeDoc.controls().inputs().size() == 3 && nativeDoc.controls().buttons().size() == 2,
            "native controls missing after source load");
        check(nativeDoc.controls().inputs().get(1).multiline() && nativeDoc.controls().inputs().get(1).height() == 40,
            "multiline options lost");
        check(!nativeDoc.controls().buttons().getFirst().close() && nativeDoc.controls().buttons().getLast().close(),
            "close setting lost");
        check(plain.menu().equals(nativeDoc.menu()), "native controls changed canvas dimensions/nodes/hits");
        check(parse(BASE + CONTROLS.replace("type: text", "type: text, z: -1000000")
            .replace("type: slider", "type: slider, z: 1000000")).controls().equals(nativeDoc.controls()),
            "legacy input z still affects native controls");
        String family = "schema-version: 3\nid: native-test\npages:\n  native-test:\n"
            + (BASE.substring(BASE.indexOf("canvas:")) + CONTROLS).indent(4);
        var template = parse(family);
        check(template.template().document(Map.of()).controls().equals(nativeDoc.controls()),
            "family refresh dropped native controls");
        LayoutStore.validateLinks(Map.of("native-test", nativeDoc));
        reject(BASE + CONTROLS.replace("type: text, label: Name", "type: text, x: 12, label: Name"));
        reject(BASE + CONTROLS.replace("min: -10, max: 100", "min: 100, max: 100"));
        reject(BASE + CONTROLS.replace("step: 0.5", "step: 0"));
        reject(BASE + CONTROLS.replace("max: 100", "max: 1.0e300"));
        reject(BASE + CONTROLS.replace("close: false", "close: 'false'"));
        reject(BASE + "native-controls:\n  inputs:\n    name: {type: text}\n");
        reject(BASE + "native-controls: []\n");
        reject(BASE + CONTROLS.replace("type: text, label: Name", "type: text, max-length: 0, label: Name"));
        reject(BASE + CONTROLS.replace("type: text, label: Name", "type: text, height: 40, label: Name"));
        try {
            LayoutStore.validateLinks(Map.of("native-test", parse(BASE + CONTROLS.replace("tell: {input.name}", "open: missing"))));
            throw new AssertionError("native open target not validated");
        } catch (IllegalArgumentException expected) { checks++; }
        check(NativeControls.expand("{input.name} {input.amount}", Map.of("name", "{input.amount}$\\", "amount", "25"))
            .equals("{input.amount}$\\ 25"), "input text recursively expanded or replacement chars corrupted");
        var context = new Context() {
            public String expand(String text) { return text; }
            public Icon item(int slot) { return null; }
            public boolean permitted(String permission) { return true; }
        };
        var renderer = new MenuRenderer(new VanillaAssets(root.resolve("native-images")));
        var fonts = FontPack.create(List.of(plain), renderer, context, List.of(452), 1_048_576,
            "", List.of(1.0), List.of(1.0), List.of());
        var bindings = new ArrayList<Region>();
        var canvas = new SpriteCanvas();
        var a = canvas.encode(plain.menu(), renderer.drawRuntime(plain.menu(), context, 452, 1_048_576),
            context, fonts, r -> ClickEvent.runCommand("/test " + r.id()));
        var b = canvas.encode(nativeDoc.menu(), renderer.drawRuntime(nativeDoc.menu(), context, 452, 1_048_576),
            context, fonts, r -> { bindings.add(r); return ClickEvent.runCommand("/test " + r.id()); });
        check(a.rows() == b.rows() && a.bodyWidth() == b.bodyWidth(), "native page compressed body rows");
        check(GsonComponentSerializer.gson().serialize(a.component()).equals(GsonComponentSerializer.gson().serialize(b.component())),
            "native page changed canvas pixels or encoded interactions");
        check(bindings.stream().anyMatch(r -> r.actions().contains("tell: bottom"))
            && bindings.stream().anyMatch(r -> r.actions().contains("tell: top")), "top/bottom canvas actions lost");
        var examples = LayoutStore.loadText("native-overlay",
            Files.readString(Path.of("examples/native-overlay.yml")));
        LayoutStore.validateLinks(examples);
        var overlayFonts = FontPack.create(examples.values(), renderer, context, List.of(452), 1_048_576,
            "", List.of(1.0), List.of(1.0), List.of());
        var fixtures = new ArrayList<Map<String, Object>>();
        for (var entry : examples.entrySet()) {
            var doc = entry.getValue();
            var frame = renderer.drawRuntime(doc.menu(), context, 452, 1_048_576);
            var calls = new ArrayList<Region>();
            var output = canvas.encodeNative(doc, frame, context, overlayFonts,
                region -> { calls.add(region); return ClickEvent.runCommand("/test " + region.id()); });
            check(output.body().rows() == 7 && output.body().bodyWidth() == 462,
                "overlay changed actual canvas width or native body rows");
            check(calls.size() == 1 && calls.getFirst().actions().getFirst().startsWith("open: native-"),
                "top navigation lost or native bar acquired Menu click: " + calls);
            check(output.layout().controls().getFirst().y() + output.layout().top() == 77,
                "first native control geometry differs from actual 26.3 layout");
            var serializer = GsonComponentSerializer.gson();
            fixtures.add(Map.of("id", entry.getKey(), "body", serializer.serializeToTree(output.body().component()),
                "foreground", serializer.serializeToTree(output.foreground()),
                "carrier", output.layout().carrier(), "bodyRows", output.layout().bodyRows()));
            SpriteCanvas.nativeLabel("Send 中文", overlayFonts, frame, output);
            checks++;
        }
        Path evidence = root.resolve("native-overlay");
        Files.createDirectories(evidence);
        overlayFonts.write(evidence.resolve("pack"));
        Files.writeString(evidence.resolve("fixtures.json"), new com.google.gson.Gson().toJson(fixtures));
        var scaledFixtures = new ArrayList<Map<String, Object>>();
        for (double scale : List.of(.8, .93, 1.0, 1.37, 2.2)) for (var doc : examples.values()) {
            var active = CanvasScale.context(context, scale);
            var frame = renderer.drawRuntime(doc.menu(), active, 452, 1_048_576);
            var output = canvas.encodeNative(doc, frame, active, overlayFonts,
                region -> ClickEvent.runCommand("/test " + region.id()));
            check(output.layout().controls().stream().map(NativeInk.Control::width).allMatch(w -> w == 280),
                "personal scale changed native widths");
            check(output.layout().controls().stream().map(NativeInk.Control::height).toList()
                .equals(List.of(20, 40, 20, 20)), "personal scale changed native heights");
            check(output.layout().top() + 144 >= frame.height(), "scaled canvas exceeds scroll container");
            SpriteCanvas.nativeLabel("Send", overlayFonts, frame, output);
            var rectangles = frame.sprites().stream().map(sprite -> {
                var node = doc.menu().nodes().stream().filter(n -> n.id().equals(sprite.id())).findFirst().orElseThrow();
                return Map.of("x", sprite.x(), "y", sprite.y(), "width", sprite.width(), "height", sprite.height(),
                    "z", node.z());
            }).toList();
            var serializer = GsonComponentSerializer.gson();
            var fixture = new LinkedHashMap<String, Object>();
            fixture.put("id", doc.menu().id());
            fixture.put("scale", scale);
            fixture.put("body", serializer.serializeToTree(output.body().component()));
            fixture.put("foreground", serializer.serializeToTree(output.foreground()));
            fixture.put("carrier", output.layout().carrier());
            fixture.put("bodyRows", output.layout().bodyRows());
            fixture.put("canvasWidth", frame.width());
            fixture.put("canvasHeight", frame.height());
            fixture.put("contentWidth", output.layout().contentWidth());
            fixture.put("controls", output.layout().controls());
            fixture.put("rectangles", rectangles);
            scaledFixtures.add(fixture);
        }
        Files.writeString(evidence.resolve("scaled-fixtures.json"), new com.google.gson.Gson().toJson(scaledFixtures));
        var shortBody = parse("""
            schema-version: 3
            id: native-test
            canvas: {width: 452, height: 50, display-width: 452}
            frontend:
              next: {type: text, content: Button, size: 2, offset: {x: 226, y: 4}, actions: 'tell: top'}
              cover: {type: rectangle, width: 452, height: 50, color: '#223F55', offset: {x: 226, y: 25, z: -1}}
            native-controls:
              overlay: true
              body-rows: 1
              inputs:
                name: {type: text, label-visible: false}
                notes: {type: text, label-visible: false, multiline: true, height: 40}
                amount: {type: slider}
              buttons:
                send: {label: Send}
            """);
        var shortBindings = new ArrayList<Region>();
        var shortFrame = renderer.drawRuntime(shortBody.menu(), context, 452, 1_048_576);
        var shortOutput = canvas.encodeNative(shortBody, shortFrame, context, overlayFonts,
            region -> { shortBindings.add(region); return ClickEvent.runCommand("/test top"); });
        check(shortOutput.body().rows() == 1 && shortBindings.size() == 1,
            "one-row body lost its genuine upper Menu interaction");
        check(GsonComponentSerializer.gson().serialize(shortOutput.body().component()).contains("/test top"),
            "one-row body navigation was moved into the native button");
        SpriteCanvas.nativeLabel("Send", overlayFonts, shortFrame, shortOutput);
        var sample = examples.get("native-overlay");
        // Resources were prepared only at 1x; every other legal integer width must still encode.
        for (int width = 64; width <= 1014; width++) {
            var active = CanvasScale.context(context, width / 452.0);
            var frame = renderer.drawRuntime(sample.menu(), active, 452, 1_048_576);
            var output = canvas.encodeNative(sample, frame, active, overlayFonts, region -> null);
            SpriteCanvas.nativeLabel("Send", overlayFonts, frame, output);
            check(output.body().bodyWidth() == width + 10, "continuous native width cannot encode: " + width);
        }
        var invalid = parse(BASE + CONTROLS.replace("native-controls:", "native-controls:\n  overlay: true"));
        try {
            canvas.encodeNative(invalid, renderer.drawRuntime(invalid.menu(), context, 452, 1_048_576),
                context, overlayFonts, region -> null);
            throw new AssertionError("native bar accepted a Menu action");
        } catch (IllegalArgumentException expected) {
            check(expected.getMessage().contains("无法在原生栏"), "wrong native bar error: " + expected);
        }
        reject(BASE + CONTROLS.replace("native-controls:", "native-controls:\n  body-rows: 7"));
        reject(BASE + CONTROLS.replace("native-controls:", "native-controls:\n  overlay: true\n  body-rows: 1.5"));
        System.out.println("PASS native controls configuration/canvas checks=" + checks);
        return checks;
    }
}
