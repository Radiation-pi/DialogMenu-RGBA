package dev.dialogmenu;

import java.awt.image.BufferedImage;
import java.io.*;
import java.nio.file.*;
import java.util.*;
import java.util.List;
import java.util.zip.*;
import javax.imageio.ImageIO;
import com.google.gson.Gson;
import com.google.gson.JsonElement;

/** 读取原版单字和图标源数据，不将整句文字渲染成图。 */
final class VanillaAssets {
    record Glyph(byte[] alpha, int width, int height, float advance) {}
    private final Map<Integer, Glyph> glyphs = new HashMap<>();
    private final Map<String, BufferedImage> icons = new HashMap<>();
    private final Path images;
    private final Map<String, BufferedImage> custom = new HashMap<>();
    private final SourceFiles inputs = new SourceFiles();
    final CraftEngineAssets craftEngine;
    record State(Map<String, BufferedImage> custom, JsonElement craftEngine) {}

    synchronized State state(Gson codec) {
        synchronized (craftEngine) {
            return new State(new HashMap<>(custom), codec.toJsonTree(craftEngine));
        }
    }
    static VanillaAssets restore(Path images, State state, Gson codec) throws IOException {
        VanillaAssets assets = new VanillaAssets(images, codec.fromJson(state.craftEngine(), CraftEngineAssets.class));
        assets.custom.putAll(state.custom());
        return assets;
    }

    VanillaAssets(Path images) throws IOException {
        this(images, (Path) null);
    }

    VanillaAssets(Path images, Path craftEngineResources) throws IOException {
        this(images, craftEngineResources, List.of());
    }

    VanillaAssets(Path images, Path craftEngineResources, List<String> definitions) throws IOException {
        this(images, new CraftEngineAssets(craftEngineResources, definitions));
    }

    private VanillaAssets(Path images, CraftEngineAssets craftEngine) throws IOException {
        this.images = images.toAbsolutePath().normalize();
        Files.createDirectories(this.images);
        this.craftEngine = craftEngine;
        try (var input = new DataInputStream(new GZIPInputStream(resource("glyphs.bin.gz")))) {
            if (input.readInt() != 0x534D4631) throw new IOException("Unknown glyph source format");
            int count = input.readInt();
            for (int i = 0; i < count; i++) {
                if (i % 128 == 0) BuildBudget.checkpoint();
                int cp = input.readInt(), w = input.readUnsignedShort(), h = input.readUnsignedShort();
                float advance = input.readFloat();
                byte[] alpha = input.readNBytes(w * h);
                if (alpha.length != w * h) throw new EOFException("Incomplete vanilla glyph");
                glyphs.put(cp, new Glyph(alpha, w, h, advance));
            }
        }
        try (var input = new ZipInputStream(resource("vanilla-icons.zip"))) {
            for (ZipEntry entry; (entry = input.getNextEntry()) != null;) {
                BuildBudget.checkpoint();
                byte[] bytes = input.readAllBytes();
                BufferedImage image = ImageIO.read(new ByteArrayInputStream(bytes));
                if (image != null) icons.put(entry.getName().replaceFirst("\\.png$", ""), image);
            }
        }
    }

    private static InputStream resource(String name) throws IOException {
        InputStream input = VanillaAssets.class.getClassLoader().getResourceAsStream(name);
        if (input == null) throw new FileNotFoundException("Missing built-in asset " + name);
        return input;
    }

    synchronized BufferedImage image(String source) throws IOException {
        if (source.startsWith("minecraft:")) {
            return vanillaImage(source);
        }
        if (source.startsWith("craftengine:")) {
            BufferedImage image = craftEngine.image(source);
            if (image != null) return image;
            String id = source.substring(source.indexOf('/') + 1);
            String file = craftEngine.imageFile(id);
            if (file != null && (file.startsWith("item/") || file.startsWith("block/")))
                return vanillaImage("minecraft:" + file);
            throw new IOException("Unresolved CraftEngine image: " + source);
        }
        BufferedImage cached = custom.get(source);
        if (cached != null) return cached;
        Path path = images.resolve(source.startsWith("/") ? source.substring(1) : source).normalize();
        if (!path.startsWith(images) || !Files.isRegularFile(path)
            || !path.toRealPath().startsWith(images.toRealPath())) throw new IOException("Image outside images/: " + source);
        BufferedImage image = ImageIO.read(new ByteArrayInputStream(inputs.read(path)));
        if (image == null || image.getWidth() < 16 || image.getHeight() < 16
            || image.getWidth() > 256 || image.getHeight() > 256
            || image.getWidth() % 16 != 0 || image.getHeight() % 16 != 0)
            throw new IOException("PNG dimensions must be multiples of 16, within 16..256: " + source);
        custom.put(source, image);
        return image;
    }

    CraftEngineAssets.Reference reference(String source) throws IOException {
        return reference(source, null);
    }

    CraftEngineAssets.Reference reference(String source, MenuModel.TextureRegion atlas) throws IOException {
        if (!source.startsWith("minecraft:")) return craftEngine.reference(source, atlas);
        if (source.startsWith("minecraft:preview/"))
            throw new IOException("预览合成图不是原版纹理路径，请使用已有CE方块图标：" + source);
        String texture = source.endsWith(".png") ? source : source + ".png";
        return new CraftEngineAssets.Reference(texture, vanillaImage(source), null);
    }

    private BufferedImage vanillaImage(String source) throws IOException {
        BufferedImage image = icons.get(source.substring("minecraft:".length()).replaceFirst("\\.png$", ""));
        if (image == null) throw new IOException("Unknown vanilla texture: " + source);
        return image;
    }

    Glyph glyph(int codepoint) { return glyphs.get(codepoint); }

    void verifyInputs() throws IOException { inputs.verify(); craftEngine.verifyInputs(); }

    List<String> sources() throws IOException {
        var result = new TreeSet<String>();
        for (String source : icons.keySet()) result.add("minecraft:" + source);
        try (var paths = Files.walk(images)) {
            paths.filter(p -> Files.isRegularFile(p, LinkOption.NOFOLLOW_LINKS) && p.toString().endsWith(".png"))
                .forEach(p -> result.add(images.relativize(p).toString().replace('\\', '/')));
        }
        result.addAll(craftEngine.sources());
        return List.copyOf(result);
    }

}
