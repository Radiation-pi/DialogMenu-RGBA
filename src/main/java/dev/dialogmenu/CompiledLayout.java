package dev.dialogmenu;

import java.util.*;
import java.util.function.UnaryOperator;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.file.YamlConfiguration;
import static dev.dialogmenu.MenuModel.*;

/** 配置只读编译；动态结构求新值后仅重建发生变化的分支。 */
final class CompiledLayout {
    private enum Kind { TEXT, NUMBER, VISIBLE }
    private record Field(String path, String source, Kind kind) {
        Object resolve(UnaryOperator<String> expand, boolean catalog) {
            if (kind == Kind.VISIBLE) {
                String value = catalog ? "true" : expand.apply(source);
                if (!Set.of("true", "false").contains(value))
                    throw new IllegalArgumentException("visible-if须解析为true/false：" + value);
                return Boolean.parseBoolean(value);
            }
            String value = expand.apply(source);
            if (kind == Kind.TEXT) return value;
            try {
                double number = Double.parseDouble(value);
                if (Double.isFinite(number)) return number;
            } catch (NumberFormatException ignored) {}
            throw new IllegalArgumentException(path + " 须解析为有限数字");
        }
    }
    private static final class Branch {
        private final YamlConfiguration source = new YamlConfiguration();
        private final List<Field> fields = new ArrayList<>();
        private final Map<List<Object>, List<Node>> cache = new LinkedHashMap<>(4, .75f, true) {
            @Override protected boolean removeEldestEntry(Map.Entry<List<Object>, List<Node>> eldest) {
                return size() > 8;
            }
        };

        Branch(String id, ConfigurationSection node) {
            MenuTemplate.copy(node, source.createSection("frontend." + id));
            compile(source.getConfigurationSection("frontend." + id));
        }
        private void compile(ConfigurationSection node) {
            String path = node.getCurrentPath();
            if (node.contains("visible-if")) {
                if (!(node.get("visible-if") instanceof String value))
                    throw new IllegalArgumentException("visible-if须为布尔变量字符串");
                fields.add(new Field(path + ".visible", value, Kind.VISIBLE));
                node.set("visible-if", null);
            }
            if (node.getString("type", "").equals("image"))
                fields.add(new Field(path + ".source", node.getString("source", ""), Kind.TEXT));
            if (node.contains("color"))
                fields.add(new Field(path + ".color", node.getString("color"), Kind.TEXT));
            for (String key : List.of("x", "y", "offset.x", "offset.y", "offset.z",
                "width", "height", "size", "line-width"))
                if (node.get(key) instanceof String value)
                    fields.add(new Field(path + "." + key, value, Kind.NUMBER));
            var children = node.getConfigurationSection("children");
            if (children != null) for (String key : children.getKeys(false)) {
                var child = children.getConfigurationSection(key);
                if (child != null) compile(child);
            }
        }
        List<Node> nodes(UnaryOperator<String> expand, boolean catalog) {
            List<Object> values = new ArrayList<>(fields.size());
            for (Field field : fields) values.add(field.resolve(expand, catalog));
            List<Object> key = List.copyOf(values);
            synchronized (cache) {
                List<Node> cached = cache.get(key);
                if (cached != null) return cached;
            }
            YamlConfiguration yaml = new YamlConfiguration();
            MenuTemplate.copy(source, yaml);
            for (int i = 0; i < fields.size(); i++) {
                Field field = fields.get(i);
                // visible-if=true 不覆盖显式 visible=false。
                if (field.kind() != Kind.VISIBLE || Boolean.FALSE.equals(values.get(i)))
                    yaml.set(field.path(), values.get(i));
            }
            List<Node> nodes = new ArrayList<>();
            LayoutStore.flatten(yaml.getConfigurationSection("frontend"), 0, 0, 0, 1, 1, nodes);
            List<Node> result = List.copyOf(nodes);
            synchronized (cache) { cache.put(key, result); }
            return result;
        }
    }
    private final LayoutStore.Header header;
    private final List<Branch> branches = new ArrayList<>();

    CompiledLayout(String id, YamlConfiguration yaml) {
        header = LayoutStore.header(id, yaml);
        var frontend = yaml.getConfigurationSection("frontend");
        if (frontend != null) for (String key : frontend.getKeys(false)) {
            LayoutStore.id(key);
            var node = frontend.getConfigurationSection(key);
            if (node == null) throw new IllegalArgumentException("frontend元素须为对象：" + key);
            branches.add(new Branch(key, node));
        }
    }
    LayoutStore.Document document(UnaryOperator<String> expand, boolean catalog) {
        List<Node> nodes = new ArrayList<>();
        for (Branch branch : branches) nodes.addAll(branch.nodes(expand, catalog));
        return header.document(nodes);
    }
}
