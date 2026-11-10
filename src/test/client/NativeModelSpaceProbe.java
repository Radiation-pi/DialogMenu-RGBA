package dev.dialogmenu;

import com.google.gson.*;
import com.mojang.blaze3d.font.SpaceProvider;
import com.mojang.blaze3d.font.GlyphInfo;
import com.mojang.blaze3d.platform.NativeImage;
import com.mojang.blaze3d.vertex.VertexConsumer;
import java.lang.reflect.*;
import java.nio.file.*;
import java.util.*;
import net.minecraft.client.StringSplitter;
import net.minecraft.client.gui.font.TextRenderable;
import net.minecraft.client.gui.font.glyphs.BakedSheetGlyph;
import net.minecraft.client.renderer.state.gui.GlyphRenderState;
import net.minecraft.client.renderer.texture.*;
import net.minecraft.client.resources.metadata.animation.FrameSize;
import net.minecraft.network.chat.Style;
import net.minecraft.resources.Identifier;
import org.joml.Matrix3x2f;

/** Actual native space widths, line wrapping and CPU GUI vertices; no GPU upload/player. */
public final class NativeModelSpaceProbe {
    static final class Sprite extends TextureAtlasSprite {
        Sprite(SpriteContents contents) {
            super(Identifier.parse("minecraft:blocks"), contents, 1024, 1024, 17, 29, 0);
        }
    }
    public static void main(String[] args) throws Exception {
        var json = JsonParser.parseString(Files.readString(Path.of(args[0]))).getAsJsonObject();
        Map<Integer, Float> widths = new HashMap<>();
        for (var entry : json.getAsJsonArray("providers").get(0).getAsJsonObject().getAsJsonObject("advances").entrySet())
            widths.put(entry.getKey().codePointAt(0), entry.getValue().getAsFloat());
        var provider = new SpaceProvider(widths);
        var splitter = new StringSplitter((code, style) -> widths.containsKey(code)
            ? provider.getGlyph(code).info().getAdvance(false) : 8f);
        var contents = new SpriteContents(Identifier.parse("minecraft:block/stone"),
            new FrameSize(16, 16), new NativeImage(16, 16, true));
        var sprite = new Sprite(contents);
        var constructor = Class.forName("net.minecraft.client.gui.font.AtlasGlyphProvider$Instance").getDeclaredConstructors()[0];
        constructor.setAccessible(true);
        int checks = 0;
        try {
            for (int face = 0; face < 8192; face++) {
                String before = ModelPack.faceMove(face, false), after = ModelPack.faceMove(face, true);
                String line = before + "X" + after + SpriteCanvas.move(-8);
                if (splitter.stringWidth(line) != 0 || splitter.splitLines(line, 300, Style.EMPTY).size() != 1)
                    throw new AssertionError("Native width/wrap changed for face " + face);
                checks++;
                float advance = splitter.stringWidth(before);
                for (int window : new int[]{1280, 1920, 3840}) for (int local : new int[]{0, 507, 1014}) {
                    List<Float> x = new ArrayList<>();
                    VertexConsumer vertices = (VertexConsumer) Proxy.newProxyInstance(
                        VertexConsumer.class.getClassLoader(), new Class[]{VertexConsumer.class}, (proxy, method, values) -> {
                            if (method.isDefault()) return InvocationHandler.invokeDefault(proxy, method, values);
                            if (method.getName().equals("addVertex")) x.add((Float) values[0]);
                            return method.getReturnType() == void.class ? null : proxy;
                        });
                    var glyph = (TextRenderable) constructor.newInstance(null, null, sprite,
                        advance + local, 100f, 0xFFBEEF01, 0x40000000, 1f, Style.EMPTY);
                    int translation = (window - 1014) / 2;
                    var state = new GlyphRenderState(new Matrix3x2f().translation(translation, 0), glyph, null);
                    state.buildVertices(vertices);
                    if (state.bounds() != null || x.size() != 8) throw new AssertionError("Native glyph was culled");
                    for (int corner = 0; corner < 4; corner++) {
                        float encoded = x.get(corner);
                        int decoded = (int) Math.floor((window / 2f - encoded) / 2000f + .5f) - 33;
                        float expected = translation + local + 1 + (corner >= 2 ? 8 : 0);
                        if (decoded != face || encoded + (decoded + 33) * 2000f != expected)
                            throw new AssertionError("Native vertex lost position: face=" + face + " window=" + window);
                        checks++;
                    }
                }
            }
            String before = Character.toString(0xF1020), after = Character.toString(0xF1021);
            String line = before + "X" + after + SpriteCanvas.move(-8);
            if (splitter.stringWidth(line) != 0 || splitter.splitLines(line, 300, Style.EMPTY).size() != 1
                || splitter.stringWidth(before) != -65536)
                throw new AssertionError("Native image scope changed width or wrapping");
            checks++;
            var bitmap = new BakedSheetGlyph(GlyphInfo.simple(16), null, null,
                0, 1, 0, 1, 0, 16, 0, 16);
            for (int window : new int[]{1280, 1920, 3840, 7680})
                for (int local : new int[]{0, 507, 1014}) for (boolean object : new boolean[]{false, true}) {
                    float advance = splitter.stringWidth(before);
                    var glyph = object
                        ? (TextRenderable) constructor.newInstance(null, null, sprite,
                            advance + local, 100f, 0xFFBEEF01, 0x60000000, 1f, Style.EMPTY)
                        : bitmap.createGlyph(advance + local, 100f, 0xFFBEEF01, 0xC0000000, Style.EMPTY, 0, 1);
                    List<Float> x = new ArrayList<>();
                    VertexConsumer vertices = (VertexConsumer) Proxy.newProxyInstance(
                        VertexConsumer.class.getClassLoader(), new Class[]{VertexConsumer.class}, (proxy, method, values) -> {
                            if (method.isDefault()) return InvocationHandler.invokeDefault(proxy, method, values);
                            if (method.getName().equals("addVertex")) x.add((Float) values[0]);
                            return method.getReturnType() == void.class ? null : proxy;
                        });
                    int translation = (window - 1014) / 2;
                    var state = new GlyphRenderState(new Matrix3x2f().translation(translation, 0), glyph, null);
                    state.buildVertices(vertices);
                    if (state.bounds() != null || x.size() != 8)
                        throw new AssertionError("Native scoped image was culled");
                    for (int vertex = 0; vertex < 8; vertex++) {
                        float restored = x.get(vertex) + 65536;
                        float expected = translation + local + (vertex < 4 ? 1 : 0)
                            + (vertex % 4 >= 2 ? (object ? 8 : 16) : 0);
                        if (restored != expected || Math.abs(restored - window / 2f) >= 1024)
                            throw new AssertionError("Native image scope lost position: object=" + object);
                        checks++;
                    }
                }
        } finally {
            contents.close();
            provider.close();
        }
        System.out.println("PASS native model spacing/wrap/GUI vertex checks=" + checks + " (CPU only, integer GUI poses)");
    }
}
