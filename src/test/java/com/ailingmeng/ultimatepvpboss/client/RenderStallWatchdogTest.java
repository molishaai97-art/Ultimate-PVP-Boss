package com.ailingmeng.ultimatepvpboss.client;

import java.util.ArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;

/** Dependency-free regression tests; also runnable with javac/java without Minecraft or OpenGL. */
public final class RenderStallWatchdogTest {
    private static final long SECOND = TimeUnit.SECONDS.toNanos(1);

    public static void main(String[] args) throws InterruptedException {
        thresholdRateLimitAndRecovery();
        capturesBlockedRenderThread();
        startsOnlyOneDaemon();
        System.out.println("RenderStallWatchdog: 3 regression tests passed");
    }

    private static void thresholdRateLimitAndRecovery() {
        AtomicLong now = new AtomicLong();
        var reports = new ArrayList<String>();
        var watchdog = new RenderStallWatchdog(now::get, reports::add);
        watchdog.sample();
        watchdog.heartbeat(false);
        now.addAndGet(60 * SECOND);
        watchdog.sample();
        require(reports.isEmpty(), "Unarmed clients must not generate reports");

        for (int frame = 0; frame < 100; frame++) {
            watchdog.heartbeat(true);
            now.addAndGet(SECOND / 20);
            watchdog.sample();
        }
        require(reports.isEmpty(), "Normal frames must not generate reports");
        watchdog.heartbeat(true);
        now.addAndGet(10 * SECOND - 1);
        watchdog.sample();
        require(reports.isEmpty(), "Must wait the full ten seconds");
        now.incrementAndGet();
        sampleAtDepth(watchdog, 20);
        require(reports.size() == 1, "Must report at the threshold");
        require(reports.get(0).contains("[PVPBOSS-RENDER-STALL]"), "Missing searchable marker");
        long deepFrames = reports.get(0).lines().filter(line -> line.contains(".sampleAtDepth(")).count();
        require(deepFrames == 21, "Stack traces must not be truncated to eight frames");
        watchdog.sample();
        now.addAndGet(10 * SECOND - 1);
        watchdog.sample();
        require(reports.size() == 1, "Samples must be ten seconds apart");
        now.incrementAndGet();
        watchdog.sample();
        now.addAndGet(10 * SECOND);
        watchdog.sample();
        require(reports.size() == 3, "Expected three samples per stall");
        now.addAndGet(3600 * SECOND);
        watchdog.sample();
        require(reports.size() == 3, "Prolonged hangs must not flood the log");

        watchdog.heartbeat(true);
        watchdog.sample();
        now.addAndGet(10 * SECOND);
        watchdog.sample();
        require(reports.size() == 4, "A recovered heartbeat must reset the report limit");
        watchdog.heartbeat(false);
        now.addAndGet(60 * SECOND);
        watchdog.sample();
        require(reports.size() == 4, "Leaving the world must disarm diagnostics");
        watchdog.heartbeat(true);
        now.addAndGet(10 * SECOND);
        watchdog.sample();
        require(reports.size() == 5, "A new world must be able to rearm diagnostics");
    }

    private static void sampleAtDepth(RenderStallWatchdog watchdog, int depth) {
        if (depth == 0) watchdog.sample();
        else sampleAtDepth(watchdog, depth - 1);
    }

    private static void capturesBlockedRenderThread() throws InterruptedException {
        AtomicLong now = new AtomicLong();
        var reports = new ArrayList<String>();
        var watchdog = new RenderStallWatchdog(now::get, reports::add);
        CountDownLatch ready = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        Thread render = new Thread(() -> {
            watchdog.heartbeat(true);
            ready.countDown();
            try {
                release.await();
            } catch (InterruptedException error) {
                Thread.currentThread().interrupt();
            }
        }, "simulated-blocked-render-thread");
        render.setDaemon(true);
        render.start();
        try {
            require(ready.await(5, TimeUnit.SECONDS), "Render thread did not start");
            long deadline = System.nanoTime() + 5 * SECOND;
            while (render.getState() != Thread.State.WAITING && System.nanoTime() < deadline) {
                Thread.sleep(1);
            }
            require(render.getState() == Thread.State.WAITING, "Render thread did not block");
            now.addAndGet(10 * SECOND);
            watchdog.sample();
            require(reports.size() == 1, "Must sample without waiting for the render thread");
            String dump = reports.get(0);
            require(dump.contains("\"simulated-blocked-render-thread\" WAITING"), "Missing blocked thread state");
            require(dump.contains("CountDownLatch.await"), "Missing actual blocking stack");
            require(dump.contains(" owner="), "Missing lock-owner diagnostics");
        } finally {
            release.countDown();
            render.join(5000);
        }
        require(!render.isAlive(), "Test must not leave a blocked thread behind");
    }

    private static void startsOnlyOneDaemon() throws InterruptedException {
        var watchdog = new RenderStallWatchdog(System::nanoTime, ignored -> { });
        watchdog.heartbeat(false);
        watchdog.start();
        watchdog.start();
        long deadline = System.nanoTime() + 5 * SECOND;
        Thread[] workers;
        do {
            workers = Thread.getAllStackTraces().keySet().stream()
                    .filter(thread -> thread.getName().equals("ultimatepvpboss-render-watchdog"))
                    .toArray(Thread[]::new);
            if (workers.length > 0) break;
            Thread.sleep(1);
        } while (System.nanoTime() < deadline);
        require(workers.length == 1 && workers[0].isDaemon(), "Repeated arming must use a single daemon");
    }

    private static void require(boolean condition, String message) {
        if (!condition) throw new AssertionError(message);
    }
}
