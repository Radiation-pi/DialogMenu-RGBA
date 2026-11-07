# Java API

## 取得服务

以同版本 RGBA JAR 作为 compileOnly 依赖，并在你的 plugin.yml 声明 `depend: [DialogMenu]` 或按需要使用 softdepend。所有 API 调用必须在服务器主线程。YAML 先用上一章的 `wiki-catalog.yml`。

下面的类放在你自己的插件项目中，玩家命令或监听器可以调用 `show(player)`。回调仅演示数据联动，不执行真实交易或持久化。

```java
import dev.dialogmenu.DialogMenuApi;
import dev.dialogmenu.DialogMenuBinding;
import dev.dialogmenu.DialogMenuEntry;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.bukkit.entity.Player;
import org.bukkit.plugin.Plugin;

public final class WikiCatalog {
    private final Plugin owner;
    private DialogMenuBinding binding;

    public WikiCatalog(Plugin owner) {
        this.owner = owner;
    }

    public void show(Player player) {
        DialogMenuApi api = owner.getServer().getServicesManager()
            .load(DialogMenuApi.class);
        if (api == null) throw new IllegalStateException("DialogMenu 未就绪");
        if (binding != null) binding.close();
        binding = api.bind(owner, player, "wiki-catalog-list",
            Map.of("selected-id", "none"),
            selected -> {
                DialogMenuBinding current = binding;
                if (current == null || !current.isActive()) return;
                if (selected.equals("create")) {
                    current.openForm("create", fields -> {
                        String name = fields.get("name").strip();
                        if (name.isEmpty()) return;
                        // 示例替换整个集合；真实业务需校验并持久化。
                        current.updateCollection("entries", List.of(
                            new DialogMenuEntry(UUID.randomUUID().toString(),
                                name, "", Map.of("size", "10x20"))));
                    });
                } else {
                    current.update(Map.of("selected-id", selected));
                    player.sendMessage("选中：" + selected);
                }
            },
            reason -> owner.getLogger().fine("菜单关闭：" + reason));
        if (binding != null) {
            binding.updateCollection("entries", List.of(
                new DialogMenuEntry("sample:one", "示例一", "",
                    Map.of("size", "10x20"))));
        }
    }
}
```

> **一个实例只持有一个 binding**上例适合演示一个玩家流程。多玩家业务应按 UUID 保存 binding，不能用一个全局字段让玩家互相替换。collection ID 与 callback 值共用回调入口，业务应避免重名，例如不要把条目 ID 命名成 `create`。

## 基础 API

`hasLayout / layouts / open / close / refresh / currentId / sessionToken / surface`。返回值需要检查，离线玩家、无权限或失效布局可能无法打开。token 和 surface 是服务端视图身份，不是客户端已经渲染的确认。

## Binding 生命周期

| 方法 | 用途 |
| --- | --- |
| `update(Map)` | 更新声明的 external 数据。 |
| `updateCollection(id, rows)` | 替换 external 集合。 |
| `openForm(id, submitted)` | 打开 YAML 声明的原生输入表单。 |
| `open(page)` | 只能切换到相同 YAML 家族的页面。 |
| `suspend / resume` | 暂停点击，再恢复最新数据，适合另一个输入流程。 |
| `close / isActive` | 关闭或查询当前句柄；过期句柄不能操作新会话。 |

DialogMenuEntry ID 为 1..128 字符，允许字母、数字、`:`、`_`、`-`。自定义字段键为 1..64 字符，仅字母、数字、`_`、`-`；不能覆盖保留字段 id/name/image。

## 关闭事件

`DialogMenuClosedEvent` 提供 player、menuId、token、reason。原因有 `CLOSED / REPLACED / RELOAD / DISABLED / PLAYER_QUIT / WORLD_CHANGED / INVENTORY_OPENED`。刷新不等于关闭；不要用同一份旧句柄对后来的新会话清理。

---

上一篇：[资源包与重载](https://github.com/palkane/DialogMenu-RGBA/wiki/%E8%B5%84%E6%BA%90%E5%8C%85%E4%B8%8E%E9%87%8D%E8%BD%BD) · 下一篇：[RGBA 原理与开销](https://github.com/palkane/DialogMenu-RGBA/wiki/RGBA-%E5%8E%9F%E7%90%86%E4%B8%8E%E5%BC%80%E9%94%80)
