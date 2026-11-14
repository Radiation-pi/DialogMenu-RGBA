package dev.dialogmenu;

import java.awt.image.BufferedImage;
import java.util.*;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.event.ClickEvent;
import net.kyori.adventure.text.serializer.plain.PlainTextComponentSerializer;
import static dev.dialogmenu.MenuModel.*;

/** 原生头像的交互覆盖使用实际绘制行，纯显示头像不创建命中区域。 */
public final class AvatarInteractionCheck {
    static List<Component> hits(Component component) {
        List<Component> out = new ArrayList<>();
        if (component.font() != null && component.font().equals(RgbaHits.FONT)) out.add(component);
        for (var child : component.children()) out.addAll(hits(child));
        return out;
    }
    static void check(boolean value, String message) { if (!value) throw new AssertionError(message); }
    public static void main(String[] args) throws Exception {
        var parsed = LayoutStore.loadText("probe", """
            schema-version: 1
            id: probe
            canvas: {width: 40, height: 30}
            frontend:
              head:
                type: player-head
                clickable: true
                tooltip: 'Profile {player}'
                actions: 'command: profile'
            """).get("probe").menu().nodes().getFirst();
        check(parsed.interaction() != null && parsed.interaction().region(parsed).hasActions(), "YAML 头像交互解析");
        var context = new Context() {
            public String expand(String text) { return text.replace("{player}", "Alex"); }
            public Icon item(int slot) { return null; }
            public boolean permitted(String permission) { return true; }
        };
        var click = ClickEvent.runCommand("/example");
        for (int mode = 0; mode < 3; mode++) {
            Interaction interaction = switch (mode) {
                case 0 -> null;
                case 1 -> new Interaction(true, "", List.of("Profile {player}"), List.of("command: profile"));
                default -> new Interaction(false, "", List.of("Profile {player}"), List.of());
            };
            Node head = new Node("head", "player-head", 0, 3, 0, 1, 8, 8,
                0xffffffff, "", "", "center", 8, 1, 0, 9, 18, List.of(), 0, interaction, false);
            Menu menu = new Menu("probe", "", 40, 30, List.of(head), List.of());
            Rendered frame = new Rendered(new BufferedImage(40, 30, BufferedImage.TYPE_INT_ARGB), List.of(), List.of());
            int[] bound = {0};
            Component output = new SpriteCanvas().encode(menu, frame, context, null, r -> { bound[0]++; return click; }).component();
            List<Component> nodes = hits(output);
            if (mode == 0) { check(nodes.isEmpty() && bound[0] == 0, "pure display"); continue; }
            // 顶部为第 8 像素，8×8 区域跨两行，共 4 列 × 2 段。
            check(nodes.size() == 8, "hit tiles");
            check(bound[0] == (mode == 1 ? 1 : 0), "only actions are bound");
            for (Component node : nodes) {
                check(Objects.equals(node.clickEvent(), mode == 1 ? click : null), "click event");
                check(node.hoverEvent() != null && PlainTextComponentSerializer.plainText().serialize(
                    (Component) node.hoverEvent().value()).equals("Profile Alex"), "expanded hover");
            }
        }
        System.out.println("AvatarInteractionCheck passed");
    }
}
