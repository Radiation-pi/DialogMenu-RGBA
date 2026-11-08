package dev.dialogmenu;

import java.nio.file.*;
import java.util.*;

/** 固定默认 CJK 字形，防止打包字库时误选日文字形来源。 */
public final class DefaultGlyphTests {
    static int run(Path root) throws Exception {
        var assets = new VanillaAssets(root.resolve("default-glyphs/images"));
        // 与默认 Unifont 一致的 16×16 单色行数据；这些字符在 JP 字库中的笔画不同。
        var expected = Map.of(
            '与', "1000100010001FFC1000200020003FF8000800080008FFC80008000800500020",
            '工', "000000007FFC0100010001000100010001000100010001000100FFFE00000000",
            '常', "0100111009207FFE40028FE4082008200FE001001FF011101110115011200100",
            '科', "08101D10F09010901010FD10109038903410501E53F090101010101010101010");
        for (var entry : expected.entrySet()) {
            var glyph = assets.glyph(entry.getKey());
            if (glyph == null || glyph.width() != 16 || glyph.height() != 16)
                throw new AssertionError("默认 CJK 字格应为 16×16：" + entry.getKey());
            byte[] rows = HexFormat.of().parseHex(entry.getValue());
            if (glyph.alpha().length != rows.length * 8)
                throw new AssertionError("默认 CJK 字形数据长度异常：" + entry.getKey());
            for (int i = 0; i < glyph.alpha().length; i++) {
                int alpha = (rows[i / 8] >> (7 - i % 8) & 1) * 255;
                if (Byte.toUnsignedInt(glyph.alpha()[i]) != alpha)
                    throw new AssertionError("默认 CJK 字形发生变化，请核对是否误用 JP 来源：" + entry.getKey());
            }
        }
        return expected.size();
    }

    public static void main(String[] args) throws Exception {
        System.out.println("PASS default CJK glyph checks=" + run(Path.of(args[0])));
    }
}
