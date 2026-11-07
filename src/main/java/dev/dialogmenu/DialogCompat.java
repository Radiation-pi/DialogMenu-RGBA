package dev.dialogmenu;

import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import org.bukkit.entity.Player;

/** 1.21.7 尚无 Adventure closeDialog；其 closeInventory 同样会关闭 Dialog。 */
final class DialogCompat {
    private static final Method CLOSE = closeMethod();

    private static Method closeMethod() {
        try { return Player.class.getMethod("closeDialog"); }
        catch (NoSuchMethodException oldApi) { return null; }
    }

    static void close(Player player) {
        if (CLOSE == null) {
            player.closeInventory();
            return;
        }
        try { CLOSE.invoke(player); }
        catch (IllegalAccessException error) { throw new IllegalStateException("Cannot close Dialog", error); }
        catch (InvocationTargetException error) {
            if (error.getCause() instanceof RuntimeException cause) throw cause;
            if (error.getCause() instanceof Error cause) throw cause;
            throw new IllegalStateException("Cannot close Dialog", error.getCause());
        }
    }
}
