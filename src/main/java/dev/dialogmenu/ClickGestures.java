package dev.dialogmenu;

import java.util.*;
import java.util.function.LongSupplier;
import static dev.dialogmenu.MenuModel.*;

/** 在主线程仲裁手势，第一次点击不立即执行业务动作。 */
final class ClickGestures {
    static final long WINDOW_MILLIS = 300;
    record Target(UUID session, String page, String control, String permission, List<String> single, List<String> twice) {
        static Target of(UUID session, Menu menu, Region region, Context context) {
            return new Target(session, menu.id(), region.id(), region.permission(),
                region.actions().stream().map(context::expand).toList(),
                region.doubleActions().stream().map(context::expand).toList());
        }
        boolean present(UUID session, Menu menu, Context context) {
            return this.session.equals(session) && page.equals(menu.id())
                && context.permitted(permission) && context.permitted(menu.permission())
                && LayoutStore.interactiveRegions(menu).stream()
                    .anyMatch(region -> control.equals(region.id()) && region.hasActions()
                        && equals(of(session, menu, region, context)));
        }
    }
    interface Scheduler { Runnable later(Runnable action, long millis); }
    private static final class Pending {
        final Target target;
        final long deadline;
        final Runnable single;
        Runnable cancel = () -> {};
        Pending(Target target, long deadline, Runnable single) {
            this.target = target;
            this.deadline = deadline;
            this.single = single;
        }
    }
    private final LongSupplier clock;
    private final Scheduler scheduler;
    private final Map<UUID, Pending> pending = new HashMap<>();

    ClickGestures(LongSupplier clock, Scheduler scheduler) {
        this.clock = clock;
        this.scheduler = scheduler;
    }
    Target pending(UUID player) {
        Pending value = pending.get(player);
        return value == null ? null : value.target;
    }
    boolean waiting(UUID player) { return pending.containsKey(player); }
    boolean hold(UUID player, UUID session, Menu menu, Context context) {
        Target target = pending(player);
        if (target == null) return false;
        if (target.present(session, menu, context)) return true;
        cancel(player);
        return false;
    }
    void cancel(UUID player) {
        Pending value = pending.remove(player);
        if (value != null) value.cancel.run();
    }
    void clear() { for (UUID player : List.copyOf(pending.keySet())) cancel(player); }
    void click(UUID player, Target target, Runnable single, Runnable twice) {
        Pending previous = pending.get(player);
        if (previous != null && previous.target.equals(target)) {
            cancel(player);
            if (clock.getAsLong() < previous.deadline) twice.run();
            else previous.single.run();
            return;
        }
        // 切换目标时丢弃未完成手势，不能把无关控件配成双击。
        cancel(player);
        Pending next = new Pending(target, clock.getAsLong() + WINDOW_MILLIS, single);
        pending.put(player, next);
        schedule(player, next, WINDOW_MILLIS);
    }
    private void schedule(UUID player, Pending next, long delay) {
        next.cancel = scheduler.later(() -> {
            if (pending.get(player) != next) return;
            long remaining = next.deadline - clock.getAsLong();
            if (remaining > 0) schedule(player, next, remaining);
            else {
                pending.remove(player);
                next.single.run();
            }
        }, delay);
    }
}
