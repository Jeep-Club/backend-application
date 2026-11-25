package com.jeepclub.backend.storage.infra.process;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.atomic.AtomicReference;

public final class StorageSupervisorFixture implements AutoCloseable {
    public static class Child {
        public static void main(String[] args) throws Exception {
            Files.writeString(Path.of(args[0]), "started");
            new CountDownLatch(1).await();
        }
    }
    public final CountDownLatch terminating = new CountDownLatch(1);
    public final CountDownLatch release = new CountDownLatch(1);
    public final AtomicReference<Process> process = new AtomicReference<>();
    private final Path marker;
    private final StorageProcessSupervisor supervisor;
    public StorageSupervisorFixture(Path marker) {
        this.marker = marker;
        supervisor = new StorageProcessSupervisor("fixture", Duration.ofSeconds(10), scratch -> {
            var builder = StorageProcessSupervisor.javaWorker(scratch, Child.class, 64);
            builder.command().add(marker.toString());
            return builder;
        }, child -> {
            process.set(child); terminating.countDown();
            try { release.await(); } catch (InterruptedException e) { Thread.currentThread().interrupt(); }
            child.destroyForcibly();
        });
    }
    public void run() {
        supervisor.run(child -> { child.getInputStream().read(); return "unexpected"; }, () -> {
            if (Files.exists(marker)) throw new IllegalStateException("fixture private deadline");
        });
    }
    public void close() { release.countDown(); if (process.get() != null && process.get().isAlive()) process.get().destroyForcibly(); }
}
