package dev.dialogmenu;

import java.util.List;
import java.util.UUID;
import org.bukkit.configuration.file.YamlConfiguration;
import static dev.dialogmenu.MenuModel.*;

/** 玩家独立画布倍率，不改变文字偏好或菜单逻辑坐标。 */
final class CanvasScale {
    static final double MIN = 0.8;
    static final double MAX = 2.2;
    static final List<Double> OPTIONS = List.of(0.75, 1.0);
    static final List<Double> DEFAULTS = List.of(0.8, 1.0, 2.0);
    @FunctionalInterface interface Check { void run() throws Exception; }

    static double selected(YamlConfiguration preferences, UUID player) {
        return selected(preferences, player, 1);
    }

    static double selected(YamlConfiguration preferences, UUID player, double fallback) {
        double value = preferences.getDouble(key(player), fallback);
        return valid(value) ? value : valid(fallback) ? fallback : 1;
    }

    static double select(YamlConfiguration preferences, UUID player, String input, Check check) throws Exception {
        double value;
        try { value = Double.parseDouble(input.replaceFirst("[xX]$", "")); }
        catch (NumberFormatException error) { throw new IllegalArgumentException("画布倍率须为0.8..2.2"); }
        if (!valid(value)) throw new IllegalArgumentException("画布倍率须为0.8..2.2");
        String key = key(player);
        Object previous = preferences.get(key);
        preferences.set(key, value);
        try { check.run(); }
        catch (Exception error) {
            preferences.set(key, previous);
            throw error;
        }
        return value;
    }

    static boolean valid(double value) {
        return Double.isFinite(value) && value >= MIN && value <= MAX;
    }

    static List<Double> options(YamlConfiguration yaml, String key, List<Double> fallback) {
        if (yaml.contains(key) && !yaml.isList(key))
            throw new IllegalArgumentException(key + "须为0.5..4的倍率列表");
        var result = new java.util.ArrayList<Double>();
        for (Object value : yaml.getList(key, fallback)) {
            if (!(value instanceof Number n) || !Double.isFinite(n.doubleValue())
                || n.doubleValue() < .5 || n.doubleValue() > 4)
                throw new IllegalArgumentException(key + "须为0.5..4的倍率列表");
            if (!result.contains(n.doubleValue())) result.add(n.doubleValue());
        }
        if (result.isEmpty()) throw new IllegalArgumentException(key + "不能为空");
        return List.copyOf(result);
    }

    static List<String> completions(List<Double> options) {
        return options.stream().map(value -> java.math.BigDecimal.valueOf(value).stripTrailingZeros()
            .toPlainString()).toList();
    }

    static String key(UUID player) { return "canvas-scales." + player; }

    static Context context(Context base, double scale) {
        return new Context() {
            public String expand(String text) { return base.expand(text); }
            public Icon item(int slot) { return base.item(slot); }
            public boolean permitted(String permission) { return base.permitted(permission); }
            public net.kyori.adventure.text.Component playerHead() { return base.playerHead(); }
            public double textScale() { return base.textScale(); }
            public double canvasScale() { return scale; }
        };
    }
}
