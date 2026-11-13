package dev.dialogmenu;

import java.nio.file.*;
import java.util.*;
import static dev.dialogmenu.MenuModel.*;

final class NativeBackgroundTests {
    private static int checks;
    private static void check(boolean value, String message) {
        if (!value) throw new AssertionError(message);
        checks++;
    }
    private static LayoutStore.Document parse(int extension, int height) throws Exception {
        return LayoutStore.loadText("background-test", """
            schema-version: 3
            id: background-test
            canvas: {width: 452, height: %d, display-width: 452}
            frontend:
              background:
                type: rectangle
                width: 452
                height: %d
                color: '#20262D'
                offset: {x: 226, y: %s}
              title: {type: text, content: Background, alignment: left, offset: {x: 18, y: 18, z: 220}}
            native-controls:
              overlay: true
              body-rows: 7
              background-extension: %d
              inputs:
                name: {type: text, width: 260, label-visible: false}
                notes: {type: text, width: 260, label-visible: false, multiline: true, height: 32}
                amount: {type: slider, width: 260}
              buttons:
                submit: {label: Submit, width: 260, z: 3}
            """.formatted(height, height, height / 2.0, extension)).get("background-test");
    }
    static int run(Path root) throws Exception {
        checks = 0;
        var before = parse(0, 205);
        var extended = parse(24, 229);
        var oldLayout = NativeInk.layout(before.controls(), 452, 205, p -> true);
        var newLayout = NativeInk.layout(extended.controls(), 452, 229, p -> true);
        check(oldLayout.controls().equals(newLayout.controls()), "background extension moved native controls");
        check(oldLayout.bodyRows() == newLayout.bodyRows(), "background extension pushed down the native bar");
        check(oldLayout.carrier() == newLayout.carrier(), "background extension changed native label positioning");
        var submit = newLayout.controls().getLast();
        check(229 - (newLayout.top() + submit.y() + submit.height()) >= 18,
            "submit button has no visible bottom margin");
        check(parse(0, 229).controls().backgroundExtension() == 0, "default extension changed");
        check(NativeInk.layout(parse(0, 229).controls(), 452, 229, p -> true).bodyRows() > oldLayout.bodyRows(),
            "default layout no longer preserves automatic body-row behavior");
        for (int invalid : List.of(-1, 257)) {
            try { parse(invalid, 229); throw new AssertionError("invalid extension accepted"); }
            catch (IllegalArgumentException expected) { checks++; }
        }
        var context = new Context() {
            public String expand(String text) { return text; }
            public Icon item(int slot) { return null; }
            public boolean permitted(String permission) { return true; }
        };
        var renderer = new MenuRenderer(new VanillaAssets(root.resolve("background-images")));
        var fonts = FontPack.create(List.of(extended), renderer, context, List.of(452), 1_048_576,
            "", List.of(1.0), List.of(1.0), List.of());
        var canvas = new SpriteCanvas();
        for (double scale : List.of(.8, 1.0, 1.37, 2.2)) {
            var active = CanvasScale.context(context, scale);
            var frame = renderer.drawRuntime(extended.menu(), active, 452, 1_048_576);
            var output = canvas.encodeNative(extended, frame, active, fonts, r -> null);
            check(output.layout().controls().equals(newLayout.controls()), "scale changed native rectangles");
            check(!output.foreground().equals(net.kyori.adventure.text.Component.empty()),
                "extended background was not encoded");
            SpriteCanvas.nativeLabel("Submit", fonts, frame, output);
            checks++;
        }
        fonts.write(root.resolve("native-background-pack"));
        check(Files.size(root.resolve("native-background-pack/assets/dialogmenu/shaders/include/native_profiles.glsl")) > 0,
            "extended native shader profiles were not written");
        System.out.println("PASS native background extension checks=" + checks);
        return checks;
    }
}
