package dev.dialogmenu;

import java.io.File;
import java.lang.reflect.Proxy;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.function.Consumer;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.entity.Player;

/** 不同页面族切换时替换会话，不发送关闭界面。 */
public final class SessionReplacementCheck {
    private static LayoutStore.Document document(String id) throws Exception {
        var yaml = new YamlConfiguration();
        yaml.loadFromString("schema-version: 1\nid: " + id + "\npages:\n  " + id
            + ":\n    canvas: {width: 300, height: 180}\n    frontend:\n"
            + "      label: {type: text, content: 'Hello'}\n");
        return MenuTemplate.load(id, yaml).get(id);
    }
    public static void main(String[] args) throws Exception {
        UUID id = UUID.randomUUID();
        Player player = (Player) Proxy.newProxyInstance(SessionReplacementCheck.class.getClassLoader(),
            new Class<?>[]{Player.class}, (proxy, method, arguments) -> switch (method.getName()) {
                case "getUniqueId" -> id;
                case "getName" -> "Test";
                case "isOnline", "hasPermission" -> true;
                default -> null;
            });
        class Host implements MenuSessions.Host {
            MenuSessions sessions;
            int replacements, closes, shown;
            String current;
            public DialogMenuApi getMenuApi() { return null; }
            public File getDataFolder() { return new File("test/plugins/DialogMenu"); }
            public java.util.logging.Logger getLogger() { return java.util.logging.Logger.getAnonymousLogger(); }
            public void closeApplication(Player p) { closes++; sessions.remove(p); }
            public void replaceApplication(Player p) { replacements++; sessions.remove(p, DialogMenuClosedEvent.Reason.REPLACED); }
            public void closeFromApi(Player p) { closes++; }
            public void suspendApplication(Player p) {}
            public void showApplication(Player p, LayoutStore.Document doc, int width, List<String> history) {
                shown++; current = doc.menu().id();
            }
            public void showForm(Player p, MenuTemplate.FormSpec f, Consumer<Map<String, String>> yes, Runnable no) {}
            public void executeActions(Player p, List<String> actions) {}
            public void later(Runnable action) { action.run(); }
        }
        var host = new Host();
        var sessions = new MenuSessions(host);
        host.sessions = sessions;
        var main = document("main");
        var shop = document("shop");
        sessions.open(player, main, 300, List.of());
        sessions.open(player, shop, 300, List.of("main"));
        if (host.closes != 0 || host.replacements != 2 || host.shown != 2 || !host.current.equals("shop"))
            throw new AssertionError("cross-family transition closed or failed to present");
        sessions.open(player, shop, 300, List.of("main"));
        if (host.closes != 0 || host.replacements != 2 || host.shown != 3)
            throw new AssertionError("same-family refresh replaced the session");
        sessions.open(player, main, 300, List.of());
        if (host.closes != 0 || host.replacements != 3 || !host.current.equals("main"))
            throw new AssertionError("return transition closed or failed to present");
    }
}
