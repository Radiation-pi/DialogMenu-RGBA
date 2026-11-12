package dev.dialogmenu;

import java.awt.AlphaComposite;
import java.awt.Color;
import java.awt.Graphics2D;
import java.awt.Rectangle;
import java.awt.RenderingHints;
import java.awt.geom.*;
import java.awt.image.BufferedImage;
import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.LinkedHashMap;
import java.util.Map;
import static dev.dialogmenu.MenuModel.*;

/** 每个图形独立生成精灵，合成图仅用于离线预览。 */
final class MenuRenderer {
    static final int MAX_WIDTH = 1014;
    static java.util.SortedSet<Integer> preparedWidths(Menu menu, java.util.Collection<Integer> widths,
                                                      double canvasScale, int maxPixels) {
        var result = new java.util.TreeSet<>(widths);
        int width = (int) Math.round(MAX_WIDTH * canvasScale);
        int height = Math.max(1, (int) Math.round(menu.height() * width / (double) menu.width()));
        boolean modelsFit = menu.nodes().stream().filter(node -> node.type().equals("model"))
            .allMatch(node -> {
                long size = Math.round(node.size() * node.scale() * width / menu.width());
                return size >= 1 && size <= 1024;
            });
        if (width >= 64 && width <= MAX_WIDTH && height <= 1024 && (long) width * height <= maxPixels
            && modelsFit)
            result.add(MAX_WIDTH);
        return result;
    }
    static boolean modelProtocolFits(Menu menu, Rendered frame) {
        double zoom = frame.width() / (double) menu.width();
        for (Node node : menu.nodes()) {
            if (!node.type().equals("model")) continue;
            long size = Math.round(node.size() * node.scale() * zoom);
            if (size < 1 || size > 1024) return false;
        }
        return true;
    }
    final VanillaAssets assets;
    private record Graphic(String id, String type, double x, double y, double scale, double width,
                           double height, int argb, String source, double thickness, double rotation,
                           boolean reuse, TextureRegion atlas, boolean interactive, int canvasWidth, int canvasHeight,
                           int logicalHeight, double zoom) {}
    private final Map<Graphic, Sprite> runtimeSprites = new LinkedHashMap<>(16, .75f, true);
    private final long pixelBudget;
    private final int spriteBudget;
    private long cachedPixels;
    MenuRenderer(VanillaAssets assets) { this(assets, 1_048_576, 512); }
    MenuRenderer(VanillaAssets assets, long pixelBudget) {
        this(assets, pixelBudget, 4096);
    }
    private MenuRenderer(VanillaAssets assets, long pixelBudget, int spriteBudget) {
        this.assets = assets;
        this.pixelBudget = pixelBudget;
        this.spriteBudget = spriteBudget;
    }

