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
import java.util.function.Consumer;
import java.util.function.Function;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

/** Owns one Storage worker and its transport until process exit is confirmed. */
public final class StorageProcessSupervisor {
    private final long timeoutNanos;
    private final String operation;
    private final Semaphore callers = new Semaphore(2);
    private final Semaphore capacity = new Semaphore(1, true);
    private final Function<Path, ProcessBuilder> command;
    private final Consumer<Process> terminate;

    public StorageProcessSupervisor(String operation, Duration budget,
                                    Function<Path, ProcessBuilder> command) {
        this(operation, budget, command, Process::destroyForcibly);
    }

    StorageProcessSupervisor(String operation, Duration budget, Function<Path, ProcessBuilder> command,
                             Consumer<Process> terminate) {
        this.operation = operation;
        this.timeoutNanos = budget.toNanos();
        this.command = command;
        this.terminate = terminate;
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
            if (execution.done.getCount() != 0) execution.requestStop();
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
        private final CountDownLatch stopCompleted = new CountDownLatch(1);
        private final List<ProcessHandle> descendants = new ArrayList<>();
        private volatile Process child;
        private volatile boolean stopping;
        private boolean stopScheduled;
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
                requestStop();
                try {
                    // Hold the slot across unconfirmed termination. No replacement can start meanwhile.
                    if (child != null) child.waitFor();
                    if (stopScheduled) stopCompleted.await();
                    for (var descendant : descendants) descendant.onExit().get();
                    if (treeObservationFailed) throw new IOException("Storage worker process tree not confirmed");
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

        private synchronized void requestStop() {
            stopping = true;
            var process = child;
            if (stopScheduled || process == null || !process.isAlive()) return;
            stopScheduled = true;
            try {
                var terminator = new Thread(() -> stop(process), "storage-worker-termination");
                terminator.setDaemon(true);
                terminator.start();
            } catch (RuntimeException | Error failedStart) {
                treeObservationFailed = true;
                stopCompleted.countDown();
            }
        }

        private void stop(Process process) {
            try {
                process.descendants().forEach(handle -> {
                    if (!descendants.contains(handle)) descendants.add(handle);
                    handle.destroyForcibly();
                });
            } catch (RuntimeException ignored) {
                treeObservationFailed = true;
            } finally {
                try {
                    terminate.accept(process);
                } catch (RuntimeException ignored) {
                    // The transport keeps waiting for observable exit.
                } finally {
                    stopCompleted.countDown();
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
