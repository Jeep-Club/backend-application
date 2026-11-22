package com.jeepclub.backend.publications.core.repository;

import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;

public interface EventParticipantRepository {
    int DEFAULT_PREVIEW_CANDIDATE_LIMIT = 10;

    record ConfirmedSummary(long count, List<Long> previewUserIds) {
        public ConfirmedSummary {
            previewUserIds = previewUserIds == null ? List.of() : List.copyOf(previewUserIds);
        }
    }

    Page<Long> confirmedUserIds(Long eventId, Pageable pageable);
    Map<Long, Long> confirmedCounts(Collection<Long> eventIds);

    default Map<Long, ConfirmedSummary> confirmedSummaries(Collection<Long> eventIds, int previewCandidateLimit) {
        return confirmedCounts(eventIds).entrySet().stream()
                .collect(Collectors.toMap(
                        Map.Entry::getKey,
                        e -> new ConfirmedSummary(e.getValue(), List.of())
                ));
    }
}
