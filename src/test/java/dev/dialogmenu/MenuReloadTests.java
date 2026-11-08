package dev.dialogmenu;

import java.nio.file.*;
import java.util.*;
import java.util.concurrent.*;
import static dev.dialogmenu.MenuModel.*;

/** 单文件重载使用真实解析、渲染和资源快照，不扫描或应用其他文件。 */
public final class MenuReloadTests {
    private static int checks;
    private static final Context PREVIEW = new Context() {
        public String expand(String text) { return text; }
        public Icon item(int slot) { return null; }
        public boolean permitted(String permission) { return true; }
    };
    private static final String GROUP = """
        schema-version: 1
        id: group
        pages:
          page-one:
            canvas: {width: 300, height: 180}
            frontend:
              label: {type: text, content: A, offset: {x: 0, y: 20}}
          page-two:
            canvas: {width: 300, height: 180}
            frontend:
              label: {type: text, content: A, offset: {x: 0, y: 20}}
        """;

    public static void main(String[] args) throws Exception {
        for (String name : List.of("first", "page-one", "group")) {
            var request = ReloadEngine.Request.command(new String[]{"reload", name});
            check(request.mode() == ReloadEngine.Mode.YAML && name.equals(request.menu()), "menu command " + name);
        }
        for (String name : List.of("yaml", "resource", "all"))
            check(ReloadEngine.Request.command(new String[]{"reload", name}).menu() == null, "reserved mode " + name);
        for (String[] command : List.of(new String[]{"reload", "../first"}, new String[]{"reload", "first", "extra"})) {
            try { ReloadEngine.Request.command(command); throw new AssertionError("invalid command accepted"); }
            catch (IllegalArgumentException expected) { checks++; }
        }
        Path data = Files.createDirectories(Path.of(args[0]).resolve("menu-reload-" + UUID.randomUUID()));
        Path layouts = Files.createDirectories(data.resolve("layouts"));
        Path config = data.resolve("config.yml"), first = layouts.resolve("first.yml");
        String settings = "default-layout: first\ntext-scales: [1]\n";
        Files.writeString(config, settings);
        Files.writeString(first, simple("first", "OLD"));
        Files.writeString(layouts.resolve("second.yml"), simple("second", "OLD"));
        Files.writeString(layouts.resolve("group.yml"), GROUP);
        try (var engine = new ReloadEngine(data, PREVIEW)) {
            var original = engine.prepare().get(90, TimeUnit.SECONDS).snapshot();
            var files = resources(data);
            var unaffected = original.documents().get("second");
            Files.writeString(config, "[broken config");
            Files.writeString(layouts.resolve("second.yml"), "[broken unrelated menu");
            Files.writeString(first, simple("first", "NEW").replace("x: 0", "x: -10"));
            var result = reload(engine, "first");
            var updated = result.snapshot();
            check(result.changed() && !result.resourcesChanged() && result.menus().equals(Set.of("first")), "only target scope");
            check(updated.pack().equals(original.pack()) && updated.fonts() == original.fonts(), "reuse exact resource snapshot");
            check(updated.settings() == original.settings(), "do not apply disk config");
            check(updated.documents().get("second") == unaffected, "do not read broken unrelated menu");
            check(updated.documents().get("page-one") == original.documents().get("page-one"), "other group preserved");
            var label = updated.documents().get("first").menu().nodes().getFirst();
            check(label.x() == -10 && label.interaction().region(label).actions().contains("tell: NEW"), "new geometry and action active");
            check(resources(data).equals(files), "all resource bytes and mtimes unchanged");
            check(!reload(engine, "first").changed(), "unchanged target reused despite other broken inputs");
            Files.writeString(layouts.resolve("second.yml"), simple("second", "PENDING"));
            Files.writeString(config, settings + "resource-build-cpu-percent: 40\n");
            Files.writeString(first, simple("first", "NEXT"));
            var secondUpdate = reload(engine, "first").snapshot();
            check(secondUpdate.documents().get("second") == unaffected
                && secondUpdate.settings().resourceBuildCpuPercent() == 80, "other valid pending changes remain pending");
            var full = engine.prepare(ReloadEngine.Mode.YAML).get(90, TimeUnit.SECONDS).snapshot();
            check(full.settings().resourceBuildCpuPercent() == 40, "later global YAML still applies pending config");
            check(!full.documents().get("second").equals(unaffected), "later global YAML still sees other pending file");
            check(resources(data).equals(files), "global YAML also leaves resources untouched");

            Files.writeString(first, "[broken target");
            rejects(engine, "first", null);
            check(reload(engine, "page-one").snapshot() == full, "parse failure preserves old snapshot");
            Files.writeString(first, simple("first", "NEW").replace("content: A", "content: B"));
            rejects(engine, "first", "/dialogmenu reload all");
            check(resources(data).equals(files), "missing resource does not rewrite pack");
            check(reload(engine, "page-one").snapshot() == full, "validation failure preserves old snapshot");
            Files.writeString(first, simple("first", "NEXT"));
            reload(engine, "first");

            Files.writeString(layouts.resolve("group.yml"), GROUP.replace("x: 0", "x: -10"));
            var group = reload(engine, "group");
            check(group.menus().equals(Set.of("page-one", "page-two")), "file name reloads all shared pages");
            var firstKept = group.snapshot().documents().get("first");
            Files.writeString(layouts.resolve("group.yml"), GROUP.replace("x: 0", "x: -20"));
            var page = reload(engine, "page-one");
            check(page.menus().equals(Set.of("page-one", "page-two")), "page ID resolves its owning YAML");
            check(page.snapshot().documents().get("page-two").menu().nodes().getFirst().x() == -20,
                "sibling page uses new shared definition");
            check(page.snapshot().documents().get("first") == firstKept, "shared-file reload does not recreate unrelated menu");
            Files.writeString(layouts.resolve("group.yml"), GROUP.replace("page-two:", "second:"));
            rejects(engine, "group", "重复布局ID");
            Files.writeString(layouts.resolve("group.yml"), GROUP);
            Files.writeString(layouts.resolve("second.yml"), simple("second", "NEXT").replace("tell: NEXT", "open: page-two"));
            reload(engine, "second");
            String removedPage = GROUP.substring(0, GROUP.indexOf("  page-two:"));
            Files.writeString(layouts.resolve("group.yml"), removedPage);
            rejects(engine, "group", "跳转布局不存在");
            check(reload(engine, "first").snapshot().documents().containsKey("page-two"),
                "inbound navigation prevents unsafe page deletion");
            Files.writeString(layouts.resolve("second.yml"), simple("second", "NEXT"));
            reload(engine, "second");
            var deleted = reload(engine, "page-two");
            check(!deleted.snapshot().documents().containsKey("page-two")
                && deleted.menus().equals(Set.of("page-one", "page-two")), "removed page invalidated atomically with owning file");

            Files.writeString(layouts.resolve("added.yml"), simple("added", "NEW"));
            Files.writeString(layouts.resolve("unrelated.yml"), "[broken new file");
            check(reload(engine, "added").snapshot().documents().containsKey("added"), "new file can load without scanning others");
            rejects(engine, "missing", null);
            Path outside = data.resolve("outside.yml");
            Files.writeString(outside, simple("escape", "NEW"));
            Files.createSymbolicLink(layouts.resolve("escape.yml"), outside);
            rejects(engine, "escape", "布局文件不在layouts");
            check(resources(data).equals(files), "all targeted operations preserve resource bytes and mtimes");
            check(!reload(engine, "added").changed(), "failure does not change last good target cache");
            var partial = reload(engine, "added").snapshot();
            var resource = engine.prepare(ReloadEngine.Mode.RESOURCE).get(90, TimeUnit.SECONDS).snapshot();
            check(resource.documents() == partial.documents() && resource.settings() == partial.settings(),
                "resource mode keeps partial menus/settings, does not read broken pending YAML");
        }
        System.out.println("PASS menu reload checks=" + checks);
    }
    private static String simple(String id, String action) {
        return """
            schema-version: 1
            id: %s
            canvas: {width: 300, height: 180}
            frontend:
              label:
                type: text
                content: A
                offset: {x: 0, y: 20}
                actions: ['tell: %s']
            """.formatted(id, action);
    }
    private static ReloadEngine.Result reload(ReloadEngine engine, String name) throws Exception {
        return engine.prepare(ReloadEngine.Request.command(new String[]{"reload", name})).get(90, TimeUnit.SECONDS);
    }
    private static void rejects(ReloadEngine engine, String name, String message) throws Exception {
        try { reload(engine, name); throw new AssertionError("invalid target accepted: " + name); }
        catch (ExecutionException expected) {
            check(message == null || expected.getCause().getMessage().contains(message), "target rejected: " + name + " " + expected.getCause());
        }
    }
    private static Map<String, String> resources(Path data) throws Exception {
        Map<String, String> files = new TreeMap<>();
        for (String name : List.of("resourcepack", "pack-cache")) {
            if (!Files.isDirectory(data.resolve(name))) continue;
            try (var paths = Files.walk(data.resolve(name))) {
                for (Path path : paths.filter(Files::isRegularFile)
                    .filter(p -> !p.getFileName().toString().equals("compiled.json.gz")).toList())
                    files.put(data.relativize(path).toString(), Files.getLastModifiedTime(path)
                        + ":" + Base64.getEncoder().encodeToString(Files.readAllBytes(path)));
            }
        }
        return files;
    }
    private static void check(boolean condition, String message) {
        checks++;
        if (!condition) throw new AssertionError(message);
    }
}
