package dev.dialogmenu;

import com.google.gson.JsonParser;
import java.awt.image.BufferedImage;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.*;
import java.util.concurrent.TimeUnit;
import java.util.zip.ZipFile;
import javax.imageio.ImageIO;
import net.kyori.adventure.text.event.ClickEvent;
import net.kyori.adventure.text.serializer.gson.GsonComponentSerializer;
import static dev.dialogmenu.MenuModel.*;

/** Independent servers must agree without sharing a persisted palette or compiled snapshot. */
public final class IndependentBuildTests {
    private static int checks;

    public static void main(String[] args) throws Exception {
        if (args.length == 3 && args[0].equals("build")) {
            build(Path.of(args[1]), Integer.parseInt(args[2]));
            return;
        }
        Path root = Files.createTempDirectory(Path.of(args[0]), "independent-");
        Map<String, byte[]> baseline = null;
        String components = null;
        for (int i = 0; i < 4; i++) {
            Path data = Files.createDirectory(root.resolve("server-" + i));
            Path log = data.resolve("build.log");
            var process = new ProcessBuilder(Path.of(System.getProperty("java.home"), "bin/java").toString(),
                "-Xmx1G", "-Djava.awt.headless=true", "-cp", System.getProperty("java.class.path"),
                IndependentBuildTests.class.getName(), "build", data.toString(), i % 2 == 0 ? "1" : "4")
                .redirectErrorStream(true).redirectOutput(log.toFile()).start();
            check(process.waitFor(90, TimeUnit.SECONDS) && process.exitValue() == 0,
                "fresh JVM compiles its own resources: " + log);
            Map<String, byte[]> resources = new TreeMap<>();
            try (var zip = new ZipFile(data.resolve("built.zip").toFile())) {
                for (var entry : zip.stream().filter(e -> !e.isDirectory()).toList()) {
                    try (var input = zip.getInputStream(entry)) {
                        resources.put(entry.getName(), input.readAllBytes());
                    }
                }
            }
            check(resources.containsKey("assets/dialogmenu/shaders/include/rgba_palette.glsl")
                && resources.containsKey("assets/dialogmenu/textures/generated/rgba_image_atlas_0.png"),
                "comparison includes actual palette shader and encoded image atlas");
            String actual = Files.readString(data.resolve("components.json"));
            if (baseline == null) {
                baseline = resources;
                components = actual;
            } else {
                check(baseline.keySet().equals(resources.keySet()), "independent resource paths agree");
                for (String name : baseline.keySet()) {
                    byte[] expected = baseline.get(name), current = resources.get(name);
                    boolean equal = name.endsWith(".json") || name.endsWith(".mcmeta")
                        ? JsonParser.parseString(new String(expected, StandardCharsets.UTF_8))
                            .equals(JsonParser.parseString(new String(current, StandardCharsets.UTF_8)))
                        : Arrays.equals(expected, current);
                    check(equal, "independently compiled resource agrees: " + name);
                }
                check(components.equals(actual), "independent text/image/hover/click encoding agrees at all scales");
            }
        }
        System.out.println("PASS independent JVM build checks=" + checks);
    }

    private static void build(Path data, int threads) throws Exception {
        Path layouts = Files.createDirectories(data.resolve("layouts"));
        Path images = Files.createDirectories(data.resolve("images"));
        var image = new BufferedImage(16, 16, BufferedImage.TYPE_INT_ARGB);
        int[] colors = {0x000000, 0x0000aa, 0x00aa00, 0x00aaaa, 0xaa0000, 0xaa00aa, 0xffaa00, 0xaaaaaa,
            0x555555, 0x5555ff, 0x55ff55, 0x55ffff, 0xff5555, 0xff55ff, 0xffff55, 0xffffff};
        for (int y = 0; y < 16; y++) for (int x = 0; x < 16; x++)
            image.setRGB(x, y, 0xff000000 | colors[x]);
        ImageIO.write(image, "png", images.resolve("colors.png").toFile());
        Files.writeString(data.resolve("config.yml"), """
            default-layout: test
            text-scales: [0.75, 1]
            default-text-scale: 1
            resource-build-threads: %d
            resource-build-cpu-percent: 100
            """.formatted(threads));
        Files.writeString(layouts.resolve("test.yml"), """
            schema-version: 1
            id: test
            canvas: {width: 300, height: 180}
            frontend:
              text:
                type: text
                content: '&0A&1B&2C&3D&4E&5F&6G&7H&8I&9J&aK&bL&cM&dN&eO&fP'
                offset: {y: 25}
                actions: ['tell: TEXT']
                tooltip: Colors
              image:
                type: image
                source: colors.png
                width: 32
                height: 16
                offset: {y: -25}
                actions: ['tell: IMAGE']
                tooltip: Image
            """);
        try (var engine = new ReloadEngine(data, context(1))) {
            var snapshot = engine.prepare().get(90, TimeUnit.SECONDS).snapshot();
            Files.copy(snapshot.pack(), data.resolve("built.zip"));
            StringBuilder encoded = new StringBuilder();
            for (double scale : new double[]{0.75, 1}) for (double canvasScale : CanvasScale.OPTIONS)
              for (int width : new int[]{300, 360, 420}) {
                var context = CanvasScale.context(context(scale), canvasScale);
                var menu = snapshot.documents().get("test").menu();
                var frame = snapshot.renderer().draw(menu, context, width, 524288);
                var component = new SpriteCanvas().encode(menu, frame, context, snapshot.fonts(),
                    region -> ClickEvent.runCommand("/" + String.join("-", region.actions()))).component();
                encoded.append(scale).append(':').append(canvasScale).append(':').append(width).append(':')
                    .append(FontPack.hash(frame.image())).append(':')
                    .append(GsonComponentSerializer.gson().serialize(component)).append('\n');
            }
            Files.writeString(data.resolve("components.json"), encoded);
        }
    }

    private static Context context(double scale) {
        return new Context() {
            public String expand(String text) { return text; }
            public Icon item(int slot) { return null; }
            public boolean permitted(String permission) { return true; }
            public double textScale() { return scale; }
        };
    }

    private static void check(boolean condition, String message) {
        checks++;
        if (!condition) throw new AssertionError(message);
    }
}
