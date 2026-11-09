package dev.dialogmenu;

import com.google.gson.*;
import java.awt.image.BufferedImage;
import java.io.IOException;
import java.io.ByteArrayInputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.*;
import javax.imageio.ImageIO;
import static dev.dialogmenu.MenuModel.TextureRegion;

/** Whole local PNGs and explicit external registrations, independent of menu references. */
final class ImageRegistry {
    static final String PREFIX = "registered:";
    record Entry(BufferedImage image, boolean tintable) {}

    static boolean source(String source) { return source != null && source.startsWith(PREFIX); }

    static String key(String source) {
        if (source == null || source.isEmpty() || source(source)) return source;
        if (source.contains(":")) return source;
        return PREFIX + localPath(source);
    }

    private static String localPath(String source) {
        String path = source.startsWith("/") ? source.substring(1) : source;
        if (path.isEmpty() || path.contains("\\") || path.contains(":")
            || Arrays.asList(path.split("/")).contains(".."))
            throw new IllegalArgumentException("Invalid images/ path: " + source);
        return Path.of(path).normalize().toString().replace('\\', '/');
    }

    static Map<String, Entry> load(Path images, CraftEngineAssets assets, SourceFiles inputs) throws IOException {
        Map<String, Entry> result = new TreeMap<>();
        for (Path path : inputs.imageDirectory(images)) {
            BuildBudget.checkpoint();
            if (!path.toRealPath().startsWith(images.toRealPath()))
                throw new IOException("Image outside images/: " + path);
            BufferedImage image = ImageIO.read(new ByteArrayInputStream(inputs.read(path)));
            if (image == null || image.getWidth() < 1 || image.getHeight() < 1
                || image.getWidth() > 256 || image.getHeight() > 256)
                throw new IOException("Registered PNG dimensions must be within 1..256: " + path);
            Path metadata = Path.of(path + ".mcmeta");
            if (Files.exists(metadata)) {
                if (!metadata.toRealPath().startsWith(images.toRealPath()))
                    throw new IOException("Image metadata outside images/: " + metadata);
                try {
                    if (JsonParser.parseString(new String(inputs.read(metadata), StandardCharsets.UTF_8))
                        .getAsJsonObject().has("animation"))
                        throw new IOException("Animated registered images are not supported: " + path);
                } catch (RuntimeException error) {
                    throw new IOException("Invalid image metadata: " + metadata, error);
                }
            }
            String relative = images.relativize(path).toString().replace('\\', '/');
            result.put(PREFIX + localPath(relative), new Entry(image, false));
        }
        Path file = images.resolve("registry.json");
        if (!Files.exists(file)) return Collections.unmodifiableMap(result);
        if (!file.toRealPath().startsWith(images.toRealPath()))
            throw new IOException("images/registry.json cannot leave images/");
        try {
            JsonObject root = JsonParser.parseString(new String(inputs.read(file), StandardCharsets.UTF_8)).getAsJsonObject();
            keys(root, Set.of("textures", "models"));
            JsonObject textures = root.getAsJsonObject("textures");
            if (textures == null) {
                if (!root.has("models")) throw new IllegalArgumentException("Missing textures object");
                textures = new JsonObject();
            }
            for (String id : new TreeSet<>(textures.keySet())) {
                BuildBudget.checkpoint();
                boolean local = !id.contains(":");
                if (local ? !id.equals(localPath(id)) || !result.containsKey(PREFIX + id)
                    : !id.matches("[a-z0-9_.-]+:[a-z0-9/._-]+") || id.contains(".."))
                    throw new IllegalArgumentException("Invalid registered image ID: " + id);
                JsonObject entry = textures.getAsJsonObject(id);
                keys(entry, Set.of("source", "atlas", "tintable"));
                boolean tintable = false;
                if (entry.has("tintable")) {
                    JsonElement value = entry.get("tintable");
                    if (!value.isJsonPrimitive() || !value.getAsJsonPrimitive().isBoolean())
                        throw new IllegalArgumentException(id + " tintable must be true or false");
                    tintable = value.getAsBoolean();
                }
                String source = entry.has("source") ? entry.get("source").getAsString() : local ? id : null;
                if (source == null) throw new IllegalArgumentException(id + " requires a source");
                if (local && !source.equals(id))
                    throw new IllegalArgumentException(id + " local overrides cannot replace their source");
                TextureRegion region = null;
                if (entry.has("atlas")) {
                    JsonObject atlas = entry.getAsJsonObject("atlas");
                    keys(atlas, Set.of("columns", "rows", "column", "row"));
                    int columns = integer(atlas, "columns"), rows = integer(atlas, "rows");
                    int column = integer(atlas, "column"), row = integer(atlas, "row");
                    if (columns < 1 || rows < 1 || columns > 4096 || rows > 4096
                        || (long) columns * rows > 65536 || column < 0 || column >= columns || row < 0 || row >= rows)
                        throw new IllegalArgumentException(id + " invalid atlas selection");
                    region = new TextureRegion(columns, rows, column, row);
                }
                BufferedImage image;
                if (source.startsWith("craftengine:")) {
                    image = assets.registeredReference(source, region).image();
                } else {
                    if (source.contains(":") || region != null)
                        throw new IllegalArgumentException(id + " requires a local PNG or craftengine: source; local PNGs remain whole");
                    Entry original = result.get(key(source));
                    if (original == null) throw new IllegalArgumentException("Unknown local image: " + source);
                    image = original.image();
                }
                result.put(PREFIX + id, new Entry(image, tintable));
            }
            return Collections.unmodifiableMap(result);
        } catch (RuntimeException error) {
            throw new IOException("Invalid images/registry.json: " + error.getMessage(), error);
        }
    }

    private static int integer(JsonObject object, String key) {
        return object.get(key).getAsBigDecimal().intValueExact();
    }

    private static void keys(JsonObject object, Set<String> allowed) {
        if (object == null) throw new IllegalArgumentException("Expected an object");
        for (String key : object.keySet())
            if (!allowed.contains(key)) throw new IllegalArgumentException("Unknown registry field: " + key);
    }
}
