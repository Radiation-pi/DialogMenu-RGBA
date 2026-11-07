package dev.dialogmenu;

import java.util.Map;

/** YAML 动态集合中由外部提供的一行数据。 */
public record DialogMenuEntry(String id, String name, String image, Map<String, String> fields) {
    public DialogMenuEntry {
        if (id == null || !id.matches("[A-Za-z0-9:_-]{1,128}"))
            throw new IllegalArgumentException("Entry ID must be stable and contain only letters, digits, :, _ or -");
        if (name == null) throw new IllegalArgumentException("Entry name is required");
        image = image == null ? "" : image;
        fields = fields == null ? Map.of() : Map.copyOf(fields);
        if (fields.keySet().stream().anyMatch(key -> !key.matches("[A-Za-z0-9_-]{1,64}")))
            throw new IllegalArgumentException("Entry field names must contain only letters, digits, _ or -");
    }
}
