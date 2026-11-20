package com.jeepclub.backend.storage.core.repository;

public interface UploadAdmission {
    record Permit(String id,long actor) {}
    Permit reserve(long actor);
    record PlannedBlob(String sha256, long sizeBytes) {}
    void charge(Permit permit,long bytes);
    /** Commit the intended representations before any physical write. */
    void recordPlannedBlobs(Permit permit, java.util.List<PlannedBlob> blobs);
    /** Complete within the caller's catalog transaction; failure rolls back the catalog. */
    void finishInCatalogTransaction(Permit permit, long mediaId);
    /** Only when the caller has not invoked the physical storage port. Idempotent. */
    void abortBeforeStore(Permit permit);
    void finish(Permit permit);
    final class LimitExceeded extends RuntimeException {public LimitExceeded(){super("Upload allowance exceeded");}}
}
