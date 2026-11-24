package com.jeepclub.backend.storage.infra.image;

import com.jeepclub.backend.storage.core.repository.MediaImageProcessor;
import com.jeepclub.backend.storage.infra.process.StorageProcessSupervisor;

import java.io.BufferedInputStream;
import java.io.BufferedOutputStream;
import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.nio.file.Path;
import java.time.Duration;
import java.util.function.Function;

final class IsolatedImageCodec {
    private final StorageProcessSupervisor supervisor;

    IsolatedImageCodec() {
        this(directory -> StorageProcessSupervisor.javaWorker(directory, ImageCodecWorker.class, 512));
    }

    IsolatedImageCodec(Function<Path, ProcessBuilder> command) {
        supervisor = new StorageProcessSupervisor("Image codec", Duration.ofSeconds(10), false, command);
    }

    MediaImageProcessor.Processed process(ImageCodecWorker.Request request, Runnable checkDeadline) {
        if (request.bytes() == null || request.bytes().length == 0
                || request.bytes().length > SynchronousImageProcessor.MAX_INPUT) {
            throw new IllegalArgumentException("Invalid image upload");
        }
        return supervisor.run(child -> {
            try (var stdin = new DataOutputStream(new BufferedOutputStream(child.getOutputStream()))) {
                ImageCodecWorker.writeRequest(stdin, request);
            }
            try (var stdout = new DataInputStream(new BufferedInputStream(child.getInputStream()))) {
                return ImageCodecWorker.readResponse(stdout, request);
            }
        }, checkDeadline);
    }
}
