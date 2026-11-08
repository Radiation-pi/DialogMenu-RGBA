package dev.dialogmenu;

import java.awt.image.BufferedImage;
import java.io.IOException;
import java.nio.file.*;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;
import javax.imageio.ImageIO;

public final class BuildTasksTests {
    private static int checks;

    public static void main(String[] args) throws Exception {
        Path root = Files.createTempDirectory(Path.of(args[0]), "build-tasks-");
        CountDownLatch started = new CountDownLatch(2);
        List<Integer> merged = new ArrayList<>();
        try (var tasks = new BuildTasks(2, 100)) {
            tasks.ordered(List.of(0, 1), i -> {
                started.countDown();
                await(started);
                BufferedImage image = new BufferedImage(16, 16, BufferedImage.TYPE_INT_ARGB);
                image.setRGB(8, 8, 0xff112233 + i);
                FontPack.writePng(image, root.resolve(i + ".png"));
                return i;
            }, merged::add);
        }
        check(merged.equals(List.of(0, 1)), "parallel results merge in input order");
        for (int i = 0; i < 2; i++)
            check(ImageIO.read(root.resolve(i + ".png").toFile()).getRGB(8, 8) == 0xff112233 + i,
                "concurrent PNG writes preserve source pixels");

        CountDownLatch failedBatch = new CountDownLatch(2);
        AtomicInteger finished = new AtomicInteger();
        try (var tasks = new BuildTasks(2, 100)) {
            try {
                tasks.ordered(List.of(0, 1, 2), i -> {
                    failedBatch.countDown();
                    await(failedBatch);
                    if (i == 0) throw new IOException("expected preparation failure");
                    FontPack.writePng(new BufferedImage(16, 16, BufferedImage.TYPE_INT_ARGB),
                        root.resolve("last.png"));
                    finished.incrementAndGet();
                    return i;
                }, ignored -> { throw new AssertionError("failed batch merged"); });
                throw new AssertionError("failure swallowed");
            } catch (IOException expected) { checks++; }
            check(finished.get() == 1 && Files.exists(root.resolve("last.png")),
                "failed batch drains actual writes before returning; later batches not run");
        }

        CountDownLatch busy = new CountDownLatch(2);
        CountDownLatch release = new CountDownLatch(1);
        CompletableFuture<Boolean> cancelled = new CompletableFuture<>();
        Thread coordinator = new Thread(() -> {
            try (var tasks = new BuildTasks(2, 1)) {
                tasks.ordered(List.of(0, 1), i -> {
                    busy.countDown();
                    await(release);
                    while (true) BuildBudget.checkpoint();
                }, ignored -> { throw new AssertionError("cancelled task merged"); });
                cancelled.complete(false);
            } catch (CancellationException expected) {
                cancelled.complete(Thread.currentThread().isInterrupted());
            } catch (Throwable error) { cancelled.completeExceptionally(error); }
        });
        coordinator.start();
        await(busy);
        coordinator.interrupt();
        release.countDown();
        check(cancelled.get(2, TimeUnit.SECONDS), "interrupt drains CPU-limited workers and preserves flag");
        coordinator.join(2000);
        check(!coordinator.isAlive(), "cancelled coordinator exits");
        System.out.println("PASS build task checks=" + checks);
    }

    private static void await(CountDownLatch latch) throws IOException {
        try {
            if (!latch.await(5, TimeUnit.SECONDS)) throw new IOException("parallel tasks did not overlap");
        } catch (InterruptedException error) {
            Thread.currentThread().interrupt();
            throw new IOException(error);
        }
    }
    private static void check(boolean condition, String message) {
        checks++;
        if (!condition) throw new AssertionError(message);
    }
}
