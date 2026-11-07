package dev.dialogmenu;

import java.util.Map;
import java.util.List;
import java.util.function.Consumer;
import org.bukkit.entity.Player;

/** 由业务插件持有的 YAML 会话，替换或清理后句柄失效。 */
public final class DialogMenuBinding {
    private final DialogMenuApi api;
    final Player player;
    final Object identity;

    DialogMenuBinding(DialogMenuApi api, Player player, Object identity) {
        this.api = api;
        this.player = player;
        this.identity = identity;
    }

    public boolean isActive() { return api.bindingActive(this); }
    public boolean update(Map<String, String> values) { return api.bindingUpdate(this, values); }
    /**
     * 替换 YAML 声明的一个 {@code external: true} 集合，可提供任意数量的行。
     * 行显示格式由页面的 {@code flow-lists} 控制；点击时将条目 ID
     * 回传给 {@link DialogMenuApi#bind} 注册的回调。
     */
    public boolean updateCollection(String collection, List<DialogMenuEntry> entries) {
        return api.bindingUpdateCollection(this, collection, entries);
    }
    /**
     * 打开模板顶层 {@code forms} 中声明的表单，字段、标签和 Paper 输入限制来自 YAML。
     * 仅提交时回传字段键值；关闭或取消均不触发提交回调。
     */
    public boolean openForm(String form, Consumer<Map<String, String>> submitted) {
        return api.bindingOpenForm(this, form, submitted);
    }
    public boolean open(String page) { return api.bindingOpen(this, page); }
    /** 显示独立文本输入对话框前，先撤销画布点击。 */
    public boolean suspend() { return api.bindingSuspend(this, true); }
    public boolean resume() { return api.bindingSuspend(this, false); }
    public void close() { api.bindingClose(this); }
}
