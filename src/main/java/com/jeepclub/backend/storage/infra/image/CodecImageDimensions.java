package com.jeepclub.backend.storage.infra.image;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.charset.StandardCharsets;

/** Verifies the dimensions announced by the codec without decoding in the parent JVM. */
final class CodecImageDimensions {
    private CodecImageDimensions() {}

    static void verify(byte[] bytes, String mime, int width, int height) throws IOException {
        switch (mime) {
            case "image/png" -> {
                var header = ByteBuffer.wrap(bytes, 16, 8);
                requireMatches(header.getInt(), header.getInt(), width, height);
            }
            case "image/jpeg" -> verifyJpeg(bytes, width, height);
            case "image/webp" -> verifyWebp(bytes, width, height);
            default -> throw new IOException("Unknown representation format");
        }
    }

    private static void verifyJpeg(byte[] bytes, int width, int height) throws IOException {
        int offset = 2;
        while (offset < bytes.length - 3 && bytes[offset] == (byte) 0xff) {
            int marker = bytes[++offset] & 255;
            if (marker == 255) continue;
            offset++;
            if (marker == 0xda || marker == 0xd9) break;
            int size = (bytes[offset] & 255) << 8 | (bytes[offset + 1] & 255);
            if (size < 2 || size > bytes.length - offset) break;
            boolean frame = marker >= 0xc0 && marker <= 0xcf && marker != 0xc4 && marker != 0xc8 && marker != 0xcc;
            if (frame && size >= 8) {
                int actualHeight = (bytes[offset + 3] & 255) << 8 | (bytes[offset + 4] & 255);
                int actualWidth = (bytes[offset + 5] & 255) << 8 | (bytes[offset + 6] & 255);
                requireMatches(actualWidth, actualHeight, width, height);
                return;
            }
            offset += size;
        }
        throw new IOException("JPEG representation has no frame dimensions");
    }

    private static void verifyWebp(byte[] bytes, int width, int height) throws IOException {
        boolean frame = false;
        var buffer = ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN);
        for (int offset = 12; offset <= bytes.length - 8;) {
            String chunk = new String(bytes, offset, 4, StandardCharsets.US_ASCII);
            int length = buffer.getInt(offset + 4);
            int data = offset + 8;
            // Container integrity was checked before inspecting the chunks.
            switch (chunk) {
                case "VP8X" -> {
                    if (length < 10) throw new IOException("Incomplete WebP canvas");
                    requireMatches(little24(bytes, data + 4) + 1, little24(bytes, data + 7) + 1, width, height);
                }
                case "VP8L" -> {
                    if (length < 5 || bytes[data] != 0x2f) throw new IOException("Incomplete lossless WebP frame");
                    int bits = buffer.getInt(data + 1);
                    requireMatches((bits & 0x3fff) + 1, ((bits >>> 14) & 0x3fff) + 1, width, height);
                    frame = true;
                }
                case "VP8 " -> {
                    if (length < 10 || (bytes[data] & 1) != 0 || bytes[data + 3] != (byte) 0x9d
                            || bytes[data + 4] != 1 || bytes[data + 5] != 0x2a) {
                        throw new IOException("Incomplete lossy WebP frame");
                    }
                    requireMatches(buffer.getShort(data + 6) & 0x3fff, buffer.getShort(data + 8) & 0x3fff, width, height);
                    frame = true;
                }
                default -> { }
            }
            offset = data + length + (length & 1);
        }
        if (!frame) throw new IOException("WebP representation has no frame dimensions");
    }

    private static int little24(byte[] bytes, int offset) {
        return (bytes[offset] & 255) | ((bytes[offset + 1] & 255) << 8) | ((bytes[offset + 2] & 255) << 16);
    }

    private static void requireMatches(int actualWidth, int actualHeight, int width, int height) throws IOException {
        if (actualWidth != width || actualHeight != height) throw new IOException("Codec dimensions do not match representation");
    }
}
