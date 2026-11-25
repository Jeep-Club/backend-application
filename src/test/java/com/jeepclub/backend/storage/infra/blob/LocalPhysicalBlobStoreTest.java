package com.jeepclub.backend.storage.infra.blob;

import com.jeepclub.backend.platform.storage.properties.StorageProperties;
import com.jeepclub.backend.storage.infra.persistence.adapter.BlobCatalogAdapter;
import org.junit.jupiter.api.Test;
import java.nio.file.Files;
import java.nio.file.Path;
import static org.assertj.core.api.Assertions.*;

class LocalPhysicalBlobStoreTest {
    public static final class CompetingPublisher {
        public static void main(String[] args) throws Exception {
            Path root = Path.of(args[0]);
            PhysicalBlobWriteWorker.serve(root, () -> {
                try {
                    Files.write(root.resolve("storage-blobs").resolve(args[1] + ".bin"),
                            new byte[]{3, 2, 1}, java.nio.file.StandardOpenOption.CREATE_NEW);
                } catch (java.io.IOException failure) {
                    throw new java.io.UncheckedIOException(failure);
                }
            });
        }
    }

    @Test void canonicalBlobIsNotOverwrittenWhenAnotherWriterWinsPublication(
            @org.junit.jupiter.api.io.TempDir Path root) throws Exception {
        byte[] bytes = {1, 2, 3};
        String digest = BlobCatalogAdapter.digest(bytes);
        var physical = new LocalPhysicalBlobStore(root, scratch -> {
            var command = com.jeepclub.backend.storage.infra.process.StorageProcessSupervisor
                    .javaWorker(scratch, CompetingPublisher.class, 128);
            command.command().add(root.toString());
            command.command().add(digest);
            return command;
        });
        assertThatThrownBy(() -> physical.putVerified(digest, bytes)).isInstanceOf(IllegalStateException.class);
        assertThat(Files.readAllBytes(root.resolve("storage-blobs").resolve(digest + ".bin"))).containsExactly(3, 2, 1);
    }

    public static final class BlockedBeforePublish {
        public static void main(String[] args) throws Exception {
            PhysicalBlobWriteWorker.serve(Path.of(args[0]), () -> {
                try {
                    Files.writeString(Path.of(args[1]), Long.toString(ProcessHandle.current().pid()));
                    new java.util.concurrent.CountDownLatch(1).await();
                } catch (java.io.IOException failure) {
                    throw new java.io.UncheckedIOException(failure);
                } catch (InterruptedException interrupted) {
                    Thread.currentThread().interrupt();
                    throw new IllegalStateException(interrupted);
                }
            });
        }
    }

    @Test void timedOutWriterLeavesPrivateStageAndReleasesCapacityOnlyAfterExit(
            @org.junit.jupiter.api.io.TempDir Path providerRoot) throws Exception {
        Path marker = providerRoot.resolve("worker-marker");
        var starts = new java.util.concurrent.atomic.AtomicInteger();
        var physical = new LocalPhysicalBlobStore(providerRoot, scratch -> {
            boolean blocked = starts.incrementAndGet() == 1;
            var command = com.jeepclub.backend.storage.infra.process.StorageProcessSupervisor.javaWorker(scratch,
                    blocked ? BlockedBeforePublish.class : PhysicalBlobWriteWorker.class, 128);
            command.command().add(providerRoot.toString());
            if (blocked) command.command().add(marker.toString());
            return command;
        });
        byte[] bytes = {4, 7, 11};
        String digest = BlobCatalogAdapter.digest(bytes);
        var childPid = new java.util.concurrent.atomic.AtomicLong();
        assertThatThrownBy(() -> physical.putVerified(digest, bytes, () -> {
            try {
                if (Files.exists(marker)) {
                    String pid = Files.readString(marker);
                    if (!pid.isBlank()) {
                        childPid.set(Long.parseLong(pid));
                        throw new IllegalStateException("fixture storage deadline");
                    }
                }
            } catch (java.io.IOException failure) {
                throw new java.io.UncheckedIOException(failure);
            }
        })).isInstanceOf(IllegalStateException.class).hasMessage("fixture storage deadline");
        assertThat(childPid.get()).isPositive();
        var child = ProcessHandle.of(childPid.get());
        if (child.isPresent()) child.get().onExit().get(10, java.util.concurrent.TimeUnit.SECONDS);
        assertThat(ProcessHandle.of(childPid.get()).filter(ProcessHandle::isAlive)).isEmpty();
        Path directory = providerRoot.resolve("storage-blobs");
        assertThat(directory.resolve(digest + ".bin")).doesNotExist();
        try (var paths = Files.list(directory)) {
            assertThat(paths.map(path -> path.getFileName().toString()).toList())
                    .hasSize(1).allMatch(name -> name.startsWith(digest + ".") && name.endsWith(".pending"));
        }
        long retryDeadline = System.nanoTime() + java.util.concurrent.TimeUnit.SECONDS.toNanos(10);
        while (true) {
            try { physical.putVerified(digest, bytes); break; }
            catch (IllegalStateException unavailable) {
                if (System.nanoTime() >= retryDeadline) throw unavailable;
                Thread.sleep(10);
            }
        }
        assertThat(physical.readVerified(digest, bytes.length)).containsExactly(bytes);
        assertThat(starts.get()).isEqualTo(2);
    }

