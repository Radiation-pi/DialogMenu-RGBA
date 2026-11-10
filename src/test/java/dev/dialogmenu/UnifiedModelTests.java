package dev.dialogmenu;

import com.google.gson.Gson;
import java.nio.file.*;
import java.util.*;
import java.util.concurrent.TimeUnit;
import net.kyori.adventure.text.event.ClickEvent;
import net.kyori.adventure.text.serializer.gson.GsonComponentSerializer;
import static dev.dialogmenu.MenuModel.*;

/** Uses actual vanilla/CE inventory resources, not replacement model fixtures. */
final class UnifiedModelTests {
    private static final Context CONTEXT = new Context() {
        public String expand(String value) { return value; }
        public Icon item(int slot) { return null; }
        public boolean permitted(String permission) { return true; }
    };
    private static int checks;
    private static void check(boolean value, String message) {
        if (!value) throw new AssertionError(message);
        checks++;
    }
    public static void main(String[] args) throws Exception {
        Path data = Files.createTempDirectory(Path.of(args[0]), "unified-models-");
        Files.createDirectories(data.resolve("layouts"));
        Files.createDirectories(data.resolve("images"));
        Files.createDirectories(data.resolve("models"));
        Files.writeString(data.resolve("config.yml"),
            "default-layout: probe\ncanvas-scales: [1]\ntext-scales: [1]\nresource-build-cpu-percent: 100\n");
        Files.writeString(data.resolve("models/settings.json"), new Gson().toJson(
            Map.of("client-jar", args[1], "ce-pack", args[2])));
        Path legacy = Path.of(args[3]);
        Files.copy(legacy, data.resolve("models/registry.json"));
        Files.writeString(data.resolve("images/registry.json"), """
            {"models":{"minecraft:stone":{},"minecraft:oak_stairs":{},"siciliaitems:al_ore":{"mode":"auto"}}}
            """);
        Path page = data.resolve("layouts/probe.yml");
        Files.writeString(page, layout("minecraft:stone", 24, 0));
        Map<Path, byte[]> bytes = new HashMap<>();
        Map<Path, java.nio.file.attribute.FileTime> times = new HashMap<>();
        Path pack;
        try (var engine = new ReloadEngine(data, CONTEXT)) {
            var first = engine.prepare().get(120, TimeUnit.SECONDS).snapshot();
            pack = first.pack();
            var files = new ArrayList<>(first.fonts().modelPacks(data));
            files.add(pack);
            for (Path file : files) {
                bytes.put(file, Files.readAllBytes(file));
                times.put(file, Files.getLastModifiedTime(file));
            }
            for (String id : List.of("minecraft:oak_stairs", "siciliaitems:al_ore", "minecraft:stone"))
                for (int size : List.of(16, 40, 64)) for (int y : List.of(-17, 0, 13)) {
                    Files.writeString(page, layout(id, size, y));
                    var result = engine.prepare(ReloadEngine.Mode.YAML).get(60, TimeUnit.SECONDS);
                    check(!result.resourcesChanged() && result.snapshot().pack().equals(pack),
                        "Unused registered model, size and Y must not rebuild resources");
                    verifyGeometry(result.snapshot(), id, size, y);
                    for (Path file : files)
                        check(Arrays.equals(bytes.get(file), Files.readAllBytes(file))
                            && times.get(file).equals(Files.getLastModifiedTime(file)),
                            "Font and both model partitions remain byte/time identical");
                }
        }
        try (var engine = new ReloadEngine(data, CONTEXT)) {
            engine.restore().get(60, TimeUnit.SECONDS);
            Files.writeString(page, layout("siciliaitems:al_ore", 36, -8));
            var result = engine.prepare(ReloadEngine.Mode.YAML).get(60, TimeUnit.SECONDS);
            verifyGeometry(result.snapshot(), "siciliaitems:al_ore", 36, -8);
            check(result.snapshot().pack().equals(pack), "Frozen registry survives restart");
            Files.writeString(page, layout("minecraft:dirt", 36, 0));
            var dirt = engine.prepare(ReloadEngine.Mode.YAML).get(60, TimeUnit.SECONDS);
            check(!dirt.resourcesChanged(), "Unused default vanilla model needs no registration or new resources");
            verifyGeometry(dirt.snapshot(), "minecraft:dirt", 36, 0);
            Files.writeString(page, layout("minecraft:not_an_inventory_item", 36, 0));
            try {
                engine.prepare(ReloadEngine.Mode.YAML).get(60, TimeUnit.SECONDS);
                throw new AssertionError("Unprepared model was accepted by YAML-only reload");
            } catch (java.util.concurrent.ExecutionException expected) { checks++; }
            check(engine.restore().get(60, TimeUnit.SECONDS).snapshot().documents().get("probe")
                .menu().nodes().getFirst().source().equals("minecraft:dirt"),
                "Rejected model keeps last persisted menu");
        }
        System.out.println("PASS unified model checks=" + checks + " data=" + data);
    }

    private static void verifyGeometry(ReloadEngine.Snapshot snapshot, String id, int size, int y) throws Exception {
        var menu = snapshot.documents().get("probe").menu();
        var frame = snapshot.renderer().drawRuntime(menu, CONTEXT, 300, 131072);
        check(frame.image() == null && frame.sprites().isEmpty(), "3D models must not become raster images");
        String json = GsonComponentSerializer.gson().serialize(new SpriteCanvas().encode(menu, frame,
            CONTEXT, snapshot.fonts(), region -> ClickEvent.runCommand("/model-check")).component());
        String spritePrefix = id.startsWith("minecraft:") ? "minecraft:block/" : "dialogmenu_ce:cache/";
        check(json.contains(spritePrefix) && json.contains("dialogmenu:row_hit")
            && json.contains("model-check"), "Real model faces and independent clicks must survive unified entry");
        var model = menu.nodes().getFirst();
        check(model.source().equals(id) && model.size() == size && model.y() == y,
            "Source and geometry must reflect YAML, not stale prepared layout");
    }

    private static String layout(String id, int size, int y) {
        return """
            schema-version: 1
            id: probe
            canvas: {width: 300, height: 180}
            frontend:
              block:
                type: image
                source: 'model:%s'
                size: %d
                offset: {y: %d}
                actions: close
                tooltip: model
            """.formatted(id, size, y);
    }
}
