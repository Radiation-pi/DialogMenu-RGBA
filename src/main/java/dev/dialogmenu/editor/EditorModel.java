package dev.dialogmenu.editor;

import com.google.gson.*;
import java.util.*;

/** 源文档树是权威数据，世界空间节点只作为选择句柄。 */
public final class EditorModel {
    public record Item(String path, String id, JsonObject source, double x, double y,
                       double parentX, double parentY, double parentScale,
                       double scale, double width, double height, double z, boolean movable) {
        public String type() { return string(source, "type", ""); }
        public boolean contains(double px, double py) {
            return Math.abs(px - x) <= width / 2 && Math.abs(py - y) <= height / 2;
        }
    }
    private JsonObject tree = new JsonObject(), saved = new JsonObject();
    private final Deque<JsonObject> undo = new ArrayDeque<>(), redo = new ArrayDeque<>();
    private String page = "", selected = "";
    private boolean dragging;
    private double dragX, dragY, startX, startY;
    private double resizeWidth, resizeHeight, resizeScale;
    private JsonObject clipboard;

    public void load(JsonObject value) {
        tree = value.deepCopy(); saved = tree.deepCopy(); undo.clear(); redo.clear();
        page = pages().getFirst(); selected = ""; dragging = false;
    }
    public JsonObject tree() { return tree; }
    public List<String> pages() {
        return tree.has("pages") ? List.copyOf(tree.getAsJsonObject("pages").keySet())
            : List.of(string(tree, "id", "menu"));
    }
    public String page() { return page; }
    public void page(String value) {
        if (!pages().contains(value)) throw new IllegalArgumentException("Unknown page");
        page = value; selected = "";
    }
    public JsonObject pageTree() { return tree.has("pages") ? tree.getAsJsonObject("pages").getAsJsonObject(page) : tree; }
    public double canvasWidth() { return number(object(pageTree(), "canvas"), "width", 300); }
    public double canvasHeight() { return number(object(pageTree(), "canvas"), "height", 180); }
    public String selected() { return selected; }
    public void select(String path) { selected = path == null ? "" : path; }
    public Item selection() { return items().stream().filter(i -> i.path().equals(selected)).findFirst().orElse(null); }
    public void cycleSelection(int direction) {
        List<Item> available = items();
        if (available.isEmpty()) { selected = ""; return; }
        int current = -1;
        for (int i = 0; i < available.size(); i++) {
            if (available.get(i).path().equals(selected)) { current = i; break; }
        }
        selected = available.get(Math.floorMod(current + direction, available.size())).path();
    }
    public boolean dirty() { return !tree.equals(saved); }
    public void markSaved() { saved = tree.deepCopy(); }
    public void markSaved(JsonObject value) { saved = value.deepCopy(); }
    public List<Item> items() {
        List<Item> result = new ArrayList<>();
        walk(object(pageTree(), "frontend"), "frontend", 0, 0, 1, 0, true, result);
        return result;
    }
    private static void walk(JsonObject nodes, String prefix, double ox, double oy, double scale,
                             double oz, boolean parentMovable, List<Item> out) {
        for (var entry : nodes.entrySet()) {
            if (!entry.getValue().isJsonObject()) continue;
            var node = entry.getValue().getAsJsonObject();
            var offset = object(node, "offset");
            double x = ox + number(offset, "x", 0) * scale, y = oy + number(offset, "y", 0) * scale;
            double nodeScale = scale * number(node, "scale", 1);
            double z = oz + number(offset, "z", 0);
            boolean movable = parentMovable && numeric(offset, "x") && numeric(offset, "y")
                && numeric(node, "scale") && nodeScale > 0;
            String path = prefix + "/" + entry.getKey();
            String type = string(node, "type", "");
            double width = number(node, "width", type.equals("text") ?
                Math.max(8, string(node, "content", "").replaceAll("[&§][0-9a-flr]", "").length()
                    * number(node, "size", 8) * .65) : type.equals("group") ? 24 : 16);
            double height = number(node, "height", type.equals("text") ? number(node, "size", 8) : 16);
            out.add(new Item(path, entry.getKey(), node, x, y, ox, oy, scale, nodeScale,
                Math.max(1, width * nodeScale), Math.max(1, height * nodeScale), z, movable));
            if (type.equals("group"))
                walk(object(node, "children"), path + "/children", x, y, nodeScale, z, movable, out);
        }
    }
    public void replaceSelection(JsonObject source) {
        Item item = selection();
        if (item == null) return;
        checkpoint();
        parent().add(item.id(), source.deepCopy());
    }
    public void replaceTree(JsonObject value) {
        checkpoint();
        tree = value.deepCopy();
        page = pages().contains(page) ? page : pages().getFirst();
        selected = "";
    }
    public Item hit(double x, double y) {
        return items().stream()
            .filter(i -> !i.type().equals("group") && i.contains(x, y))
            .max(Comparator.comparingDouble(Item::z)).orElse(null);
    }
    private void checkpoint() {
        undo.addLast(tree.deepCopy());
        while (undo.size() > 50) undo.removeFirst();
        redo.clear();
    }
    public void beginDrag() {
        Item item = selection();
        if (item == null || !item.movable()) throw new IllegalArgumentException("Expression-based positions cannot be dragged; edit the expression");
        JsonObject position = object(item.source(), "offset");
        startX = number(position, "x", 0); startY = number(position, "y", 0);
        dragX = dragY = 0;
        checkpoint(); dragging = true;
    }
    public void drag(double dx, double dy, double grid) {
        if (!dragging) return;
        Item item = selection();
        if (item == null) return;
        dragX += dx; dragY += dy;
        var node = item.source();
        var offset = ensure(node, "offset");
        offset.addProperty("x", snap(startX + dragX / item.parentScale(), grid));
        offset.addProperty("y", snap(startY + dragY / item.parentScale(), grid));
    }
    public void beginResize() {
        Item item = selection();
        if (item == null || !item.movable()) throw new IllegalArgumentException("Element cannot be resized");
        resizeWidth = item.width();
        resizeHeight = item.height();
        resizeScale = number(item.source(), "scale", 1);
        checkpoint();
        dragging = true;
    }
    public void resize(double width, double height) {
        Item item = selection();
        if (item == null || !item.movable()) return;
        if (!Double.isFinite(width) || !Double.isFinite(height) || width <= 0 || height <= 0)
            throw new IllegalArgumentException("Invalid element size");
        if (Set.of("rectangle", "frame", "line").contains(item.type())) {
            if (!numeric(item.source(), "width") || !numeric(item.source(), "height"))
                throw new IllegalArgumentException("Edit dynamic dimensions as expressions");
            double scale = resizeScale * item.parentScale();
            item.source().addProperty("width", width / scale);
            item.source().addProperty("height", height / scale);
        } else item.source().addProperty("scale", resizeScale * Math.min(width / resizeWidth, height / resizeHeight));
    }
    public void resizeBy(double dx, double dy) {
        resize(Math.max(4, resizeWidth + dx), Math.max(4, resizeHeight + dy));
    }
    public void endDrag() { dragging = false; }
    private static double snap(double value, double grid) { return grid > 0 ? Math.round(value / grid) * grid : value; }
    public void set(String property, JsonElement value) {
        Item item = selection();
        if (item == null) return;
        checkpoint();
        JsonObject node = item.source();
        String[] parts = property.split("\\.");
        for (int i = 0; i + 1 < parts.length; i++) node = ensure(node, parts[i]);
        if (value == null || value.isJsonNull()) node.remove(parts[parts.length - 1]);
        else node.add(parts[parts.length - 1], value);
    }
    public void add(String type) {
        if (!Set.of("text", "image", "rectangle", "frame", "line", "group").contains(type))
            throw new IllegalArgumentException("Add a text or image element and configure its click actions");
        checkpoint();
        String prefix = "frontend";
        JsonObject parent = ensure(pageTree(), prefix);
        if (!selected.isBlank()) {
            Item item = selection();
            if (item != null && item.type().equals("group")) {
                parent = ensure(item.source(), "children"); prefix = item.path() + "/children";
            }
        }
        int index = 1; while (parent.has(type + "-" + index)) index++;
        String id = type + "-" + index;
        JsonObject node = new JsonObject();
        node.addProperty("type", type);
        switch (type) {
            case "text" -> { node.addProperty("content", "Text"); node.addProperty("size", 8); }
            case "image" -> {
                node.addProperty("source", "minecraft:item/book"); node.addProperty("width", 32); node.addProperty("height", 32);
            }
            case "rectangle", "frame", "line" -> {
                node.addProperty("width", 60); node.addProperty("height", type.equals("line") ? 2 : 24);
                node.addProperty("color", "#CBD0D5");
            }
            case "group" -> node.add("children", new JsonObject());
            default -> {}
        }
        parent.add(id, node); selected = prefix + "/" + id;
    }
    private JsonObject parent() {
        String[] parts = selected.split("/");
        JsonObject result = pageTree();
        for (int i = 0; i + 1 < parts.length; i++) result = result.getAsJsonObject(parts[i]);
        return result;
    }
    public void delete() {
        if (selection() == null) return;
        checkpoint(); String id = selected.substring(selected.lastIndexOf('/') + 1);
        parent().remove(id); selected = "";
    }
    public void duplicate() {
        Item item = selection(); if (item == null) return;
        checkpoint(); var parent = parent();
        int i = 1; while (parent.has(item.id() + "-copy-" + i)) i++;
        String id = item.id() + "-copy-" + i;
        parent.add(id, item.source().deepCopy());
        selected = selected.substring(0, selected.lastIndexOf('/') + 1) + id;
    }
    public void copy() {
        Item item = selection();
        if (item != null) clipboard = item.source().deepCopy();
    }
    public void paste() {
        if (clipboard == null) return;
        checkpoint();
        String parentPath = selected.isBlank() ? "frontend" : selected.substring(0, selected.lastIndexOf('/') + 1);
        JsonObject parent = selected.isBlank() ? ensure(pageTree(), "frontend") : parent();
        int i = 1;
        String id;
        do { id = "paste-" + i++; } while (parent.has(id));
        JsonObject pasted = clipboard.deepCopy();
        var offset = ensure(pasted, "offset");
        offset.addProperty("x", number(offset, "x", 0) + 8);
        offset.addProperty("y", number(offset, "y", 0) - 8);
        parent.add(id, pasted);
        selected = parentPath + id;
    }
    public void addImage(String source) {
        add("image");
        Item item = selection();
        if (item != null) item.source().addProperty("source", source);
    }
    public void undo() {
        if (undo.isEmpty()) return;
        redo.addLast(tree.deepCopy()); tree = undo.removeLast(); selected = ""; dragging = false;
    }
    public void redo() {
        if (redo.isEmpty()) return;
        undo.addLast(tree.deepCopy()); tree = redo.removeLast(); selected = ""; dragging = false;
    }
    public static JsonObject object(JsonObject node, String key) {
        return node.has(key) && node.get(key).isJsonObject() ? node.getAsJsonObject(key) : new JsonObject();
    }
    private static JsonObject ensure(JsonObject node, String key) {
        if (!node.has(key) || !node.get(key).isJsonObject()) node.add(key, new JsonObject());
        return node.getAsJsonObject(key);
    }
    public static String string(JsonObject node, String key, String fallback) {
        return node.has(key) && node.get(key).isJsonPrimitive() ? node.get(key).getAsString() : fallback;
    }
    public static double number(JsonObject node, String key, double fallback) {
        try {
            double value = Double.parseDouble(string(node, key, Double.toString(fallback)));
            return Double.isFinite(value) ? value : fallback;
        } catch (RuntimeException error) { return fallback; }
    }
    private static boolean numeric(JsonObject node, String key) {
        return !node.has(key) || Double.isFinite(number(node, key, Double.NaN));
    }
}
