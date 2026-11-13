package dev.dialogmenu;

import io.papermc.paper.dialog.DialogResponseView;
import io.papermc.paper.registry.data.dialog.input.DialogInput;
import io.papermc.paper.registry.data.dialog.input.TextDialogInput;
import java.util.*;
import java.util.regex.Pattern;
import net.kyori.adventure.text.Component;
import org.bukkit.configuration.ConfigurationSection;

/** 原生控件数据和提交；遮挡模式使用真实排版，不单独移动命中。 */
final class NativeControls {
    private NativeControls() {}
    static final Spec EMPTY = new Spec(List.of(), List.of(), false, -1);
    private static final Pattern INPUT = Pattern.compile("\\{input\\.([a-z0-9_-]+)}");
    record Field(String id, String type, String label, int width, String initial, int maxLength,
                 boolean multiline, int height, int maxLines, float min, float max, float step,
                 boolean labelVisible) {}
    record Button(String id, String label, int width, String permission, List<String> tooltip,
                  List<String> actions, boolean close, int z) {}
    record Spec(List<Field> inputs, List<Button> buttons, boolean overlay, int bodyRows) {
        boolean empty() { return inputs.isEmpty() && buttons.isEmpty(); }
    }

    static Spec parse(ConfigurationSection parent) {
        if (!parent.contains("native-controls")) return EMPTY;
        var section = object(parent, "native-controls");
        LayoutStore.keys(section, Set.of("inputs", "buttons", "overlay", "body-rows"));
        boolean overlay = bool(section, "overlay", false);
        int bodyRows = section.contains("body-rows")
            ? LayoutStore.integer(section, "body-rows", 1, 1, 113) : -1;
        if (!overlay && bodyRows != -1)
            throw new IllegalArgumentException("body-rows仅用于原生栏遮挡模式");
        List<Field> fields = new ArrayList<>();
        if (section.contains("inputs")) {
            var inputs = object(section, "inputs");
            for (String id : inputs.getKeys(false)) {
                LayoutStore.id(id);
                var field = object(inputs, id);
                String type = field.getString("type", "text");
                if (!Set.of("text", "slider").contains(type))
                    throw new IllegalArgumentException(id + " 原生控件type须为text或slider");
                LayoutStore.keys(field, type.equals("text")
                    ? Set.of("type", "label", "width", "initial", "max-length", "multiline", "height", "max-lines", "label-visible", "z")
                    : Set.of("type", "label", "width", "initial", "min", "max", "step", "z"));
                int width = LayoutStore.integer(field, "width", 280, 1, 1024);
                // 兼容旧配置；输入控件固定置顶，不再保存可排序的层级。
                LayoutStore.integer(field, "z", 0, -1_000_000, 1_000_000);
                if (type.equals("text")) {
                    boolean multiline = bool(field, "multiline", false);
                    if (!multiline && (field.contains("height") || field.contains("max-lines")))
                        throw new IllegalArgumentException(id + " height/max-lines仅用于multiline");
                    fields.add(new Field(id, type, field.getString("label", id), width,
                        field.getString("initial", ""), LayoutStore.integer(field, "max-length", 256, 1, 1024),
                        multiline, LayoutStore.integer(field, "height", 40, 20, 512),
                        LayoutStore.integer(field, "max-lines", 4, 1, 1024), 0, 0, 0,
                        bool(field, "label-visible", true)));
                } else {
                    float min = value(field, "min", 0), max = value(field, "max", 100);
                    float initial = value(field, "initial", min), step = value(field, "step", 1);
                    if (min >= max || initial < min || initial > max || step <= 0)
                        throw new IllegalArgumentException(id + " 须满足min < max、initial在范围内、step > 0");
                    fields.add(new Field(id, type, field.getString("label", id), width,
                        Float.toString(initial), 0, false, 20, 1, min, max, step, true));
                }
            }
        }
        List<Button> buttons = new ArrayList<>();
        if (section.contains("buttons")) {
            var actions = object(section, "buttons");
            for (String id : actions.getKeys(false)) {
                LayoutStore.id(id);
                var button = object(actions, id);
                LayoutStore.keys(button, Set.of("label", "width", "permission", "tooltip", "actions", "close", "z"));
                buttons.add(new Button(id, button.getString("label", "提交"),
                    LayoutStore.integer(button, "width", 280, 1, 1024), button.getString("permission", ""),
                    LayoutStore.strings(button.get("tooltip")), LayoutStore.actions(button.get("actions"), false),
                    bool(button, "close", false), LayoutStore.integer(button, "z", 0, -1_000_000, 1_000_000)));
            }
        }
        if (buttons.size() > 1024) throw new IllegalArgumentException("原生按钮最多1024个");
        if (!fields.isEmpty() && buttons.isEmpty())
            throw new IllegalArgumentException("原生输入至少需要一个buttons提交按钮");
        return new Spec(List.copyOf(fields), List.copyOf(buttons), overlay, bodyRows);
    }

