package dev.dialogmenu;

import java.util.List;
import java.util.Map;

/** 内部中心原点、Y向上；入口换算版本2的顶部中央和版本3的左上角坐标。 */
public final class MenuModel {
    private MenuModel() {}

    public record Node(String id, String type, double x, double y, double z, double scale,
                       double width, double height, int argb, String text, String source,
                       String alignment, int size, double thickness, double rotation,
                       int columns, int slotSize, List<Integer> slots, int maxLines,
                       Interaction interaction, boolean reuseTexture, TextureRegion atlas) {
        public Node {
            if (!Double.isFinite(scale) || scale <= 0)
                throw new IllegalArgumentException("scale须为有限正数");
        }
        public Node(String id, String type, double x, double y, double z, double scale,
                    double width, double height, int argb, String text, String source,
                    String alignment, int size, double thickness, double rotation,
                    int columns, int slotSize, List<Integer> slots, int maxLines,
                    Interaction interaction, boolean reuseTexture) {
            this(id, type, x, y, z, scale, width, height, argb, text, source, alignment,
                size, thickness, rotation, columns, slotSize, slots, maxLines, interaction, reuseTexture, null);
        }
        public Node(String id, String type, double x, double y, double z, double scale,
                    double width, double height, int argb, String text, String source,
                    String alignment, int size, double thickness, double rotation,
                    int columns, int slotSize, List<Integer> slots, int maxLines) {
            this(id, type, x, y, z, scale, width, height, argb, text, source, alignment,
                size, thickness, rotation, columns, slotSize, slots, maxLines, null, false);
        }
        public Node(String id, String type, double x, double y, double z, double scale,
                    double width, double height, int argb, String text, String source,
                    String alignment, int size, double thickness, double rotation,
                    int columns, int slotSize, List<Integer> slots) {
            this(id, type, x, y, z, scale, width, height, argb, text, source, alignment,
                size, thickness, rotation, columns, slotSize, slots, 0);
        }
    }

    public record Interaction(boolean clickable, String permission, List<String> tooltip, List<String> actions,
                              List<String> doubleActions) {
        public Interaction(boolean clickable, String permission, List<String> tooltip, List<String> actions) {
            this(clickable, permission, tooltip, actions, List.of());
        }
        Region region(Node node) {
            return new Region(node.id(), node.x(), node.y(), node.width(), node.height(), 0,
                permission, tooltip, clickable ? actions : List.of(), clickable ? doubleActions : List.of());
        }
    }

    public record Region(String id, double x, double y, double width, double height, int priority,
                         String permission, List<String> tooltip, List<String> actions, List<String> doubleActions) {
        public Region(String id, double x, double y, double width, double height, int priority,
                      String permission, List<String> tooltip, List<String> actions) {
            this(id, x, y, width, height, priority, permission, tooltip, actions, List.of());
        }
        public boolean hasActions() { return !actions.isEmpty() || !doubleActions.isEmpty(); }
        public boolean contains(double px, double py) {
            return px >= x - width / 2 && px < x + width / 2
                && py > y - height / 2 && py <= y + height / 2;
        }
    }

    public record Menu(String id, String permission, int width, int height,
                       List<Node> nodes, List<Region> regions, Boolean closeWithEscape, boolean scaleTextWithCanvas,
                       int displayWidth) {
        public Menu(String id, String permission, int width, int height, List<Node> nodes, List<Region> regions,
                    Boolean closeWithEscape, boolean scaleTextWithCanvas) {
            this(id, permission, width, height, nodes, regions, closeWithEscape, scaleTextWithCanvas, 0);
        }
        public Menu(String id, String permission, int width, int height, List<Node> nodes, List<Region> regions) {
            this(id, permission, width, height, nodes, regions, null, false);
        }
        int requestedWidth(int fallback) { return displayWidth == 0 ? fallback : displayWidth; }
        public Region hit(double x, double y) {
            for (Region region : regions) if (region.contains(x, y)) return region;
            return null;
        }
    }

    public interface Context {
        String expand(String text);
        Icon item(int slot);
        boolean permitted(String permission);
        default double textScale() { return 1; }
        default double canvasScale() { return 1; }
    }

    public record Icon(String material, int amount, String name, List<String> lore) {}
    public record PreviewSlot(int x, int y, int width, int height, Icon item) {
        public boolean contains(int px, int py) {
            return px >= x && px < x + width && py >= y && py < y + height;
        }
    }
    public record TextureRegion(int columns, int rows, int column, int row) {
        public List<String> chars(int code) {
            List<String> result = new java.util.ArrayList<>();
            for (int y = 0; y < rows; y++) {
                StringBuilder line = new StringBuilder();
                for (int x = 0; x < columns; x++) line.appendCodePoint(x == column && y == row ? code : 0);
                result.add(line.toString());
            }
            return result;
        }
    }
    public record Sprite(String id, int x, int y, java.awt.image.BufferedImage image,
                         String texture, java.awt.image.BufferedImage original, TextureRegion textureRegion,
                         int width, int height) {
        public Sprite(String id, int x, int y, java.awt.image.BufferedImage image,
                      String texture, java.awt.image.BufferedImage original, TextureRegion textureRegion) {
            this(id, x, y, image, texture, original, textureRegion, image.getWidth(), image.getHeight());
        }
        public Sprite(String id, int x, int y, java.awt.image.BufferedImage image,
                      String texture, java.awt.image.BufferedImage original) {
            this(id, x, y, image, texture, original, null);
        }
        public Sprite(String id, int x, int y, java.awt.image.BufferedImage image) {
            this(id, x, y, image, null, null);
        }
    }
    public record Rendered(java.awt.image.BufferedImage image, List<Sprite> sprites, List<Node> textNodes,
                           int width, int height) {
        public Rendered(java.awt.image.BufferedImage image, List<Sprite> sprites, List<Node> textNodes) {
            this(image, sprites, textNodes, image.getWidth(), image.getHeight());
        }
    }
}
