package dev.dialogmenu;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import java.util.UUID;
import net.kyori.adventure.text.serializer.gson.GsonComponentSerializer;

/** 原生头像必须保留真实身份和签名皮肤，不能静默丢弃为普通文字。 */
final class NativePlayerHeadCheck {
    static void run() {
        if (!NativePlayerHeads.supported()) {
            try {
                NativePlayerHeads.preview();
                throw new AssertionError("unsupported native head must be rejected explicitly");
            } catch (IllegalStateException expected) {}
            System.out.println("PASS unsupported native head rejects without linking new API classes");
            return;
        }
        var id = UUID.fromString("00000000-0000-0000-0000-000000000001");
        var property = new JsonObject();
        property.addProperty("name", "textures");
        property.addProperty("value", "c2tpbg==");
        property.addProperty("signature", "c2lnbmF0dXJl");
        var properties = new JsonArray();
        properties.add(property);
        var component = NativePlayerHeads.profile(id, "Alex", properties);
        properties.remove(0);
        var json = JsonParser.parseString(GsonComponentSerializer.gson().serialize(component)).getAsJsonObject();
        var player = json.getAsJsonObject("player");
        // 序列化器将 UUID 规范化为 Minecraft 的四个整数组件。
        var actualId = player.getAsJsonArray("id");
        long most = (long) actualId.get(0).getAsInt() << 32 | actualId.get(1).getAsInt() & 0xffffffffL;
        long least = (long) actualId.get(2).getAsInt() << 32 | actualId.get(3).getAsInt() & 0xffffffffL;
        if (!new UUID(most, least).equals(id) || !player.get("name").getAsString().equals("Alex")
            || !player.getAsJsonArray("properties").get(0).equals(property) || !json.get("hat").getAsBoolean())
            throw new AssertionError("native head lost player identity, skin or hat layer");
        if (!JsonParser.parseString(GsonComponentSerializer.gson().serialize(NativePlayerHeads.preview()))
            .getAsJsonObject().has("player")) throw new AssertionError("preview is not a native player head");
        System.out.println("PASS native head identity and signed skin serialization (offline)");
    }
}
