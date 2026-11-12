package dev.dialogmenu;

import com.google.gson.*;
import java.io.*;
import java.nio.channels.FileChannel;
import java.nio.file.*;
import java.nio.file.attribute.BasicFileAttributes;
import java.util.*;
import java.util.function.BiConsumer;
import java.util.zip.ZipFile;

/** An explicit menu/texture allowlist, never a server-directory file browser. */
public final class DialogMenuEditorFiles implements DialogMenuEditorService.Files {
    private record Entry(Path root, String relative, Path archive, String member, boolean writable) {}
    private final Path data, layouts, backups, resources;
    private final Path assetCache;
    private Map<String, Entry> entries = Map.of();
    private static final int MAX_ASSET = 16 * 1024 * 1024;

    public DialogMenuEditorFiles(Path data, Path resources) throws IOException {
        this.data = data.toAbsolutePath().normalize();
        Files.createDirectories(this.data.resolve("layouts"));
        layouts = this.data.resolve("layouts").toRealPath();
        backups = this.data.resolve("editor-backups");
        Files.createDirectories(backups);
        if (Files.isSymbolicLink(backups)) throw new IOException("Invalid editor backup directory");
        this.resources = resources;
        assetCache = this.data.resolve("editor-cache/assets");
    }

    @Override public synchronized List<DialogMenuEditorService.FileEntry> manifest() throws IOException {
        var next = new TreeMap<String, Entry>();
        try (var paths = Files.list(layouts)) {
            for (Path path : paths.toList()) {
                String name = path.getFileName().toString();
                if (name.matches("[a-z0-9][a-z0-9_-]{0,63}\\.yml") && Files.isRegularFile(path, LinkOption.NOFOLLOW_LINKS))
                    next.put("layouts/" + name, new Entry(layouts, name, null, null, true));
            }
        }
        scan(next, data.resolve("images"), "images/", "(.*\\.png|registry\\.json)");
        scan(next, data.resolve("models/prepared"), "models/prepared/", "(vanilla|ce)/state\\.json");
        if (resources != null)
            scan(next, resources, "CraftEngine/resources/", "[^/]+/resourcepack/assets/[^/]+/textures/.+\\.png");
        Path settings = data.resolve("models/settings.json");
        if (Files.isRegularFile(settings, LinkOption.NOFOLLOW_LINKS)) {
            try {
                var json = JsonParser.parseString(Files.readString(settings)).getAsJsonObject();
                if (json.has("client-jar")) {
                    Path archive = Path.of(json.get("client-jar").getAsString());
                    if (!archive.isAbsolute()) archive = data.resolve("models").resolve(archive);
                    if (Files.isRegularFile(archive)) {
                        archive = archive.toRealPath();
                        try (var zip = new ZipFile(archive.toFile())) {
                            for (var member : zip.stream().filter(e -> !e.isDirectory()).toList()) {
                                String name = member.getName();
                                if (name.matches("assets/[^/]+/textures/[a-z0-9_./-]+\\.png") && !name.contains(".."))
                                    next.put("minecraft/" + name, new Entry(null, null, archive, name, false));
                            }
                        }
                    }
                }
            } catch (RuntimeException error) { throw new IOException("Invalid model settings", error); }
        }
        entries = Map.copyOf(next);
        var manifest = new ArrayList<DialogMenuEditorService.FileEntry>();
        var attributes = new HashMap<Path, BasicFileAttributes>();
        for (var entry : next.entrySet()) {
            Entry source = entry.getValue();
            Path file = source.archive() == null ? checked(source) : source.archive();
            BasicFileAttributes stat = attributes.get(file);
            if (stat == null) {
                stat = Files.readAttributes(file, BasicFileAttributes.class, LinkOption.NOFOLLOW_LINKS);
                attributes.put(file, stat);
            }
            manifest.add(new DialogMenuEditorService.FileEntry(entry.getKey(), source.writable(),
                stat.size(), stat.lastModifiedTime().toMillis()));
        }
        return List.copyOf(manifest);
    }

    private static void scan(Map<String, Entry> result, Path configured, String prefix, String pattern) throws IOException {
        if (!Files.isDirectory(configured)) return;
        // Configured roots may intentionally be shared symlinks; children may not escape them.
        Path root = configured.toRealPath();
        try (var paths = Files.walk(root, 24)) {
            for (Path file : paths.filter(p -> Files.isRegularFile(p, LinkOption.NOFOLLOW_LINKS)).toList()) {
                String relative = root.relativize(file).toString().replace('\\', '/');
                if (relative.matches(pattern))
                    result.put(prefix + relative, new Entry(root, relative, null, null, false));
            }
        }
    }

    private static Path checked(Entry entry) throws IOException {
        Path file = entry.root();
        for (Path part : Path.of(entry.relative())) {
            file = file.resolve(part);
            if (Files.isSymbolicLink(file)) throw new IOException("Symlink assets are not accessible");
        }
        if (!file.toRealPath().startsWith(entry.root())) throw new IOException("File outside editor roots");
        return file;
    }

    @Override public synchronized byte[] read(String path) throws IOException {
        Entry entry = entries.get(path);
        if (entry == null) throw new FileNotFoundException("File not in editor manifest");
        if (entry.writable()) return source(entry, null);
        return cached(path, entry, false, null);
    }

    private Path cacheFile(String path, Entry entry) throws IOException {
        Path source = entry.archive() == null ? checked(entry) : entry.archive();
        var stat = Files.readAttributes(source, BasicFileAttributes.class, LinkOption.NOFOLLOW_LINKS);
        String version = source + "\n" + path + "\n" + stat.size() + "\n" + stat.lastModifiedTime();
        return assetCache.resolve(SourceFiles.digest(version.getBytes(java.nio.charset.StandardCharsets.UTF_8)) + ".bin");
    }

