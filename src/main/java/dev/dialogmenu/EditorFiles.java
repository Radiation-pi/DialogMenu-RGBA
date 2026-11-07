package dev.dialogmenu;

import com.google.gson.*;
import java.io.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.security.*;
import java.util.*;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.file.YamlConfiguration;

/** 管理编辑源文档而非展开后的运行节点，所有调用均在编辑工作线程执行。 */
final class EditorFiles {
    static final int MAX_YAML = 256 * 1024;
    record Draft(String id, String base, String revision, String yaml, JsonObject tree) {}
    record Commit(String id, String before, String after, Path backup) {}
    private final Path data, layouts, drafts, backups;

    EditorFiles(Path data) throws IOException {
        this.data = data.toAbsolutePath().normalize();
        layouts = this.data.resolve("layouts");
        drafts = this.data.resolve("editor-drafts");
        backups = this.data.resolve("editor-backups");
        Files.createDirectories(layouts);
        Files.createDirectories(drafts);
        Files.createDirectories(backups);
        for (Path root : List.of(layouts, drafts, backups))
            if (Files.isSymbolicLink(root)) throw new IOException("Editor directories must not be symlinks");
    }

    List<String> list() throws IOException {
        try (var paths = Files.list(layouts)) {
            return paths.filter(p -> p.getFileName().toString().matches("[a-z0-9][a-z0-9_-]{0,63}\\.yml"))
                .filter(p -> Files.isRegularFile(p, LinkOption.NOFOLLOW_LINKS))
                .map(p -> p.getFileName().toString().replaceFirst("\\.yml$", "")).sorted().toList();
        }
    }

