package dev.dialogmenu;

import java.io.ByteArrayInputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.*;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.zip.ZipFile;
import javax.imageio.ImageIO;
import org.bukkit.configuration.file.YamlConfiguration;
import static dev.dialogmenu.MenuModel.*;

/** Actual ZIPs, exports and persisted snapshots; no client rendering is simulated. */
final class NativeUiTextureTests {
    private static final String ROOT = "assets/minecraft/textures/gui/sprites/";
    private static final List<String> WARNING = List.of("dialog/warning_button.png",
        "dialog/warning_button_disabled.png", "dialog/warning_button_highlighted.png");
    private static final List<String> SCROLLBAR = List.of("widget/scroller.png", "widget/scroller_background.png");
    private static final Context BASE = new Context() {
        public String expand(String text) { return text; }
        public Icon item(int slot) { return null; }
        public boolean permitted(String permission) { return true; }
    };
    private static int checks;

    public static void main(String[] args) throws Exception {
        System.out.println("PASS native UI texture checks=" + run(Path.of(args[0])));
    }

    static int run(Path root) throws Exception {
        checks = 0;
        Files.createDirectories(root);
        Path data = Files.createTempDirectory(root, "native-ui-");
        Files.createDirectories(data.resolve("layouts"));
        Files.writeString(data.resolve("layouts/probe.yml"), """
            schema-version: 3
            id: probe
            canvas: {width: 300, height: 180}
            frontend:
              label:
                type: text
                content: Test
                offset: {x: 80, y: 60}
                actions: close
            """);
        String config = """
            default-layout: probe
            max-canvas-pixels: 1048576
            resource-build-cpu-percent: 100
            """;
        Files.writeString(data.resolve("config.yml"), config);
        String preferences = "canvas-scales:\n  unchanged: 1.46\n";
        Files.writeString(data.resolve("player-text-scales.yml"), preferences);
        try (var engine = new ReloadEngine(data, BASE)) {
            var current = engine.prepare().get(60, TimeUnit.SECONDS).snapshot();
            check(!current.settings().hideDialogWarning() && !current.settings().hideScrollbar(),
                "Existing configs default to visible native controls");
            Map<String, byte[]> baseline = readPack(current.pack());
            verify(baseline, false, false);
            engine.export(current).get(60, TimeUnit.SECONDS);
            Path unrelated = data.resolve("resourcepack/assets/minecraft/textures/gui/sprites/widget/unrelated.txt");
            Files.createDirectories(unrelated.getParent());
            Files.writeString(unrelated, "keep");
            for (int mask : List.of(1, 2, 3, 0)) {
                boolean warning = (mask & 1) != 0, scrollbar = (mask & 2) != 0;
                Files.writeString(data.resolve("config.yml"), config
                    + "hide-dialog-warning: " + warning + "\nhide-scrollbar: " + scrollbar + "\n");
                var resourceOnly = engine.prepare(ReloadEngine.Mode.RESOURCE).get(60, TimeUnit.SECONDS);
                check(resourceOnly.snapshot().pack().equals(current.pack()),
                    "Resource-only reload does not apply pending config switches");
                try {
                    engine.prepare(ReloadEngine.Mode.YAML).get(60, TimeUnit.SECONDS);
                    throw new AssertionError("YAML reload applied a resource-only visibility change");
                } catch (ExecutionException expected) {
                    check(expected.getCause().getMessage().contains("/dialogmenu reload all"),
                        "Changed switches require an explicit full resource rebuild");
                }
                check(engine.restore().get(60, TimeUnit.SECONDS).snapshot().pack().equals(current.pack()),
                    "Rejected YAML update retains the old pack");
                var rebuilt = engine.prepare().get(60, TimeUnit.SECONDS);
                check(rebuilt.resourcesChanged(), "Changing either switch builds a new resource generation");
                current = rebuilt.snapshot();
                check(current.settings().hideDialogWarning() == warning && current.settings().hideScrollbar() == scrollbar,
                    "The two switches are independent");
                Map<String, byte[]> contents = readPack(current.pack());
                verify(contents, warning, scrollbar);
                for (var entry : baseline.entrySet())
                    check(Arrays.equals(entry.getValue(), contents.get(entry.getKey())),
                        "Native visibility must not change existing menu resources: " + entry.getKey());
                int additions = (warning ? 3 : 0) + (scrollbar ? 2 : 0);
                check(contents.size() == baseline.size() + additions, "Only requested native sprites are added");
                engine.export(current).get(60, TimeUnit.SECONDS);
                verify(readDirectory(data.resolve("resourcepack")), warning, scrollbar);
                check(Files.readString(unrelated).equals("keep"), "Disabling overrides preserves unrelated export files");
                try (var restored = new ReloadEngine(data, BASE)) {
                    var saved = restored.restore().get(60, TimeUnit.SECONDS).snapshot();
                    check(saved.settings().hideDialogWarning() == warning && saved.settings().hideScrollbar() == scrollbar,
                        "Both switches survive disk snapshot restoration");
                    verify(readPack(saved.pack()), warning, scrollbar);
                    restored.export(saved).get(60, TimeUnit.SECONDS);
                    verify(readDirectory(data.resolve("resourcepack")), warning, scrollbar);
                }
            }
        }
        check(Files.readString(data.resolve("player-text-scales.yml")).equals(preferences),
            "Native visibility never changes personal calibration");
        try (var stream = NativeUiTextureTests.class.getResourceAsStream("/config.yml")) {
            var defaults = YamlConfiguration.loadConfiguration(
                new java.io.InputStreamReader(stream, java.nio.charset.StandardCharsets.UTF_8));
            for (String key : List.of("hide-dialog-warning", "hide-scrollbar"))
                check(defaults.isBoolean(key) && !defaults.getBoolean(key), "Packaged defaults explicitly preserve visibility");
        }
        System.out.println("Native UI texture fixtures: " + data);
        return checks;
    }

