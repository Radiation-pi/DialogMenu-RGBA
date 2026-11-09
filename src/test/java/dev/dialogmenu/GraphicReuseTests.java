package dev.dialogmenu;

import java.awt.image.BufferedImage;
import java.nio.file.*;
import java.util.*;
import java.util.concurrent.*;
import javax.imageio.ImageIO;
import org.bukkit.configuration.file.YamlConfiguration;
import net.kyori.adventure.text.event.ClickEvent;
import net.kyori.adventure.text.serializer.gson.GsonComponentSerializer;
import static dev.dialogmenu.MenuModel.*;

/** 缓存命中、淘汰和并行绘制须与不复用的预览保持像素及交互一致。 */
final class GraphicReuseTests {
    private static final Context CONTEXT = new Context() {
        public String expand(String text) { return text; }
        public Icon item(int slot) { return null; }
        public boolean permitted(String permission) { return true; }
    };
    static int run(Path root) throws Exception {
        Path images = Files.createDirectories(root.resolve("graphic-reuse"));
        var source = new BufferedImage(16, 16, BufferedImage.TYPE_INT_ARGB);
        for (int y = 0; y < 16; y++) for (int x = 0; x < 16; x++)
            source.setRGB(x, y, 0xff000000 | x * 0x100000 | y * 0x1000);
        ImageIO.write(source, "png", images.resolve("sample.png").toFile());
        List<LayoutStore.Document> docs = new ArrayList<>();
        for (int i = 0; i < 12; i++) {
            var yaml = new YamlConfiguration();
            yaml.loadFromString("""
                schema-version: 1
                id: probe%d
                canvas: {width: 300, height: 180}
                frontend:
                  background:
                    type: rectangle
                    width: 299
                    height: 179
                    color: '#%06X'
                  border:
                    type: frame
                    width: 285
                    height: 160
                    thickness: %d
                    color: '#AACCFF'
                    offset: {x: %d, y: 0}
                    rotation: %d
                  icon:
                    type: image
                    source: sample.png
                    width: 32
                    height: 24
                    color: '#%06X'
                    offset: {x: %d, y: 20}
                    rotation: %d
                    actions: ['tell: IMAGE%d']
                    tooltip: ['IMAGE%d']
                  direct:
                    type: image
                    source: minecraft:item/diamond
                    width: 16
                    height: 16
                    offset: {y: -30}
                    actions: ['tell: DIRECT']
                """.formatted(i, 0x102030 + i * 0x010101, i % 3 + 1, i - 6, i % 4 * 5,
                    0xffffff - i * 0x010203, i * 2, i % 3 * 10, i, i));
            docs.add(LayoutStore.parse("probe" + i, yaml));
        }
        var assets = new VanillaAssets(images);
        var renderer = new MenuRenderer(assets, 16_777_216);
        var fonts = FontPack.create(docs, renderer, CONTEXT, List.of(300, 360), 1_048_576);
        int checks = 0;
        try (var workers = Executors.newFixedThreadPool(4)) {
            List<Future<Integer>> results = new ArrayList<>();
            for (long budget : new long[]{1, 16_777_216}) {
                var shared = new MenuRenderer(assets, budget);
                for (int pass = 0; pass < 3; pass++) for (var doc : docs)
                    for (int width : new int[]{300, 360}) results.add(workers.submit(() -> {
                        var expected = new MenuRenderer(assets).draw(doc.menu(), CONTEXT, width, 1_048_576);
                        var actual = shared.drawRuntime(doc.menu(), CONTEXT, width, 1_048_576);
                        if (expected.width() != actual.width() || expected.height() != actual.height()
                            || expected.sprites().size() != actual.sprites().size())
                            throw new AssertionError("Reused frame geometry differs");
                        for (int n = 0; n < expected.sprites().size(); n++) {
                            Sprite before = expected.sprites().get(n), after = actual.sprites().get(n);
                            if (!before.id().equals(after.id()) || before.x() != after.x() || before.y() != after.y()
                                || before.width() != after.width() || before.height() != after.height())
                                throw new AssertionError("Reused sprite geometry differs");
                            if (before.texture() == null && before.original() == null
                                && !Arrays.equals(before.image().getRGB(0, 0, before.width(), before.height(), null, 0, before.width()),
                                    after.image().getRGB(0, 0, after.width(), after.height(), null, 0, after.width())))
                                throw new AssertionError("Reused raster pixels differ");
                        }
                        String a = encoded(doc.menu(), expected, fonts), b = encoded(doc.menu(), actual, fonts);
                        if (!a.equals(b)) throw new AssertionError("Reused rendering changed pixels or interactions");
                        return 1;
                    }));
            }
            for (var result : results) checks += result.get();
        }
        System.out.println("PASS graphic reuse checks=" + checks);
        return checks;
    }
    private static String encoded(Menu menu, Rendered frame, FontPack fonts) {
        var output = new SpriteCanvas().encode(menu, frame, CONTEXT, fonts,
            region -> ClickEvent.runCommand("/" + region.id()));
        return GsonComponentSerializer.gson().serialize(output.component());
    }
}
