package dev.dialogmenu;

import java.awt.image.BufferedImage;
import java.nio.file.*;
import java.util.*;
import java.util.concurrent.*;
import javax.imageio.ImageIO;
import net.kyori.adventure.text.serializer.gson.GsonComponentSerializer;
import static dev.dialogmenu.MenuModel.*;

/** 实际重载引擎的模式隔离和失败保留，不使用资源构建替身。 */
final class ReloadTests {
    private static int checks;
    private static final Context PREVIEW = new Context() {
        public String expand(String text) { return text; }
        public Icon item(int slot) { return null; }
        public boolean permitted(String permission) { return true; }
    };
    private static final String YAML = """
        schema-version: 1
        id: modes
        canvas: {width: 300, height: 180}
        frontend:
          label:
            type: text
            content: A
            offset: {x: -10, y: 20}
          button:
            type: image
            source: button.png
            width: 48
            height: 24
            offset: {x: 50, y: -20}
            actions: ['tell: OLD']
        """;

    public static void main(String[] args) throws Exception { run(Path.of(args[0])); }

    static int run(Path root) throws Exception {
        checks = 0;
        check(ReloadEngine.Request.command(new String[]{"reload"}).mode() == ReloadEngine.Mode.YAML,
            "无参数只重载YAML");
        for (var mode : ReloadEngine.Mode.values())
            check(ReloadEngine.Request.command(new String[]{"reload", mode.name().toLowerCase(Locale.ROOT)}).mode() == mode,
                "命令映射 " + mode);
        try {
            ReloadEngine.Request.command(new String[]{"reload", "../resources"});
            throw new AssertionError("错误命令不应隐式全量重载");
        } catch (IllegalArgumentException expected) { check(true, "拒绝未知参数"); }
        Path data = Files.createDirectories(root.resolve("reload-modes-" + RenderingEdition.ID));
        Files.createDirectories(data.resolve("layouts"));
        Files.createDirectories(data.resolve("images"));
        Path yaml = data.resolve("layouts/modes.yml"), config = data.resolve("config.yml");
        String configText = "default-layout: modes\ntext-scales: [1]\n";
        Files.writeString(config, configText);
        Files.writeString(yaml, YAML);
        image(data, 0xFF334455);
        Path unused = data.resolve("images/unused.png");
        Files.copy(data.resolve("images/button.png"), unused);
        try (var engine = new ReloadEngine(data, PREVIEW)) {
            var first = engine.prepare().get(60, TimeUnit.SECONDS);
            var originalResources = files(data);
            check(!originalResources.isEmpty(), "缓存中存在实际资源ZIP");
            String before = render(first.snapshot());
            String movedYaml = YAML.replace("x: -10", "x: -20").replace("x: 50", "x: 40")
                .replace("tell: OLD", "tell: NEW");
            movedYaml = movedYaml.replace("y: 20", "y: 23");
            Files.writeString(yaml, movedYaml);
            var moved = engine.prepare(ReloadEngine.Mode.YAML).get(60, TimeUnit.SECONDS);
            check(moved.changed() && !moved.resourcesChanged(), "布局变动不重建材质包");
            check(moved.snapshot().pack().equals(first.snapshot().pack()), "沿用同一个ZIP");
            check(files(data).equals(originalResources), "导出目录/缓存ZIP字节和修改时间保持不变");
            check(!render(moved.snapshot()).equals(before), "实际组件位置随YAML改变");
            check(moved.snapshot().documents().get("modes").menu().nodes().stream()
                .filter(n -> n.interaction() != null).anyMatch(n -> n.interaction().region(n).actions().contains("tell: NEW")),
                "只重载YAML会应用新动作");
            check(!engine.prepare(ReloadEngine.Mode.YAML).get(60, TimeUnit.SECONDS).changed(),
                "未改动的YAML复用快照");

            Files.writeString(yaml, "[not a layout");
            Files.writeString(config, "[not a config");
            image(data, 0xFFAA5511);
            var resource = engine.prepare(ReloadEngine.Mode.RESOURCE).get(60, TimeUnit.SECONDS);
            check(resource.resourcesChanged(), "资源改动生成新材质");
            check(resource.snapshot().documents() == moved.snapshot().documents(), "材质模式不读取损坏的磁盘布局");
            check(resource.snapshot().settings() == moved.snapshot().settings(), "材质模式不读取损坏的配置");
            check(!resource.snapshot().pack().equals(moved.snapshot().pack()), "材质模式发布新ZIP");
            check(!Arrays.equals(Files.readAllBytes(resource.snapshot().pack()), Files.readAllBytes(first.snapshot().pack())),
                "新图片像素实际进入新包");
            try {
                engine.prepare(ReloadEngine.Mode.YAML).get(60, TimeUnit.SECONDS);
                throw new AssertionError("损坏YAML必须拒绝");
            } catch (ExecutionException expected) { check(true, "损坏YAML拒绝"); }
            check(engine.prepare(ReloadEngine.Mode.RESOURCE).get(60, TimeUnit.SECONDS).snapshot() == resource.snapshot(),
                "失败YAML不破坏当前资源或快照");

            Files.writeString(config, configText);
            Files.writeString(yaml, movedYaml.replace("content: A", "content: B"));
            var intactResources = files(data);
            try {
                engine.prepare(ReloadEngine.Mode.YAML).get(60, TimeUnit.SECONDS);
                throw new AssertionError("新增未准备字符不能只重载YAML");
            } catch (ExecutionException expected) {
                check(expected.getCause().getMessage().contains("/dialogmenu reload all"), "缺字提示全量重载");
            }
            check(files(data).equals(intactResources), "失败YAML不重写资源");
            var all = engine.prepare(ReloadEngine.Mode.ALL).get(60, TimeUnit.SECONDS);
            check(all.changed() && all.resourcesChanged(), "all更新布局和资源");
            check(all.snapshot().documents().get("modes").menu().nodes().stream().anyMatch(n -> n.text().equals("B")),
                "all实际应用新布局");
            render(all.snapshot());
            check(!engine.prepare().get(60, TimeUnit.SECONDS).changed(), "all后缓存可复用");
            Path editorCache = data.resolve("editor-cache/assets");
            Map<Path, java.nio.file.attribute.FileTime> cached = new HashMap<>();
            try (var paths = Files.list(editorCache)) {
                for (Path path : paths.toList()) cached.put(path, Files.getLastModifiedTime(path));
            }
            check(cached.size() == 2, "all为包括未引用图片的全部编辑器素材建立磁盘缓存");
            var editorFiles = new DialogMenuEditorFiles(data, null);
            editorFiles.manifest();
            check(Arrays.equals(editorFiles.read("images/unused.png"), Files.readAllBytes(unused)),
                "新文件服务实例可读取上次all落盘的未引用素材");
            engine.prepare(ReloadEngine.Mode.ALL).get(60, TimeUnit.SECONDS);
            for (var entry : cached.entrySet())
                check(Files.getLastModifiedTime(entry.getKey()).equals(entry.getValue()), "未变素材不重写磁盘缓存");
            for (Path path : cached.keySet())
                Files.setLastModifiedTime(path, java.nio.file.attribute.FileTime.fromMillis(1));
            engine.prepare(ReloadEngine.Mode.FULL).get(60, TimeUnit.SECONDS);
            for (Path path : cached.keySet())
                check(Files.getLastModifiedTime(path).toMillis() > 1, "full强制重建素材文件缓存");
            image(data, 0xFF123456);
            editorFiles.manifest();
            editorFiles.preload(false, (done, total) -> {});
            check(Arrays.equals(editorFiles.read("images/button.png"), Files.readAllBytes(data.resolve("images/button.png"))),
                "素材变更后读回新像素，而不是旧缓存");
            check(Files.isRegularFile(first.snapshot().pack()), "旧快照租约保留旧ZIP");
            check(first.snapshot().settings().resourceBuildCpuPercent() == 80, "默认每路资源CPU预算为80");
            check(first.snapshot().buildThread().equals("DialogMenu-reload"), "资源准备在后台线程完成");
            for (String value : List.of("0", "101", "12.5", "slow")) {
                Files.writeString(config, configText + "resource-build-cpu-percent: " + value + "\n");
                try {
                    engine.prepare(ReloadEngine.Mode.YAML).get(60, TimeUnit.SECONDS);
                    throw new AssertionError("无效资源CPU预算必须拒绝");
                } catch (ExecutionException expected) {
                    check(expected.getCause().getMessage().contains("resource-build-cpu-percent"),
                        "拒绝无效资源CPU预算 " + value);
                }
            }
            Files.writeString(config, configText + "resource-build-cpu-percent: 100\nresource-build-threads: 4\n");
            var unlimited = engine.prepare(ReloadEngine.Mode.YAML).get(60, TimeUnit.SECONDS);
            check(unlimited.snapshot().settings().resourceBuildCpuPercent() == 100
                && unlimited.snapshot().settings().resourceBuildThreads() == 4
                && !unlimited.resourcesChanged(), "YAML可应用预算和并行度但不重建材质");
            image(data, 0xFF336699);
            Files.writeString(config, configText + "resource-build-cpu-percent: 25\n");
            var retained = engine.prepare(ReloadEngine.Mode.RESOURCE).get(60, TimeUnit.SECONDS);
            check(retained.snapshot().settings().resourceBuildCpuPercent() == 100
                && retained.snapshot().settings().resourceBuildThreads() == 4,
                "resource保留内存预算和并行度，不偷读未应用配置");
            var changedBudget = engine.prepare(ReloadEngine.Mode.ALL).get(60, TimeUnit.SECONDS);
            check(changedBudget.snapshot().settings().resourceBuildCpuPercent() == 25, "all读取新预算");
        }
        System.out.println("PASS reload modes " + RenderingEdition.ID + " checks=" + checks);
        return checks;
    }
    private static String render(ReloadEngine.Snapshot snapshot) throws Exception {
        var doc = snapshot.documents().get("modes");
        var frame = snapshot.renderer().draw(doc.menu(), PREVIEW, 300, 131072);
        return GsonComponentSerializer.gson().serialize(new SpriteCanvas().encode(doc.menu(), frame, PREVIEW,
            snapshot.fonts(), region -> null).component());
    }
    private static void image(Path data, int color) throws Exception {
        var image = new BufferedImage(16, 16, BufferedImage.TYPE_INT_ARGB);
        for (int y = 0; y < 16; y++) for (int x = 0; x < 16; x++) image.setRGB(x, y, color);
        ImageIO.write(image, "png", data.resolve("images/button.png").toFile());
    }
    private static Map<String, String> files(Path data) throws Exception {
        Map<String, String> result = new TreeMap<>();
        for (String dir : List.of("resourcepack", "pack-cache")) {
            if (!Files.isDirectory(data.resolve(dir))) continue;
            try (var files = Files.walk(data.resolve(dir))) {
                for (Path file : files.filter(Files::isRegularFile)
                    .filter(p -> !p.getFileName().toString().equals("compiled.json.gz")).toList())
                    result.put(data.relativize(file).toString(), Files.getLastModifiedTime(file)
                        + ":" + Base64.getEncoder().encodeToString(Files.readAllBytes(file)));
            }
        }
        return result;
    }
    private static void check(boolean condition, String message) {
        checks++;
        if (!condition) throw new AssertionError(message);
    }
}
