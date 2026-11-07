package dev.dialogmenu;

import java.util.Objects;

/** 已准备与已发布的版本分离；过期回执不改变玩家正在使用的版本。 */
final class ResourcePublication<T> {
    record Candidate<T>(long generation, T value) {}
    private Candidate<T> pending;
    private long committed;

    void prepare(long generation, T value) {
        if (generation > committed && (pending == null || generation > pending.generation()))
            pending = new Candidate<>(generation, Objects.requireNonNull(value));
    }

    Candidate<T> pending() { return pending; }

    T commit(long generation) {
        if (pending == null || pending.generation() != generation)
            throw new IllegalStateException("资源版本已过期，请重新确认待发布版本。");
        T value = pending.value();
        committed = generation;
        pending = null;
        return value;
    }
}
