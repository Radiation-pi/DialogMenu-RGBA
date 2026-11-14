package dev.dialogmenu;

import com.google.gson.JsonArray;
import java.nio.file.*;
import java.util.*;
import java.util.concurrent.Executors;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.event.ClickEvent;
import net.kyori.adventure.text.serializer.gson.GsonComponentSerializer;
import static dev.dialogmenu.MenuModel.*;

final class AvatarSnapshotCheck {
    static void run(Path root) throws Exception {
        if (!NativePlayerHeads.supported()) return;
        Path data = Files.createTempDirectory(root, "avatar-snapshot-");
        Files.createDirectories(data.resolve("layouts"));
        Files.writeString(data.resolve("config.yml"),
            "default-layout: avatar\ncanvas-width: 300\nresource-build-cpu-percent: 100\n");
        Files.writeString(data.resolve("layouts/avatar.yml"), """
            schema-version: 3
            id: avatar
            canvas: {width: 300, height: 180}
            frontend:
              head:
                type: player-head
                offset: {x: 150, y: 30}
                tooltip: 'Profile {player}'
                actions: 'tell: {player}'
            """);
        Thread owner = Thread.currentThread();
        Context preview = context("Preview", null);
        try (var engine = new ReloadEngine(data, preview)) {
            var snapshot = engine.prepare().get().snapshot();
            var doc = snapshot.documents().get("avatar");
            var codec = GsonComponentSerializer.gson();
            var canvas = new SpriteCanvas();
            for (String name : List.of("Alex", "Steve", "Alex")) {
                Context live = context(name, owner);
                var frozen = DialogMenuPlugin.freezeContext(doc, live);
                var renderer = new MenuRenderer(snapshot.assets());
                var frame = snapshot.presentations().frame(renderer, doc.menu(), frozen, 300, 655360);
                var click = ClickEvent.runCommand("/profile-" + name);
                String expected = codec.serialize(canvas.encode(doc.menu(), frame, live, snapshot.fonts(),
                    region -> click).component());
                try (var worker = Executors.newSingleThreadExecutor()) {
                    String actual = worker.submit(() -> codec.serialize(canvas.encode(doc.menu(), frame, frozen,
                        snapshot.fonts(), region -> click, snapshot.presentations()).component())).get();
                    if (!expected.equals(actual) || !actual.contains(name) || actual.contains("Preview"))
                        throw new AssertionError("compiled rendering reused another player's head or actions");
                }
            }
            String overlay = Files.readString(data.resolve("layouts/avatar.yml"))
                .replace("y: 30", "y: 174") + """
                native-controls:
                  overlay: true
                  body-rows: 1
                  buttons:
                    submit: {label: Submit, width: 280}
                """;
            var blocked = LayoutStore.loadText("avatar", overlay).get("avatar");
            var frame = snapshot.renderer().drawRuntime(blocked.menu(), preview, 300, 655360);
            try {
                canvas.encodeNative(blocked, frame, preview, snapshot.fonts(), region -> null);
                throw new AssertionError("native bar accepted a player head");
            } catch (IllegalArgumentException expected) {
                if (!expected.getMessage().contains("玩家头像")) throw expected;
            }
        }
        System.out.println("PASS avatar snapshot: three player renders, worker isolation and native-bar rejection");
    }

    private static Context context(String name, Thread owner) {
        return new Context() {
            private void mainThread() {
                if (owner != null && Thread.currentThread() != owner)
                    throw new AssertionError("worker read live player state");
            }
            public String expand(String text) { mainThread(); return text.replace("{player}", name); }
            public Icon item(int slot) { throw new AssertionError("unexpected inventory read"); }
            public boolean permitted(String permission) { mainThread(); return true; }
            public Component playerHead() {
                mainThread();
                return NativePlayerHeads.profile(UUID.nameUUIDFromBytes(name.getBytes(java.nio.charset.StandardCharsets.UTF_8)),
                    name, new JsonArray());
            }
        };
    }
}
