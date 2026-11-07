package dev.dialogmenu;

import com.google.gson.JsonParser;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import static dev.dialogmenu.MenuModel.*;

/** 检查实际导出的字体映射，而非仅检查资源包生成器的内部记录。 */
final class UsedGlyphTests {
    private static int checks;
    private static final Context PREVIEW = context("甲");

    static int run(Path temporary) throws Exception {
        checks = 0;
        Path data = temporary.resolve("used-glyphs");
        Files.createDirectories(data.resolve("layouts"));
        Files.writeString(data.resolve("layouts/sparse.yml"), """
            schema-version: 1
            id: sparse
            canvas: {width: 300, height: 180}
            frontend:
              small:
                type: text
                content: '&aA'
                offset: {x: -50, y: 20}
              large:
                type: text
                content: 中
                size: 12
                offset: {x: 50, y: -20}
              percent:
                type: text
                content: '50% / 100%'
                size: 10
                offset: {y: 50}
            """);
        var renderer = new MenuRenderer(new VanillaAssets(data.resolve("images")));
        var docs = new LayoutStore(data.resolve("layouts")).load();
        var doc = docs.get("sparse");
        var fonts = FontPack.create(docs.values(), renderer, PREVIEW, List.of(300), 131072);
        Path pack = data.resolve("pack");
        fonts.write(pack);
        for (var node : doc.menu().nodes()) {
            int top = SpriteCanvas.textTop(doc.menu(), node, 300, 0, node.size());
            String font = fonts.textFont(top, node.size()).value();
            Set<Integer> expected = switch (node.size()) {
                case 8 -> Set.of((int) 'A');
                case 12 -> Set.of((int) '中');
                default -> Set.of((int) '5', (int) '0', (int) '1', (int) '%', (int) '/');
            };
            check(codes(pack, font).equals(expected), "Each size/offset exports only its displayed characters: " + font);
        }
        try (var paths = Files.list(pack.resolve("assets/dialogmenu/font"))) {
            check(paths.filter(p -> p.getFileName().toString().startsWith("text_")).count() == 3,
                "Static one-line labels do not reserve unrelated multiline offsets");
        }
        fails(() -> fonts.textWidth("Z"), "文字已变化");
        new SpriteCanvas().encode(doc.menu(), renderer.draw(doc.menu(), PREVIEW, 300, 131072),
            PREVIEW, fonts, region -> null);
        check(true, "Sparse static fonts remain renderable");

        Files.delete(data.resolve("layouts/sparse.yml"));
        Files.writeString(data.resolve("layouts/choices.yml"), """
            schema-version: 1
            id: choices
            characters: 戊
            variables:
              value:
                type: external
                fallback: 乙
                preview: 甲
                values: {third: 丙}
              unused:
                type: external
                preview: 霜
              open:
                type: external
                preview: 甲
              number:
                type: external
                preview: '1'
                max-length: 4
            samples:
              - {value: 丁}
            messages: {unused: 雪}
            pages:
              choices:
                canvas: {width: 300, height: 180}
                frontend:
                  dynamic:
                    type: text
                    content: '{value}'
                    actions: ['tell: 雾']
                  static:
                    type: text
                    content: A
                    size: 12
                    offset: {y: 30}
                  open:
                    type: text
                    content: '{open}'
                    size: 9
                    offset: {y: -30}
                  number:
                    type: text
                    content: '{number}'
                    size: 7
                    offset: {y: 50}
            """);
        docs = new LayoutStore(data.resolve("layouts")).load();
        var catalog = MenuTemplate.catalog(docs.values());
        var choiceFonts = FontPack.create(catalog, renderer, PREVIEW, List.of(300), 131072,
            MenuTemplate.characters(docs.values()));
        Path choices = data.resolve("choice-pack");
        choiceFonts.write(choices);
        doc = docs.get("choices");
        var dynamic = doc.menu().nodes().stream().filter(n -> n.id().equals("frontend.dynamic")).findFirst().orElseThrow();
        var fixed = doc.menu().nodes().stream().filter(n -> n.id().equals("frontend.static")).findFirst().orElseThrow();
        int top = SpriteCanvas.textTop(doc.menu(), dynamic, 300, 0, 8);
        String font = choiceFonts.textFont(top, 8).value();
        check(codes(choices, font).equals(Set.of((int) '甲', (int) '乙', (int) '丙', (int) '丁', (int) '戊')),
            "Only visible choices, fallback, samples and explicitly required dynamic characters are prepared");
        int fixedTop = SpriteCanvas.textTop(doc.menu(), fixed, 300, 0, 12);
        check(codes(choices, choiceFonts.textFont(fixedTop, 12).value()).equals(Set.of((int) 'A')),
            "Dynamic character declarations do not inflate unrelated static fonts");
        var open = doc.menu().nodes().stream().filter(n -> n.id().equals("frontend.open")).findFirst().orElseThrow();
        int openTop = SpriteCanvas.textTop(doc.menu(), open, 300, 0, 9);
        Set<Integer> openCodes = codes(choices, choiceFonts.textFont(openTop, 9).value());
        check(openCodes.containsAll("Zz_019!?".codePoints().boxed().toList()),
            "Open dynamic fields retain letters, digits, underscores and punctuation beyond their preview");
        var number = doc.menu().nodes().stream().filter(n -> n.id().equals("frontend.number")).findFirst().orElseThrow();
        int numberTop = SpriteCanvas.textTop(doc.menu(), number, 300, 0, 7);
        Set<Integer> numberCodes = codes(choices, choiceFonts.textFont(numberTop, 7).value());
        check(numberCodes.equals("0123456789.,-+EMAX戊".codePoints()
            .collect(HashSet::new, Set::add, Set::addAll)),
            "Numeric fields retain their formatter's output range, not every ASCII letter");
        for (String input : List.of("-12.50", "1234567890", "99999999999999999999")) {
            String output = MenuSessions.shortNumber(input, 4);
            check(numberCodes.containsAll(output.codePoints().boxed().toList()),
                "Every actual numeric formatter output has prepared glyphs: " + output);
        }
        for (String value : List.of("甲", "乙", "丙", "丁", "戊", "甲\n戊")) {
            var live = context(value);
            new SpriteCanvas().encode(doc.menu(), renderer.draw(doc.menu(), live, 300, 131072),
                live, choiceFonts, region -> null);
            check(true, "Declared dynamic text renders: " + value);
        }
        fails(() -> choiceFonts.textWidth("霜"), "文字已变化");
        fails(() -> choiceFonts.textWidth("雪"), "文字已变化");
        fails(() -> choiceFonts.textWidth("雾"), "文字已变化");
        Context updated = new Context() {
            public String expand(String text) {
                return PREVIEW.expand(text.replace("{open}", "Zz_019!?").replace("{number}", "MAX"));
            }
            public Icon item(int slot) { return null; }
            public boolean permitted(String permission) { return true; }
        };
        new SpriteCanvas().encode(doc.menu(), renderer.draw(doc.menu(), updated, 300, 131072),
            updated, choiceFonts, region -> null);
        check(true, "Updated dynamic text encodes against the original pack without regeneration");
        sharedUsedAtlases(temporary);
        return checks;
    }

