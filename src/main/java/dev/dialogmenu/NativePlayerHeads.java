package dev.dialogmenu;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import java.util.UUID;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.serializer.gson.GsonComponentSerializer;
import org.bukkit.entity.Player;

/** 通过现有组件序列化器适配原生头像，不增加新 Adventure 类的链接要求。 */
final class NativePlayerHeads {
    private static final String PREVIEW = "{\"player\":\"Player\",\"hat\":true}";
    private static final boolean SUPPORTED = supportsHeads();

    private static boolean supportsHeads() {
        try {
            var codec = GsonComponentSerializer.gson();
            var result = JsonParser.parseString(codec.serialize(codec.deserialize(PREVIEW))).getAsJsonObject();
            return result.has("player") && result.get("player").getAsString().equals("Player");
        } catch (RuntimeException unavailable) { return false; }
    }

    static Component preview() {
        requireSupported();
        return GsonComponentSerializer.gson().deserialize(PREVIEW);
    }

    static boolean supported() { return SUPPORTED; }

    static Component of(Player player) {
        var profile = player.getPlayerProfile();
        JsonArray properties = new JsonArray();
        for (var property : profile.getProperties()) {
            JsonObject entry = new JsonObject();
            entry.addProperty("name", property.getName());
            entry.addProperty("value", property.getValue());
            if (property.getSignature() != null) entry.addProperty("signature", property.getSignature());
            properties.add(entry);
        }
        // 只读取已登录玩家的资料，不请求更新或在主线程等待皮肤网络查询。
        return profile(profile.getUniqueId(), profile.getName(), properties);
    }

    static Component profile(UUID id, String name, JsonArray properties) {
        requireSupported();
        JsonObject player = new JsonObject();
        if (id != null) player.addProperty("id", id.toString());
        if (name != null && !name.isBlank()) player.addProperty("name", name);
        player.add("properties", properties.deepCopy());
        JsonObject value = new JsonObject();
        value.add("player", player);
        value.addProperty("hat", true);
        return GsonComponentSerializer.gson().deserialize(value.toString());
    }

    static void requireSupported() {
        if (!SUPPORTED) throw new IllegalStateException("当前服务端组件接口不支持原生玩家头像");
    }
}