    Rendered draw(Menu menu, Context context, int width, int maxPixels) throws IOException {
        return draw(menu, context, width, maxPixels, true);
    }
    Rendered drawRuntime(Menu menu, Context context, int width, int maxPixels) throws IOException {
        return draw(menu, context, width, maxPixels, false);
    }
    private Rendered draw(Menu menu, Context context, int width, int maxPixels, boolean preview) throws IOException {
        width = (int) Math.round(width * context.canvasScale());
        int height = Math.max(1, (int) Math.round(menu.height() * width / (double) menu.width()));
        if (width < 64 || width > MAX_WIDTH || height > 1024 || (long) width * height > maxPixels)
            throw new IllegalArgumentException("当前画布比例/尺寸超出正文范围，请减小尺寸；不自动重排布局");
        BufferedImage image = preview ? new BufferedImage(width, height, BufferedImage.TYPE_INT_ARGB) : null;
        Graphics2D root = preview ? image.createGraphics() : null;
        double zoom = width / (double) menu.width();
        List<Sprite> sprites = new ArrayList<>();
        List<Node> textNodes = new ArrayList<>();
        for (Node original : menu.nodes()) {
            Node n = original;
            if (n.type().equals("model")) continue;
            if (n.type().equals("text")) {
                if (n.rotation() != 0) throw new IllegalArgumentException(n.id() + " 原生文字不支持旋转");
                textNodes.add(n);
                continue;
            }
            int argb = n.type().equals("image") ? assets.imageColor(n.source(), n.argb()) : n.argb();
            Graphic key = new Graphic(n.id(), n.type(), n.x(), n.y(), n.scale(), n.width(),
                n.height(), argb, n.source(), n.thickness(), n.rotation(), n.reuseTexture(), n.atlas(),
                n.interaction() != null, width, height, menu.height(), zoom);
            if (!preview) {
                Sprite cached = cached(key);
                if (cached != null) { sprites.add(cached); continue; }
            }
            BufferedImage imageSource = null;
            if (n.type().equals("image")) {
                boolean reference = n.reuseTexture() || n.atlas() != null
                    || assets.craftEngine.atlasReference(n.source());
                imageSource = reference ? assets.reference(n.source(), n.atlas()).image() : assets.image(n.source());
            } else {
                imageSource = PrimitiveShapes.source();
            }
            if (n.type().equals("image")) n = fittedImage(n, imageSource, zoom);
            double h = n.height();
            AffineTransform transform = new AffineTransform();
            transform.scale(zoom, zoom);
            transform.translate(menu.width() / 2.0 + n.x(), menu.height() / 2.0 - n.y());
            transform.rotate(-Math.toRadians(n.rotation()));
            transform.scale(n.scale(), n.scale());
            Rectangle2D box = transform.createTransformedShape(
                new Rectangle2D.Double(-n.width() / 2, -h / 2, n.width(), h)).getBounds2D();
            int left = (int) Math.floor(box.getMinX() + 1e-7), top = (int) Math.floor(box.getMinY() + 1e-7);
            Rectangle bounds = new Rectangle(left, top,
                (int) Math.ceil(box.getMaxX() - 1e-7) - left, (int) Math.ceil(box.getMaxY() - 1e-7) - top);
            // Decide containment before integer rounding; a full-canvas source must not become a raster variant.
            double tolerance = 0.5;
            boolean complete = box.getMinX() >= -tolerance && box.getMinY() >= -tolerance
                && box.getMaxX() <= width + tolerance
                && box.getMaxY() <= menu.height() * zoom + tolerance;
            if (n.rotation() == 0 && n.argb() >>> 24 == 255 && complete) {
                int sw = Math.min(width, Math.max(1, (int) Math.round(n.width() * n.scale() * zoom)));
                int sh = Math.min(height, Math.max(1, (int) Math.round(h * n.scale() * zoom)));
                int x = Math.clamp((int) Math.round((menu.width() / 2.0 + n.x()) * zoom - sw / 2.0), 0, width - sw);
                int y = Math.clamp((int) Math.round((menu.height() / 2.0 - n.y()) * zoom - sh / 2.0), 0, height - sh);
                String alias = n.type().equals("image") ? assets.registeredSource(n.source()) : PrimitiveShapes.SOURCE;
                BufferedImage source = imageSource;
                if (source.getWidth() > 256 || source.getHeight() > 256)
                    throw new IOException(n.id() + " 源图片各边须为1..256");
                if (alias == null) alias = RegisteredImages.sourceKey(source);
                add(sprites, key, new Sprite(n.id(), x, y, null, alias, source, null, sw, sh), preview);
                if (root != null) {
                    if (PrimitiveShapes.type(n.type())) {
                        root.setColor(new Color(argb, true));
                        for (Rectangle rect : PrimitiveShapes.rectangles(n, sw, sh, zoom))
                            root.fillRect(x + rect.x, y + rect.y, rect.width, rect.height);
                    } else root.drawImage(tint(source, argb), x, y, sw, sh, null);
                }
                continue;
            }
            if (!preview)
                throw new IOException(n.id() + " 二维元素须完整位于画布内、无旋转、opacity=255；运行时不重绘或裁切源图片");
            bounds = bounds.intersection(new Rectangle(0, 0, width, height));
            if (bounds.isEmpty()) continue;
            if (n.source().startsWith("minecraft:") || n.reuseTexture() || n.atlas() != null
                || ImageRegistry.source(n.source()) || assets.craftEngine.atlasReference(n.source()))
                throw new IOException(n.id() + " 纹理直引须完整显示、等比缩放、不旋转且opacity=255");
            BufferedImage layer = new BufferedImage(bounds.width, bounds.height, BufferedImage.TYPE_INT_ARGB);
            Graphics2D g = layer.createGraphics();
            g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
            g.setRenderingHint(RenderingHints.KEY_INTERPOLATION, RenderingHints.VALUE_INTERPOLATION_NEAREST_NEIGHBOR);
            g.translate(-bounds.x, -bounds.y);
            g.transform(transform);
            double x = -n.width() / 2, y = -h / 2;
            BufferedImage texture = tint(imageSource, argb);
            g.setComposite(AlphaComposite.getInstance(AlphaComposite.SRC_OVER, (n.argb() >>> 24) / 255f));
            g.drawImage(texture, (int) Math.round(x), (int) Math.round(y),
                (int) Math.round(n.width()), (int) Math.round(h), null);
            g.dispose();
            add(sprites, key, new Sprite(n.id(), bounds.x, bounds.y, layer), preview);
            if (root != null) root.drawImage(layer, bounds.x, bounds.y, null);
        }
        if (root != null) root.dispose();
        return new Rendered(image, List.copyOf(sprites), List.copyOf(textNodes), width, height);
    }
    private static Node fittedImage(Node node, BufferedImage source, double zoom) {
        int boxWidth = Math.max(1, (int) Math.round(node.width() * node.scale() * zoom));
        double heightLimit = node.type().equals("line") ? node.thickness() : node.height();
        int boxHeight = Math.max(1, (int) Math.round(heightLimit * node.scale() * zoom));
        double ratio = source.getWidth() / (double) source.getHeight();
        int height = Math.max(1, (int) Math.round(Math.min(boxHeight, boxWidth / ratio)));
        int width = Math.max(1, (int) Math.round(height * ratio));
        while (height > 1 && (width > boxWidth || height > boxHeight)) {
            height--;
            width = Math.max(1, (int) Math.round(height * ratio));
        }
        if (width > boxWidth)
            throw new IllegalArgumentException(node.id() + " 图片显示框过窄，无法容纳高度1像素的等比图片");
        return new Node(node.id(), node.type(), node.x(), node.y(), node.z(), node.scale(),
            width / (node.scale() * zoom), height / (node.scale() * zoom), node.argb(), node.text(), node.source(),
            node.alignment(), node.size(), node.thickness(), node.rotation(), node.columns(), node.slotSize(),
            node.slots(), node.maxLines(), node.interaction(), node.reuseTexture(), node.atlas());
    }
    private synchronized Sprite cached(Graphic key) { return runtimeSprites.get(key); }
    private synchronized void add(List<Sprite> sprites, Graphic key, Sprite sprite, boolean preview) {
        if (preview) { sprites.add(sprite); return; }
        Sprite existing = runtimeSprites.get(key);
        if (existing != null) { sprites.add(existing); return; }
        sprites.add(sprite);
        runtimeSprites.put(key, sprite);
        cachedPixels += pixels(sprite);
        var iterator = runtimeSprites.entrySet().iterator();
        while (runtimeSprites.size() > spriteBudget || cachedPixels > pixelBudget) {
            cachedPixels -= pixels(iterator.next().getValue());
            iterator.remove();
        }
    }
    private static long pixels(Sprite sprite) {
        return sprite.image() == null ? 0 : (long) sprite.width() * sprite.height();
    }
    private static BufferedImage tint(BufferedImage source, int argb) {
        if ((argb & 0xFFFFFF) == 0xFFFFFF) return source;
        BufferedImage image = new BufferedImage(source.getWidth(), source.getHeight(), BufferedImage.TYPE_INT_ARGB);
        int red = argb >> 16 & 255, green = argb >> 8 & 255, blue = argb & 255;
        for (int y = 0; y < source.getHeight(); y++) for (int x = 0; x < source.getWidth(); x++) {
            int pixel = source.getRGB(x, y);
            image.setRGB(x, y, pixel & 0xFF000000
                | ((pixel >> 16 & 255) * red / 255) << 16
                | ((pixel >> 8 & 255) * green / 255) << 8 | (pixel & 255) * blue / 255);
        }
        return image;
    }
}
