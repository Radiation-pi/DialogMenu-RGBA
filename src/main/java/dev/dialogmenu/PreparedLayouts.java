package dev.dialogmenu;

import java.io.IOException;
import java.util.*;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.event.*;
import static dev.dialogmenu.MenuModel.*;

/** Snapshot-owned static fragments. Player values and callbacks are never retained here. */
final class PreparedLayouts {
    static final ClickEvent CLICK_SLOT = ClickEvent.runCommand("/dialogmenu:compiled-slot");
    static final HoverEvent<Component> HOVER_SLOT = HoverEvent.showText(Component.text("dialogmenu:compiled-slot"));
    private record FrameKey(Menu menu, int width, double scale, int pixels) {}
    private record FragmentKey(Node node, int menuWidth, int menuHeight, boolean scaleText,
                               int width, int height, double textScale, double canvasScale) {}
    private final FontPack fonts;
    private final Map<FrameKey, Rendered> frames = new LinkedHashMap<>(16, .75f, true);
    private final Map<FragmentKey, Fragment> fragments = new LinkedHashMap<>(16, .75f, true);
    private int weight;
    private static final int MAX_COMPONENTS = 65_536;
    private static final Fragment TOO_LARGE = new Fragment(List.of(), false);

    PreparedLayouts(FontPack fonts) { this.fonts = fonts; }

    Rendered frame(MenuRenderer renderer, Menu menu, Context context, int width, int pixels) throws IOException {
        var key = new FrameKey(menu, width, context.canvasScale(), pixels);
        synchronized (this) {
            var found = frames.get(key);
            if (found != null) return found;
        }
        var result = renderer.drawRuntime(menu, context, width, pixels);
        synchronized (this) {
            frames.put(key, result);
            if (frames.size() > 32) frames.remove(frames.keySet().iterator().next());
        }
        return result;
    }

    Fragment fragment(Menu menu, Node node, Rendered frame, Context context, SpriteCanvas canvas) {
        if (node.type().equals("text")
            && (MenuTemplate.dynamicText(node) || !context.expand(node.text()).equals(node.text()))) return null;
        var key = new FragmentKey(node, menu.width(), menu.height(), menu.scaleTextWithCanvas(),
            frame.width(), frame.height(), context.textScale(), context.canvasScale());
        synchronized (this) {
            var found = fragments.get(key);
            if (found != null) return found == TOO_LARGE ? null : found;
        }
        var result = canvas.compileNode(menu, node, frame, context, fonts);
        if (result.weight > MAX_COMPONENTS) result = TOO_LARGE;
        synchronized (this) {
            var found = fragments.get(key);
            if (found != null) return found == TOO_LARGE ? null : found;
            fragments.put(key, result);
            weight += result.weight;
            var iterator = fragments.values().iterator();
            while (weight > MAX_COMPONENTS || fragments.size() > 4096) {
                weight -= iterator.next().weight;
                iterator.remove();
            }
        }
        return result == TOO_LARGE ? null : result;
    }

    void prepare(ReloadEngine.Snapshot snapshot, Context preview) throws Exception {
        var settings = snapshot.settings();
        var canvas = new SpriteCanvas();
        var renderer = new MenuRenderer(snapshot.assets());
        for (var doc : MenuTemplate.catalog(snapshot.documents().values())) {
            BuildBudget.checkpoint();
            Context base = MenuTemplate.preview(doc, preview);
            Context context = new Context() {
                public String expand(String text) { return base.expand(text); }
                public Icon item(int slot) { return base.item(slot); }
                public boolean permitted(String permission) { return base.permitted(permission); }
                public double textScale() { return settings.defaultTextScale(); }
                public double canvasScale() { return settings.defaultCanvasScale(); }
            };
            // Prepare the configured opening size, not every possible personal scale.
            var frame = frame(renderer, doc.menu(), context, settings.width(), settings.maxPixels());
            for (var node : doc.menu().nodes()) {
                BuildBudget.checkpoint();
                fragment(doc.menu(), node, frame, context, canvas);
            }
        }
    }

    static final class Fragment {
        private record Part(int row, Patch patch) {}
        private final List<Part> parts;
        private final boolean omitUnboundHits;
        final int weight;
        Fragment(List<List<Component>> rows, boolean omitUnboundHits) {
            var parts = new ArrayList<Part>();
            int weight = 0;
            for (int row = 0; row < rows.size(); row++) for (var component : rows.get(row)) {
                var patch = new Patch(component);
                parts.add(new Part(row, patch));
                weight += patch.weight;
            }
            this.parts = List.copyOf(parts);
            this.weight = Math.max(1, weight);
            this.omitUnboundHits = omitUnboundHits;
        }
        void append(List<List<Component>> rows, ClickEvent click, HoverEvent<Component> hover) {
            for (var part : parts) {
                if (omitUnboundHits && part.patch.dynamic && click == null && hover == null) continue;
                rows.get(part.row).add(part.patch.bind(click, hover));
            }
        }
    }

    private static final class Patch {
        private final Component source;
        private final List<Patch> children;
        private final boolean clickSlot, hoverSlot, dynamic;
        private final int weight;
        Patch(Component source) {
            this.source = source;
            clickSlot = CLICK_SLOT.equals(source.clickEvent());
            hoverSlot = HOVER_SLOT.equals(source.hoverEvent());
            var children = source.children().stream().map(Patch::new).toList();
            dynamic = clickSlot || hoverSlot || children.stream().anyMatch(child -> child.dynamic);
            weight = 1 + children.stream().mapToInt(child -> child.weight).sum();
            this.children = dynamic ? children : List.of();
        }
        Component bind(ClickEvent click, HoverEvent<Component> hover) {
            if (!dynamic) return source;
            Component result = source;
            if (clickSlot) result = result.clickEvent(click);
            if (hoverSlot) result = result.hoverEvent(hover);
            if (!children.isEmpty()) result = result.children(children.stream().map(child -> child.bind(click, hover)).toList());
            return result;
        }
    }
}
