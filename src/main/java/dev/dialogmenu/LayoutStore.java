package dev.dialogmenu;

import java.io.IOException;
import java.nio.file.*;
import java.util.*;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.file.YamlConfiguration;
import static dev.dialogmenu.MenuModel.*;

/** ArcMenu-shaped documents, interpreted locally without its plugin or API. */
final class LayoutStore {
    record FlowRow(String collection, String id, String text) {}
    record Document(Menu menu, List<String> open, List<String> close, MenuTemplate template, List<FlowRow> flowRows) {
        Document(Menu menu, List<String> open, List<String> close) { this(menu, open, close, null, List.of()); }
        Document(Menu menu, List<String> open, List<String> close, MenuTemplate template) {
            this(menu, open, close, template, List.of());
        }
    }
    private final Path directory;
    private Map<String, Document> documents = Map.of();

    LayoutStore(Path directory) throws IOException {
        this.directory = directory.toAbsolutePath().normalize();
        Files.createDirectories(this.directory);
    }
    static String id(String value) {
        if (value == null || !value.matches("[a-z0-9][a-z0-9_-]{0,63}"))
            throw new IllegalArgumentException("ID须为小写字母、数字、-或_，最多64字符");
        return value;
    }
    Document get(String id) { return documents.get(id); }
    Set<String> ids() { return documents.keySet(); }
    Map<String, Document> load() throws Exception {
        Map<String, Document> next = new LinkedHashMap<>();
        try (var files = Files.list(directory)) {
            for (Path path : files.filter(p -> p.toString().endsWith(".yml")).sorted().toList()) {
                if (!path.toRealPath().startsWith(directory.toRealPath()))
                    throw new IOException("布局文件不在layouts/中");
                String name = path.getFileName().toString().replaceFirst("\\.yml$", "");
                var yaml = new YamlConfiguration();
                yaml.loadFromString(Files.readString(path));
                Map<String, Document> loaded = yaml.isConfigurationSection("pages")
                    ? MenuTemplate.load(name, yaml) : Map.of(name, parse(name, yaml));
                for (var entry : loaded.entrySet())
                    if (next.putIfAbsent(entry.getKey(), entry.getValue()) != null)
                        throw new IllegalArgumentException("重复布局ID：" + entry.getKey());
            }
        }
        for (Document doc : next.values()) for (Region region : interactiveRegions(doc.menu()))
            for (String action : java.util.stream.Stream.concat(region.actions().stream(), region.doubleActions().stream()).toList()) {
                String[] parts = action.split(":", 2);
                if (parts[0].strip().equals("open") && !next.containsKey(parts[1].strip()))
                    throw new IllegalArgumentException("跳转布局不存在：" + action);
            }
        return Collections.unmodifiableMap(next);
    }
    void publish(Map<String, Document> next) { documents = next; }
    static List<Region> interactiveRegions(Menu menu) {
        List<Region> result = new ArrayList<>();
        for (Node node : menu.nodes()) if (node.interaction() != null)
            result.add(node.interaction().region(node));
        return result;
    }

    static List<String> clickActions(ConfigurationSection section) {
        if (!section.isConfigurationSection("actions")) return actions(section.get("actions"), false);
        var branches = section.getConfigurationSection("actions");
        keys(branches, Set.of("all", "left", "single", "double"));
        if (branches.contains("single") || branches.contains("double")) {
            if (branches.contains("all") || branches.contains("left"))
                throw new IllegalArgumentException("actions.single/double不能与all/left混用");
            return actions(branches.get("single"), false);
        }
        List<String> result = new ArrayList<>(actions(branches.get("all"), false));
        result.addAll(actions(branches.get("left"), false));
        return List.copyOf(result);
    }
    static List<String> doubleActions(ConfigurationSection section) {
        return section.isConfigurationSection("actions")
            ? actions(section.get("actions.double"), false) : List.of();
    }