    private Path layout(String id) throws IOException {
        Path path = layouts.resolve(LayoutStore.id(id) + ".yml");
        if (Files.isSymbolicLink(path)) throw new IOException("Symlink layouts cannot be edited");
        return path;
    }
    private Path draft(UUID owner, String id) throws IOException {
        Path directory = drafts.resolve(owner.toString());
        Files.createDirectories(directory);
        if (Files.isSymbolicLink(directory)) throw new IOException("Invalid draft directory");
        return directory.resolve(LayoutStore.id(id) + ".json");
    }
    private static String text(Path path) throws IOException {
        if (Files.size(path) > MAX_YAML) throw new IOException("YAML exceeds 256 KiB");
        return Files.readString(path);
    }
    static String revision(String value) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                .digest(value.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException impossible) { throw new AssertionError(impossible); }
    }
    static YamlConfiguration parse(String value) throws Exception {
        if (value.getBytes(StandardCharsets.UTF_8).length > MAX_YAML)
            throw new IOException("YAML exceeds 256 KiB");
        var yaml = new YamlConfiguration();
        yaml.options().parseComments(true);
        yaml.loadFromString(value);
        return yaml;
    }
    static JsonObject tree(ConfigurationSection section) {
        var result = new JsonObject();
        for (String key : section.getKeys(false)) {
            Object value = section.get(key);
            result.add(key, value instanceof ConfigurationSection child ? tree(child) : new Gson().toJsonTree(value));
        }
        return result;
    }
    private static void update(ConfigurationSection section, JsonObject tree) {
        for (String key : new ArrayList<>(section.getKeys(false)))
            if (!tree.has(key)) section.set(key, null);
        for (var entry : tree.entrySet()) {
            String key = entry.getKey();
            if (key.indexOf('.') >= 0 || key.length() > 128)
                throw new IllegalArgumentException("Editor keys cannot contain dots or exceed 128 characters");
            JsonElement value = entry.getValue();
            if (value.isJsonObject()) {
                var child = section.getConfigurationSection(key);
                if (child == null) child = section.createSection(key);
                update(child, value.getAsJsonObject());
            } else section.set(key, new Gson().fromJson(value, Object.class));
        }
    }
    private static void validate(String id, YamlConfiguration yaml) throws Exception {
        if (yaml.isConfigurationSection("pages")) MenuTemplate.load(id, yaml);
        else LayoutStore.parse(id, yaml);
    }
    Draft discard(UUID owner, String id, String expected) throws Exception {
        Draft current = read(owner, id, false);
        if (!current.revision().equals(expected)) throw new IOException("Draft changed; reopen it before discarding");
        applied(owner, id);
        return read(owner, id, true);
    }
    Draft read(UUID owner, String id, boolean original) throws Exception {
        String active = text(layout(id)), base = revision(active);
        Path path = draft(owner, id);
        if (!original && Files.isRegularFile(path, LinkOption.NOFOLLOW_LINKS)) {
            if (Files.size(path) > MAX_YAML * 4L) throw new IOException("Draft is too large");
            JsonObject saved = JsonParser.parseString(Files.readString(path)).getAsJsonObject();
            String value = saved.get("yaml").getAsString();
            return new Draft(id, saved.get("base").getAsString(), revision(value), value, tree(parse(value)));
        }
        return new Draft(id, base, "", active, tree(parse(active)));
    }
    Draft save(UUID owner, String id, String base, String previous, JsonObject document) throws Exception {
        String active = text(layout(id));
        if (!revision(active).equals(base)) throw new IOException("Menu changed on server; reload original before saving");
        Draft current = read(owner, id, false);
        if (!current.revision().equals(previous)) throw new IOException("Draft changed in another editor; reopen it");
        if (!current.base().equals(base)) throw new IOException("Draft is based on an older menu; reload original");
        YamlConfiguration yaml = parse(current.yaml());
        if (document.toString().length() > MAX_YAML) throw new IOException("Document exceeds 256 KiB");
        update(yaml, document);
        validate(id, yaml);
        String value = yaml.saveToString();
        parse(value);
        var saved = new JsonObject();
        saved.addProperty("base", base);
        saved.addProperty("yaml", value);
        atomic(draft(owner, id), saved.toString());
        return new Draft(id, base, revision(value), value, tree(yaml));
    }
    Commit commit(UUID owner, String id, String base, String draftRevision) throws Exception {
        Draft next = read(owner, id, false);
        if (next.revision().isBlank() || !next.base().equals(base) || !next.revision().equals(draftRevision))
            throw new IOException("Save this draft before applying");
        String before = text(layout(id));
        if (!revision(before).equals(base)) throw new IOException("Menu changed on server; apply refused");
        validate(id, parse(next.yaml()));
        Path backup = backups.resolve(id + "-" + UUID.randomUUID() + ".yml");
        atomic(backup, before);
        atomic(layout(id), next.yaml());
        return new Commit(id, before, next.yaml(), backup);
    }
    void rollback(Commit commit) throws IOException {
        if (!text(layout(commit.id())).equals(commit.after()))
            throw new IOException("Apply failed, but a newer external edit prevents automatic rollback; use " + commit.backup());
        atomic(layout(commit.id()), commit.before());
    }
    void applied(UUID owner, String id) throws IOException { Files.deleteIfExists(draft(owner, id)); }
    Path stage(Draft draft) throws Exception {
        Path root = Files.createTempDirectory(data, "editor-preview-");
        try {
            Files.createDirectories(root.resolve("layouts"));
            for (String id : list()) Files.copy(layout(id), root.resolve("layouts/" + id + ".yml"));
            Files.writeString(root.resolve("layouts/" + draft.id() + ".yml"), draft.yaml());
            var config = parse(Files.readString(data.resolve("config.yml")));
            config.set("craftengine-resources",
                data.resolve(config.getString("craftengine-resources", "../CraftEngine/resources")).normalize().toString());
            config.save(root.resolve("config.yml").toFile());
            Files.createSymbolicLink(root.resolve("images"), data.resolve("images"));
            return root;
        } catch (Exception error) { remove(root); throw error; }
    }
    static void remove(Path root) throws IOException {
        if (!Files.exists(root)) return;
        try (var paths = Files.walk(root)) {
            for (Path path : paths.sorted(Comparator.reverseOrder()).toList()) Files.deleteIfExists(path);
        }
    }
    static void atomic(Path target, String value) throws IOException {
        if (Files.isSymbolicLink(target)) throw new IOException("Cannot overwrite a symlink");
        Path temporary = Files.createTempFile(target.getParent(), ".editor-", ".tmp");
        try {
            Files.writeString(temporary, value);
            Files.move(temporary, target, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
        } finally { Files.deleteIfExists(temporary); }
    }
}