    private static void sharedUsedAtlases(Path temporary) throws Exception {
        Path data = temporary.resolve("shared-used-glyphs");
        Files.createDirectories(data.resolve("layouts"));
        Files.writeString(data.resolve("layouts/shared.yml"), """
            schema-version: 1
            id: shared
            canvas: {width: 300, height: 180}
            frontend:
              head:
                type: text
                content: A
                offset: {y: 86}
              body:
                type: text
                content: AB
                offset: {y: 5}
            """);
        var renderer = new MenuRenderer(new VanillaAssets(data.resolve("images")));
        var docs = new LayoutStore(data.resolve("layouts")).load();
        var doc = docs.get("shared");
        var fonts = FontPack.create(docs.values(), renderer, PREVIEW, List.of(300), 131072);
        Path pack = data.resolve("pack");
        fonts.write(pack);
        String head = fonts.textFont(0, 8).value(), body = fonts.textFont(81, 8).value();
        check(codes(pack, head).equals(Set.of((int) 'A')),
            "Sharing an atlas must not expose another label's unused characters");
        check(codes(pack, body).equals(Set.of((int) 'A', (int) 'B')),
            "The second font keeps its complete declared character range");
        check(bitmapFiles(pack, head).equals(bitmapFiles(pack, body)),
            "Different used-character sets share the same source pixels for identical fragments");
        checks += RowPackTests.verify(pack, doc.menu(), renderer, PREVIEW, fonts, 300);
        var rowFonts = fonts;
        fails(() -> rowFonts.textFont(0, 8, true, net.kyori.adventure.text.Component.text("B")), "文字已变化");
        fails(() -> rowFonts.textWidth("未"), "文字已变化");
        String originalTexture = bitmapFiles(pack, head).iterator().next().substring("dialogmenu:".length());
        byte[] originalPng = Files.readAllBytes(pack.resolve("assets/dialogmenu/textures/" + originalTexture));
        Files.writeString(data.resolve("layouts/unrelated.yml"), """
            schema-version: 1
            id: unrelated
            canvas: {width: 300, height: 180}
            frontend:
              other-size:
                type: text
                content: XYZ0123456789
                size: 5
            """);
        docs = new LayoutStore(data.resolve("layouts")).load();
        fonts = FontPack.create(docs.values(), renderer, PREVIEW, List.of(300), 131072);
        Path expanded = data.resolve("other-size-pack");
        fonts.write(expanded);
        String sharedTexture = bitmapFiles(expanded, head).iterator().next().substring("dialogmenu:".length());
        check(java.util.Arrays.equals(originalPng,
            Files.readAllBytes(expanded.resolve("assets/dialogmenu/textures/" + sharedTexture))),
            "Characters used only at another size must not inflate this shared fragment's PNG");
        Files.delete(data.resolve("layouts/unrelated.yml"));

        Files.writeString(data.resolve("layouts/shared.yml"), """
            schema-version: 1
            id: shared
            canvas: {width: 300, height: 180}
            frontend:
              small:
                type: text
                content: '&oA'
                offset: {y: 20}
              large:
                type: text
                content: '&oAB'
                size: 12
                offset: {y: -20}
            """);
        docs = new LayoutStore(data.resolve("layouts")).load();
        doc = docs.get("shared");
        fonts = FontPack.create(docs.values(), renderer, PREVIEW, List.of(300), 131072);
        Path absolutePack = data.resolve("absolute-pack");
        fonts.write(absolutePack);
        String small = fonts.textFont(66, 8, false).value(), large = fonts.textFont(104, 12, false).value();
        check(bitmapFiles(absolutePack, small).equals(bitmapFiles(absolutePack, large)),
            "Whole-glyph compatibility fonts share source PNGs across sizes and offsets");
        check(codes(absolutePack, small).equals(Set.of((int) 'A'))
            && codes(absolutePack, large).equals(Set.of((int) 'A', (int) 'B')),
            "Whole-glyph sharing also retains per-font sparse mappings");
    }

