package dev.dialogmenu;

import java.util.*;
import java.util.function.UnaryOperator;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.file.YamlConfiguration;
import static dev.dialogmenu.MenuModel.*;

/** YAML 菜单家族，提供可复用的目录和列表网格，不包含特定应用 ID 或业务函数。 */
final class MenuTemplate {
    private static final java.util.regex.Pattern TEXT_VARIABLE =
        java.util.regex.Pattern.compile("\\{([^{}]+)}|%[^%\\s]+%");
    record Entry(String label, String image, List<String> actions, TextureRegion atlas, String model, List<String> tooltip) {
        Entry(String label, String image, List<String> actions, TextureRegion atlas, String model) {
            this(label, image, actions, atlas, model, List.of(label));
        }
    }
    record CollectionSpec(boolean external, String load, String list, String status, String loading, String ready,
                          String write, String token, String result, int capacity, int timeout,
                          String empty, String editState, List<String> saved) {}
    record FlowListSpec(String collection, String title, String row, String empty) {}
    record FormField(String key, String label, String initial, int maxLength) {}
    record FormSpec(String title, String submit, String cancel, List<FormField> fields) {}
    static final class Definition {
        final String id;
        final ConfigurationSection variables;
        final MenuExpansion.Plan expansionPlan;
        final Map<String, String> initial = new LinkedHashMap<>();
        final Map<String, Entry> entries = new LinkedHashMap<>();
        final Map<String, CollectionSpec> collections = new LinkedHashMap<>();
        final Map<String, FormSpec> forms = new LinkedHashMap<>();
        final Map<String, String> messages = new LinkedHashMap<>();
        final List<Map<String, String>> samples = new ArrayList<>();
        final String characters;
        private final Map<String, List<String>> previewChoices = new LinkedHashMap<>();
        private final Preview defaultPreview;
        private final List<Preview> samplePreviews;
        Definition(String id, YamlConfiguration yaml) {
            this.id = id;
            variables = yaml.getConfigurationSection("variables");
            expansionPlan = variables == null ? MenuExpansion.Plan.EMPTY : new MenuExpansion.Plan(variables);
            var state = yaml.getConfigurationSection("state");
            if (state != null) for (String key : state.getKeys(false))
                initial.put(LayoutStore.id(key), state.getString(key, ""));
            var catalog = yaml.getConfigurationSection("entries");
            if (catalog != null) for (String key : catalog.getKeys(false)) {
                if (!key.matches("[a-zA-Z0-9:_-]+")) throw new IllegalArgumentException("entry ID非法：" + key);
                var e = Objects.requireNonNull(catalog.getConfigurationSection(key), "entry须为对象");
                LayoutStore.keys(e, Set.of("label", "image", "actions", "atlas", "model", "tooltip"));
                String model = e.getString("model", "");
                if (!model.isEmpty() && (!model.matches("[a-z0-9_.-]+:[a-z0-9/._-]+")
                    || model.contains("..") || e.contains("image") || e.contains("atlas")))
                    throw new IllegalArgumentException(key + " model须为完整namespace:id，不能同时配置image/atlas");
                entries.put(key, new Entry(e.getString("label", key), e.getString("image", ""),
                    LayoutStore.actions(e.get("actions"), false), LayoutStore.atlas(e, e.getString("image", "")), model,
                    e.contains("tooltip") ? LayoutStore.strings(e.get("tooltip")) : List.of(e.getString("label", key))));
            }
            var sources = yaml.getConfigurationSection("collections");
            if (sources != null) for (String key : sources.getKeys(false)) {
                var s = Objects.requireNonNull(sources.getConfigurationSection(key), "collection须为对象");
                LayoutStore.keys(s, Set.of("external", "load", "list", "status", "loading", "ready", "write", "token", "result",
                    "capacity", "timeout", "empty", "edit-state", "saved"));
                boolean external = s.getBoolean("external", false);
                if (external) {
                    if (s.getKeys(false).size() != 1) throw new IllegalArgumentException(key + " external集合不能配置Skript字段");
                    collections.put(LayoutStore.id(key), new CollectionSpec(true, "", "", "", "", "",
                        "", "", "", 0, 0, "", "", List.of()));
                } else {
                    for (String field : List.of("load", "list", "status", "ready", "write", "token", "result"))
                        if (s.getString(field, "").isBlank()) throw new IllegalArgumentException(key + " 缺少" + field);
                    String edit = s.getString("edit-state", "");
                    if (!initial.containsKey(edit)) throw new IllegalArgumentException("edit-state须在state中定义");
                    collections.put(LayoutStore.id(key), new CollectionSpec(false, s.getString("load"), s.getString("list"),
                        s.getString("status"), s.getString("loading", "loading"), s.getString("ready"),
                        s.getString("write"), s.getString("token"), s.getString("result"),
                        LayoutStore.integer(s, "capacity", 8, 1, 64), LayoutStore.integer(s, "timeout", 320, 1, 1200),
                        s.getString("empty", "none"), edit, LayoutStore.actions(s.get("saved"), false)));
                }
            }
            if (variables != null) for (String key : variables.getKeys(false)) {
                var v = Objects.requireNonNull(variables.getConfigurationSection(key), "variable须为对象");
                LayoutStore.keys(v, Set.of("type", "source", "fallback", "preview", "max-length", "values"));
                if (!Set.of("papi", "skript", "state", "literal", "external").contains(v.getString("type", "")))
                    throw new IllegalArgumentException(key + " variable type非法");
                LayoutStore.integer(v, "max-length", 0, 0, 128);
                List<String> choices = new ArrayList<>();
                for (String field : List.of("preview", "fallback")) choices.add(v.getString(field, ""));
                var mapped = v.getConfigurationSection("values");
                if (mapped != null) for (Object value : mapped.getValues(false).values())
                    choices.add(String.valueOf(value));
                previewChoices.put("{" + key + "}", List.copyOf(choices));
            }
            var text = yaml.getConfigurationSection("messages");
            if (text != null) for (String key : text.getKeys(false)) messages.put(key, text.getString(key, ""));
            var formSources = yaml.getConfigurationSection("forms");
            if (formSources != null) for (String formId : formSources.getKeys(false)) {
                var form = Objects.requireNonNull(formSources.getConfigurationSection(formId), "form须为对象");
                LayoutStore.keys(form, Set.of("title", "submit", "cancel", "fields"));
                var fieldSources = Objects.requireNonNull(form.getConfigurationSection("fields"), "form缺少fields");
                List<FormField> fields = new ArrayList<>();
                for (String key : fieldSources.getKeys(false)) {
                    LayoutStore.id(key);
                    var field = Objects.requireNonNull(fieldSources.getConfigurationSection(key), "form field须为对象");
                    LayoutStore.keys(field, Set.of("label", "initial", "max-length"));
                    fields.add(new FormField(key, field.getString("label", key), field.getString("initial", ""),
                        LayoutStore.integer(field, "max-length", 64, 1, 128)));
                }
                if (fields.isEmpty()) throw new IllegalArgumentException(formId + " form至少需要一个字段");
                forms.put(LayoutStore.id(formId), new FormSpec(form.getString("title", ""),
                    form.getString("submit", "提交"), form.getString("cancel", "取消"), List.copyOf(fields)));
            }
            for (Map<?, ?> sample : yaml.getMapList("samples")) {
                Map<String, String> values = new LinkedHashMap<>();
                for (var entry : sample.entrySet()) {
                    String key = String.valueOf(entry.getKey());
                    if (variables == null || !variables.isConfigurationSection(key))
                        throw new IllegalArgumentException("sample variable未定义：" + key);
                    values.put(key, String.valueOf(entry.getValue()));
                }
                samples.add(Map.copyOf(values));
            }
            characters = yaml.getString("characters", "");
            defaultPreview = preparePreview(Map.of());
            samplePreviews = samples.stream().map(this::preparePreview).toList();
        }
        String message(String key) { return messages.getOrDefault(key, key); }
        void changeState(Map<String, String> values, String action, String value) {
            String[] parts = value.split(" ", 2);
            if (action.equals("set") || action.equals("toggle")) {
                if (!values.containsKey(parts[0])) throw new IllegalArgumentException("state未定义：" + parts[0]);
            }
            switch (action) {
                case "toggle" -> values.put(parts[0], Boolean.toString(!Boolean.parseBoolean(values.get(parts[0]))));
                case "set" -> {
                    if (parts.length != 2) throw new IllegalArgumentException("set须为key value");
                    values.put(parts[0], parts[1]);
                }
                case "notice" -> values.put("notice", value);
                default -> throw new IllegalArgumentException("状态动作不支持：" + action);
            }
        }
        List<String> changed(String collection, List<String> before, String id) {
            CollectionSpec spec = Objects.requireNonNull(collections.get(collection), "collection不存在");
            List<String> next = new ArrayList<>(before);
            if (!next.remove(id)) {
                if (!entries.containsKey(id)) throw new IllegalArgumentException(message("unknown"));
                if (next.size() >= spec.capacity()) throw new IllegalArgumentException(message("full"));
                next.add(id);
            }
            return List.copyOf(next);
        }
        UnaryOperator<String> preview(Map<String, String> state, String player) {
            Preview prepared = state.isEmpty() ? defaultPreview : preparePreview(state);
            return text -> prepared.expand(text, player);
        }
        private Preview preparePreview(Map<String, String> state) {
            Map<String, String> values = new LinkedHashMap<>(initial);
            values.putAll(state);
            if (variables != null) for (String key : variables.getKeys(false)) {
                var v = variables.getConfigurationSection(key);
                String value = v.getString("preview", v.getString("fallback", ""));
                if (state.containsKey(key)) {
                    value = state.get(key);
                    var mapped = v.getConfigurationSection("values");
                    if (mapped != null) value = mapped.getString(value, value);
                }
                else if (v.getString("type", "").equals("state")) {
                    String source = values.getOrDefault(v.getString("source"), "");
                    var mapped = v.getConfigurationSection("values");
                    value = mapped == null ? source : mapped.getString(source, v.getString("fallback", ""));
                }
                else if (v.isConfigurationSection("values"))
                    value = v.getConfigurationSection("values").getString(value, v.getString("fallback", ""));
                values.put(key, value);
            }
            return new Preview(values);
        }
    }
    private static final class Preview {
        private final List<Map.Entry<String, String>> replacements;
        private final Map<String, String> expanded = new LinkedHashMap<>(16, .75f, true) {
            @Override protected boolean removeEldestEntry(Map.Entry<String, String> eldest) {
                return size() > 256;
            }
        };
        Preview(Map<String, String> values) {
            replacements = values.entrySet().stream()
                .map(entry -> Map.entry("{" + entry.getKey() + "}", entry.getValue())).toList();
        }
        String expand(String text, String player) {
            if (text.indexOf('{') < 0) return text;
            String result;
            synchronized (expanded) { result = expanded.get(text); }
            if (result == null) {
                result = text;
                // 保留声明顺序，包括替换值中再次出现后续变量的情况。
                for (var replacement : replacements)
                    result = result.replace(replacement.getKey(), replacement.getValue());
                synchronized (expanded) { expanded.put(text, result); }
            }
            return result.replace("{player}", player).replace("{uuid}", "preview");
        }
    }
    final Definition definition;
    final String page;
    private final CompiledLayout layout;
    private final LayoutStore.Header header;
    private final String yamlText;
    private final List<GridPlan> grids = new ArrayList<>();
    private final Map<String, FlowListSpec> flowLists = new LinkedHashMap<>();
    private record MenuLayout(List<List<Node>> branches, Menu menu) {}
    private volatile MenuLayout latestMenu;
    private MenuTemplate(Definition definition, String page, ConfigurationSection config, int schema) {
        this.definition = definition;
        this.page = page;
        YamlConfiguration yaml = new YamlConfiguration();
        copy(config, yaml);
        yaml.set("id", page);
        yaml.set("schema-version", schema);
        var section = yaml.getConfigurationSection("grids");
        if (section != null) for (String id : section.getKeys(false)) {
            var grid = Objects.requireNonNull(section.getConfigurationSection(id), "grid须为对象");
            LayoutStore.keys(grid, Set.of("collection", "items", "slots", "columns", "x", "y", "step-x", "step-y",
                "width", "height", "background", "icon-x", "icon-y", "icon-size", "model-size", "text-x", "color",
                "empty-label", "empty-actions", "unknown-label"));
            if (!definition.collections.containsKey(grid.getString("collection")))
                throw new IllegalArgumentException("grid collection不存在：" + id);
            int slots = LayoutStore.integer(grid, "slots", 8, 1, 64);
            LayoutStore.integer(grid, "columns", 4, 1, 64);
            for (String field : List.of("width", "height", "step-x", "step-y", "icon-size"))
                if (LayoutStore.number(grid, field, 0) <= 0) throw new IllegalArgumentException("grid " + field + "须为正数");
            if (grid.contains("model-size")) LayoutStore.integer(grid, "model-size", 10, 1, 96);
            if (grid.getString("background", "").isBlank()) throw new IllegalArgumentException("grid缺少background");
            if (grid.contains("items")) {
                List<String> items = LayoutStore.strings(grid.get("items"));
                if (items.size() > slots) throw new IllegalArgumentException("grid items超过slots");
                for (String key : items) if (!definition.entries.containsKey(key))
                    throw new IllegalArgumentException("grid entry不存在：" + key);
            }
            LayoutStore.actions(grid.get("empty-actions"), false);
            grids.add(new GridPlan(grid));
        }
        yaml.set("grids", null);
        var flowSection = yaml.getConfigurationSection("flow-lists");
        if (flowSection != null) for (String id : flowSection.getKeys(false)) {
            var flowConfig = Objects.requireNonNull(flowSection.getConfigurationSection(id), "flow-list须为对象");
            LayoutStore.keys(flowConfig, Set.of("collection", "title", "row", "empty"));
            String collection = LayoutStore.id(flowConfig.getString("collection"));
            CollectionSpec source = definition.collections.get(collection);
            if (source == null || !source.external())
                throw new IllegalArgumentException("flow-list须引用external collection：" + collection);
            flowLists.put(LayoutStore.id(id), new FlowListSpec(collection, flowConfig.getString("title", ""),
                flowConfig.getString("row", "{entry.name}"), flowConfig.getString("empty", "")));
        }
        yaml.set("flow-lists", null);
        yamlText = yaml.saveToString();
        header = LayoutStore.header(page, yaml);
        layout = new CompiledLayout(page, yaml);
    }
    static Map<String, LayoutStore.Document> load(String id, YamlConfiguration yaml) throws Exception {
        LayoutStore.keys(yaml, Set.of("schema-version", "id", "variables", "state", "collections",
            "entries", "messages", "forms", "pages", "characters", "samples"));
        int schema = LayoutStore.integer(yaml, "schema-version", 1, 1, 3);
        if (!id.equals(yaml.getString("id")))
            throw new IllegalArgumentException("应用id须与文件名一致");
        Definition definition = new Definition(id, yaml);
        Map<String, LayoutStore.Document> result = new LinkedHashMap<>();
        var pages = Objects.requireNonNull(yaml.getConfigurationSection("pages"), "缺少pages");
        for (String page : pages.getKeys(false)) {
            MenuTemplate template = new MenuTemplate(definition, LayoutStore.id(page),
                Objects.requireNonNull(pages.getConfigurationSection(page), "page须为对象"), schema);
            result.put(page, template.document(Map.of()));
        }
        return result;
    }
    static void copy(ConfigurationSection source, ConfigurationSection target) {
        for (String key : source.getKeys(false)) {
            if (source.isConfigurationSection(key)) copy(source.getConfigurationSection(key), target.createSection(key));
            else target.set(key, source.get(key));
        }
    }
    static String expand(String text, Map<String, String> values) {
        for (var e : values.entrySet()) text = text.replace("{" + e.getKey() + "}", e.getValue());
        return text;
    }
    LayoutStore.Document document(Map<String, List<String>> lists) throws Exception {
        return document(lists, Map.of(), definition.preview(Map.of(), "Player"));
    }
    LayoutStore.Document document(Map<String, List<String>> lists,
                                  java.util.function.UnaryOperator<String> expand) throws Exception {
        return document(lists, Map.of(), expand);
    }
    LayoutStore.Document document(Map<String, List<String>> lists,
                                  Map<String, List<DialogMenuEntry>> external,
                                  java.util.function.UnaryOperator<String> expand) throws Exception {
        return document(lists, external, expand, false);
    }
    private LayoutStore.Document document(Map<String, List<String>> lists,
                                  Map<String, List<DialogMenuEntry>> external,
                                  java.util.function.UnaryOperator<String> expand, boolean catalog) throws Exception {
        var base = layout.document(expand, catalog);
        List<List<Node>> parts = new ArrayList<>();
        parts.add(base.menu().nodes());
        for (int g = 0; g < grids.size(); g++) parts.add(grids.get(g).nodes(g, lists));
        MenuLayout menuLayout = latestMenu;
        boolean same = menuLayout != null && menuLayout.branches().size() == parts.size();
        if (same) for (int i = 0; i < parts.size(); i++)
            if (parts.get(i) != menuLayout.branches().get(i)) { same = false; break; }
        Menu menu;
        if (same) menu = menuLayout.menu();
        else {
            List<Node> nodes = new ArrayList<>();
            parts.forEach(nodes::addAll);
            nodes.sort(Comparator.comparingDouble(Node::z));
            menu = new Menu(page, base.menu().permission(), base.menu().width(), base.menu().height(),
                List.copyOf(nodes), List.of(), base.menu().closeWithEscape(), base.menu().scaleTextWithCanvas(),
                base.menu().displayWidth());
            latestMenu = new MenuLayout(List.copyOf(parts), menu);
        }
        List<LayoutStore.FlowRow> flowRows = new ArrayList<>();
        for (FlowListSpec spec : flowLists.values()) {
            String collection = spec.collection();
            List<DialogMenuEntry> rows = external.getOrDefault(collection, List.of());
            if (!spec.title().isBlank())
                flowRows.add(new LayoutStore.FlowRow(collection, "", expand.apply(spec.title())));
            if (rows.isEmpty() && !spec.empty().isBlank())
                flowRows.add(new LayoutStore.FlowRow(collection, "", expand.apply(spec.empty())));
            for (DialogMenuEntry item : rows) {
                Map<String, String> fields = new LinkedHashMap<>();
                item.fields().forEach((key, value) -> fields.put("entry." + key, value));
                fields.put("entry.id", item.id());
                fields.put("entry.name", item.name());
                fields.put("entry.image", item.image());
                flowRows.add(new LayoutStore.FlowRow(collection, item.id(),
                    MenuTemplate.expand(expand.apply(spec.row()), fields)));
            }
        }
        return new LayoutStore.Document(menu, base.open(), base.close(), this, List.copyOf(flowRows), null, base.controls());
    }
    private final class GridPlan {
        private final ConfigurationSection grid;
        private final Map<List<String>, List<Node>> cache = new LinkedHashMap<>(4, .75f, true) {
            @Override protected boolean removeEldestEntry(Map.Entry<List<String>, List<Node>> eldest) {
                return size() > 8;
            }
        };
        GridPlan(ConfigurationSection grid) { this.grid = grid; }
        List<Node> nodes(int g, Map<String, List<String>> lists) {
            String collection = grid.getString("collection");
            List<String> items = grid.contains("items") ? LayoutStore.strings(grid.get("items"))
                : lists.getOrDefault(collection, List.of());
            synchronized (cache) {
                List<Node> cached = cache.get(items);
                if (cached != null) return cached;
            }
            List<Node> nodes = new ArrayList<>();
            int slots = grid.getInt("slots", 8), columns = grid.getInt("columns", 4);
            for (int i = 0; i < slots; i++) {
                String id = i < items.size() ? items.get(i) : "";
                Entry entry = definition.entries.get(id);
                String prefix = "grid-" + g + "-" + i;
                double x = grid.getDouble("x") + i % columns * grid.getDouble("step-x");
                double y = grid.getDouble("y") + (header.schema() >= 2 ? 1 : -1)
                    * (i / columns) * grid.getDouble("step-y");
                String label = entry != null ? entry.label() : id.isEmpty() ? grid.getString("empty-label", "")
                    : grid.getString("unknown-label", id);
                double w = grid.getDouble("width"), h = grid.getDouble("height");
                List<String> actions = id.isEmpty() ? LayoutStore.actions(grid.get("empty-actions"), false)
                    : List.of("entry: " + collection + " " + id);
                Interaction interaction = new Interaction(true, "", entry == null ? List.of(label) : entry.tooltip(), actions);
                nodes.add(image(prefix + "-base", grid.getString("background"), x, y, w, h,
                    interaction));
                boolean model = entry != null && !entry.model().isBlank();
                boolean icon = model || entry != null && !entry.image().isBlank();
                if (model) {
                    int size = LayoutStore.integer(grid, "model-size",
                        Math.max(1, (int) Math.round(grid.getDouble("icon-size"))), 1, 96);
                    nodes.add(new Node(prefix + "-icon", "model", x + grid.getDouble("icon-x"),
                        y + grid.getDouble("icon-y"), 0, 1, size, size, 0xFFFFFFFF, "", entry.model(),
                        "center", size, 1, 0, 9, 18, List.of(), 0, interaction, false));
                } else if (icon) nodes.add(image(prefix + "-icon", entry.image(), x + grid.getDouble("icon-x"),
                    y + grid.getDouble("icon-y"), grid.getDouble("icon-size"), grid.getDouble("icon-size"),
                    interaction, entry.atlas()));
                String color = grid.getString("color", "#343537");
                if (!color.matches("#[0-9a-fA-F]{6}")) throw new IllegalArgumentException("grid color非法");
                nodes.add(new Node(prefix + "-label", "text", icon ? x + grid.getDouble("text-x") : x, y,
                    1, 1, 0, 0, 0xFF000000 | Integer.parseInt(color.substring(1), 16), label, "",
                    icon ? "left" : "center", 8, 1, 0, 9, 18, List.of(), 0, interaction, false));
            }
            List<Node> result = nodes.stream().map(header::renderNode).toList();
            synchronized (cache) { cache.put(List.copyOf(items), result); }
            return result;
        }
    }
    private static Node image(String id, String source, double x, double y, double w, double h,
                              Interaction interaction) {
        return image(id, source, x, y, w, h, interaction, null);
    }
    private static Node image(String id, String source, double x, double y, double w, double h,
                              Interaction interaction, TextureRegion atlas) {
        return new Node(id, "image", x, y, 0, 1, w, h, 0xFFFFFFFF, "", source,
            "center", 8, 1, 0, 9, 18, List.of(), 0, interaction, false, atlas);
    }
    List<LayoutStore.Document> variants() throws Exception {
        List<LayoutStore.Document> result = new ArrayList<>();
        UnaryOperator<String> preview = definition.preview(Map.of(), "Player");
        result.add(document(Map.of(), Map.of(), preview, true));
        Set<Map<String,String>> usedSamples = new HashSet<>();
        for (Map<String, String> sample : definition.samples) {
            BuildBudget.checkpoint();
            Map<String,String> relevant = new LinkedHashMap<>();
            sample.forEach((key, value) -> { if (yamlText.contains("{" + key + "}")) relevant.put(key, value); });
            if (!relevant.isEmpty() && usedSamples.add(relevant))
                result.add(document(Map.of(), Map.of(), definition.preview(relevant, "Player"), true));
        }
        for (var source : definition.collections.entrySet())
            for (String id : definition.entries.keySet())
                if (!source.getValue().external())
                    result.add(document(Map.of(source.getKey(), Collections.nCopies(source.getValue().capacity(), id)),
                        Map.of(), preview, true));
        if (definition.variables != null) for (String key : definition.variables.getKeys(false)) {
            BuildBudget.checkpoint();
            var variable = definition.variables.getConfigurationSection(key);
            var choices = variable.getConfigurationSection("values");
            if (choices != null) for (String value : new LinkedHashSet<>(choices.getValues(false).values().stream()
                .map(String::valueOf).toList()))
                result.add(document(Map.of(), Map.of(), definition.preview(Map.of(key, value), "Player"), true));
        }
        return result;
    }
    static List<LayoutStore.Document> catalog(Collection<LayoutStore.Document> docs) throws Exception {
        List<LayoutStore.Document> result = new ArrayList<>();
        for (var doc : docs) {
            BuildBudget.checkpoint();
            if (doc.template() == null) result.add(doc);
            else result.addAll(doc.template().variants());
        }
        return result;
    }
    static String characters(Collection<LayoutStore.Document> docs) {
        StringBuilder text = new StringBuilder();
        Set<Definition> visited = new HashSet<>();
        for (var doc : docs) if (doc.template() != null && visited.add(doc.template().definition))
            text.append(doc.template().definition.characters);
        return text.toString();
    }
    static List<String> textValues(LayoutStore.Document doc, Node node, Context context) {
        Set<String> values = new LinkedHashSet<>();
        values.add(context.expand(node.text()));
        if (doc.template() != null) {
            Definition definition = doc.template().definition;
            for (var sample : definition.samplePreviews)
                values.add(context.expand(sample.expand(node.text(), "{player}")));
            for (var choice : definition.previewChoices.entrySet()) {
                if (!node.text().contains(choice.getKey())) continue;
                for (String value : choice.getValue()) values.add(context.expand(value));
            }
        }
        return List.copyOf(values);
    }
    static boolean dynamicText(Node node) { return TEXT_VARIABLE.matcher(node.text()).find(); }

