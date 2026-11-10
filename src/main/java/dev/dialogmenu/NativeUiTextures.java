package dev.dialogmenu;

import java.awt.image.BufferedImage;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

/** Global texture overrides only; native layout, input and tooltips remain unchanged. */
final class NativeUiTextures {
    static void write(Path target, boolean hideWarning, boolean hideScrollbar) throws IOException {
        Path sprites = target.resolve("assets/minecraft/textures/gui/sprites");
        for (String name : List.of("warning_button", "warning_button_disabled", "warning_button_highlighted"))
            write(sprites.resolve("dialog/" + name + ".png"), hideWarning, 20, 20);
        for (String name : List.of("scroller", "scroller_background"))
            write(sprites.resolve("widget/" + name + ".png"), hideScrollbar, 6, 32);
    }

    private static void write(Path path, boolean hidden, int width, int height) throws IOException {
        if (hidden) {
            Files.createDirectories(path.getParent());
            FontPack.writePng(new BufferedImage(width, height, BufferedImage.TYPE_INT_ARGB), path);
        } else {
            // Re-exporting into the same generated directory must restore vanilla visibility.
            Files.deleteIfExists(path);
        }
    }
}