    static List<DialogInput> inputs(Spec spec, MenuModel.Context context, Map<String, String> submitted) {
        return spec.inputs().stream().map(field -> {
            String initial = submitted.getOrDefault(field.id(), context.expand(field.initial()));
            if (field.type().equals("slider"))
                return (DialogInput) DialogInput.numberRange(field.id(), Component.text(context.expand(field.label())),
                    field.min(), field.max()).width(field.width()).step(field.step())
                    .initial(Float.parseFloat(initial)).build();
            var input = DialogInput.text(field.id(), Component.text(context.expand(field.label())))
                .width(field.width()).labelVisible(field.labelVisible()).initial(initial).maxLength(field.maxLength());
            if (field.multiline())
                input.multiline(TextDialogInput.MultilineOptions.create(field.maxLines(), field.height()));
            return (DialogInput) input.build();
        }).toList();
    }

    static Map<String, String> values(Spec spec, DialogResponseView response) {
        if (!spec.inputs().isEmpty() && response == null)
            throw new IllegalArgumentException("原生提交缺少输入数据");
        Map<String, String> values = new LinkedHashMap<>();
        for (Field field : spec.inputs()) {
            String value;
            if (field.type().equals("text")) {
                value = response.getText(field.id());
                if (value == null || value.length() > field.maxLength()
                    || !field.multiline() && (value.contains("\n") || value.contains("\r"))
                    || field.multiline() && value.split("\\n", -1).length > field.maxLines())
                    throw new IllegalArgumentException(field.id() + " 文本长度或行数不合法");
            } else {
                Float number = response.getFloat(field.id());
                if (number == null || !Float.isFinite(number) || number < field.min() || number > field.max())
                    throw new IllegalArgumentException(field.id() + " 滑块值超出范围");
                value = Float.toString(number);
            }
            values.put(field.id(), value);
        }
        return Map.copyOf(values);
    }

    static String expand(String text, Map<String, String> values) {
        return INPUT.matcher(text).replaceAll(match -> java.util.regex.Matcher.quoteReplacement(
            values.getOrDefault(match.group(1), match.group())));
    }
    private static ConfigurationSection object(ConfigurationSection parent, String key) {
        var section = parent.getConfigurationSection(key);
        if (section == null) throw new IllegalArgumentException(parent.getCurrentPath() + "." + key + " 须为对象");
        return section;
    }
    private static boolean bool(ConfigurationSection section, String key, boolean fallback) {
        if (section.contains(key) && !(section.get(key) instanceof Boolean))
            throw new IllegalArgumentException(key + " 须为true/false");
        return section.getBoolean(key, fallback);
    }
    private static float value(ConfigurationSection section, String key, double fallback) {
        float value = (float) LayoutStore.number(section, key, fallback);
        if (!Float.isFinite(value)) throw new IllegalArgumentException(key + " 超出原生浮点数范围");
        return value;
    }
}
