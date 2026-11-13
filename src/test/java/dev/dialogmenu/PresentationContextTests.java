package dev.dialogmenu;

import java.nio.file.Path;
import java.util.*;
import java.util.concurrent.Executors;
import net.kyori.adventure.text.event.ClickEvent;
import net.kyori.adventure.text.serializer.gson.GsonComponentSerializer;
import static dev.dialogmenu.MenuModel.*;

final class PresentationContextTests {
    private static int checks;
    private static void check(boolean value, String message) {
        if (!value) throw new AssertionError(message);
        checks++;
    }

    static int run(Path root) throws Exception {
        var document = LayoutStore.loadText("snapshot-test", """
            schema-version: 3
            id: snapshot-test
            canvas: {width: 452, height: 205, display-width: 452}
            frontend:
              top:
                type: text
                content: '{player}'
                offset: {x: 226, y: 20}
                permission: test.click
                tooltip: ['Hello {player}', 'Second line']
                actions: {single: 'tell: {player}', double: 'tell: twice {player}'}
            native-controls:
              overlay: true
              body-rows: 7
              inputs:
                name: {type: text, label: 'Name {player}', initial: ''}
              buttons:
                denied: {label: Hidden, permission: test.denied}
                submit:
                  label: 'Send {player}'
                  tooltip: ['Submit {player}', 'Second line']
                  actions: 'tell: {input.name}'
            """).get("snapshot-test");
        Thread owner = Thread.currentThread();
        var liveAllowed = new HashSet<>(Set.of("", "test.click"));
        Context live = new Context() {
            public String expand(String text) {
                if (Thread.currentThread() != owner) throw new AssertionError("worker expanded live state");
                return text.replace("{player}", "Alex");
            }
            public Icon item(int slot) { throw new AssertionError("unexpected inventory read"); }
            public boolean permitted(String permission) {
                if (Thread.currentThread() != owner) throw new AssertionError("worker queried live permission");
                return liveAllowed.contains(permission);
            }
        };
        var frozen = DialogMenuPlugin.freezeContext(document, live);
        check(frozen.permissionsMatch(live), "unchanged permissions rejected");
        var renderer = new MenuRenderer(new VanillaAssets(root.resolve("snapshot-images")));
        var fonts = FontPack.create(List.of(document), renderer, live, List.of(452), 1_048_576,
            "", List.of(1.0), List.of(1.0), List.of());
        var frame = renderer.drawRuntime(document.menu(), live, 452, 1_048_576);
        var expected = new SpriteCanvas().encodeNative(document, frame, live, fonts,
            region -> ClickEvent.runCommand("/test " + region.id()));
        var serializer = GsonComponentSerializer.gson();
        String body = serializer.serialize(expected.body().component());
        String foreground = serializer.serialize(expected.foreground());
        UUID token = UUID.randomUUID();
        var region = LayoutStore.interactiveRegions(document.menu()).getFirst();
        var target = ClickGestures.Target.of(token, document.menu(), region, live);
        liveAllowed.remove("test.click");
        check(!frozen.permissionsMatch(live), "changed permission was not detected before publish");
        check(frozen.permitted("test.click") && !frozen.permitted("test.denied"),
            "frozen permissions changed with source");
        try (var worker = Executors.newSingleThreadExecutor()) {
            worker.submit(() -> {
                try {
                    var rendered = new MenuRenderer(renderer.assets).drawRuntime(document.menu(), frozen, 452, 1_048_576);
                    var actual = new SpriteCanvas().encodeNative(document, rendered, frozen, fonts,
                        hit -> ClickEvent.runCommand("/test " + hit.id()));
                    check(body.equals(serializer.serialize(actual.body().component())), "frozen body differs from live rendering");
                    check(foreground.equals(serializer.serialize(actual.foreground())), "frozen native graphics differ");
                    check(target.equals(ClickGestures.Target.of(token, document.menu(), region, frozen)),
                        "frozen actions changed");
                    for (var field : document.controls().inputs()) {
                        check(frozen.expand(field.initial()).equals(""), "empty native initial text lost");
                        check(frozen.expand(field.label()).equals("Name Alex"), "native label not captured");
                    }
                    check(frozen.expand(String.join("\n", document.controls().buttons().getLast().tooltip()))
                        .equals("Submit Alex\nSecond line"), "multiline native tooltip not captured");
                } catch (Exception error) { throw new RuntimeException(error); }
            }).get();
        }
        System.out.println("PASS presentation context checks=" + checks);
        return checks;
    }
}
