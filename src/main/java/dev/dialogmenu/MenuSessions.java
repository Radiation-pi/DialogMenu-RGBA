package dev.dialogmenu;

import ch.njol.skript.lang.function.Functions;
import ch.njol.skript.variables.Variables;
import java.math.BigDecimal;
import java.util.*;
import org.bukkit.Bukkit;
import org.bukkit.entity.Player;
import org.bukkit.plugin.Plugin;
import java.util.function.Consumer;
import static dev.dialogmenu.DialogMenuClosedEvent.Reason;

/** 管理通用 YAML 状态、占位符以及带确认流程的 Skript 列表绑定。 */
final class MenuSessions {
    interface Host {
        DialogMenuApi getMenuApi();
        java.io.File getDataFolder();
        java.util.logging.Logger getLogger();
        void closeApplication(Player player);
        void replaceApplication(Player player);
        void closeFromApi(Player player);
        void suspendApplication(Player player);
        void showApplication(Player player, LayoutStore.Document document, int width, List<String> history) throws Exception;
        void showForm(Player player, MenuTemplate.FormSpec form, Consumer<Map<String, String>> submitted, Runnable cancelled);
        void executeActions(Player player, List<String> actions);
        void later(Runnable action);
    }
    private final Host plugin;
    private final Map<UUID, Session> sessions = new HashMap<>();
    private record Evaluation(Session session, MenuExpansion expansion) {}
    private final Map<UUID, Evaluation> evaluations = new HashMap<>();
    private static final class Session {
        final MenuTemplate.Definition definition;
        final Map<String, String> values;
        final Map<String, List<String>> lists = new LinkedHashMap<>();
        final Map<String, List<DialogMenuEntry>> externalCollections = new LinkedHashMap<>();
        final Map<String, String> external = new LinkedHashMap<>();
        Plugin owner;
        DialogMenuBinding binding;
        Consumer<String> callback;
        Consumer<Reason> closed;
        LayoutStore.Document document;
        int width;
        boolean busy, suspended;
        long presentations;
        Object form;
        List<String> history;
        Session(LayoutStore.Document document, int width, List<String> history) {
            this.document = document;
            this.definition = document.template().definition;
            this.width = width;
            this.history = history;
            this.values = new LinkedHashMap<>(definition.initial);
        }
    }
    MenuSessions(Host plugin) { this.plugin = plugin; }
    private static Object variable(String key) {
        if (!Bukkit.getPluginManager().isPluginEnabled("Skript")) return null;
        return Variables.getVariable(key.toLowerCase(Locale.ROOT), null, false);
    }
    private static String key(Player player, String pattern) {
        return pattern.replace("{uuid}", player.getUniqueId().toString()).replace("{player}", player.getName());
    }
    private static void call(String name, Object... args) {
        if (!Bukkit.getPluginManager().isPluginEnabled("Skript")) throw new IllegalStateException("Skript未启用");
        var function = Functions.getFunction(name);
        if (function == null) throw new IllegalStateException("Skript函数不存在：" + name);
        try { function.execute(Arrays.stream(args).map(a -> new Object[]{a}).toArray(Object[][]::new)); }
        finally { function.resetReturnValue(); }
    }
    private static List<String> list(Player player, MenuTemplate.CollectionSpec spec) {
        List<String> result = new ArrayList<>();
        var values = Variables.getVariableIterator(key(player, spec.list()), false, null);
        while (values.hasNext()) result.add(String.valueOf(values.next().getSecond()));
        return List.copyOf(new LinkedHashSet<>(result));
    }
    boolean open(Player player, LayoutStore.Document document, int width, List<String> history) throws Exception {
        Session session = sessions.get(player.getUniqueId());
        if (session != null && session.definition == document.template().definition) {
            if (session.busy) return true;
            session.document = document;
            session.width = width;
            session.history = history;
            session.form = null;
            show(player, session);
            return true;
        }
        plugin.replaceApplication(player);
        Session next = new Session(document, width, history);
        sessions.put(player.getUniqueId(), next);
        next.busy = true;
        try { load(player, next, next.definition.collections.entrySet().stream()
            .filter(entry -> !entry.getValue().external()).map(Map.Entry::getKey).toList(), 0, false, 0); }
        catch (Exception error) { plugin.closeApplication(player); throw error; }
        return true;
    }
    DialogMenuBinding bind(Plugin owner, Player player, LayoutStore.Document document, int width,
                           Map<String, String> values, Consumer<String> callback, Consumer<Reason> closed) throws Exception {
        return bind(owner, player, document, width, values, null, List.of(), callback, closed);
    }
    DialogMenuBinding bind(Plugin owner, Player player, LayoutStore.Document document, int width,
                           Map<String, String> values, String collection, List<DialogMenuEntry> entries,
                           Consumer<String> callback, Consumer<Reason> closed) throws Exception {
        if (document.template().definition.collections.values().stream().anyMatch(spec -> !spec.external()))
            throw new IllegalArgumentException("External binding requires external-only collections");
        Session next = new Session(document, width, List.of());
        validateExternal(next, values);
        // 在替换旧会话前验证并复制首屏集合，不能先显示空列表再补内容。
        if (collection != null) next.externalCollections.put(LayoutStore.id(collection),
            validateEntries(next, collection, entries));
        next.external.putAll(values);
        next.owner = owner;
        next.callback = callback;
        next.closed = closed;
        next.binding = new DialogMenuBinding(plugin.getMenuApi(), player, next);
        plugin.replaceApplication(player);
        sessions.put(player.getUniqueId(), next);
        try { show(player, next); }
        catch (Exception error) {
            plugin.closeApplication(player);
            throw error;
        }
        return next.binding;
    }
    private static void validateExternal(Session session, Map<String, String> values) {
        Set<String> names = new HashSet<>();
        var variables = session.definition.variables;
        if (variables != null) for (String key : variables.getKeys(false)) {
            var spec = variables.getConfigurationSection(key);
            if (spec.getString("type", "").equals("external")) names.add(spec.getString("source", key));
        }
        for (String key : values.keySet()) if (!names.contains(key))
            throw new IllegalArgumentException("External variable not declared in YAML: " + key);
    }
    private Session bound(DialogMenuBinding binding) {
        Session session = sessions.get(binding.player.getUniqueId());
        return session != null && session == binding.identity && session.binding == binding
            && session.owner.isEnabled() && binding.player.isOnline() ? session : null;
    }
    private static boolean permitted(Player player, Session session) {
        return player.hasPermission("dialogmenu.use") && (session.document.menu().permission().isBlank()
            || player.hasPermission(session.document.menu().permission()));
    }
    boolean active(DialogMenuBinding binding) { return bound(binding) != null; }
    boolean update(DialogMenuBinding binding, Map<String, String> values) throws Exception {
        Session session = bound(binding);
        if (session == null) return false;
        validateExternal(session, values);
        Map<String, String> previous = new LinkedHashMap<>(session.external);
        session.external.putAll(values);
        try { show(binding.player, session); }
        catch (Exception error) {
            session.external.clear();
            session.external.putAll(previous);
            throw error;
        }
        return bound(binding) != null;
    }
    boolean updateCollection(DialogMenuBinding binding, String name, List<DialogMenuEntry> entries) throws Exception {
        Session session = bound(binding);
        if (session == null) return false;
        name = LayoutStore.id(name);
        List<DialogMenuEntry> validated = validateEntries(session, name, entries);
        List<DialogMenuEntry> previous = session.externalCollections.put(name, validated);
        try { show(binding.player, session); }
        catch (Exception error) {
            if (previous == null) session.externalCollections.remove(name);
            else session.externalCollections.put(name, previous);
            throw error;
        }
        return bound(binding) != null;
    }
    private static List<DialogMenuEntry> validateEntries(Session session, String name, List<DialogMenuEntry> entries) {
        name = LayoutStore.id(name);
        var spec = session.definition.collections.get(name);
        if (spec == null || !spec.external())
            throw new IllegalArgumentException("Collection is not declared external in YAML: " + name);
        Set<String> ids = new HashSet<>();
        for (DialogMenuEntry entry : entries)
            if (!ids.add(entry.id())) throw new IllegalArgumentException("Duplicate collection entry ID: " + entry.id());
        return List.copyOf(entries);
    }
    boolean openForm(DialogMenuBinding binding, String name, Consumer<Map<String, String>> submitted) {
        Session session = bound(binding);
        if (session == null || session.suspended || !permitted(binding.player, session)) return false;
        var form = session.definition.forms.get(LayoutStore.id(name));
        if (form == null) throw new IllegalArgumentException("Form is not declared in YAML: " + name);
        Object identity = new Object();
        session.form = identity;
        Consumer<Map<String, String>> complete = values -> {
            if (bound(binding) == null || !permitted(binding.player, session)
                || session.suspended || session.form != identity) return;
            session.form = null;
            try {
                long before = session.presentations;
                if (values != null) {
                    Map<String, String> declared = new LinkedHashMap<>();
                    boolean valid = true;
                    for (var field : form.fields()) {
                        String value = values.get(field.key());
                        if (value == null || value.length() > field.maxLength()) { valid = false; break; }
                        declared.put(field.key(), value);
                    }
                    if (valid) submitted.accept(Map.copyOf(declared));
                }
                if (bound(binding) != null && session.presentations == before) show(binding.player, session);
            } catch (Exception error) { fail(binding.player, session, error); }
        };
        try { plugin.showForm(binding.player, form, complete, () -> complete.accept(null)); }
        catch (RuntimeException error) { session.form = null; throw error; }
        return bound(binding) != null;
    }
    boolean navigate(DialogMenuBinding binding, LayoutStore.Document document) throws Exception {
        Session session = bound(binding);
        if (session == null) return false;
        if (document.template() == null || document.template().definition != session.definition)
            throw new IllegalArgumentException("Binding navigation must stay in its YAML family");
        LayoutStore.Document previous = session.document;
        session.form = null;
        session.document = document;
        try { show(binding.player, session); }
        catch (Exception error) { session.document = previous; throw error; }
        return bound(binding) != null;
    }
    boolean suspend(DialogMenuBinding binding, boolean value) throws Exception {
        Session session = bound(binding);
        if (session == null) return false;
        if (session.suspended == value) return true;
        session.suspended = value;
        if (value) {
            session.form = null;
            plugin.suspendApplication(binding.player);
        }
        else try { show(binding.player, session); }
        catch (Exception error) { session.suspended = true; throw error; }
        return true;
    }
    boolean suspended(Player player) {
        Session session = sessions.get(player.getUniqueId());
        return session != null && (session.suspended || session.form != null);
    }
    void callback(Player player, String value) {
        Session session = sessions.get(player.getUniqueId());
        if (session == null || session.binding == null || bound(session.binding) == null
            || !permitted(player, session) || suspended(player))
            throw new IllegalStateException("No active external binding");
        session.callback.accept(value);
    }
    void ownerDisabled(Plugin owner) {
        for (Session session : List.copyOf(sessions.values()))
            if (session.owner == owner) plugin.closeFromApi(session.binding.player);
    }
    private boolean current(Player player, Session session) {
        return player.isOnline() && sessions.get(player.getUniqueId()) == session;
    }
    private void load(Player player, Session session, List<String> names, int index, boolean started, int ticks) {
        if (!current(player, session)) return;
        try {
            if (index == names.size()) {
                session.busy = false;
                show(player, session);
                return;
            }
            String name = names.get(index);
            var spec = session.definition.collections.get(name);
            if (!started) call(spec.load(), player);
            if (!spec.loading().equals(variable(key(player, spec.status())))) {
                if (!Boolean.TRUE.equals(variable(key(player, spec.ready()))))
                    throw new IllegalStateException(session.definition.message("load-failed"));
                session.lists.put(name, list(player, spec));
                load(player, session, names, index + 1, false, 0);
            } else if (ticks >= spec.timeout()) throw new IllegalStateException(session.definition.message("load-failed"));
            else plugin.later(() -> load(player, session, names, index, true, ticks + 1));
        } catch (Exception error) { fail(player, session, error); }
    }
    private void show(Player player, Session session) throws Exception {
        if (session.suspended || session.form != null) return;
        try (var ignored = frame(player)) {
            plugin.showApplication(player, session.document.template().document(session.lists,
                session.externalCollections, text -> expand(player, text)),
                session.width, session.history);
            session.presentations++;
        }
    }
    void state(Player player, String action, String value) throws Exception {
        Session session = sessions.get(player.getUniqueId());
        if (session == null || session.busy || suspended(player)) return;
        session.definition.changeState(session.values, action, value);
        // 整条动作链结束后再刷新，避免“set + open”中途使自身令牌失效。
    }
    void refresh(Player player) throws Exception {
        Session session = sessions.get(player.getUniqueId());
        if (session != null && !session.busy) show(player, session);
    }
    void entry(Player player, String value) throws Exception {
        Session session = sessions.get(player.getUniqueId());
        if (session == null || session.busy || suspended(player)) return;
        String[] parts = value.split(" ", 2);
        if (parts.length != 2) throw new IllegalArgumentException("entry须为collection id");
        var spec = session.definition.collections.get(parts[0]);
        if (spec == null) throw new IllegalArgumentException("collection未定义：" + parts[0]);
        if (spec.external()) {
            if (session.binding == null || bound(session.binding) == null || !permitted(player, session)) return;
            if (session.externalCollections.getOrDefault(parts[0], List.of()).stream()
                .noneMatch(entry -> entry.id().equals(parts[1]))) return;
            long before = session.presentations;
            session.callback.accept(parts[1]);
            if (current(player, session) && session.presentations == before) show(player, session);
            return;
        }
        if (Boolean.parseBoolean(session.values.get(spec.editState()))) {
            List<String> next = session.definition.changed(parts[0], session.lists.getOrDefault(parts[0], List.of()), parts[1]);
            if (variable(key(player, spec.token())) != null && variable(key(player, spec.result())) == null)
                throw new IllegalStateException(session.definition.message("busy"));
            String token = UUID.randomUUID().toString();
            session.busy = true;
            try {
                call(spec.write(), player, next.isEmpty() ? spec.empty() : String.join(",", next), token);
                awaitSave(player, session, parts[0], spec, token, 0);
            } catch (Exception error) { session.busy = false; throw error; }
        } else {
            var entry = session.definition.entries.get(parts[1]);
            if (entry == null) state(player, "notice", session.definition.message("unknown"));
            else plugin.executeActions(player, entry.actions());
        }
    }
    private void awaitSave(Player player, Session session, String name, MenuTemplate.CollectionSpec spec,
                           String token, int ticks) {
        if (!current(player, session)) return;
        try {
            if (!token.equals(variable(key(player, spec.token()))))
                throw new IllegalStateException(session.definition.message("unconfirmed"));
            Object result = variable(key(player, spec.result()));
            if (result == null && ticks < spec.timeout()) {
                plugin.later(() -> awaitSave(player, session, name, spec, token, ticks + 1));
                return;
            }
            session.busy = false;
            if (result == null) {
                session.values.put("notice", session.definition.message("unconfirmed"));
            } else {
                Variables.setVariable(key(player, spec.token()), null, null, false);
                Variables.setVariable(key(player, spec.result()), null, null, false);
                if (Boolean.TRUE.equals(result)) {
                    session.lists.put(name, list(player, spec));
                    plugin.executeActions(player, spec.saved());
                } else session.values.put("notice", session.definition.message("save-failed"));
            }
            if (current(player, session)) show(player, session);
        } catch (Exception error) { fail(player, session, error); }
    }
    String expand(Player player, String text) {
        Evaluation evaluation = evaluations.get(player.getUniqueId());
        return (evaluation == null ? expansion(player) : evaluation.expansion()).apply(text);
    }
    final class ExpansionFrame implements AutoCloseable {
        private final UUID player;
        private final Evaluation previous;
        private ExpansionFrame(Player player) {
            this.player = player.getUniqueId();
            previous = evaluations.get(this.player);
            Session session = sessions.get(this.player);
            if (previous == null || previous.session() != session)
                evaluations.put(this.player, new Evaluation(session, expansion(player)));
        }
        @Override public void close() {
            if (previous == null) evaluations.remove(player);
            else evaluations.put(player, previous);
        }
    }
    ExpansionFrame frame(Player player) { return new ExpansionFrame(player); }
    private MenuExpansion expansion(Player player) {
        Session session = sessions.get(player.getUniqueId());
        Map<String, String> values = new LinkedHashMap<>();
        values.put("player", player.getName());
        values.put("uuid", player.getUniqueId().toString());
        values.put("server", plugin.getDataFolder().getAbsoluteFile().getParentFile().getParentFile().getName());
        if (session != null) values.putAll(session.values);
        return new MenuExpansion(values, session == null ? null : session.definition.variables,
            session == null ? Map.of() : session.external,
            (text, fallback) -> placeholder(player, text, fallback),
            source -> Objects.toString(variable(key(player, source)), null));
    }
    static String shortNumber(String value, int limit) {
        if (!value.matches("-?[0-9.,]+")) return "-";
        if (value.length() <= limit) return value;
        try {
            String result = new BigDecimal(value.replace(",", "")).round(new java.math.MathContext(3)).toString();
            return result.length() <= limit ? result : "MAX";
        } catch (NumberFormatException ignored) { return "-"; }
    }
    private static String placeholder(Player player, String text, String fallback) {
        var papi = Bukkit.getPluginManager().getPlugin("PlaceholderAPI");
        if (papi == null || !papi.isEnabled()) return fallback;
        try {
            String value = (String) Class.forName("me.clip.placeholderapi.PlaceholderAPI", true, papi.getClass().getClassLoader())
                .getMethod("setPlaceholders", org.bukkit.OfflinePlayer.class, String.class).invoke(null, player, text);
            return value == null || value.equals(text) ? fallback : value;
        } catch (ReflectiveOperationException error) { throw new IllegalStateException("PAPI读取失败", error); }
    }
    private void fail(Player player, Session session, Exception error) {
        if (!sessions.remove(player.getUniqueId(), session)) return;
        plugin.closeApplication(player);
        plugin.getLogger().log(java.util.logging.Level.WARNING, "YAML data binding failed: " + session.definition.id, error);
        player.sendMessage("§cDialog：" + error.getMessage());
    }
    void remove(Player player) { remove(player, Reason.REPLACED); }
    void remove(Player player, Reason reason) {
        Session session = sessions.remove(player.getUniqueId());
        notifyClosed(session, reason);
    }
    private void notifyClosed(Session session, Reason reason) {
        if (session == null || session.closed == null) return;
        try { session.closed.accept(reason); }
        catch (RuntimeException error) {
            plugin.getLogger().log(java.util.logging.Level.WARNING, "Dialog binding cleanup failed", error);
        }
    }
    boolean active(Player player) { return sessions.containsKey(player.getUniqueId()); }
    void clear() { clear(Reason.RELOAD); }
    void clear(Set<String> menus) {
        List<Session> removed = new ArrayList<>();
        sessions.entrySet().removeIf(entry -> {
            if (!menus.contains(entry.getValue().document.menu().id())) return false;
            removed.add(entry.getValue());
            return true;
        });
        for (Session session : removed) notifyClosed(session, Reason.RELOAD);
    }
    void clear(Reason reason) {
        var previous = List.copyOf(sessions.values());
        sessions.clear();
        for (Session session : previous) notifyClosed(session, reason);
    }
}
