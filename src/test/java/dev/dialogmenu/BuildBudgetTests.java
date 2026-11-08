package dev.dialogmenu;

import java.lang.management.ManagementFactory;
import java.lang.management.ThreadMXBean;
import java.util.concurrent.*;

public final class BuildBudgetTests {
    private static final ThreadMXBean CPU = ManagementFactory.getThreadMXBean();
    private static volatile long sink;
    private static int checks;
    record Timing(long cpu, long wall) {
        double usage() { return cpu * 100.0 / wall; }
    }

    public static void main(String[] args) throws Exception {
        for (int value : new int[] {0, -1, 101}) {
            try (var ignored = BuildBudget.open(value)) {
                throw new AssertionError("invalid budget accepted");
            } catch (IllegalArgumentException expected) { checks++; }
        }
        Timing unlimited = run(100, 200_000_000);
        Timing limited = run(50, 600_000_000);
        Timing defaultBudget = run(80, 600_000_000);
        check(defaultBudget.usage() < 90, "80% budget actually yields CPU: " + defaultBudget);
        check(defaultBudget.cpu() > 590_000_000, "80% budget performs all work");
        check(limited.usage() < 60, "50% budget actually yields CPU: " + limited);
        check(limited.wall() > limited.cpu() * 1.7, "CPU work takes longer when limited");
        check(limited.cpu() > 590_000_000, "work was performed, not skipped");
        try (var budget = BuildBudget.open(1)) {
            ExecutorService unrelated = Executors.newSingleThreadExecutor();
            try {
                Timing other = unrelated.submit(() -> work(150_000_000)).get(2, TimeUnit.SECONDS);
                check(other.wall() < 1_000_000_000, "budget does not throttle another thread");
            } finally { unrelated.shutdownNow(); }
        }
        try {
            try (var ignored = BuildBudget.open(1)) { throw new IllegalStateException("failure"); }
        } catch (IllegalStateException expected) { checks++; }
        Timing clean = work(100_000_000);
        check(clean.wall() < 1_000_000_000, "exception cleans budget on same thread");
        try (var ignored = BuildBudget.open(100)) {
            try (var nested = BuildBudget.open(1)) {}
            check(work(100_000_000).wall() < 1_000_000_000, "nested scope restores outer budget");
        }
        CountDownLatch started = new CountDownLatch(1);
        CompletableFuture<Boolean> cancelled = new CompletableFuture<>();
        Thread worker = new Thread(() -> {
            try (var ignored = BuildBudget.open(1)) {
                started.countDown();
                work(1_000_000_000);
                cancelled.complete(false);
            } catch (CancellationException expected) {
                cancelled.complete(Thread.currentThread().isInterrupted());
            } catch (Throwable failure) { cancelled.completeExceptionally(failure); }
        });
        worker.start();
        started.await();
        Thread.sleep(40);
        worker.interrupt();
        check(cancelled.get(1, TimeUnit.SECONDS), "waiting is promptly interruptible, flag preserved");
        worker.join(1000);
        check(!worker.isAlive(), "cancelled worker exits");
        check(work(100_000_000).wall() < 1_000_000_000, "scope does not leak after close");
        System.out.printf("PASS build budget checks=%d unlimited=%.1f%% limited=%.1f%% default=%.1f%% cpu=%.3fs wall=%.3fs%n",
            checks, unlimited.usage(), limited.usage(), defaultBudget.usage(), limited.cpu() / 1e9, limited.wall() / 1e9);
    }

    private static Timing run(int percent, long nanos) {
        try (var ignored = BuildBudget.open(percent)) { return work(nanos); }
    }

    private static Timing work(long nanos) {
        long cpu = CPU.getCurrentThreadCpuTime(), wall = System.nanoTime(), value = sink;
        do {
            for (int i = 0; i < 10000; i++) value = Long.rotateLeft(value + i, 7) ^ 0x517cc1b727220a95L;
            sink = value;
            BuildBudget.checkpoint();
        } while (CPU.getCurrentThreadCpuTime() - cpu < nanos);
        return new Timing(CPU.getCurrentThreadCpuTime() - cpu, System.nanoTime() - wall);
    }

    private static void check(boolean condition, String message) {
        checks++;
        if (!condition) throw new AssertionError(message);
    }
}
