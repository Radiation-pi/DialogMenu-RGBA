package dev.dialogmenu;

import java.util.*;
import java.util.function.BiFunction;
import java.util.function.Function;
import java.util.function.UnaryOperator;
import org.bukkit.configuration.ConfigurationSection;

/** 单次呈现的按需变量快照，不跨帧或业务动作缓存。 */
final class MenuExpansion implements UnaryOperator<String> {
    private record Variable(int index, ConfigurationSection spec) {}
    private record Placeholder(String source, String fallback) {}
    private final Map<String, String> base;
    private final Map<String, String> external;
    private final Map<String, Variable> variables = new LinkedHashMap<>();
    private final Map<String, String> resolved = new HashMap<>();
    private final Map<Placeholder, String> placeholders = new HashMap<>();
    private final Set<String> order = new LinkedHashSet<>();
    private final BiFunction<String, String, String> papi;
    private final Function<String, String> skript;

    MenuExpansion(Map<String, String> base, ConfigurationSection variables, Map<String, String> external,
                  BiFunction<String, String, String> papi, Function<String, String> skript) {
        this.base = new LinkedHashMap<>(base);
        this.external = new LinkedHashMap<>(external);
        this.papi = papi;
        this.skript = skript;
        order.addAll(base.keySet());
        if (variables != null) for (String name : variables.getKeys(false)) {
            this.variables.put(name, new Variable(this.variables.size(), variables.getConfigurationSection(name)));
            order.add(name);
        }
    }

    @Override public String apply(String text) {
        String expanded = replace(text, variables.size());
        return expanded.contains("%") ? placeholder(expanded, expanded) : expanded;
    }

    private String replace(String text, int before) {
        for (String name : order) {
            String token = "{" + name + "}";
            if (!text.contains(token)) continue;
            String value = value(name, before);
            if (value != null) text = text.replace(token, value);
        }
        return text;
    }

    private String value(String name, int before) {
        Variable variable = variables.get(name);
        if (variable == null || variable.index() >= before) return base.get(name);
        if (resolved.containsKey(name)) return resolved.get(name);
        var spec = variable.spec();
        String source = spec.getString("source", ""), fallback = spec.getString("fallback", "");
        String value = switch (spec.getString("type", "")) {
            // 保持原先按声明顺序展开 literal/state 的语义。
            case "literal" -> replace(source, variable.index());
            case "state" -> Objects.toString(value(source, variable.index()), fallback);
            case "papi" -> placeholder(source, fallback);
            case "skript" -> Objects.toString(skript.apply(source), fallback);
            case "external" -> external.getOrDefault(spec.getString("source", name), fallback);
            default -> fallback;
        };
        var mappings = spec.getConfigurationSection("values");
        if (mappings != null) value = mappings.getString(value, fallback);
        int limit = spec.getInt("max-length", 0);
        if (limit > 0) value = MenuSessions.shortNumber(value, limit);
        resolved.put(name, value);
        return value;
    }

    private String placeholder(String text, String fallback) {
        return placeholders.computeIfAbsent(new Placeholder(text, fallback), key -> papi.apply(text, fallback));
    }
}
