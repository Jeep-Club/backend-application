package com.jeepclub.backend.publications.core.domain.model;

import com.jeepclub.backend.publications.core.domain.enums.NoticePriority;
import com.jeepclub.backend.publications.core.domain.enums.PublicationStatus;
import java.time.Instant;
import java.util.List;
import java.util.Objects;

public final class Notice extends Publication {
    private NoticePriority priority;
    private Instant validFrom;
    private Instant validUntil;

    private Notice(Long id, Long authorUserId, String title, String content, PublicationStatus status,
                   Instant createdAt, Instant updatedAt, Instant publishedAt, Instant archivedAt,
                   List<PublicationImage> images, NoticePriority priority, Instant validFrom, Instant validUntil) {
        super(id, authorUserId, title, content, status, createdAt, updatedAt, publishedAt, archivedAt, images);
        validateValidityWindow(validFrom, validUntil);
        this.priority = priority == null ? NoticePriority.NORMAL : priority;
        this.validFrom = validFrom;
        this.validUntil = validUntil;
    }

    public static Notice create(Long authorUserId, String title, String content, List<PublicationImage> images, Instant now) {
        return create(authorUserId, title, content, images, NoticePriority.NORMAL, null, null, now);
    }

    public static Notice create(Long authorUserId, String title, String content, List<PublicationImage> images,
                                NoticePriority priority, Instant validFrom, Instant validUntil, Instant now) {
        return new Notice(null, authorUserId, title, content, PublicationStatus.DRAFT, now, now, null, null,
                images, priority == null ? NoticePriority.NORMAL : priority, validFrom, validUntil);
    }

    public static Notice reconstitute(Long id, Long authorUserId, String title, String content, PublicationStatus status,
                                     Instant createdAt, Instant updatedAt, Instant publishedAt, Instant archivedAt,
                                     List<PublicationImage> images) {
        return reconstitute(id, authorUserId, title, content, status, createdAt, updatedAt, publishedAt, archivedAt,
                images, NoticePriority.NORMAL, null, null);
    }

    public static Notice reconstitute(Long id, Long authorUserId, String title, String content, PublicationStatus status,
                                     Instant createdAt, Instant updatedAt, Instant publishedAt, Instant archivedAt,
                                     List<PublicationImage> images, NoticePriority priority,
                                     Instant validFrom, Instant validUntil) {
        positive(id, "id");
        return new Notice(id, authorUserId, title, content, status, createdAt, updatedAt, publishedAt, archivedAt,
                images, priority, validFrom, validUntil);
    }

    public void updatePriorityAndValidity(NoticePriority priority, Instant validFrom, Instant validUntil, Instant now) {
        touchUpdatedAt(now);
        if (getStatus() == PublicationStatus.ARCHIVED) {
            throw new IllegalStateException("Archived publication cannot be edited.");
        }
        Objects.requireNonNull(priority, "priority is required.");
        validateValidityWindow(validFrom, validUntil);
        this.priority = priority;
        this.validFrom = validFrom;
        this.validUntil = validUntil;
    }

    public boolean isTemporallyVisibleAt(Instant now) {
        Objects.requireNonNull(now, "now");
        return (validFrom == null || !now.isBefore(validFrom))
                && (validUntil == null || !now.isAfter(validUntil));
    }

    public boolean isExpiredAt(Instant now) {
        Objects.requireNonNull(now, "now");
        return validUntil != null && now.isAfter(validUntil);
    }

    private static void validateValidityWindow(Instant validFrom, Instant validUntil) {
        if (validFrom != null && validUntil != null && validFrom.isAfter(validUntil)) {
            throw new IllegalArgumentException("Notice validFrom cannot be after validUntil.");
        }
    }

    public NoticePriority getPriority() { return priority; }
    public Instant getValidFrom() { return validFrom; }
    public Instant getValidUntil() { return validUntil; }
}
