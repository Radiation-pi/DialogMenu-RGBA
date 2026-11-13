package dev.dialogmenu;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.Executor;

/** Retains only the latest waiting task per key, without interrupting a running task. */
final class LatestTasks<K> {
    private final Executor executor;
    private final Map<K, Runnable> pending = new LinkedHashMap<>();
    private boolean scheduled;

    LatestTasks(Executor executor) { this.executor = executor; }

    synchronized void submit(K key, Runnable action) {
        pending.put(key, action);
        if (!scheduled) schedule();
    }

    synchronized void cancel(K key) { pending.remove(key); }
    synchronized void clear() { pending.clear(); }

    private void schedule() {
        scheduled = true;
        try { executor.execute(this::drain); }
        catch (RuntimeException error) {
            scheduled = false;
            pending.clear();
            throw error;
        }
    }

    private void drain() {
        while (true) {
            Runnable action;
            synchronized (this) {
                var iterator = pending.values().iterator();
                if (!iterator.hasNext()) {
                    scheduled = false;
                    return;
                }
                action = iterator.next();
                iterator.remove();
            }
            try { action.run(); }
            catch (RuntimeException | Error error) {
                synchronized (this) {
                    scheduled = false;
                    if (!pending.isEmpty()) schedule();
                }
                throw error;
            }
        }
    }
}
