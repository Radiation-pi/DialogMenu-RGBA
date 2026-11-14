package dev.dialogmenu;

import java.io.File;
import java.lang.reflect.Proxy;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.function.Consumer;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.entity.Player;
import org.bukkit.plugin.Plugin;

/** 验证首屏数据、失败前保留旧会话和替换后的绑定隔离。 */
final class InitialCollectionCheck {
    static void run() throws Exception {
        var yaml = new YamlConfiguration();
        yaml.loadFromString("""
            schema-version: 1
            id: choices
            collections:
              buttons: {external: true}
            pages:
              choices:
                canvas: {width: 300, height: 16}
                frontend:
                  label: {type: text, content: Hello}
                flow-lists:
                  buttons: {collection: buttons, row: '{entry.name}', empty: Empty}
            """);
        var document = MenuTemplate.load("choices", yaml).get("choices");
        UUID id = UUID.randomUUID();
        Player player = (Player) Proxy.newProxyInstance(InitialCollectionCheck.class.getClassLoader(),
            new Class<?>[]{Player.class}, (proxy, method, args) -> switch (method.getName()) {
                case "getUniqueId" -> id;
                case "getName" -> "Player";
                case "isOnline", "hasPermission" -> true;
                default -> null;
            });
        Plugin owner = (Plugin) Proxy.newProxyInstance(InitialCollectionCheck.class.getClassLoader(),
            new Class<?>[]{Plugin.class}, (proxy, method, args) -> method.getName().equals("isEnabled") ? true : null);
        class Host implements MenuSessions.Host {
            MenuSessions sessions;
            int shown, closes, replacements;
            boolean fail;
            List<LayoutStore.FlowRow> rows;
            public DialogMenuApi getMenuApi() { return null; }
            public File getDataFolder() { return new File("test/plugins/DialogMenu"); }
            public java.util.logging.Logger getLogger() { return java.util.logging.Logger.getAnonymousLogger(); }
            public void closeApplication(Player p) { closes++; sessions.remove(p); }
            public void replaceApplication(Player p) { replacements++; sessions.remove(p, DialogMenuClosedEvent.Reason.REPLACED); }
            public void closeFromApi(Player p) { closeApplication(p); }
            public void suspendApplication(Player p) {}
            public void showApplication(Player p, LayoutStore.Document doc, int width, List<String> history) throws Exception {
                if (fail) throw new java.io.IOException("presentation failed");
                shown++; rows = doc.flowRows();
            }
            public void showForm(Player p, MenuTemplate.FormSpec form, Consumer<Map<String, String>> yes, Runnable no) {}
            public void executeActions(Player p, List<String> actions) {}
            public void later(Runnable action) { action.run(); }
        }
        var host = new Host();
        var sessions = new MenuSessions(host);
        host.sessions = sessions;
        var reasons = new ArrayList<DialogMenuClosedEvent.Reason>();
        var rows = new ArrayList<>(List.of(new DialogMenuEntry("one", "One", "", Map.of())));
        var first = sessions.bind(owner, player, document, 300, Map.of(), "buttons", rows, key -> {}, reasons::add);
        rows.clear();
        if (host.shown != 1 || host.closes != 0 || host.rows.size() != 1 || !host.rows.getFirst().text().equals("One"))
            throw new AssertionError("first presentation must already contain the supplied rows");
        for (String collection : List.of("unknown", "buttons")) {
            try {
                var entry = new DialogMenuEntry("duplicate", "Duplicate", "", Map.of());
                sessions.bind(owner, player, document, 300, Map.of(), collection, List.of(entry, entry), key -> {}, reasons::add);
                throw new AssertionError("invalid initial collection accepted");
            } catch (IllegalArgumentException expected) {}
            if (!sessions.active(first) || !reasons.isEmpty() || host.replacements != 1 || host.shown != 1)
                throw new AssertionError("validation failure must preserve the current binding");
        }
        sessions.refresh(player);
        if (host.rows.size() != 1 || !host.rows.getFirst().id().equals("one"))
            throw new AssertionError("initial rows must be copied");
        var second = sessions.bind(owner, player, document, 300, Map.of(), key -> {}, reasons::add);
        if (host.rows.size() != 1 || !host.rows.getFirst().text().equals("Empty") || host.closes != 0
            || !reasons.equals(List.of(DialogMenuClosedEvent.Reason.REPLACED)) || sessions.active(first))
            throw new AssertionError("legacy bind or replacement notification changed");
        if (sessions.updateCollection(first, "buttons", List.of()))
            throw new AssertionError("replaced binding must not update the new session");
        host.fail = true;
        try {
            sessions.bind(owner, player, document, 300, Map.of(), "buttons", List.of(), key -> {}, reasons::add);
            throw new AssertionError("presentation failure was swallowed");
        } catch (java.io.IOException expected) {}
        if (sessions.active(player) || host.closes != 1 || sessions.active(second))
            throw new AssertionError("failed presentation must close the stale screen");
    }
}