    private static Map<String, byte[]> readPack(Path path) throws Exception {
        Map<String, byte[]> files = new HashMap<>();
        try (var zip = new ZipFile(path.toFile())) {
            for (var entry : Collections.list(zip.entries())) if (!entry.isDirectory()) {
                try (var stream = zip.getInputStream(entry)) { files.put(entry.getName(), stream.readAllBytes()); }
            }
        }
        return files;
    }

    private static Map<String, byte[]> readDirectory(Path root) throws Exception {
        Map<String, byte[]> files = new HashMap<>();
        try (var paths = Files.walk(root)) {
            for (Path path : paths.filter(Files::isRegularFile).toList())
                files.put(root.relativize(path).toString().replace('\\', '/'), Files.readAllBytes(path));
        }
        return files;
    }

    private static void verify(Map<String, byte[]> files, boolean warning, boolean scrollbar) throws Exception {
        for (String name : WARNING) verify(files.get(ROOT + name), warning, 20, 20);
        for (String name : SCROLLBAR) verify(files.get(ROOT + name), scrollbar, 6, 32);
    }

    private static void verify(byte[] bytes, boolean hidden, int width, int height) throws Exception {
        check((bytes != null) == hidden, "Disabled settings must not override vanilla textures");
        if (!hidden) return;
        var image = ImageIO.read(new ByteArrayInputStream(bytes));
        check(image != null && image.getWidth() == width && image.getHeight() == height,
            "Transparent textures retain native sprite and nine-slice dimensions");
        for (int y = 0; y < height; y++) for (int x = 0; x < width; x++)
            check(image.getRGB(x, y) >>> 24 == 0, "Every overridden texel must be fully transparent");
    }

    private static void check(boolean value, String message) {
        checks++;
        if (!value) throw new AssertionError(message);
    }
}
