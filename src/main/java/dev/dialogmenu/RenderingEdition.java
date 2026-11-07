package dev.dialogmenu;

import java.io.IOException;
import java.util.Properties;

/** The installed artifact, not a mutable server setting, selects the renderer. */
final class RenderingEdition {
    static final String ID = read();

    private RenderingEdition() {}

    static boolean shader() { return ID.equals("rgba"); }

    private static String read() {
        var properties = new Properties();
        try (var input = RenderingEdition.class.getResourceAsStream("/dialogmenu-edition.properties")) {
            if (input == null) throw new IllegalStateException("Missing DialogMenu edition");
            properties.load(input);
        } catch (IOException error) {
            throw new IllegalStateException("Cannot read DialogMenu edition", error);
        }
        String edition = properties.getProperty("edition");
        if (!"native".equals(edition) && !"rgba".equals(edition))
            throw new IllegalStateException("Unknown DialogMenu edition: " + edition);
        return edition;
    }
}
