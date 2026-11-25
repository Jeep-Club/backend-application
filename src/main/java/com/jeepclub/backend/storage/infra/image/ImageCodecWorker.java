package com.jeepclub.backend.storage.infra.image;

import com.jeepclub.backend.storage.core.repository.MediaImageProcessor;
import com.jeepclub.backend.storage.infra.image.SynchronousImageProcessor.Category;
import com.jeepclub.backend.storage.infra.image.SynchronousImageProcessor.Preset;

import java.io.BufferedInputStream;
import java.io.BufferedOutputStream;
import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/** Binary protocol for the one-request codec process. The process never starts Spring. */
public final class ImageCodecWorker {
    private static final int VERSION = 0x4a430001;
    private static final int MAX_VARIANTS = 16;
    private static final int MAX_RESULT_BYTES = 20 * 1024 * 1024;
    private static final int SUCCESS = 0;
    private static final int INVALID_IMAGE = 1;
    private static final int FAILED = 2;

    private ImageCodecWorker() {}

    record Request(UUID execution, byte[] bytes, Category category, List<Preset> presets,
                   Map<String, MediaImageProcessor.Crop> crops) {}

    public static void main(String[] args) throws IOException {
        try (var input = new DataInputStream(new BufferedInputStream(System.in));
             var output = new DataOutputStream(new BufferedOutputStream(System.out))) {
            var request = readRequest(input);
            SynchronousImageProcessor.Result result;
            try {
                result = new SynchronousImageProcessor().process(request.bytes(), request.category(),
                        request.presets(), request.crops());
            } catch (IOException | IllegalArgumentException invalid) {
                writeHeader(output, request.execution(), INVALID_IMAGE);
                return;
            } catch (RuntimeException unavailable) {
                writeHeader(output, request.execution(), FAILED);
                return;
            }
            writeHeader(output, request.execution(), SUCCESS);
            output.writeInt(1 + result.variants().size());
            writeImage(output, result.master());
            for (var image : result.variants()) writeImage(output, image);
        }
    }

    static void writeRequest(DataOutputStream output, Request request) throws IOException {
        output.writeInt(VERSION);
        writeUuid(output, request.execution());
        output.writeUTF(request.category().name());
        output.writeInt(request.presets().size());
        for (var preset : request.presets()) {
            output.writeUTF(preset.code());
            output.writeInt(preset.maxWidth());
            output.writeInt(preset.maxHeight());
        }
        output.writeInt(request.crops().size());
        for (var entry : request.crops().entrySet()) {
            var crop = entry.getValue();
            output.writeUTF(entry.getKey());
            output.writeDouble(crop.x());
            output.writeDouble(crop.y());
            output.writeDouble(crop.width());
            output.writeDouble(crop.height());
        }
        output.writeInt(request.bytes().length);
        output.write(request.bytes());
    }

    private static Request readRequest(DataInputStream input) throws IOException {
        if (input.readInt() != VERSION) throw new IOException("Invalid codec protocol");
        var execution = readUuid(input);
        var category = Category.valueOf(input.readUTF());
        int presetsCount = bounded(input.readInt(), 0, MAX_VARIANTS);
        var presets = new ArrayList<Preset>(presetsCount);
        for (int index = 0; index < presetsCount; index++) {
            presets.add(new Preset(input.readUTF(), input.readInt(), input.readInt(), null));
        }
        int cropsCount = bounded(input.readInt(), 0, presetsCount);
        var crops = new HashMap<String, MediaImageProcessor.Crop>(cropsCount);
        for (int index = 0; index < cropsCount; index++) {
            var code = input.readUTF();
            var crop = new MediaImageProcessor.Crop(input.readDouble(), input.readDouble(), input.readDouble(), input.readDouble());
            if (crops.putIfAbsent(code, crop) != null) throw new IOException("Duplicate crop");
        }
        var bytes = readBytes(input, bounded(input.readInt(), 1, SynchronousImageProcessor.MAX_INPUT));
        if (input.read() != -1) throw new IOException("Unexpected codec input");
        return new Request(execution, bytes, category, presets, crops);
    }

