package dev.dialogmenu;

import java.util.*;
import net.kyori.adventure.key.Key;
import net.kyori.adventure.nbt.api.BinaryTagHolder;
import net.kyori.adventure.text.event.ClickEvent;
import io.papermc.paper.dialog.DialogResponseView;
import java.util.function.Consumer;

final class ActionTokens {
    static final class Page {
        final UUID nonce = UUID.randomUUID();
        private record Action(Consumer<DialogResponseView> run, Object gesture) {}
        final List<Action> actions = new ArrayList<>();
        private Object pendingGesture;
        ClickEvent bind(Runnable action) {
            return bind(action, null);
        }
        ClickEvent bind(Runnable action, Object gesture) {
            return bind(response -> action.run(), gesture);
        }
        ClickEvent bind(Consumer<DialogResponseView> action) {
            return bind(action, null);
        }
        private ClickEvent bind(Consumer<DialogResponseView> action, Object gesture) {
            int index = actions.size();
            actions.add(new Action(action, gesture));
            return ClickEvent.custom(Key.key("dialogmenu", "action/" + nonce + "/" + index),
                BinaryTagHolder.binaryTagHolder("{}"));
        }
    }
    private final Map<UUID, Page> pages = new HashMap<>();
    void publish(UUID player, Page page) { pages.put(player, page); }
    Page remove(UUID player) { return pages.remove(player); }
    void clear() { pages.clear(); }
    Consumer<DialogResponseView> takeResponse(UUID player, String identifier) {
        String[] parts = identifier.split("/", -1);
        Page page = pages.get(player);
        if (page == null || parts.length != 3 || !parts[0].equals("action")
            || !page.nonce.toString().equals(parts[1])) return null;
        int index;
        try { index = Integer.parseInt(parts[2]); } catch (NumberFormatException ignored) { return null; }
        if (index < 0 || index >= page.actions.size()) return null;
        var action = page.actions.get(index);
        // 第一次手势的随机令牌仅保留到匹配的第二次点击，且只能使用一次。
        if (action.gesture == null || action.gesture.equals(page.pendingGesture)) pages.remove(player);
        else page.pendingGesture = action.gesture;
        return action.run;
    }
}
