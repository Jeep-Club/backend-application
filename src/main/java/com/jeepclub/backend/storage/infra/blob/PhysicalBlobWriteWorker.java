package com.jeepclub.backend.storage.infra.blob;

import java.io.BufferedInputStream;
import java.io.BufferedOutputStream;
import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.nio.file.Path;
import java.util.UUID;

/** Runs the private provider's write, force, rename and verification as one operation. */
public final class PhysicalBlobWriteWorker {
    private static final int PROTOCOL = 0x4a420001;
    static final int MAX_BYTES = 10 * 1024 * 1024;

    private PhysicalBlobWriteWorker() {}

    public static void main(String[] args) throws IOException {
        serve(Path.of(args[0]), () -> {});
    }

    static void serve(Path root, Runnable beforePublish) throws IOException {
        try (var input = new DataInputStream(new BufferedInputStream(System.in));
             var output = new DataOutputStream(new BufferedOutputStream(System.out))) {
            if (input.readInt() != PROTOCOL) throw new IOException("Invalid physical write request");
            long high = input.readLong();
            long low = input.readLong();
            String digest = input.readUTF();
            int length = input.readInt();
            if (length <= 0 || length > MAX_BYTES) throw new IOException("Invalid physical write length");
            byte[] bytes = new byte[length];
            input.readFully(bytes);
            if (input.read() != -1) throw new IOException("Unexpected physical write data");
            boolean written;
            try {
                new LocalPhysicalBlobStore(root).putVerifiedDirect(digest, bytes, beforePublish);
                written = true;
            } catch (RuntimeException failure) {
                written = false;
            }
            output.writeInt(PROTOCOL);
            output.writeLong(high);
            output.writeLong(low);
            output.writeBoolean(written);
        }
    }

    static boolean exchange(Process child, UUID operation, String digest, byte[] bytes) throws IOException {
        try (var input = new DataOutputStream(new BufferedOutputStream(child.getOutputStream()))) {
            input.writeInt(PROTOCOL);
            input.writeLong(operation.getMostSignificantBits());
            input.writeLong(operation.getLeastSignificantBits());
            input.writeUTF(digest);
            input.writeInt(bytes.length);
            input.write(bytes);
        }
        try (var output = new DataInputStream(new BufferedInputStream(child.getInputStream()))) {
            if (output.readInt() != PROTOCOL || output.readLong() != operation.getMostSignificantBits()
                    || output.readLong() != operation.getLeastSignificantBits()) {
                throw new IOException("Invalid physical write response");
            }
            boolean written = output.readBoolean();
            if (output.read() != -1 || !written) throw new IOException("Physical write not confirmed");
            return true;
        }
    }
}