    private static void writeHeader(DataOutputStream output, UUID execution, int status) throws IOException {
        output.writeInt(VERSION);
        writeUuid(output, execution);
        output.writeByte(status);
    }

    private static void writeImage(DataOutputStream output, SynchronousImageProcessor.Image image) throws IOException {
        output.writeUTF(image.code());
        output.writeUTF(image.mimeType());
        output.writeInt(image.width());
        output.writeInt(image.height());
        byte[] bytes = image.bytes();
        output.writeInt(bytes.length);
        output.write(bytes);
    }

    static MediaImageProcessor.Processed readResponse(DataInputStream input, Request request) throws IOException {
        if (input.readInt() != VERSION || !request.execution().equals(readUuid(input))) {
            throw new IOException("Invalid codec response");
        }
        int status = input.readUnsignedByte();
        if (status != SUCCESS) {
            if (input.read() != -1) throw new IOException("Unexpected codec output");
            if (status == INVALID_IMAGE) throw new IllegalArgumentException("Invalid image content");
            throw new IOException("Codec unavailable");
        }
        int count = input.readInt();
        if (count != 1 + request.presets().size()) throw new IOException("Invalid codec representation count");
        int remaining = MAX_RESULT_BYTES;
        long derivativePixels = 0;
        var images = new ArrayList<MediaImageProcessor.Representation>(count);
        for (int index = 0; index < count; index++) {
            Preset preset = index == 0 ? null : request.presets().get(index - 1);
            String code = input.readUTF();
            String mime = input.readUTF();
            if (!code.equals(preset == null ? "MASTER" : preset.code())) throw new IOException("Invalid codec variant");
            int width = bounded(input.readInt(), 1, preset == null ? 4096 : preset.maxWidth());
            int height = bounded(input.readInt(), 1, preset == null ? 4096 : preset.maxHeight());
            if (preset != null) derivativePixels += (long) width * height;
            if (derivativePixels > 20_000_000L) throw new IOException("Invalid codec pixel budget");
            int limit = preset == null ? (request.category() == Category.OTHER ? 5 : 2) * 1024 * 1024 : remaining;
            int length = bounded(input.readInt(), 1, Math.min(limit, remaining));
            byte[] bytes = readBytes(input, length);
            remaining -= length;
            try {
                SynchronousImageProcessor.validateContainer(bytes);
                if (!SynchronousImageProcessor.containerMime(bytes).equals(mime)) throw new IOException("Invalid codec MIME");
                CodecImageDimensions.verify(bytes, mime, width, height);
            } catch (IllegalArgumentException invalid) {
                throw new IOException("Invalid representation container", invalid);
            }
            images.add(new MediaImageProcessor.Representation(code, bytes, mime, width, height));
        }
        if (input.read() != -1) throw new IOException("Unexpected codec output");
        return new MediaImageProcessor.Processed(inputHash(request.bytes()), images);
    }

    private static int bounded(int value, int min, int max) throws IOException {
        if (value < min || value > max) throw new IOException("Codec protocol limit exceeded");
        return value;
    }

    private static byte[] readBytes(DataInputStream input, int length) throws IOException {
        byte[] bytes = new byte[length];
        input.readFully(bytes);
        return bytes;
    }

    private static String inputHash(byte[] bytes) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes));
        } catch (NoSuchAlgorithmException unavailable) {
            throw new IllegalStateException("Codec digest unavailable", unavailable);
        }
    }

    private static UUID readUuid(DataInputStream input) throws IOException {
        return new UUID(input.readLong(), input.readLong());
    }

    private static void writeUuid(DataOutputStream output, UUID value) throws IOException {
        output.writeLong(value.getMostSignificantBits());
        output.writeLong(value.getLeastSignificantBits());
    }
}
