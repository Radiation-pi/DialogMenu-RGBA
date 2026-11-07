package dev.dialogmenu;

import java.nio.file.*;
import java.util.*;
import java.util.concurrent.TimeUnit;
import net.kyori.adventure.text.event.ClickEvent;
import static dev.dialogmenu.MenuModel.*;

/** 在独立 JVM 中检查实际发行 JAR，而非主源码输出目录的类。 */
public final class EditionTests {
    private static int checks;

    private static void check(boolean condition, String message) {
        checks++;
        if (!condition) throw new AssertionError(message);
    }

    public static void main(String[] args) throws Exception {
        String edition = args[0];
        boolean shader = edition.equals("rgba");
        check(RenderingEdition.ID.equals(edition), "Installed artifact selects " + edition);
        check(RenderingEdition.class.getProtectionDomain().getCodeSource().getLocation().getPath().endsWith(".jar"),
            "Edition tests must use the packaged classes");
        check((RenderingEdition.class.getResource("/rgba/text.vsh") != null) == shader,
            "Only the RGBA distribution bundles shader resources");
        check((RenderingEdition.class.getResource("/rgba/characters-3500.txt") != null) == shader,
            "Only the RGBA distribution bundles the full common character set");
        if (shader) check(RgbaOffsets.commonCharacters().codePoints().distinct().count() == 3500,
            "The RGBA artifact prepares all 3500 common Han characters");
        Path data = Files.createTempDirectory(Files.createDirectories(Path.of(args[1])), "install-");
        Files.createDirectories(data.resolve("layouts"));
        Files.writeString(data.resolve("layouts/edition.yml"), """
            schema-version: 1
            id: edition
            canvas: {width: 300, height: 180}
            frontend:
              text:
                type: text
                content: 'A中'
                offset: {x: 0, y: 13}
                actions: ['tell: CLICK']
                tooltip: Hover
            """);
        Context context = new Context() {
            public String expand(String text) { return text; }
            public Icon item(int slot) { return null; }
            public boolean permitted(String permission) { return true; }
        };
        // 模拟从旧 RGBA 安装切换到原生发行包。
        Path staleShader = data.resolve("resourcepack/assets/minecraft/shaders/core/text.vsh");
        Files.createDirectories(staleShader.getParent());
        Files.writeString(staleShader, "old shader");
        try (var engine = new ReloadEngine(data, context)) {
            for (boolean legacy : List.of(false, true)) {
                Files.writeString(data.resolve("config.yml"), """
                    default-layout: edition
                    canvas-width: 300
                    text-scales: [1]
                    shader-rendering: %s
                    """.formatted(legacy));
                var result = engine.prepare().get(90, TimeUnit.SECONDS);
                var snapshot = result.snapshot();
                check(result.changed(), "Changed legacy config is read");
                check(snapshot.fonts().shader() == shader, "Legacy config cannot change the installed edition");
                check(Files.exists(staleShader) == shader, "Native export removes stale core shader overrides");
                var doc = snapshot.documents().get("edition");
                var frame = snapshot.renderer().draw(doc.menu(), context, 300, 131072);
                List<String> callbacks = new ArrayList<>();
                var output = new SpriteCanvas().encode(doc.menu(), frame, context, snapshot.fonts(), region -> {
                    callbacks.add(region.id());
                    return ClickEvent.runCommand("/edition-click");
                });
                var glyphs = MenuText.glyphs(output.component());
                check(callbacks.equals(List.of("frontend.text")), "The original element receives its callback");
                check(glyphs.stream().anyMatch(g -> g.style().clickEvent() != null
                    && g.style().hoverEvent() != null), "Click and hover remain available");
                check(glyphs.stream().anyMatch(g -> g.style().font() != null
                    && g.style().font().value().startsWith("text_rgba_")) == shader,
                    "Outgoing component uses the selected renderer");
                check(!engine.prepare().get(90, TimeUnit.SECONDS).changed(), "Unchanged reload reuses the snapshot");
                try (var zip = new java.util.zip.ZipFile(snapshot.pack().toFile())) {
                    check((zip.getEntry("assets/minecraft/shaders/core/text.vsh") != null) == shader,
                        "Delivered pack matches the installed edition");
                }
            }
        }
        System.out.println("PASS " + edition + " edition checks=" + checks);
    }
}
