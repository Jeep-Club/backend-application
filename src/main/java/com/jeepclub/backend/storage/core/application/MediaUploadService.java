package com.jeepclub.backend.storage.core.application;

import com.jeepclub.backend.storage.api.module.StorageConsumerPolicy;
import com.jeepclub.backend.storage.core.repository.*;
import com.jeepclub.backend.storage.core.domain.model.*;
import com.jeepclub.backend.storage.core.domain.enums.*;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.security.access.AccessDeniedException;
import java.time.Instant;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;

@Service
public class MediaUploadService {
    private static final long PROCESSING_BUDGET_NANOS = Duration.ofSeconds(10).toNanos();

    public static final class ProcessingDeadlineExceeded extends RuntimeException {
        private ProcessingDeadlineExceeded() {
            super("Upload processing deadline exceeded");
        }
    }

    /** An upload has custody but no operational reference until a consumer attaches it. */
    public record Uploaded(long mediaId,Long referenceId) {}
    private final UploadAdmission admission;private final MediaCatalogRepository catalog; private final BlobCatalog blobs;
    private final MediaImageProcessor images; private final MediaGovernance governance;
    private final List<StorageConsumerPolicy> policies; private final TransactionTemplate transaction;private final io.micrometer.core.instrument.MeterRegistry metrics;
    // Bound admitted uploads from payload buffering through physical writes and completion.
    private final java.util.concurrent.Semaphore inFlight = new java.util.concurrent.Semaphore(2, true);
    public MediaUploadService(MediaCatalogRepository catalog,BlobCatalog blobs,MediaImageProcessor images,MediaGovernance governance,
        List<StorageConsumerPolicy> policies,PlatformTransactionManager manager,io.micrometer.core.instrument.MeterRegistry metrics,UploadAdmission admission) {
        this.admission=admission;this.metrics=metrics;this.catalog=catalog;this.blobs=blobs;this.images=images;this.governance=governance;this.policies=List.copyOf(policies);transaction=new TransactionTemplate(manager);
        transaction.setPropagationBehavior(org.springframework.transaction.TransactionDefinition.PROPAGATION_REQUIRES_NEW);
        // The quota refresh following a locking read must see the latest committed counters on MySQL.
        transaction.setIsolationLevel(org.springframework.transaction.TransactionDefinition.ISOLATION_READ_COMMITTED);
        // Bounds supported JDBC statements in catalog completion, not codec, filesystem or commit.
        transaction.setTimeout(5);
    }
    public Uploaded upload(long actor,MediaContext context,String consumerId,byte[] bytes) {
        return upload(actor,context,consumerId,() -> bytes);
    }
    public Uploaded upload(long actor,MediaContext context,String consumerId,java.util.function.Supplier<byte[]> content) {
        return upload(actor,context,consumerId,content,MediaImageProcessor.Options.defaults());
    }
    public Uploaded upload(long actor,MediaContext context,String consumerId,java.util.function.Supplier<byte[]> content,MediaImageProcessor.Options options) {
        long started=System.nanoTime();try {
        if(actor<=0) throw new AccessDeniedException("Media unavailable");
        var policy=policies.stream().filter(p -> StoragePolicyViews.matches(p,context) && p.mayUpload(actor,consumerId)).findFirst()
            .orElseThrow(() -> new AccessDeniedException("Media unavailable"));
        var identity=policy.uploadIdentity(actor,consumerId);
        if (!inFlight.tryAcquire()) throw new IllegalStateException("Upload capacity exceeded");
        long admittedAt = System.nanoTime();
        try {
        var permit=admission.reserve(actor);
        boolean storeStarted=false;
        try {
        requireTimeRemaining(admittedAt);
        byte[] input = content.get();
        requireTimeRemaining(admittedAt);
        var processed=images.process(input,context,options);
        requireTimeRemaining(admittedAt);
        var planned=processed.images().stream().map(i -> {
            requireTimeRemaining(admittedAt);
            byte[] representation=i.bytes();
            try {
                String digest=java.util.HexFormat.of().formatHex(java.security.MessageDigest.getInstance("SHA-256").digest(representation));
                return new UploadAdmission.PlannedBlob(digest,representation.length);
            } catch (java.security.NoSuchAlgorithmException impossible) { throw new IllegalStateException(impossible); }
        }).toList();
        requireTimeRemaining(admittedAt);
        admission.charge(permit, planned.stream().mapToLong(UploadAdmission.PlannedBlob::sizeBytes).sum());
        requireTimeRemaining(admittedAt);
        admission.recordPlannedBlobs(permit,planned);
        var stored = new ArrayList<BlobCatalog.Blob>(processed.images().size());
        for (var image : processed.images()) {
            requireTimeRemaining(admittedAt);
            byte[] representation = image.bytes();
            requireTimeRemaining(admittedAt);
            storeStarted = true; // The provider may write even if it throws.
            stored.add(blobs.store(representation));
        }
        requireTimeRemaining(admittedAt);
        return transaction.execute(status -> {
            requireTimeRemaining(admittedAt);
            Instant now=Instant.now(); var audit=new MediaAuditContext(MediaActorKind.USER,actor,now,null);
            var asset=catalog.registerAsset(new MediaAsset(null,MediaOrigin.UPLOAD,context,consumerId,actor,identity.ownerUserId(),MediaSubjectType.valueOf(identity.subjectType().name()),identity.subjectId(),
                "PHOTO",MediaClassification.CLASSIFIED,MediaPhysicalState.PRESENT,now,now,null),audit);
            for(int i=0;i<processed.images().size();i++) {
                requireTimeRemaining(admittedAt);
                var image=processed.images().get(i);var blob=stored.get(i);
                var variant=catalog.registerVariant(new MediaVariant(null,asset.mediaId(),image.code(),image.code().equals("MASTER") ? MediaVariantRole.MASTER : MediaVariantRole.DERIVED,
                    "LOCAL","storage-blobs",blob.storageKey(),image.mimeType(),blob.sizeBytes(),image.width(),image.height(),"SHA-256",blob.sha256(),image.code(),image.code().equals("AVATAR_SMALL") ? "V2" : "V1",null,now),audit);
                requireTimeRemaining(admittedAt);
                blobs.bind(variant.variantId(),blob);
            }
            requireTimeRemaining(admittedAt);
            governance.initialize(asset.mediaId(),processed.receivedSha256(),java.util.Set.of(MediaContext.DEPENDENT_PHOTO,MediaContext.MEDICAL_IMAGE,MediaContext.IDENTIFICATION_PHOTO).contains(context),audit);
            requireTimeRemaining(admittedAt);
            admission.finishInCatalogTransaction(permit,asset.mediaId());
            requireTimeRemaining(admittedAt);
            return new Uploaded(asset.mediaId(),null);
        });
        } catch (RuntimeException | Error failure) {
            try { if(storeStarted) admission.finish(permit); else admission.abortBeforeStore(permit); }
            catch (RuntimeException cleanup) { failure.addSuppressed(cleanup); }
            throw failure;
        }
        } finally {inFlight.release();}
        } finally { metrics.timer("storage.upload.duration").record(System.nanoTime()-started,java.util.concurrent.TimeUnit.NANOSECONDS); }
    }

    private static void requireTimeRemaining(long admittedAt) {
        if (System.nanoTime() - admittedAt >= PROCESSING_BUDGET_NANOS) {
            throw new ProcessingDeadlineExceeded();
        }
    }
}
