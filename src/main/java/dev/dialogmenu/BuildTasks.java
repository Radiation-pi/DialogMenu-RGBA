package dev.dialogmenu;

import java.io.IOException;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;

/** 有界分批并行，阶段完成后按输入顺序合并；失败也等待本批全部退出。 */
final class BuildTasks implements AutoCloseable {
    @FunctionalInterface interface Task<T, R> { R apply(T input) throws IOException; }
    @FunctionalInterface interface Sink<T> { void accept(T input) throws IOException; }
    private final int threads;
    private final int percent;
    private final ExecutorService workers;
    private final AtomicBoolean cancelled = new AtomicBoolean();

    static int defaultThreads() {
        return Math.max(1, Math.min(4, Runtime.getRuntime().availableProcessors() - 1));
    }

    BuildTasks(int threads, int percent) {
        this.threads = threads;
        this.percent = percent;
        AtomicInteger sequence = new AtomicInteger();
        workers = Executors.newFixedThreadPool(threads, task -> {
            Thread thread = new Thread(task, "DialogMenu-build-" + sequence.incrementAndGet());
            thread.setDaemon(true);
            return thread;
        });
    }

    static <T, R> void ordered(BuildTasks tasks, List<T> inputs, Task<T, R> task, Sink<R> merge) throws IOException {
        if (tasks == null) {
            for (T input : inputs) {
                BuildBudget.checkpoint();
                merge.accept(task.apply(input));
            }
        } else tasks.ordered(inputs, task, merge);
    }

    <T, R> void ordered(List<T> inputs, Task<T, R> task, Sink<R> merge) throws IOException {
        for (int start = 0; start < inputs.size(); start += threads) {
            BuildBudget.checkpoint();
            List<CompletableFuture<R>> batch = new ArrayList<>();
            for (T input : inputs.subList(start, Math.min(start + threads, inputs.size()))) {
                batch.add(CompletableFuture.supplyAsync(() -> {
                    try (var budget = BuildBudget.open(percent, cancelled::get)) {
                        BuildBudget.checkpoint();
                        R result = task.apply(input);
                        BuildBudget.settle();
                        return result;
                    } catch (IOException error) { throw new CompletionException(error); }
                }, workers));
            }
            var barrier = CompletableFuture.allOf(batch.toArray(CompletableFuture[]::new));
            try {
                barrier.get();
            } catch (InterruptedException error) {
                cancelled.set(true);
                // 不取消Future：完成信号必须表示实际任务已退出，暂存目录才能安全清理。
                try { barrier.join(); } catch (CompletionException ignored) {}
                Thread.currentThread().interrupt();
                throw new CancellationException("资源构建已取消");
            } catch (ExecutionException error) {
                Throwable cause = error.getCause();
                while (cause instanceof CompletionException && cause.getCause() != null) cause = cause.getCause();
                if (cause instanceof IOException io) throw io;
                if (cause instanceof RuntimeException runtime) throw runtime;
                if (cause instanceof Error fatal) throw fatal;
                throw new IOException(cause);
            }
            BuildBudget.checkpoint();
            for (var result : batch) merge.accept(result.join());
        }
    }

    @Override public void close() { workers.shutdown(); }
}