    static String dynamicCharacters(LayoutStore.Document doc, Node node, String extraCharacters) {
        StringBuilder characters = new StringBuilder(doc.template() == null
            ? extraCharacters : doc.template().definition.characters);
        var fields = TEXT_VARIABLE.matcher(node.text());
        while (fields.find()) {
            var variables = doc.template() == null ? null : doc.template().definition.variables;
            var spec = variables == null || fields.group(1) == null ? null
                : variables.getConfigurationSection(fields.group(1));
            if (spec != null && spec.getInt("max-length", 0) > 0)
                characters.append("0123456789.,-+EMAX");
            else if (spec == null || !spec.isConfigurationSection("values"))
                for (int cp = 33; cp < 127; cp++) characters.appendCodePoint(cp);
        }
        return characters.toString();
    }
    static Context preview(LayoutStore.Document doc, Context base) {
        if (doc.template() == null) return base;
        UnaryOperator<String> preview = doc.template().definition.preview(Map.of(), "{player}");
        return new Context() {
            public String expand(String text) {
                return base.expand(preview.apply(text));
            }
            public Icon item(int slot) { return base.item(slot); }
            public boolean permitted(String permission) { return base.permitted(permission); }
            public net.kyori.adventure.text.Component playerHead() { return base.playerHead(); }
            public double textScale() { return base.textScale(); }
            public double canvasScale() { return base.canvasScale(); }
        };
    }
}