    @Test void missingBlobIsDistinguishedFromCorruptContent() throws Exception {
        var root=Files.createTempDirectory("storage-missing-fixture-");var properties=new StorageProperties();properties.local().setRootDirectory(root);
        var physical=new LocalPhysicalBlobStore(properties);byte[] bytes={1,2,3};String hash=BlobCatalogAdapter.digest(bytes);
        assertThatThrownBy(()->physical.readVerified(hash,bytes.length)).isInstanceOf(com.jeepclub.backend.shared.storage.exception.StorageObjectNotFoundException.class);
        physical.putVerified(hash,bytes);
        assertThat(physical.readVerified(hash,bytes.length)).containsExactly(bytes);
    }
    @Test void privateObjectsUseRestrictivePosixModesWhenSupported() throws Exception {
        var root=Files.createTempDirectory("storage-mode-fixture-");
        org.junit.jupiter.api.Assumptions.assumeTrue(Files.getFileStore(root).supportsFileAttributeView(java.nio.file.attribute.PosixFileAttributeView.class));
        var properties=new StorageProperties();properties.local().setRootDirectory(root);var physical=new LocalPhysicalBlobStore(properties);
        byte[] bytes={4,5,6};String hash=BlobCatalogAdapter.digest(bytes);physical.putVerified(hash,bytes);
        assertThat(Files.getPosixFilePermissions(root.resolve("storage-blobs"))).isEqualTo(java.nio.file.attribute.PosixFilePermissions.fromString("rwx------"));
        assertThat(Files.getPosixFilePermissions(root.resolve("storage-blobs").resolve(hash+".bin"))).isEqualTo(java.nio.file.attribute.PosixFilePermissions.fromString("rw-------"));
    }
    @Test void inventoryResumesWithoutRestartingTheDirectoryAndBoundsEachPage() throws Exception {
        var root=Files.createTempDirectory("storage-page-fixture-");var properties=new StorageProperties();properties.local().setRootDirectory(root);
        var physical=new LocalPhysicalBlobStore(properties);
        for(int i=1;i<=5;i++){byte[] bytes={(byte)i};physical.putVerified(BlobCatalogAdapter.digest(bytes),bytes);}
        var names=new java.util.HashSet<String>();String cursor=null;int pages=0;
        do {var page=physical.inventory(cursor,2);assertThat(page.filenames()).hasSizeLessThanOrEqualTo(2);names.addAll(page.filenames());cursor=page.nextCursor();pages++;}while(cursor!=null);
        assertThat(names).hasSize(5);assertThat(pages).isEqualTo(3);
        assertThatThrownBy(() -> physical.inventory("expired",2)).isInstanceOf(IllegalArgumentException.class);
        physical.closeInventories();
    }
    @Test void corruptionIsRejectedAndExistingCustodyIsNeverOverwritten() throws Exception {
        var root=Files.createTempDirectory("storage-corrupt-fixture-");var properties=new StorageProperties();properties.local().setRootDirectory(root);
        var physical=new LocalPhysicalBlobStore(properties);byte[] bytes={1,2,3};String hash=BlobCatalogAdapter.digest(bytes);
        physical.putVerified(hash,bytes);var target=root.resolve("storage-blobs").resolve(hash+".bin");
        Files.write(target,new byte[]{3,2,1});
        assertThatThrownBy(() -> physical.readVerified(hash,3)).isInstanceOf(IllegalStateException.class);
        assertThatThrownBy(() -> physical.putVerified(hash,bytes)).isInstanceOf(IllegalStateException.class);
        assertThat(Files.readAllBytes(target)).containsExactly(new byte[]{3,2,1});
    }
}

