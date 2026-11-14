package com.jeepclub.backend.storage.core.repository;

public interface UploadAdmission {
    record Permit(String id,long actor) {}
    Permit reserve(long actor);
    void charge(Permit permit,long bytes);
    /** Complete within the caller's catalog transaction; failure rolls back the catalog. */
    void finishInCatalogTransaction(Permit permit);
    void finish(Permit permit);
    final class LimitExceeded extends RuntimeException {public LimitExceeded(){super("Upload allowance exceeded");}}
}
