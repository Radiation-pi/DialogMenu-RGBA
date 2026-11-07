package dev.dialogmenu;

import java.lang.management.ManagementFactory;
import java.lang.management.ThreadMXBean;
import java.util.concurrent.CancellationException;
import java.util.concurrent.locks.LockSupport;

/** 仅对资源准备作用域按线程 CPU 时间让出执行时间，不累积空闲额度。 */
final class BuildBudget implements AutoCloseable {
    private static final ThreadMXBean CPU = ManagementFactory.getThreadMXBean();
    private static final ThreadLocal<BuildBudget> CURRENT = new ThreadLocal<>();
    private static final long SLICE = 5_000_000;
    private final BuildBudget previous;
    private final int percent;
    private long wall;
    private long cpu;

    static BuildBudget open(int percent) {
        if (percent < 1 || percent > 100)
            throw new IllegalArgumentException("resource-build-cpu-percent须为1..100的整数");
        return new BuildBudget(percent);
    }

    private BuildBudget(int percent) {
        this.percent = percent;
        if (percent < 100) {
            if (!CPU.isCurrentThreadCpuTimeSupported())
                throw new IllegalStateException("当前JVM不支持线程CPU计时，请设置resource-build-cpu-percent: 100");
            if (!CPU.isThreadCpuTimeEnabled()) CPU.setThreadCpuTimeEnabled(true);
            cpu = CPU.getCurrentThreadCpuTime();
        }
        wall = System.nanoTime();
        previous = CURRENT.get();
        CURRENT.set(this);
    }

    static void checkpoint() {
        BuildBudget budget = CURRENT.get();
        if (budget != null) budget.yieldCpu(false);
    }

    static void settle() {
        BuildBudget budget = CURRENT.get();
        if (budget != null) budget.yieldCpu(true);
    }

    private void yieldCpu(boolean force) {
        if (Thread.currentThread().isInterrupted())
            throw new CancellationException("资源构建已取消");
        if (percent == 100) return;
        long now = System.nanoTime();
        if (!force && now - wall < SLICE) return;
        long used = CPU.getCurrentThreadCpuTime() - cpu;
        long remaining = used * 100 / percent - (now - wall);
        if (remaining > 0) {
            long until = now + remaining;
            do {
                LockSupport.parkNanos(remaining);
                if (Thread.currentThread().isInterrupted())
                    throw new CancellationException("资源构建已取消");
                remaining = until - System.nanoTime();
            } while (remaining > 0);
        }
        cpu = CPU.getCurrentThreadCpuTime();
        wall = System.nanoTime();
    }

    @Override public void close() {
        if (previous == null) CURRENT.remove();
        else CURRENT.set(previous);
    }
}
