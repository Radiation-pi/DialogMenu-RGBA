package dev.dialogmenu;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.*;
import java.util.concurrent.TimeUnit;
import org.bukkit.configuration.file.YamlConfiguration;
import static dev.dialogmenu.MenuModel.*;

/** Real component encoding and disk preferences, including non-preset personal values. */
final class ScaleCalibrationTests {
    private static int checks;
    private static final Context BASE = new Context() {
        public String expand(String value) { return value; }
        public Icon item(int slot) { return null; }
        public boolean permitted(String permission) { return true; }
        public double textScale() { return 1.5; }
        public double canvasScale() { return 1.93; }
    };

    public static void main(String[] args) throws Exception {
        System.out.println("PASS scale calibration checks=" + run(Path.of(args[0])));
    }

    static int run(Path root) throws Exception {
        checks = 0;
        Path data = Files.createTempDirectory(root, "scale-calibration-");
        Files.createDirectories(data.resolve("layouts"));
        Files.writeString(data.resolve("config.yml"), """
            default-layout: probe
            canvas-width: 300
            max-canvas-pixels: 1048576
            text-scales: [0.75, 1, 2]
            canvas-scales: [1]
            resource-build-cpu-percent: 100
            """);
        Files.writeString(data.resolve("layouts/probe.yml"), """
            schema-version: 3
            id: probe
            canvas: {width: 300, height: 180}
            frontend:
              image:
                type: image
                source: 'symbol:■'
                width: 32
                height: 24
                offset: {x: 50, y: 50}
                actions: 'tell: image'
              text:
                type: text
                content: 'Scale 123'
                offset: {x: 130, y: 80}
                actions: 'tell: text'
            """);
        UUID player = UUID.randomUUID(), other = UUID.randomUUID();
        var preferences = new YamlConfiguration();
        preferences.set("players." + player, 1.5);
        // An arbitrary saved fraction, independent of the design reference.
        double initial = 211 / 177.0;
        CanvasScale.select(preferences, player, Double.toString(initial), () -> {});
        Context reference = ScaleCalibration.reference(BASE);
        check(reference.canvasScale() == 1 && reference.textScale() == 1,
            "Calibration ignores both previous personal zooms");
        for (int step : List.of(-10, -5, -1, 1, 5, 10)) {
            check(ScaleCalibration.adjust(1, step) == 1 + step / 100.0, "Button changes requested hundredths");
            double adjusted = ScaleCalibration.adjust(initial, step);
            check(Math.abs(adjusted - initial - step / 100.0) < 1e-15, "Arbitrary previous fractions are preserved");
            check(ScaleCalibration.adjust(adjusted, -step) == initial, "Opposite steps undo without quantization");
            check(ScaleCalibration.adjust(step < 0 ? .8 : 2.2, step) == (step < 0 ? .8 : 2.2),
                "Endpoints cannot be exceeded");
        }
        check(ScaleCalibration.adjust(.81, -10) == .8 && ScaleCalibration.adjust(2.19, 10) == 2.2,
            "Overshooting clamps to exact endpoints");
        try {
            ScaleCalibration.adjust(1, 2);
            throw new AssertionError("Unsupported step accepted");
        } catch (IllegalArgumentException expected) { checks++; }
        try (var engine = new ReloadEngine(data, reference)) {
            var snapshot = engine.prepare().get(60, TimeUnit.SECONDS).snapshot();
            check(!snapshot.documents().containsKey(ScaleCalibration.document().menu().id()),
                "Internal calibration is not a business layout");
            var samples = new ArrayList<Double>();
            for (int hundredths = 80; hundredths <= 220; hundredths++) samples.add(hundredths / 100.0);
            samples.add(initial);
            samples.add(1.23456789012345);
            var gpuCases = new ArrayList<Map<String, Object>>();
            var tokens = new ActionTokens();
            int maxBytes = 0;
            check(ScaleCalibration.document().menu().width() == 324,
                "Default resource preparation uses the centered calibration");
            for (double scale : samples) {
                var doc = ScaleCalibration.document(scale);
                check(Boolean.TRUE.equals(doc.menu().closeWithEscape()), "Esc remains available");
                var frame = snapshot.renderer().drawRuntime(doc.menu(), reference,
                    doc.menu().width(), ScaleCalibration.PIXELS);
                var page = new ActionTokens.Page();
                var actions = new LinkedHashMap<String, List<String>>();
                var received = new ArrayList<String>();
                var output = new SpriteCanvas().encode(doc.menu(), frame, reference, snapshot.fonts(), region -> {
                    actions.put(region.id(), region.actions());
                    return page.bind(() -> received.add(region.actions().getFirst()));
                });
                check(output.bodyWidth() == 334 && output.rows() == 114,
                    "Calibration always uses a narrow centered body; reference height does not zoom");
                var plain = net.kyori.adventure.text.serializer.plain.PlainTextComponentSerializer.plainText()
                    .serialize(output.component());
                String visible = plain.codePoints().filter(cp -> cp < 0xF0000)
                    .collect(StringBuilder::new, StringBuilder::appendCodePoint, StringBuilder::append).toString();
                check(visible.contains(ScaleCalibration.INSTRUCTION), "No-scroll instruction is actually encoded");
                check(visible.contains("当前" + ScaleCalibration.label(scale) + "x"), "Current draft label is encoded");
                check(visible.contains("1步=0.01x"), "Button units are explained");
                check(frame.sprites().isEmpty(), "Calibration adds no image sprites");
                for (int step : List.of(-10, -5, -1, 1, 5, 10)) {
                    boolean enabled = step < 0 ? scale > .8 : scale < 2.2;
                    check(actions.containsKey("step-" + step) == enabled, "Unavailable direction has no action");
                    if (enabled) check(actions.get("step-" + step).equals(List.of("calibrate-scale: " + step)),
                        "Encoded adjustment carries its exact step");
                }
                check(actions.get("confirm").equals(List.of("calibrate-scale: confirm")), "Confirm has a distinct action");
                check(actions.get("cancel").equals(List.of("close")), "Cancel only closes");
                check(!actions.containsKey("alignment")
                    && actions.values().stream().noneMatch(value -> value.contains("calibrate-scale: align")),
                    "Calibration exposes no alignment action");
                check(!visible.contains("靠左") && !visible.contains("居中"),
                    "Calibration exposes no alignment option");
                var boundary = doc.menu().nodes().stream().filter(node -> node.id().equals("boundary"))
                    .findFirst().orElseThrow();
                var style = new TextStyle(boundary.size(), boundary.scale());
                check(snapshot.fonts().textWidth(MenuText.parse(boundary.text()), style) == 320 && style.height() == 1,
                    "Calibration boundary uses adjacent proportional one-pixel glyphs");
                int top = SpriteCanvas.textTop(doc.menu(), boundary, frame.width(), 0, style);
                check(top == Math.round(205 * scale) - 1,
                    "White boundary follows the scaled baseline bottom");
                check(boundary.x() + frame.width() / 2.0 == 3, "Boundary remains centered in the native body advance");
                List<Map<String, Integer>> rows = new ArrayList<>();
                for (var row : RgbaRows.slices(top, 1))
                    rows.add(Map.of("row", row.row(), "payload", RgbaText.payload(1, 1, row.local(top), row.band(), 0, false)));
                gpuCases.add(Map.of("scale", scale, "top", top, "centered", true,
                    "bodyWidth", output.bodyWidth(), "left", 3, "rows", rows, "repeat", 320));
                String encoded = net.kyori.adventure.text.serializer.gson.GsonComponentSerializer.gson()
                    .serialize(output.component());
                maxBytes = Math.max(maxBytes, encoded.getBytes(java.nio.charset.StandardCharsets.UTF_8).length);
                check(maxBytes < 2_097_152, "Encoded page remains within the 2MiB budget");
                if (scale == 1) Files.writeString(data.resolve("calibration-centered-component.json"), encoded);
                tokens.publish(player, page);
                int index = 0;
                for (var action : actions.values()) {
                    tokens.publish(player, page);
                    String id = "action/" + page.nonce + "/" + index++;
                    var callback = tokens.takeResponse(player, id);
                    check(callback != null, "Actual encoded callback can be dispatched");
                    callback.accept(null);
                    check(received.getLast().equals(action.getFirst()), "Callback carries the correct action");
                    check(tokens.takeResponse(player, id) == null, "Consumed callback cannot replay");
                }
                var replacement = new ActionTokens.Page();
                replacement.bind(() -> {});
                tokens.publish(player, replacement);
                check(tokens.takeResponse(player, "action/" + page.nonce + "/0") == null, "Reopening invalidates old actions");
                tokens.remove(player);
                check(tokens.takeResponse(player, "action/" + replacement.nonce + "/0") == null, "Closing invalidates actions");
                check(CanvasScale.selected(preferences, player) == initial, "Draft rendering never writes preferences");
                check(CanvasScale.selected(preferences, other) == 1, "Other player's preference is unaffected");
                var probe = snapshot.documents().get("probe");
                Context active = CanvasScale.context(reference, scale);
                var scaled = snapshot.renderer().drawRuntime(probe.menu(), active, 300, 1048576);
                int[] bound = {0};
                new SpriteCanvas().encode(probe.menu(), scaled, active, snapshot.fonts(), target -> {
                    bound[0]++;
                    return net.kyori.adventure.text.event.ClickEvent.runCommand("/probe");
                });
                check(scaled.width() == Math.round(300 * scale) && bound[0] == 2,
                    "Arbitrary scale preserves text/image actions and geometry");
            }
            javax.imageio.ImageIO.write(RgbaText.wrap(snapshot.renderer().assets.glyph(RgbaText.RULE)), "png",
                data.resolve("rule-source.png").toFile());
            Files.writeString(data.resolve("boundary-cases.json"), new com.google.gson.Gson().toJson(gpuCases));
            double chosen = ScaleCalibration.adjust(initial, 5);
            CanvasScale.select(preferences, player, Double.toString(chosen), () -> {});
            engine.savePreferences(preferences.saveToString()).get(30, TimeUnit.SECONDS);
            var restored = new YamlConfiguration();
            restored.load(data.resolve("player-text-scales.yml").toFile());
            check(CanvasScale.selected(restored, player, 1) == chosen,
                "Confirmed arbitrary fraction survives disk without rounding");
            check(restored.getDouble("players." + player) == 1.5, "Text preference is independent");
            for (String invalid : List.of("0", "0.7999", "2.2001", "NaN", "Infinity", "bad")) {
                try {
                    CanvasScale.select(restored, player, invalid, () -> { throw new AssertionError("Invalid value rendered"); });
                    throw new AssertionError("Invalid scale accepted");
                } catch (IllegalArgumentException expected) {
                    check(CanvasScale.selected(restored, player) == chosen, "Invalid choice preserves saved value");
                }
            }
            System.out.println("Scale calibration max encoded JSON bytes=" + maxBytes);
        }
        System.out.println("Scale calibration fixtures: " + data);
        return checks;
    }

    private static void check(boolean value, String message) {
        checks++;
        if (!value) throw new AssertionError(message);
    }
}
