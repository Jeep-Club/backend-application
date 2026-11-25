package com.jeepclub.backend.storage.infra.process;

import org.junit.jupiter.api.Test;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import static org.assertj.core.api.Assertions.*;

class StorageProcessSupervisorTest {
    public static class BlockedChild {
        public static void main(String[] args) throws Exception {
            Files.writeString(Path.of(args[0]), Long.toString(ProcessHandle.current().pid()));
            new CountDownLatch(1).await();
        }
    }

    @Test void deadlineReturnsWhileTerminationIsBlockedAndSlotRemainsOwned() throws Exception {
        Path marker = Files.createTempFile("storage-supervisor-marker", ".txt");
        Files.delete(marker);
        var started = new AtomicInteger();
        var terminating = new CountDownLatch(1);
        var release = new CountDownLatch(1);
        var process = new AtomicReference<Process>();
        var expired = new AtomicBoolean();
        var supervisor = new StorageProcessSupervisor("fixture", Duration.ofSeconds(15), scratch -> {
            started.incrementAndGet();
            var builder = StorageProcessSupervisor.javaWorker(scratch, BlockedChild.class, 64);
            builder.command().add(marker.toString());
            return builder;
        }, child -> {
            process.set(child);
            terminating.countDown();
            try { release.await(); }
            catch (InterruptedException interrupted) { Thread.currentThread().interrupt(); }
            child.destroyForcibly();
        });
        var executor = Executors.newSingleThreadExecutor();
        try {
            var first = executor.submit(() -> assertThatThrownBy(() -> supervisor.run(child -> {
                child.getInputStream().read();
                return "unexpected";
            }, () -> { if (expired.get()) throw new IllegalStateException("fixture deadline"); }))
                    .isInstanceOf(IllegalStateException.class).hasMessage("fixture deadline"));
            long until = System.nanoTime() + TimeUnit.SECONDS.toNanos(10);
            while (!Files.exists(marker) && System.nanoTime() < until) Thread.sleep(10);
            assertThat(Files.exists(marker)).isTrue();
            expired.set(true);
            assertThat(terminating.await(5, TimeUnit.SECONDS)).isTrue();
            first.get(5, TimeUnit.SECONDS);
            assertThat(process.get().isAlive()).isTrue();
            var secondChecks = new AtomicInteger();
            assertThatThrownBy(() -> supervisor.run(child -> "unexpected", () -> {
                if (secondChecks.incrementAndGet() >= 3) throw new IllegalStateException("second deadline");
            })).isInstanceOf(IllegalStateException.class).hasMessage("second deadline");
            assertThat(secondChecks.get()).isGreaterThanOrEqualTo(3);
            assertThat(started.get()).isEqualTo(1);
            release.countDown();
            process.get().onExit().get(10, TimeUnit.SECONDS);
            until = System.nanoTime() + TimeUnit.SECONDS.toNanos(10);
            while (started.get() == 1 && System.nanoTime() < until) {
                var checks = new AtomicInteger();
                try { supervisor.run(child -> { child.destroyForcibly(); return "ok"; }, () -> {
                    if (checks.incrementAndGet() > 8) throw new IllegalStateException("retry");
                }); } catch (IllegalStateException ignored) { Thread.sleep(10); }
            }
            assertThat(started.get()).isEqualTo(2);
        } finally {
            release.countDown();
            if (process.get() != null && process.get().isAlive()) process.get().destroyForcibly();
            executor.shutdownNow();
            Files.deleteIfExists(marker);
        }
    }
}
