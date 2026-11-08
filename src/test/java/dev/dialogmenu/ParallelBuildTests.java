package dev.dialogmenu;

import com.google.gson.JsonParser;
import java.awt.image.BufferedImage;
import java.io.IOException;
import java.lang.management.ManagementFactory;
import java.nio.file.*;
import java.util.*;
import java.util.concurrent.*;
import javax.imageio.ImageIO;
import java.util.zip.ZipFile;
import net.kyori.adventure.text.event.ClickEvent;
import net.kyori.adventure.text.serializer.gson.GsonComponentSerializer;
import static dev.dialogmenu.MenuModel.*;

/** 真实构建资源、组件和CPU工作量对照，不使用渲染或资源写入替身。 */
public final class ParallelBuildTests {
    private static int checks;
    private static final Context CONTEXT = new Context() {
        public String expand(String text) { return text; }
        public Icon item(int slot) { return null; }
        public boolean permitted(String permission) { return true; }
    };
    public static void main(String[] args) throws Exception {
        builds(Path.of(args[0]));
        System.out.println("PASS parallel build checks=" + checks);
    }

    private static void builds(Path root) throws Exception {
        Map<String, byte[]> baseline = null;
        String component = null;
        for (int threads : new int[]{1, 4}) {
            Path data = Files.createDirectories(root.resolve("parallel-" + UUID.randomUUID()));
            Files.createDirectories(data.resolve("layouts"));
            Path images = Files.createDirectories(data.resolve("images"));
            BufferedImage image = new BufferedImage(16, 16, BufferedImage.TYPE_INT_ARGB);
            for (int y = 0; y < 16; y++) for (int x = 0; x < 16; x++)
                image.setRGB(x, y, 0xff000000 | x * 0x10100 | y * 0x10);
            ImageIO.write(image, "png", images.resolve("button.png").toFile());
            Path ce = data.resolve("ce/demo/resourcepack/assets/example/textures/font");
            Files.createDirectories(ce);
            ImageIO.write(image, "png", ce.resolve("sample.png").toFile());
            Files.writeString(data.resolve("config.yml"), """
                default-layout: test
                text-scales: [1]
                craftengine-resources: ce
                resource-build-threads: %d
                resource-build-cpu-percent: 80
                """.formatted(threads));
            Files.writeString(data.resolve("layouts/test.yml"), """
                schema-version: 1
                id: test
                canvas: {width: 300, height: 180}
                frontend:
                  text:
                    type: text
                    content: 'A中文'
                    offset: {x: 0, y: 20}
                    actions: ['tell: TEXT']
                    tooltip: Text
                  stretched:
                    type: text
                    content: 'XY汉'
                    offset: {x: 0, y: 50}
                    scale: {x: 1.5, y: 2}
                  dynamic:
                    type: text
                    content: '{player}'
                    offset: {x: 0, y: -50}
                  button:
                    type: image
                    source: button.png
                    width: 48
                    height: 24
                    offset: {x: 40, y: -20}
                    actions: ['tell: BUTTON']
                    tooltip: Button
                  ce:
                    type: image
                    source: craftengine:demo/font/sample
                    width: 16
                    height: 16
                    offset: {x: -40, y: -20}
                """);
            try (var engine = new ReloadEngine(data, CONTEXT)) {
                Set<Long> buildThreads = ConcurrentHashMap.newKeySet();
                var cpu = ManagementFactory.getThreadMXBean();
                if (!cpu.isThreadCpuTimeEnabled()) cpu.setThreadCpuTimeEnabled(true);
                long caller = Thread.currentThread().threadId();
                var pending = engine.prepare(new ReloadEngine.Request(ReloadEngine.Mode.ALL, null), progress -> {
                    for (var thread : cpu.getThreadInfo(cpu.getAllThreadIds())) {
                        if (thread != null && thread.getThreadName().startsWith("DialogMenu-build-")
                            && cpu.getThreadCpuTime(thread.getThreadId()) > 0)
                            buildThreads.add(thread.getThreadId());
                    }
                });
                var first = pending.get(90, TimeUnit.SECONDS);
                var snapshot = first.snapshot();
                check(!buildThreads.contains(caller), "build stays off caller thread");
                check(threads == 1 ? buildThreads.size() == 1 : buildThreads.size() > 1,
                    "real resource work uses configured serial/parallel execution: " + buildThreads.size());
                check(buildThreads.size() <= threads, "resource concurrency stays bounded");
                check(snapshot.buildThread().equals("DialogMenu-reload"), "snapshot has one background coordinator");
                System.out.println("threads=" + threads + " observed-resource-workers=" + buildThreads.size()
                    + " full-build-ms=" + snapshot.buildMillis());
                Map<String, byte[]> files = files(snapshot.pack());
                check(files.containsKey("pack.mcmeta") && files.size() > 10, "compare actual complete ZIP, not empty export");
                if (baseline == null) baseline = files;
                else equivalent(baseline, files);
                var doc = snapshot.documents().get("test");
                List<String> actions = new ArrayList<>();
                String encoded = GsonComponentSerializer.gson().serialize(new SpriteCanvas().encode(doc.menu(),
                    snapshot.renderer().draw(doc.menu(), CONTEXT, 300, 131072), CONTEXT, snapshot.fonts(), region -> {
                        actions.add(region.id());
                        return ClickEvent.runCommand("/click-" + region.id());
                    }).component());
                check(actions.equals(List.of("frontend.text", "frontend.button")), "click bindings retained");
                if (component == null) component = encoded;
                else check(component.equals(encoded), "actual outgoing text/image/hover/click component unchanged");
                check(!engine.prepare().get(90, TimeUnit.SECONDS).changed(), "resource cache reused");
                var before = files(snapshot.pack());
                Files.writeString(data.resolve("layouts/test.yml"), "[broken");
                try {
                    engine.prepare().get(90, TimeUnit.SECONDS);
                    throw new AssertionError("failed reload accepted");
                } catch (ExecutionException expected) { checks++; }
                equivalent(before, files(snapshot.pack()));
                check(engine.prepare(ReloadEngine.Mode.RESOURCE).get(90, TimeUnit.SECONDS).snapshot() == snapshot,
                    "failed all preserves previous snapshot");
                Path layout = data.resolve("layouts/test.yml");
                // 解析可通过，但实际并行绘图失败。
                Files.writeString(layout, """
                    schema-version: 1
                    id: test
                    canvas: {width: 300, height: 180}
                    frontend:
                      broken: {type: image, source: missing.png, width: 16, height: 16}
                    """);
                try {
                    engine.prepare().get(90, TimeUnit.SECONDS);
                    throw new AssertionError("missing image accepted");
                } catch (ExecutionException expected) { checks++; }
                equivalent(before, files(snapshot.pack()));
                check(engine.prepare(ReloadEngine.Mode.RESOURCE).get(90, TimeUnit.SECONDS).snapshot() == snapshot,
                    "parallel rendering failure preserves previous snapshot");
                check(noStaging(data), "failed preparation leaves no staging directory");
            }
        }
    }
    private static Map<String, byte[]> files(Path root) throws IOException {
        Map<String, byte[]> result = new TreeMap<>();
        try (var zip = new ZipFile(root.toFile())) {
            for (var entry : zip.stream().filter(e -> !e.isDirectory()).toList())
                try (var input = zip.getInputStream(entry)) { result.put(entry.getName(), input.readAllBytes()); }
        }
        return result;
    }
    private static boolean noStaging(Path data) throws IOException {
        try (var files = Files.list(data.resolve("pack-cache"))) {
            return files.noneMatch(p -> p.getFileName().toString().startsWith("staging-"));
        }
    }
    private static void equivalent(Map<String, byte[]> expected, Map<String, byte[]> actual) {
        check(expected.keySet().equals(actual.keySet()), "resource path set identical");
        for (String key : expected.keySet()) {
            if (key.endsWith(".json") || key.endsWith(".mcmeta"))
                check(JsonParser.parseString(new String(expected.get(key), java.nio.charset.StandardCharsets.UTF_8))
                    .equals(JsonParser.parseString(new String(actual.get(key), java.nio.charset.StandardCharsets.UTF_8))),
                    "font/shader metadata semantics unchanged: " + key);
            else check(Arrays.equals(expected.get(key), actual.get(key)), "PNG/shader bytes unchanged: " + key);
        }
    }
    private static void check(boolean condition, String message) {
        checks++;
        if (!condition) throw new AssertionError(message);
    }
}
