package dev.dialogmenu;

import java.util.List;
import java.util.UUID;
import org.bukkit.configuration.file.YamlConfiguration;
import static dev.dialogmenu.MenuModel.*;

/** 玩家独立画布倍率，不改变文字偏好或菜单逻辑坐标。 */
final class CanvasScale {
    static final List<Double> OPTIONS = List.of(0.5, 0.75, 1.0, 2.0);
    static final String CHOICES = "0.5、0.75、1、2";
    @FunctionalInterface interface Check { void run() throws Exception; }

    static double selected(YamlConfiguration preferences, UUID player) {
        double value = preferences.getDouble(key(player), 1);
        return OPTIONS.contains(value) ? value : 1;
    }

    static double select(YamlConfiguration preferences, UUID player, String input, Check check) throws Exception {
        double value;
        try { value = Double.parseDouble(input.replaceFirst("[xX]$", "")); }
        catch (NumberFormatException error) { throw new IllegalArgumentException("画布倍率请选择：" + CHOICES); }
        if (!OPTIONS.contains(value)) throw new IllegalArgumentException("画布倍率请选择：" + CHOICES);
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

    private static String key(UUID player) { return "canvas-scales." + player; }

    static Context context(Context base, double scale) {
        return new Context() {
            public String expand(String text) { return base.expand(text); }
            public Icon item(int slot) { return base.item(slot); }
            public boolean permitted(String permission) { return base.permitted(permission); }
            public double textScale() { return base.textScale(); }
            public double canvasScale() { return scale; }
        };
    }
}
