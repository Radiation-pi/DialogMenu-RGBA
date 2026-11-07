package dev.dialogmenu;

import java.io.File;
import java.lang.reflect.Proxy;
import java.nio.file.Files;
import java.util.*;
import java.util.function.Consumer;
import java.util.logging.Logger;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.entity.Player;
import org.bukkit.plugin.Plugin;

/** 验证真实绑定会话，仅替代 Paper 的界面发送通道。 */
public final class BindingTests {
    private static int checks;
    private static void check(boolean value, String message) {
        checks++;
        if (!value) throw new AssertionError(message);
    }
    private static void rejects(Throwing action) throws Exception {
        try { action.run(); }
        catch (IllegalArgumentException expected) { checks++; return; }
        throw new AssertionError("Invalid collection update must be rejected");
    }
    private interface Throwing { void run() throws Exception; }
    private static final class Fixture implements MenuSessions.Host {
        final UUID id = UUID.randomUUID();
        boolean enabled = true, online = true, permitted = true, failPresentation;
        final MenuSessions sessions = new MenuSessions(this);
        final List<String> selected = new ArrayList<>();
        final List<DialogMenuClosedEvent.Reason> closed = new ArrayList<>();
        final Player player = proxy(Player.class, (name, args) -> switch (name) {
            case "getUniqueId" -> id;
            case "getName" -> "Tester";
            case "isOnline" -> online;
            case "hasPermission" -> permitted;
            default -> null;
        });
        final Plugin owner = proxy(Plugin.class, (name, args) -> name.equals("isEnabled") ? enabled : null);
        LayoutStore.Document source, shown;
        int presentations;
        Consumer<Map<String, String>> submit;
        Runnable cancel;
        DialogMenuBinding binding;
        Fixture() throws Exception {
            var yaml = new YamlConfiguration();
            yaml.loadFromString("""
                schema-version: 1
                id: fixture
                collections:
                  blueprints: {external: true}
                forms:
                  create:
                    title: Create
                    fields:
                      name: {label: Name, max-length: 24}
                  details:
                    title: Details
                    fields:
                      name: {label: Name, max-length: 24}
                      description: {label: Description, initial: Default, max-length: 128}
                pages:
                  catalog:
                    canvas: {width: 300, height: 180}
                    frontend:
                      title: {type: text, content: Catalog}
                    flow-lists:
                      rows:
                        collection: blueprints
                        row: '{entry.id} {entry.name} {entry.size}'
                """);
            source = MenuTemplate.load("fixture", yaml).get("catalog");
            bind(selected::add);
        }
        void bind(Consumer<String> callback) throws Exception {
            binding = sessions.bind(owner, player, source, 300, Map.of(), callback, closed::add);
        }
        public DialogMenuApi getMenuApi() { return null; }
        public File getDataFolder() { return new File("build/fixture/plugins/DialogMenu"); }
        public Logger getLogger() { return Logger.getLogger("BindingTests"); }
        public void closeApplication(Player player) { sessions.remove(player); }
        public void closeFromApi(Player player) { sessions.remove(player, DialogMenuClosedEvent.Reason.CLOSED); }
        public void suspendApplication(Player player) {}
        public void later(Runnable action) { throw new AssertionError("External bindings do not use Skript polling"); }
        public void executeActions(Player player, List<String> actions) { throw new AssertionError(actions); }
        public void showApplication(Player player, LayoutStore.Document document, int width, List<String> history) {
            if (failPresentation) throw new IllegalArgumentException("Presentation failed");
            shown = document;
            presentations++;
        }
        public void showForm(Player player, MenuTemplate.FormSpec form,
                             Consumer<Map<String, String>> submitted, Runnable cancelled) {
            submit = submitted;
            cancel = cancelled;
        }
    }
    private interface Invocation { Object invoke(String name, Object[] args); }
    private static <T> T proxy(Class<T> type, Invocation invocation) {
        return type.cast(Proxy.newProxyInstance(type.getClassLoader(), new Class<?>[]{type},
            (object, method, args) -> {
                if (method.getName().equals("toString")) return type.getSimpleName() + "Fixture";
                if (method.getName().equals("hashCode")) return System.identityHashCode(object);
                if (method.getName().equals("equals")) return object == args[0];
                Object value = invocation.invoke(method.getName(), args);
                if (value == null && method.getReturnType() == boolean.class) return false;
                return value;
            }));
    }
    private static DialogMenuEntry row(int index) {
        return new DialogMenuEntry("bp-" + index, "Blueprint " + index, "", Map.of("size", "10x20"));
    }
    public static void main(String[] args) throws Exception {
        Files.createDirectories(java.nio.file.Path.of("build"));
        collections();
        forms();
        lifecycle();
        tokens();
        System.out.println("PASS " + checks + " binding/form checks");
    }
    private static void collections() throws Exception {
        var f = new Fixture();
        var rows = new ArrayList<DialogMenuEntry>();
        for (int i = 0; i < 130; i++) rows.add(row(i));
        check(f.sessions.updateCollection(f.binding, "blueprints", rows), "Collection update succeeds");
        check(f.shown.flowRows().size() == 130, "Long lists are not truncated to canvas slots");
        check(f.shown.flowRows().getLast().text().equals("bp-129 Blueprint 129 10x20"), "Last row displays fields");
        rows.add(row(130));
        f.sessions.updateCollection(f.binding, "blueprints", rows);
        check(f.shown.flowRows().size() == 131, "Added blueprint appears without layout edits");
        f.sessions.entry(f.player, "blueprints bp-130");
        check(f.selected.equals(List.of("bp-130")), "Selection returns stable ID, not name/index");
        f.sessions.entry(f.player, "blueprints absent");
        check(f.selected.size() == 1, "Unknown IDs cannot invoke plugin callbacks");
        rejects(() -> f.sessions.updateCollection(f.binding, "blueprints", List.of(row(1), row(1))));
        rejects(() -> f.sessions.updateCollection(f.binding, "unknown", List.of()));
        f.failPresentation = true;
        rejects(() -> f.sessions.updateCollection(f.binding, "blueprints", List.of(row(2))));
        f.failPresentation = false;
        f.sessions.refresh(f.player);
        check(f.shown.flowRows().size() == 131, "Failed update preserves previous collection");
        f.sessions.updateCollection(f.binding, "blueprints", List.of(
            new DialogMenuEntry("real", "Real", "", Map.of("id", "forged", "name", "Forged", "size", "1x2"))));
        check(f.shown.flowRows().getFirst().text().equals("real Real 1x2"), "Display fields cannot replace reserved identity");
        f.sessions.updateCollection(f.binding, "blueprints", List.of());
        check(f.shown.flowRows().isEmpty(), "Collection can be emptied");
        f.sessions.entry(f.player, "blueprints real");
        check(f.selected.size() == 1, "Removed entries cannot invoke callbacks");
        f.sessions.updateCollection(f.binding, "blueprints", List.of(row(1)));
        f.permitted = false;
        f.sessions.entry(f.player, "blueprints bp-1");
        check(f.selected.size() == 1, "Revoked permission prevents row callbacks");
        check(f.sessions.active(f.binding), "Revoking permission does not prevent owner cleanup");
    }
    private static void forms() throws Exception {
        var f = new Fixture();
        var submitted = new ArrayList<Map<String, String>>();
        f.bind(id -> f.sessions.openForm(f.binding, "create", submitted::add));
        f.sessions.updateCollection(f.binding, "blueprints", List.of(row(1)));
        int before = f.presentations;
        f.sessions.entry(f.player, "blueprints bp-1");
        check(f.presentations == before, "Click refresh cannot overwrite a form opened in callback");
        f.sessions.updateCollection(f.binding, "blueprints", List.of(row(2)));
        f.sessions.refresh(f.player);
        check(f.presentations == before, "Background updates cannot overwrite active form input");
        Consumer<Map<String, String>> stale = f.submit;
        f.cancel.run();
        check(submitted.isEmpty(), "Cancel/Escape never invokes submission");
        check(f.shown.flowRows().getFirst().id().equals("bp-2"), "Cancel restores latest list data");
        stale.accept(Map.of("name", "Cancelled"));
        check(submitted.isEmpty(), "Stale submit after cancellation is ignored");
        f.sessions.openForm(f.binding, "create", submitted::add);
        f.submit.accept(Map.of("name", "City"));
        check(submitted.equals(List.of(Map.of("name", "City"))), "Submit delivers keyed field values");
        f.submit.accept(Map.of("name", "Replay"));
        check(submitted.size() == 1, "Form callback is single-use");
        f.sessions.openForm(f.binding, "create", submitted::add);
        f.submit.accept(Map.of());
        check(submitted.size() == 1, "Missing declared input cannot create city");
        f.sessions.openForm(f.binding, "create", submitted::add);
        f.submit.accept(Map.of("name", "x".repeat(25)));
        check(submitted.size() == 1, "Forged overlength input cannot reach plugin");
        f.sessions.openForm(f.binding, "create", values -> {
            submitted.add(values);
            f.sessions.openForm(f.binding, "create", submitted::add);
        });
        before = f.presentations;
        f.submit.accept(Map.of("name", "First"));
        check(f.presentations == before, "Submit callback may open next form without refresh overwriting it");
        f.cancel.run();
        f.sessions.openForm(f.binding, "create", submitted::add);
        stale = f.submit;
        f.sessions.suspend(f.binding, true);
        stale.accept(Map.of("name", "Suspended"));
        check(submitted.size() == 2, "Suspending invalidates pending form");
        f.sessions.suspend(f.binding, false);
        f.sessions.openForm(f.binding, "create", submitted::add);
        f.permitted = false;
        f.submit.accept(Map.of("name", "NoPermission"));
        check(submitted.size() == 2, "Submit rechecks current permission");
        f.permitted = true;
        f.sessions.openForm(f.binding, "details", submitted::add);
        f.submit.accept(Map.of("name", "Multi"));
        check(submitted.size() == 2, "Every declared field must be present");
        f.sessions.openForm(f.binding, "details", submitted::add);
        f.submit.accept(Map.of("name", "Multi", "description", "Notes", "undeclared", "Ignored"));
        check(submitted.getLast().equals(Map.of("name", "Multi", "description", "Notes")),
            "Multi-field submit returns exactly the declared keys");
        try {
            submitted.getLast().put("name", "Changed");
            throw new AssertionError("Submitted field map must be immutable");
        } catch (UnsupportedOperationException expected) { checks++; }
    }
    private static void lifecycle() throws Exception {
        var f = new Fixture();
        var results = new ArrayList<Map<String, String>>();
        f.sessions.openForm(f.binding, "create", results::add);
        var submit = f.submit;
        var old = f.binding;
        f.bind(f.selected::add);
        submit.accept(Map.of("name", "Old"));
        check(results.isEmpty() && !f.sessions.active(old), "Replacement invalidates old form and binding");
        check(f.closed.equals(List.of(DialogMenuClosedEvent.Reason.REPLACED)), "Replacement notifies once");
        f.sessions.openForm(f.binding, "create", results::add);
        submit = f.submit;
        f.sessions.ownerDisabled(f.owner);
        submit.accept(Map.of("name", "Disabled"));
        check(results.isEmpty() && !f.sessions.active(f.binding), "Owner disable invalidates callback");
        f.bind(f.selected::add);
        f.sessions.openForm(f.binding, "create", results::add);
        submit = f.submit;
        f.sessions.clear(DialogMenuClosedEvent.Reason.RELOAD);
        submit.accept(Map.of("name", "Reloaded"));
        check(results.isEmpty(), "Reload cannot submit old forms");
    }
    private static void tokens() {
        var tokens = new ActionTokens();
        var page = new ActionTokens.Page();
        var results = new ArrayList<Object>();
        page.bind(results::add);
        UUID player = UUID.randomUUID();
        String id = "action/" + page.nonce + "/0";
        tokens.publish(player, page);
        check(tokens.takeResponse(UUID.randomUUID(), id) == null, "Form token rejects another player");
        var response = proxy(io.papermc.paper.dialog.DialogResponseView.class,
            (name, args) -> name.equals("getText") ? "City" : null);
        tokens.takeResponse(player, id).accept(response);
        check(results.equals(List.of(response)), "Token delivers original native response");
        check(tokens.takeResponse(player, id) == null, "Submit token cannot replay");
        tokens.publish(player, page);
        tokens.publish(player, new ActionTokens.Page());
        check(tokens.takeResponse(player, id) == null, "Replaced dialog rejects old submit token");
        tokens.publish(player, page);
        tokens.remove(player);
        check(tokens.takeResponse(player, id) == null, "Closed dialog rejects submit token");
    }
}
