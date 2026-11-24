package com.jeepclub.backend.storage.infra.image;

import com.jeepclub.backend.storage.core.repository.MediaImageProcessor;

import java.io.BufferedInputStream;
import java.io.BufferedOutputStream;
import java.io.DataInputStream;
import java.io.DataOutputStream;
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

/** Owns one isolated codec execution and its transport until process exit is confirmed. */
final class IsolatedImageCodec {
    private static final long MAX_CODEC_NANOS = Duration.ofSeconds(10).toNanos();
    private final Semaphore callers = new Semaphore(2);
    private final Semaphore capacity = new Semaphore(1, true);
    private final Function<Path, ProcessBuilder> command;

    IsolatedImageCodec() {
        this(IsolatedImageCodec::workerCommand);
    }

    IsolatedImageCodec(Function<Path, ProcessBuilder> command) {
        this.command = command;
    }

    MediaImageProcessor.Processed process(ImageCodecWorker.Request request, Runnable checkDeadline) {
        if (request.bytes() == null || request.bytes().length == 0
                || request.bytes().length > SynchronousImageProcessor.MAX_INPUT) {
            throw new IllegalArgumentException("Invalid image upload");
        }
        if (!callers.tryAcquire()) throw new IllegalStateException("Image codec capacity exceeded");
        long started = System.nanoTime();
        try {
            checkDeadline.run();
            while (!capacity.tryAcquire(25, TimeUnit.MILLISECONDS)) {
                checkTime(checkDeadline, started);
            }
            return execute(request, checkDeadline, started);
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("Image codec interrupted", interrupted);
        } finally {
            callers.release();
        }
    }

    private MediaImageProcessor.Processed execute(ImageCodecWorker.Request request, Runnable checkDeadline, long started)
            throws InterruptedException {
        var execution = new Execution(request);
        try {
            checkTime(checkDeadline, started);
            var transport = new Thread(execution::run, "storage-codec-transport");
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
            checkDeadline.run();
            if (execution.failure != null) throw execution.failure;
            if (execution.result == null) throw new IllegalStateException("Image codec unavailable");
            return execution.result;
        } finally {
            if (execution.done.getCount() != 0) execution.stop();
        }
    }

    private static void checkTime(Runnable checkDeadline, long started) {
        checkDeadline.run();
        if (System.nanoTime() - started >= MAX_CODEC_NANOS) {
            throw new IllegalStateException("Image codec deadline exceeded");
        }
    }

    private final class Execution {
        private final ImageCodecWorker.Request request;
        private final CountDownLatch done = new CountDownLatch(1);
        private final List<ProcessHandle> descendants = new ArrayList<>();
        private volatile Process child;
        private volatile boolean stopping;
        private boolean treeObservationFailed;
        private RuntimeException failure;
        private MediaImageProcessor.Processed result;

        private Execution(ImageCodecWorker.Request request) {
            this.request = request;
        }

        private void run() {
            Path directory = null;
            try {
                directory = Files.createTempDirectory("storage-codec-");
                if (stopping) return;
                ProcessBuilder builder = command.apply(directory);
                builder.directory(directory.toFile());
                cleanEnvironment(builder);
                builder.redirectError(ProcessBuilder.Redirect.DISCARD);
                child = builder.start();
                if (stopping) return;
                try (var stdin = new DataOutputStream(new BufferedOutputStream(child.getOutputStream()))) {
                    ImageCodecWorker.writeRequest(stdin, request);
                }
                try (var stdout = new DataInputStream(new BufferedInputStream(child.getInputStream()))) {
                    result = ImageCodecWorker.readResponse(stdout, request);
                }
                if (child.waitFor() != 0) throw new IOException("Codec exited unexpectedly");
            } catch (IllegalArgumentException invalid) {
                failure = invalid;
            } catch (IOException | RuntimeException unavailable) {
                failure = new IllegalStateException("Image codec unavailable", unavailable);
            } catch (InterruptedException interrupted) {
                Thread.currentThread().interrupt();
                failure = new IllegalStateException("Image codec interrupted", interrupted);
            } finally {
                stop();
                try {
                    // Hold the slot across unconfirmed termination. No replacement can start meanwhile.
                    if (child != null) child.waitFor();
                    for (var descendant : knownDescendants()) descendant.onExit().get();
                    if (treeObservationFailed) throw new IOException("Codec process tree not confirmed");
                    cleanDirectory(directory);
                    capacity.release();
                } catch (IOException | InterruptedException | java.util.concurrent.ExecutionException | RuntimeException unsafeCleanup) {
                    if (unsafeCleanup instanceof InterruptedException) Thread.currentThread().interrupt();
                    failure = new IllegalStateException("Image codec cleanup unavailable", unsafeCleanup);
                } finally {
                    done.countDown();
                }
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

    private static ProcessBuilder workerCommand(Path directory) {
        boolean windows = System.getProperty("os.name").toLowerCase(Locale.ROOT).startsWith("windows");
        var java = Path.of(System.getProperty("java.home"), "bin", windows ? "java.exe" : "java").toString();
        var worker = ImageCodecWorker.class.getName();
        String classpath = Arrays.stream(System.getProperty("java.class.path").split(Pattern.quote(File.pathSeparator)))
                .map(path -> Path.of(path).toAbsolutePath().normalize().toString())
                .collect(Collectors.joining(File.pathSeparator));
        var command = new ArrayList<>(List.of(java, "-Xmx512m", "-XX:ActiveProcessorCount=2",
                "-Djava.awt.headless=true", "-Djava.io.tmpdir=" + directory, "-cp", classpath));
        if (ImageCodecWorker.class.getClassLoader().getClass().getName().startsWith("org.springframework.boot.loader.")) {
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