    private static Set<String> bitmapFiles(Path pack, String font) throws Exception {
        var providers = JsonParser.parseString(Files.readString(pack.resolve(
            "assets/dialogmenu/font/" + font + ".json"))).getAsJsonObject().getAsJsonArray("providers");
        Set<String> files = new HashSet<>();
        for (var element : providers) {
            var provider = element.getAsJsonObject();
            if (provider.get("type").getAsString().equals("bitmap"))
                files.add(provider.get("file").getAsString());
        }
        return files;
    }

    private static Set<Integer> codes(Path pack, String font) throws Exception {
        var json = JsonParser.parseString(Files.readString(pack.resolve("assets/dialogmenu/font/" + font + ".json")))
            .getAsJsonObject();
        Set<Integer> codes = new HashSet<>();
        for (var element : json.getAsJsonArray("providers")) {
            var provider = element.getAsJsonObject();
            if (provider.get("type").getAsString().equals("bitmap")) {
                for (var row : provider.getAsJsonArray("chars"))
                    row.getAsString().codePoints().filter(cp -> cp != 0 && cp < 0xF0000).forEach(codes::add);
                String texture = provider.get("file").getAsString().substring("dialogmenu:".length());
                check(Files.isRegularFile(pack.resolve("assets/dialogmenu/textures/" + texture)),
                    "Every bitmap provider references an exported PNG");
            } else {
                for (String key : provider.getAsJsonObject("advances").keySet())
                    key.codePoints().filter(cp -> cp != ' ' && cp < 0xF0000).forEach(codes::add);
            }
        }
        return codes;
    }

    private static Context context(String value) {
        return new Context() {
            public String expand(String text) {
                return text.replace("{value}", value).replace("{open}", "甲").replace("{number}", "1");
            }
            public Icon item(int slot) { return null; }
            public boolean permitted(String permission) { return true; }
        };
    }

    private static void check(boolean condition, String message) {
        checks++;
        if (!condition) throw new AssertionError(message);
    }

    private static void fails(Runnable action, String message) {
        try { action.run(); }
        catch (IllegalArgumentException error) {
            check(error.getMessage().contains(message), "Unexpected error: " + error);
            return;
        }
        throw new AssertionError("Expected missing-character failure");
    }
}
