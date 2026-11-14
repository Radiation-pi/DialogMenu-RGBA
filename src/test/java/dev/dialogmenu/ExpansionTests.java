package dev.dialogmenu;

import java.util.*;
import java.util.concurrent.atomic.AtomicInteger;
import org.bukkit.configuration.file.YamlConfiguration;
import static dev.dialogmenu.MenuModel.*;

/** 求值工作量、同帧一致性和点击动态校验，不替代外部脚本引擎。 */
final class ExpansionTests {
    private static int checks;
    private static void check(boolean value, String message) {
        checks++;
        if (!value) throw new AssertionError(message);
    }
    static int run() throws Exception {
        var yaml = new YamlConfiguration();
        yaml.loadFromString("""
            variables:
              status: {type: papi, source: '%status%', fallback: unavailable}
              caption: {type: literal, source: '{player}:{status}'}
              alias: {type: state, source: caption}
              mapped: {type: papi, source: '%mapped%', fallback: 'no', values: {'yes': ACTIVE}}
              number: {type: papi, source: '%number%', max-length: 5}
              external: {type: external, source: title}
              saved: {type: skript, source: saved}
              missing: {type: skript, source: missing, fallback: fallback}
              earlier: {type: literal, source: '{later}'}
              later: {type: literal, source: L}
            """);
        for (int i = 0; i < 73; i++) yaml.createSection("variables.unused-" + i,
            Map.of("type", "papi", "source", "%unused_" + i + "%"));
        Map<String, String> data = new HashMap<>(Map.of("%status%", "READY", "%mapped%", "yes", "%number%", "123456"));
        var calls = new LinkedHashMap<String, Integer>();
        AtomicInteger scripts = new AtomicInteger();
        var plan = new MenuExpansion.Plan(yaml.getConfigurationSection("variables"));
        java.util.function.Supplier<MenuExpansion> frame = () -> new MenuExpansion(
            Map.of("player", "Tester"), plan, Map.of("title", "Title"),
            (source, fallback) -> {
                calls.merge(source, 1, Integer::sum);
                return data.getOrDefault(source, fallback);
            }, source -> { scripts.incrementAndGet(); return source.equals("saved") ? "stored" : null; });
        var first = frame.get();
        check(first.apply("static tell: close").equals("static tell: close"), "Static text is unchanged");
        check(calls.isEmpty() && scripts.get() == 0, "Static text must not invoke external sources");
        check(first.apply("{alias} {caption} {status}").equals("Tester:READY Tester:READY READY"),
            "Literal and state dependencies preserve declaration order");
        for (int i = 0; i < 100; i++)
            check(first.apply("{status}").equals("READY"), "Same-frame text, hover and action agree");
        check(calls.equals(Map.of("%status%", 1)), "Referenced source is read once; unused variables are not read");
        check(first.apply("{mapped} {number} {external} {saved} {missing}")
            .equals("ACTIVE MAX Title stored fallback"), "Mappings, number limits and fallback remain intact");
        check(first.apply("{earlier}").equals("L"), "Later substitutions retain ordered expansion");
        data.put("%status%", "CHANGED");
        check(first.apply("{status}").equals("READY"), "Frame remains a consistent snapshot");
        check(frame.get().apply("{status}").equals("CHANGED"), "Next frame reads new data, not cached data");
        check(first.apply("%direct%").equals("%direct%"), "Unresolved direct placeholder is retained");
        first.apply("%direct%");
        check(calls.get("%direct%") == 1, "Repeated direct placeholder text is read once per frame");
        for (int i = 0; i < 73; i++) check(!calls.containsKey("%unused_" + i + "%"), "Unused PAPI stays unread");
        indexedBindings();
        targets();
        System.out.println("PASS expansion checks=" + checks);
        return checks;
    }
    private static void indexedBindings() throws Exception {
        var yaml = new YamlConfiguration();
        yaml.loadFromString("""
            variables:
              first: {type: external, source: data}
              second: {type: literal, source: end}
              name: {type: literal, source: second}
              back: {type: literal, source: '{missing}'}
              same: {type: literal, source: '{same} then {first}'}
            """);
        var plan = new MenuExpansion.Plan(yaml.getConfigurationSection("variables"));
        Map<String, String> base = Map.of("same", "base");
        var reads = new ArrayList<String>();
        var frame = new MenuExpansion(base, plan, Map.of("data", "{second}"),
            (s, fallback) -> { reads.add(s); return fallback; }, s -> null);
        check(frame.apply("{second}/{first}/{first}").equals("end/end/end"), "Injected later tokens must resolve in order");
        check(frame.apply("{{name}}").equals("{second}"), "Injected earlier token must not be revisited");
        check(frame.apply("{same}").equals("base then end"), "Base position and shadowed literal must keep their order");
        check(frame.apply("{unknown}/%raw%").equals("{unknown}/%raw%") && reads.equals(List.of("{unknown}/%raw%")),
            "Unknown tokens must survive into direct PAPI");
        var other = new MenuExpansion(base, plan, Map.of("data", "other"), (s, f) -> f, s -> null);
        check(other.apply("{first}").equals("other") && frame.apply("{first}").equals("end"),
            "Players sharing a definition must not share evaluated values");
        yaml.createSection("variables.brace}key", Map.of("type", "literal", "source", "legacy"));
        var legacy = new MenuExpansion(base, new MenuExpansion.Plan(yaml.getConfigurationSection("variables")),
            Map.of(), (s, f) -> f, s -> null);
        check(legacy.apply("{brace}key}").equals("legacy"), "Literal matching of existing nonstandard names changed");
    }
    private static void targets() throws Exception {
        var yaml = new YamlConfiguration();
        yaml.loadFromString("""
            schema-version: 1
            id: target
            canvas: {width: 300, height: 180}
            frontend:
              unrelated: {type: text, content: OTHER, actions: ['tell: {unrelated}']}
              target: {type: text, content: TARGET, actions: ['tell: {value}'], permission: target.use}
            """);
        var menu = LayoutStore.parse("target", yaml).menu();
        var value = new String[]{"first"};
        var allowed = new boolean[]{true};
        var calls = new ArrayList<String>();
        Context context = new Context() {
            public String expand(String text) {
                calls.add(text);
                return text.replace("{value}", value[0]);
            }
            public Icon item(int slot) { return null; }
            public boolean permitted(String permission) { return permission.isBlank() || allowed[0]; }
        };
        UUID token = UUID.randomUUID();
        var region = LayoutStore.interactiveRegions(menu).getLast();
        var target = ClickGestures.Target.of(token, menu, region, context);
        calls.clear();
        check(target.present(token, menu, context), "Current target can execute");
        check(calls.equals(List.of("tell: {value}")), "Revalidation expands only clicked control, not other controls");
        value[0] = "changed";
        check(!target.present(token, menu, context), "Changed dynamic action rejects the old target");
        allowed[0] = false;
        check(!target.present(token, menu, context), "Permission is still rechecked");
        allowed[0] = true;
        check(!target.present(UUID.randomUUID(), menu, context), "Replaced session rejects old target");
    }
}