    private byte[] cached(String path, Entry entry, boolean force, ZipFile archive) throws IOException {
        if (Files.isSymbolicLink(assetCache) || Files.isSymbolicLink(assetCache.getParent()))
            throw new IOException("Invalid editor cache directory");
        Path target = cacheFile(path, entry);
        if (!force && Files.isRegularFile(target, LinkOption.NOFOLLOW_LINKS)) {
            try (var input = Files.newInputStream(target, LinkOption.NOFOLLOW_LINKS)) {
                return bounded(input, MAX_ASSET);
            }
        }
        byte[] bytes = source(entry, archive);
        if (!target.equals(cacheFile(path, entry))) throw new IOException("Asset changed during caching: " + path);
        Files.createDirectories(assetCache);
        Path temp = Files.createTempFile(assetCache, "asset-", ".tmp");
        try {
            Files.write(temp, bytes);
            Files.move(temp, target, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
        } finally { Files.deleteIfExists(temp); }
        return bytes;
    }

    /** Called on the existing reload worker, including ALL's unchanged-resource path. */
    synchronized void preload(boolean force, BiConsumer<Integer, Integer> progress) throws IOException {
        manifest();
        var assets = entries.entrySet().stream().filter(e -> !e.getValue().writable()).toList();
        var archives = new HashMap<Path, ZipFile>();
        var retained = new HashSet<Path>();
        try {
            int done = 0;
            for (var item : assets) {
                BuildBudget.checkpoint();
                Entry entry = item.getValue();
                ZipFile zip = null;
                if (entry.archive() != null) {
                    zip = archives.get(entry.archive());
                    if (zip == null) {
                        zip = new ZipFile(entry.archive().toFile());
                        archives.put(entry.archive(), zip);
                    }
                }
                cached(item.getKey(), entry, force, zip);
                retained.add(cacheFile(item.getKey(), entry));
                progress.accept(++done, assets.size());
            }
            if (Files.isDirectory(assetCache)) try (var paths = Files.list(assetCache)) {
                for (Path path : paths.filter(p -> p.getFileName().toString().matches("[a-f0-9]{64}\\.bin")).toList())
                    if (!retained.contains(path)) Files.deleteIfExists(path);
            }
        } finally {
            for (ZipFile zip : archives.values()) zip.close();
        }
    }

    private static byte[] source(Entry entry, ZipFile archive) throws IOException {
        if (entry.archive() != null) {
            if (archive != null) return member(archive, entry.member());
            try (var zip = new ZipFile(entry.archive().toFile())) {
                return member(zip, entry.member());
            }
        }
        try (var input = Files.newInputStream(checked(entry), LinkOption.NOFOLLOW_LINKS)) {
            return bounded(input, entry.writable() ? EditorFiles.MAX_YAML : MAX_ASSET);
        }
    }

    private static byte[] member(ZipFile zip, String name) throws IOException {
        var member = zip.getEntry(name);
        if (member == null) throw new FileNotFoundException("Texture not found");
        try (var input = zip.getInputStream(member)) { return bounded(input, MAX_ASSET); }
    }

    static byte[] bounded(InputStream input, int max) throws IOException {
        byte[] bytes = input.readNBytes(max + 1);
        if (bytes.length > max) throw new IOException("File or request exceeds size limit");
        return bytes;
    }

    @Override public synchronized void save(String path, String base, String text) throws Exception {
        Entry entry = entries.get(path);
        if (entry == null || !entry.writable()) throw new IOException("Only existing menu YAML can be saved");
        if (text.getBytes(java.nio.charset.StandardCharsets.UTF_8).length > EditorFiles.MAX_YAML)
            throw new IOException("YAML exceeds 256 KiB");
        String id = path.substring("layouts/".length()).replaceFirst("\\.yml$", "");
        // Validate with the real engine parser, including links to the other menu files.
        var documents = new LinkedHashMap<String, LayoutStore.Document>();
        try (var paths = Files.list(layouts)) {
            for (Path file : paths.filter(p -> p.getFileName().toString().endsWith(".yml")).toList()) {
                String name = file.getFileName().toString().replaceFirst("\\.yml$", "");
                if (Files.isSymbolicLink(file)) throw new IOException("Symlink menus cannot be validated");
                var parsed = LayoutStore.loadText(name, name.equals(id) ? text : Files.readString(file));
                for (var doc : parsed.entrySet())
                    if (documents.putIfAbsent(doc.getKey(), doc.getValue()) != null)
                        throw new IOException("Duplicate page ID: " + doc.getKey());
            }
        }
        LayoutStore.validateLinks(documents);
        Path lock = layouts.resolve(".web-editor.lock");
        if (Files.isSymbolicLink(lock)) throw new IOException("Invalid editor lock");
        try (var channel = FileChannel.open(lock, StandardOpenOption.CREATE, StandardOpenOption.WRITE,
                LinkOption.NOFOLLOW_LINKS); var ignored = channel.lock()) {
            String before = new String(read(path), java.nio.charset.StandardCharsets.UTF_8);
            if (!EditorFiles.revision(before).equals(base))
                throw new DialogMenuEditorService.Conflict("原文件已在外部修改，请重新读取后合并");
            if (before.equals(text)) return;
            if (Files.isSymbolicLink(backups)) throw new IOException("Invalid backup directory");
            EditorFiles.atomic(backups.resolve(id + "-web-" + UUID.randomUUID() + ".yml"), before);
            Path target = checked(entry);
            EditorFiles.atomic(target, text);
        }
    }

}