    static void keys(ConfigurationSection section, Set<String> allowed) {
        for (String key : section.getKeys(false))
            if (key.equals("backend"))
                throw new IllegalArgumentException("backend独立点击区域已移除；请将clickable/actions/tooltip配置到frontend的text或image节点");
            else if (!allowed.contains(key))
                throw new IllegalArgumentException(section.getCurrentPath() + "." + key + " 尚不支持");
    }
    static double number(ConfigurationSection s, String key, double fallback) {
        Object value = s.get(key);
        if (value == null) return fallback;
        if (!(value instanceof Number n) || !Double.isFinite(n.doubleValue()))
            throw new IllegalArgumentException(s.getCurrentPath() + "." + key + " 须为有限数字");
        return n.doubleValue();
    }
    static int integer(ConfigurationSection s, String key, int fallback, int min, int max) {
        double value = number(s, key, fallback);
        if (value != Math.rint(value) || value < min || value > max)
            throw new IllegalArgumentException(s.getCurrentPath() + "." + key + " 须为" + min + ".." + max + "整数");
        return (int) value;
    }
    static List<String> strings(Object value) {
        if (value == null) return List.of();
        if (value instanceof String text) return List.of(text);
        if (value instanceof List<?> list && list.stream().allMatch(String.class::isInstance))
            return list.stream().map(String.class::cast).toList();
        throw new IllegalArgumentException("动作/提示须为字符串或字符串列表");
    }
    static List<String> actions(Object value, boolean lifecycle) {
        List<String> result = strings(value);
        for (String text : result) {
            String[] parts = text.split(":", 2);
            String kind = parts[0].strip();
            if (!Set.of("tell", "sound", "command", "command-in-place", "console", "open", "back", "close", "return",
                "toggle", "set", "notice", "entry", "callback").contains(kind))
                throw new IllegalArgumentException("动作尚不支持：" + kind);
            if (lifecycle && !Set.of("tell", "sound").contains(kind))
                throw new IllegalArgumentException("open/close事件仅支持tell和sound，避免递归导航");
            if (Set.of("tell", "sound", "command", "command-in-place", "console", "open", "toggle", "set", "notice", "entry", "callback").contains(kind)
                && (parts.length != 2 || parts[1].isBlank()))
                throw new IllegalArgumentException("动作缺少参数：" + text);
            if (kind.equals("sound")) sound(parts[1].strip());
        }
        return result;
    }
    record SoundSpec(String key, float volume, float pitch) {}
    static SoundSpec sound(String value) {
        String[] parts = value.split("-");
        if (parts.length > 3 || parts[0].isBlank()) throw new IllegalArgumentException("声音格式须为name-volume-pitch");
        String key = parts[0].contains(":") ? parts[0]
            : "minecraft:" + parts[0].toLowerCase(Locale.ROOT).replace('_', '.');
        if (!key.matches("[a-z0-9_.-]+:[a-z0-9_./-]+")) throw new IllegalArgumentException("声音ID非法");
        float volume = parts.length > 1 ? Float.parseFloat(parts[1]) : 1;
        float pitch = parts.length > 2 ? Float.parseFloat(parts[2]) : 1;
        if (!Float.isFinite(volume) || !Float.isFinite(pitch) || volume < 0 || volume > 16 || pitch < 0 || pitch > 2)
            throw new IllegalArgumentException("声音volume须为0..16，pitch须为0..2");
        return new SoundSpec(key, volume, pitch);
    }
    static Document parse(String expected, YamlConfiguration yaml) {
        id(expected);
        keys(yaml, Set.of("schema-version", "id", "permission", "canvas", "frontend", "events",
            "close-with-escape", "scale-text-with-canvas"));
        if (integer(yaml, "schema-version", -1, 1, 1) != 1 || !expected.equals(yaml.getString("id")))
            throw new IllegalArgumentException("schema-version须为1，id须与文件名一致");
        ConfigurationSection canvas = yaml.getConfigurationSection("canvas");
        if (canvas == null) throw new IllegalArgumentException("缺少canvas");
        keys(canvas, Set.of("width", "height"));
        int width = integer(canvas, "width", 300, 16, 4096);
        int height = integer(canvas, "height", 180, 16, 4096);
        List<Node> nodes = new ArrayList<>();
        flatten(yaml.getConfigurationSection("frontend"), 0, 0, 0, 1, 1, nodes);
        if (nodes.isEmpty()) throw new IllegalArgumentException("frontend至少需要一个可见元素");
        nodes.sort(Comparator.comparingDouble(Node::z));
        List<String> open = List.of(), close = List.of();
        var events = yaml.getConfigurationSection("events");
        if (events != null) {
            keys(events, Set.of("open", "close"));
            open = actions(events.get("open"), true);
            close = actions(events.get("close"), true);
        }
        return new Document(new Menu(expected, yaml.getString("permission", ""), width, height,
            List.copyOf(nodes), List.of(),
            yaml.contains("close-with-escape") ? yaml.getBoolean("close-with-escape") : null,
            yaml.getBoolean("scale-text-with-canvas", false)), open, close);
    }
    static void flatten(ConfigurationSection section, double ox, double oy, double oz,
                        double sx, double sy, List<Node> out) {
        if (section == null) return;
        for (String key : section.getKeys(false)) {
            id(key);
            var s = section.getConfigurationSection(key);
            if (s == null) throw new IllegalArgumentException("frontend元素须为对象：" + key);
            keys(s, Set.of("type", "visible", "offset", "scale", "rotation", "children",
                "width", "height", "color", "opacity", "content", "source", "alignment", "size", "thickness",
                "line-width", "max-lines", "clickable", "permission", "tooltip", "actions", "reuse-texture"));
            for (String transform : List.of("offset", "scale", "rotation")) {
                var t = s.getConfigurationSection(transform);
                if (t != null) keys(t, Set.of("x", "y", "z"));
            }
            if (!s.getBoolean("visible", true)) continue;
            String type = s.getString("type", "");
            boolean interactive = s.contains("clickable") || s.contains("actions") || s.contains("tooltip");
            if (interactive && !Set.of("text", "image").contains(type))
                throw new IllegalArgumentException(key + " 直接点击仅用于text/image");
            if (s.contains("clickable") && !(s.get("clickable") instanceof Boolean))
                throw new IllegalArgumentException(key + " clickable须为true/false");
            double x = ox + number(s, "offset.x", 0) * sx;
            double y = oy + number(s, "offset.y", 0) * sy;
            double z = oz + number(s, "offset.z", 0);
            double nx = sx * number(s, "scale.x", 1), ny = sy * number(s, "scale.y", 1);
            if (nx <= 0 || ny <= 0) throw new IllegalArgumentException(key + " 平面缩放须为正数");
            if (number(s, "rotation.x", 0) != 0 || number(s, "rotation.y", 0) != 0
                || number(s, "scale.z", 1) != 1)
                throw new IllegalArgumentException(key + " 不支持三维变换");
            double rotation = number(s, "rotation.z", 0);
            if (type.equals("group")) {
                if (rotation != 0) throw new IllegalArgumentException(key + " 分组暂不支持旋转");
                flatten(s.getConfigurationSection("children"), x, y, z, nx, ny, out);
                continue;
            }
            if (!Set.of("rectangle", "frame", "line", "text", "image").contains(type))
                throw new IllegalArgumentException(key + " 元素尚不支持：" + type + "；模型请勿当成平面图片");
            double w = number(s, "width", type.equals("text") ? 0 : 16);
            double h = number(s, "height", type.equals("text") ? 0 : 16);
            double thickness = number(s, "thickness", 1);
            if ((!type.equals("text") && (w <= 0 || h <= 0)) || thickness <= 0)
                throw new IllegalArgumentException(key + " 宽高/线宽须为正数");
            String color = s.getString("color", "#FFFFFF");
            if (!color.matches("#[0-9a-fA-F]{6}")) throw new IllegalArgumentException("color须为#RRGGBB");
            int argb = integer(s, "opacity", 255, 0, 255) << 24 | Integer.parseInt(color.substring(1), 16);
            String alignment = s.getString("alignment", "center");
            if (!Set.of("left", "center", "right").contains(alignment)) throw new IllegalArgumentException("alignment非法");
            String source = s.getString("source", "");
            if (type.equals("image") && source.isBlank()) throw new IllegalArgumentException(key + " 缺少source");
            int textSize = integer(s, "size", 8, 1, 128);
            int lines = integer(s, "max-lines", 0, 0, 32);
            if (s.contains("line-width")) w = number(s, "line-width", 0);
            if ((s.contains("line-width") || lines > 0) && (!type.equals("text") || w <= 0 || lines == 0))
                throw new IllegalArgumentException(key + " line-width与max-lines须同时用于文字");
            Interaction interaction = null;
            if (interactive) {
                interaction = new Interaction(s.getBoolean("clickable", true), s.getString("permission", ""),
                    strings(s.get("tooltip")), clickActions(s), doubleActions(s));
            }
            if (s.getBoolean("reuse-texture", false) && !type.equals("image"))
                throw new IllegalArgumentException(key + " reuse-texture仅用于image");
            out.add(new Node(s.getCurrentPath(), type, x, y, z, nx, ny, w, h, argb,
                s.getString("content", ""), source, alignment, textSize,
                thickness, rotation, 9, 18, List.of(), lines, interaction, s.getBoolean("reuse-texture", false)));
        }
    }
}
