package com.jeepclub.backend.storage.infra.image;

import org.junit.jupiter.api.Test;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.File;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

import static org.assertj.core.api.Assertions.*;

class IsolatedImageCodecTest {
    private static ImageCodecWorker.Request request() {
        return new ImageCodecWorker.Request(UUID.randomUUID(), new byte[]{1},
                SynchronousImageProcessor.Category.PROFILE, List.of(), Map.of());
    }

    private static ProcessBuilder javaProcess(Class<?> main, String... args) {
        String executable = System.getProperty("os.name").toLowerCase().startsWith("windows") ? "java.exe" : "java";
        String classpath = java.util.Arrays.stream(System.getProperty("java.class.path").split(Pattern.quote(File.pathSeparator)))
                .map(p -> Path.of(p).toAbsolutePath().normalize().toString()).collect(Collectors.joining(File.pathSeparator));
        var command = new java.util.ArrayList<>(List.of(Path.of(System.getProperty("java.home"), "bin", executable).toString(),
                "-cp", classpath, main.getName()));
        command.addAll(List.of(args));
        return new ProcessBuilder(command);
    }

    public static class BlockingChild {
        public static void main(String[] args) throws Exception {
            Files.writeString(Path.of(args[0]), Long.toString(ProcessHandle.current().pid()));
            Thread.sleep(TimeUnit.MINUTES.toMillis(1));
        }
    }

    @Test void deadlineKillsBlockedChildAndCapacityReturnsAfterExit() throws Exception {
        var marker = Files.createTempFile("codec-child-marker", ".txt");
        Files.delete(marker);
        var count = new AtomicInteger();
        var child = new AtomicReference<ProcessHandle>();
        var codec = new IsolatedImageCodec(directory -> count.incrementAndGet() == 1
                ? javaProcess(BlockingChild.class, marker.toString()) : javaProcess(ImageCodecWorker.class));
        var expired = new AtomicBoolean();
        var executor = Executors.newFixedThreadPool(2);
        try {
            var first = executor.submit(() -> assertThatThrownBy(() -> codec.process(request(), () -> {
                if (expired.get()) throw new IllegalStateException("fixture deadline");
            })).isInstanceOf(IllegalStateException.class).hasMessage("fixture deadline"));
            long until = System.nanoTime() + Duration.ofSeconds(15).toNanos();
            while (!Files.exists(marker) && System.nanoTime() < until) Thread.sleep(10);
            assertThat(Files.exists(marker)).isTrue();
            child.set(ProcessHandle.of(Long.parseLong(Files.readString(marker))).orElseThrow());
            assertThat(child.get().isAlive()).isTrue();
            var waiting = new java.util.concurrent.CountDownLatch(1);
            var replacement = executor.submit(() -> assertThatThrownBy(() -> codec.process(request(), waiting::countDown))
                    .isInstanceOf(IllegalArgumentException.class));
            assertThat(waiting.await(3, TimeUnit.SECONDS)).isTrue();
            assertThatThrownBy(() -> codec.process(request(), () -> {})).isInstanceOf(IllegalStateException.class)
                    .hasMessageContaining("capacity");
            assertThat(count.get()).isEqualTo(1);
            expired.set(true);
            first.get(10, TimeUnit.SECONDS);
            child.get().onExit().get(10, TimeUnit.SECONDS);
            assertThat(child.get().isAlive()).isFalse();
            // The replacement reaches the real worker, which rejects this intentionally invalid image.
            replacement.get(10, TimeUnit.SECONDS);
            assertThat(count.get()).isEqualTo(2);
        } finally {
            expired.set(true);
            if (child.get() != null && child.get().isAlive()) child.get().destroyForcibly();
            executor.shutdownNow();
            Files.deleteIfExists(marker);
        }
    }

    @Test void startupFailureReleasesCapacity() {
        var starts = new AtomicInteger();
        var codec = new IsolatedImageCodec(directory -> {
            starts.incrementAndGet();
            return new ProcessBuilder(directory.resolve("nonexistent-codec-executable").toString());
        });
        for (int i = 0; i < 2; i++) {
            assertThatThrownBy(() -> codec.process(request(), () -> {})).isInstanceOf(IllegalStateException.class)
                    .hasMessage("Image codec unavailable");
        }
        assertThat(starts.get()).isEqualTo(2);
    }

    @Test void protocolRejectsTruncatedAndOversizedResponses() throws Exception {
        var request = request();
        assertThatThrownBy(() -> ImageCodecWorker.readResponse(new DataInputStream(new ByteArrayInputStream(new byte[]{1, 2})), request))
                .isInstanceOf(java.io.IOException.class);
        var bytes = new ByteArrayOutputStream();
        try (var output = new DataOutputStream(bytes)) {
            output.writeInt(0x4a430001);
            output.writeLong(request.execution().getMostSignificantBits());
            output.writeLong(request.execution().getLeastSignificantBits());
            output.writeByte(0);
            output.writeInt(1);
            output.writeUTF("MASTER");
            output.writeUTF("image/png");
            output.writeInt(1);
            output.writeInt(1);
            output.writeInt(2 * 1024 * 1024 + 1);
        }
        assertThatThrownBy(() -> ImageCodecWorker.readResponse(new DataInputStream(new ByteArrayInputStream(bytes.toByteArray())), request))
                .isInstanceOf(java.io.IOException.class).hasMessageContaining("limit");
    }
}
