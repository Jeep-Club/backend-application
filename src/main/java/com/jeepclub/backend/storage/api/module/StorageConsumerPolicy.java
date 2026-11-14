package com.jeepclub.backend.storage.api.module;

/** Public, sanitized authorization contract implemented by the consuming context. */
public interface StorageConsumerPolicy {
    enum Context { UNKNOWN, IDENTITY_PROFILE, VEHICLE_PHOTO, PUBLICATION_IMAGE, PUBLICATION_COMMENT, EVENT_IMAGE, TOOL_IMAGE, DEPENDENT_PHOTO, MEDICAL_IMAGE, IDENTIFICATION_PHOTO }
    enum SubjectType { UNKNOWN, USER, DEPENDENT }
    record UploadIdentity(Long ownerUserId, SubjectType subjectType, Long subjectId) {}
    record Asset(Long ownerUserId) {}
    record Reference(Context context, String consumerId, String role) {}

    Context context();
    boolean mayUpload(long actor, String consumerId);
    default UploadIdentity uploadIdentity(long actor, String consumerId) {
        return new UploadIdentity(null, SubjectType.UNKNOWN, null);
    }
    default boolean mayAssociate(long actor, Asset asset, String consumerId, String role) { return false; }
    default boolean mayDissociate(long actor, Reference reference) { return false; }
    boolean mayRead(long actor, Reference reference, boolean master);
}
