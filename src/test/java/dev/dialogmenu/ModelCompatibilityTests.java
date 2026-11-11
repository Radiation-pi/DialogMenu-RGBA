package dev.dialogmenu;

import com.google.gson.*;
import java.nio.file.*;
import java.util.*;
import java.util.zip.ZipFile;
import static dev.dialogmenu.MenuModel.*;

/** 验证模型、字体快照及 26.2 图集之间的资源合同。 */
final class ModelCompatibilityTests {
    static int run(Path root) throws Exception {
        Path data = Files.createTempDirectory(root, "model-compatibility-");
        Files.createDirectories(data.resolve("layouts"));
        Files.writeString(data.resolve("config.yml"),
            "default-layout: probe\ntext-scales: [1]\ncanvas-scales: [1]\nresource-build-cpu-percent: 100\n");
        Files.writeString(data.resolve("layouts/probe.yml"), """
            schema-version: 1
            id: probe
            canvas: {width: 300, height: 180}
            frontend:
              log: {type: model, source: minecraft:oak_log, size: 20}
              wood: {type: model, source: minecraft:oak_wood, size: 20, offset: {x: 50}}
            """);
        Context context = new Context() {
            public String expand(String value) { return value; }
            public Icon item(int slot) { return null; }
            public boolean permitted(String permission) { return true; }
        };
        int checks = 0;
        try (var engine = new ReloadEngine(data, context)) {
            var snapshot = engine.prepare().get().snapshot();
            try (var zip = new ZipFile(snapshot.pack().toFile())) {
                for (String name : List.of("assets/dialogmenu_vanilla/shaders/include/models.glsl",
                        "assets/dialogmenu_ce/shaders/include/models.glsl")) {
                    if (zip.getEntry(name) == null) throw new AssertionError("Snapshot misses " + name);
                    checks++;
                }
                String vertex = new String(zip.getInputStream(zip.getEntry(
                    "dialogmenu_26_2/assets/minecraft/shaders/core/text.vsh")).readAllBytes(), java.nio.charset.StandardCharsets.UTF_8);
                if (!vertex.contains("dialogmenu_model(position, imageUv)"))
                    throw new AssertionError("26.2 does not dispatch model geometry");
                checks++;
                String include = new String(zip.getInputStream(zip.getEntry(
                    "assets/dialogmenu/shaders/include/model_vertex_26_2.glsl")).readAllBytes(), java.nio.charset.StandardCharsets.UTF_8);
                if (include.contains("gl_VertexIndex") || include.contains("layout(location = 7)"))
                    throw new AssertionError("26.2 contains unsupported shader interface");
                checks++;
                if (InventoryAssets.usesItemsAtlas()) {
                    JsonArray sources = JsonParser.parseString(new String(zip.getInputStream(zip.getEntry(
                        "assets/minecraft/atlases/items.json")).readAllBytes(), java.nio.charset.StandardCharsets.UTF_8))
                        .getAsJsonObject().getAsJsonArray("sources");
                    Set<String> sprites = new HashSet<>();
                    for (var source : sources) {
                        var object = source.getAsJsonObject();
                        if (object.has("sprite")) sprites.add(object.get("sprite").getAsString());
                    }
                    if (sprites.contains("minecraft:block/stone") || sprites.contains("minecraft:block/oak_log"))
                        throw new AssertionError("3D registration reuses world sprite IDs");
                    if (zip.getEntry("assets/minecraft/atlases/blocks.json") != null)
                        throw new AssertionError("26.2 font ZIP modifies world atlas definitions");
                    checks += 2;
                    for (var model : InventoryAssets.json("models.json").getAsJsonObject("models").entrySet())
                        for (var face : model.getValue().getAsJsonArray()) {
                            String sprite = face.getAsJsonObject().get("sprite").getAsString();
                            if (!sprites.contains(ModelPack.itemSprite(sprite))) throw new AssertionError("Missing 26.2 sprite: " + sprite);
                            checks++;
                        }
                }
            }
            var models = InventoryAssets.json("models.json").getAsJsonObject("models");
            String log = models.get("minecraft:oak_log").toString();
            String wood = models.get("minecraft:oak_wood").toString();
            if (!log.contains("oak_log_top") || wood.contains("oak_log_top"))
                throw new AssertionError("Log and wood inventory geometry lost their distinct top textures");
            checks++;
            // 合包目标已含二维/自定义图集时，追加模型资源不能抹掉它们。
            Path output = data.resolve("merge");
            Files.createDirectories(output.resolve("assets/minecraft/atlases"));
            Files.writeString(output.resolve("assets/minecraft/atlases/items.json"),
                "{\"sources\":[{\"type\":\"single\",\"resource\":\"sample:icon\"}]}");
            ModelPack.prepare(data, snapshot.documents().values()).write(output);
            if (!Files.readString(output.resolve("assets/minecraft/atlases/items.json")).contains("sample:icon"))
                throw new AssertionError("Model resources overwrite existing item atlas sources");
            checks++;
        }
        System.out.println("PASS model compatibility checks=" + checks + " (offline, no client)");
        return checks;
    }
}
