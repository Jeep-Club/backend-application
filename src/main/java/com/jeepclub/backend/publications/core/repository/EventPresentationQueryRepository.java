package com.jeepclub.backend.publications.core.repository;
import com.jeepclub.backend.publications.api.module.EventPresentationQuery;
import com.jeepclub.backend.publications.core.domain.enums.EventStatus;
public interface EventPresentationQueryRepository {
    java.util.List<EventPresentationQuery.Details> findByIds(java.util.Collection<Long> ids);
    record HistoricalEvent(Long id, String title, java.time.Instant startsAt, java.time.Instant endsAt,
        EventStatus status, java.time.Instant deletedAt) {
        public EventStatus effectiveStatusAtDeletion() {
            if (status == EventStatus.CANCELLED || status == EventStatus.FINISHED) return status;
            if (endsAt != null && !deletedAt.isBefore(endsAt)) return EventStatus.FINISHED;
            return deletedAt.isBefore(startsAt) ? EventStatus.OPEN : EventStatus.IN_PROGRESS;
        }
    }
    java.util.Optional<HistoricalEvent> findHistoricalById(Long id);
}
