package com.jeepclub.backend.storage.infra.process;
import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Semaphore;
import java.util.concurrent.TimeUnit;
import java.util.function.Function;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

/** Owns one Storage worker and its transport until process exit is confirmed. */
public final class StorageProcessSupervisor {
    private final long timeoutNanos;
    private final String operation;
    private final boolean requireExitBeforeReturn;
    private final Semaphore callers = new Semaphore(2);
    private final Semaphore capacity = new Semaphore(1, true);
    private final Function<Path, ProcessBuilder> command;

    public StorageProcessSupervisor(String operation, Duration budget, boolean requireExitBeforeReturn,
                                    Function<Path, ProcessBuilder> command) {
        this.operation = operation;
        this.timeoutNanos = budget.toNanos();
        this.requireExitBeforeReturn = requireExitBeforeReturn;
        this.command = command;
    }

    @FunctionalInterface
    public interface Exchange<T> {
        T transfer(Process worker) throws IOException;
    }

    public <T> T run(Exchange<T> exchange, Runnable checkDeadline) {
        if (!callers.tryAcquire()) throw new IllegalStateException(operation + " capacity exceeded");
        long started = System.nanoTime();
        try {
            checkTime(checkDeadline, started);
            while (!capacity.tryAcquire(25, TimeUnit.MILLISECONDS)) {
                checkTime(checkDeadline, started);
            }
            return execute(exchange, checkDeadline, started);
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException(operation + " interrupted", interrupted);
        } finally {
            callers.release();
        }
    }

    private <T> T execute(Exchange<T> exchange, Runnable checkDeadline, long started)
            throws InterruptedException {
        var execution = new Execution<>(exchange);
        try {
            checkTime(checkDeadline, started);
            var transport = new Thread(execution::run, "storage-worker-transport");
            transport.setDaemon(true);
            transport.start();
        } catch (RuntimeException | Error failedStart) {
            capacity.release();
            throw failedStart;
        }
        try {
            while (!execution.done.await(25, TimeUnit.MILLISECONDS)) {
                checkTime(checkDeadline, started);
            }
            checkTime(checkDeadline, started);
            if (execution.failure != null) throw execution.failure;
            if (execution.result == null) throw new IllegalStateException(operation + " unavailable");
            return execution.result;
        } finally {
            if (execution.done.getCount() != 0) execution.stop();
            if (requireExitBeforeReturn) execution.awaitTermination();
        }
    }

    private void checkTime(Runnable checkDeadline, long started) {
        checkDeadline.run();
        if (System.nanoTime() - started >= timeoutNanos) {
            throw new IllegalStateException(operation + " deadline exceeded");
        }
    }

    private final class Execution<T> {
        private final Exchange<T> exchange;
        private final CountDownLatch done = new CountDownLatch(1);
        private final CountDownLatch terminated = new CountDownLatch(1);
        private final List<ProcessHandle> descendants = new ArrayList<>();
        private volatile Process child;
        private volatile boolean stopping;
        private boolean treeObservationFailed;
        private RuntimeException failure;
        private T result;

        private Execution(Exchange<T> exchange) {
            this.exchange = exchange;
        }

        private void run() {
            Path directory = null;
            try {
                directory = Files.createTempDirectory("storage-worker-");
                if (stopping) return;
                ProcessBuilder builder = command.apply(directory);
                builder.directory(directory.toFile());
                cleanEnvironment(builder);
                builder.redirectError(ProcessBuilder.Redirect.DISCARD);
                child = builder.start();
                if (stopping) return;
                result = exchange.transfer(child);
                if (child.waitFor() != 0) throw new IOException("Storage worker exited unexpectedly");
            } catch (IllegalArgumentException invalid) {
                failure = invalid;
            } catch (IOException | RuntimeException unavailable) {
                failure = new IllegalStateException(operation + " unavailable", unavailable);
            } catch (InterruptedException interrupted) {
                Thread.currentThread().interrupt();
                failure = new IllegalStateException(operation + " interrupted", interrupted);
            } finally {
                stop();
                try {
                    // Hold the slot across unconfirmed termination. No replacement can start meanwhile.
                    if (child != null) child.waitFor();
                    for (var descendant : knownDescendants()) descendant.onExit().get();
                    if (treeObservationFailed) throw new IOException("Storage worker process tree not confirmed");
                    terminated.countDown();
                    cleanDirectory(directory);
                    capacity.release();
                } catch (IOException | InterruptedException | java.util.concurrent.ExecutionException | RuntimeException unsafeCleanup) {
                    if (unsafeCleanup instanceof InterruptedException) Thread.currentThread().interrupt();
                    failure = new IllegalStateException(operation + " cleanup unavailable", unsafeCleanup);
                } finally {
                    done.countDown();
                }
            }
        }

        private void awaitTermination() {
            boolean interrupted = Thread.interrupted();
            try {
                for (;;) {
                    try {
                        terminated.await();
                        return;
                    } catch (InterruptedException ignored) {
                        interrupted = true;
                    }
                }
            } finally {
                if (interrupted) Thread.currentThread().interrupt();
            }
        }

        private synchronized List<ProcessHandle> knownDescendants() {
            return List.copyOf(descendants);
        }

        private synchronized void stop() {
            stopping = true;
            var process = child;
            if (process == null || !process.isAlive()) return;
            try {
                process.descendants().forEach(handle -> {
                    if (!descendants.contains(handle)) descendants.add(handle);
                    handle.destroyForcibly();
                });
            } catch (RuntimeException ignored) {
                treeObservationFailed = true;
            } finally {
                try {
                    process.destroyForcibly();
                } catch (RuntimeException ignored) {
                    // The transport keeps waiting for observable exit.
                }
            }
        }
    }

    public static ProcessBuilder javaWorker(Path directory, Class<?> workerType, int heapMiB) {
        boolean windows = System.getProperty("os.name").toLowerCase(Locale.ROOT).startsWith("windows");
        var java = Path.of(System.getProperty("java.home"), "bin", windows ? "java.exe" : "java").toString();
        var worker = workerType.getName();
        String classpath = Arrays.stream(System.getProperty("java.class.path").split(Pattern.quote(File.pathSeparator)))
                .map(path -> Path.of(path).toAbsolutePath().normalize().toString())
                .collect(Collectors.joining(File.pathSeparator));
        var command = new ArrayList<>(List.of(java, "-Xmx" + heapMiB + "m", "-XX:ActiveProcessorCount=2",
                "-Djava.awt.headless=true", "-Djava.io.tmpdir=" + directory, "-cp", classpath));
        if (workerType.getClassLoader().getClass().getName().startsWith("org.springframework.boot.loader.")) {
            command.add(1, "-Dloader.main=" + worker);
            command.add("org.springframework.boot.loader.launch.PropertiesLauncher");
        } else {
            command.add(worker);
        }
        return new ProcessBuilder(command);
    }

    private static void cleanEnvironment(ProcessBuilder builder) {
        var environment = builder.environment();
        String systemRoot = environment.get("SystemRoot");
        environment.clear();
        if (systemRoot != null) environment.put("SystemRoot", systemRoot);
    }

    private static void cleanDirectory(Path directory) throws IOException {
        if (directory == null) return;
        List<Path> files;
        try (var tree = Files.walk(directory)) {
            files = tree.sorted(Comparator.reverseOrder()).toList();
        }
        for (Path file : files) Files.deleteIfExists(file);
    }
}
