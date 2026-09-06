package com.ailingmeng.ultimatepvpboss.client;

import java.lang.management.ManagementFactory;
import java.lang.management.ThreadInfo;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.function.Consumer;
import java.util.function.LongSupplier;

/** Observes the render heartbeat without posting tasks to (or waiting on) the render thread. */
final class RenderStallWatchdog {
    private static final long STALL_NANOS = TimeUnit.SECONDS.toNanos(10);
    private static final int MAX_REPORTS = 3;
    private final LongSupplier clock;
    private final Consumer<String> output;
    private volatile Heartbeat heartbeat;
    private boolean started;
    // Only the sampler thread accesses these fields.
    private Heartbeat sampledHeartbeat;
    private int reports;
    private long lastReport;

    RenderStallWatchdog(LongSupplier clock, Consumer<String> output) {
        this.clock = clock;
        this.output = output;
    }

    void heartbeat(boolean watchingBoss) {
        heartbeat = new Heartbeat(clock.getAsLong(), Thread.currentThread().getName(), watchingBoss);
    }

    // Called only on the render thread, once the boss is first drawn. One daemon per client,
    // not per entity/world. It is intentionally not a Minecraft/RenderSystem executor.
    void start() {
        if (started) return;
        started = true;
        Executors.newSingleThreadScheduledExecutor(task -> {
            Thread thread = new Thread(task, "ultimatepvpboss-render-watchdog");
            thread.setDaemon(true);
            return thread;
        }).scheduleWithFixedDelay(this::sample, 1, 1, TimeUnit.SECONDS);
    }

    void sample() {
        Heartbeat current = heartbeat;
        if (current == null || !current.watchingBoss()) return;
        long now = clock.getAsLong();
        if (current != sampledHeartbeat) {
            sampledHeartbeat = current;
            reports = 0;
        }
        if (now - current.nanos() < STALL_NANOS || reports >= MAX_REPORTS
                || (reports > 0 && now - lastReport < STALL_NANOS)) return;
        reports++;
        lastReport = now;
        StringBuilder dump = new StringBuilder("[PVPBOSS-RENDER-STALL] No render heartbeat for ")
                .append(TimeUnit.NANOSECONDS.toSeconds(now - current.nanos()))
                .append("s; watched thread: ").append(current.threadName())
                .append("; sample ").append(reports).append('/').append(MAX_REPORTS)
                .append(". A boss has been rendered in this world. This is diagnostic evidence, not proof of the culprit.\n");
        try {
            var threads = ManagementFactory.getThreadMXBean();
            ThreadInfo[] infos = threads.dumpAllThreads(threads.isObjectMonitorUsageSupported(),
                    threads.isSynchronizerUsageSupported());
            for (ThreadInfo info : infos) {
                dump.append('\n').append('"').append(info.getThreadName()).append("\" ")
                        .append(info.getThreadState()).append(" id=").append(info.getThreadId())
                        .append(" lock=").append(info.getLockName())
                        .append(" owner=").append(info.getLockOwnerName()).append('\n');
                // ThreadInfo.toString() truncates deep stacks. Keep every frame for mixin diagnosis.
                for (StackTraceElement frame : info.getStackTrace()) {
                    dump.append("    at ").append(frame).append('\n');
                }
                for (var monitor : info.getLockedMonitors()) {
                    dump.append("    locked monitor ").append(monitor).append('\n');
                }
                for (var lock : info.getLockedSynchronizers()) {
                    dump.append("    locked synchronizer ").append(lock).append('\n');
                }
            }
        } catch (RuntimeException error) {
            dump.append("Thread dump unavailable: ").append(error);
        }
        output.accept(dump.toString());
    }

    private record Heartbeat(long nanos, String threadName, boolean watchingBoss) { }
}
