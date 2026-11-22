package com.jeepclub.backend.publications.infra.persistence.query;

import com.jeepclub.backend.publications.core.repository.EventParticipantRepository;
import jakarta.persistence.EntityManager;
import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Repository;

@Repository @RequiredArgsConstructor
public class EventParticipantJpaQuery implements EventParticipantRepository {
    private final EntityManager em;
    public Page<Long> confirmedUserIds(Long eventId, Pageable pageable) {
        long total = em.createQuery("select count(r) from EventRegistrationEntity r where r.eventId = :id and r.status = 'CONFIRMED'", Long.class)
                .setParameter("id", eventId).getSingleResult();
        if (pageable.getOffset() >= total) return new PageImpl<>(java.util.List.of(), pageable, total);
        var users = em.createQuery("select r.userId from EventRegistrationEntity r where r.eventId = :id and r.status = 'CONFIRMED' order by r.id", Long.class)
                .setParameter("id", eventId).setFirstResult(Math.toIntExact(pageable.getOffset()))
                .setMaxResults(pageable.getPageSize()).getResultList();
        return new PageImpl<>(users, pageable, total);
    }
    public Map<Long, Long> confirmedCounts(Collection<Long> eventIds) {
        if (eventIds.isEmpty()) return Map.of();
        return em.createQuery("select r.eventId, count(r) from EventRegistrationEntity r where r.eventId in :ids and r.status = 'CONFIRMED' group by r.eventId", Object[].class)
                .setParameter("ids", eventIds).getResultList().stream()
                .collect(Collectors.toMap(row -> (Long) row[0], row -> (Long) row[1]));
    }

    @Override
    @SuppressWarnings("unchecked")
    public Map<Long, ConfirmedSummary> confirmedSummaries(Collection<Long> eventIds, int previewCandidateLimit) {
        if (eventIds == null || eventIds.isEmpty()) return Map.of();
        int effectiveLimit = Math.max(1, previewCandidateLimit);
        List<Object[]> rows = em.createNativeQuery("""
                select c.event_id, c.total, p.user_id
                from (
                    select r.event_id, count(r.id) as total
                    from event_registrations r
                    where r.event_id in (:ids) and r.status = 'CONFIRMED'
                    group by r.event_id
                ) c
                left join (
                    select r.event_id, r.user_id, r.id,
                           row_number() over (partition by r.event_id order by r.id asc) as rn
                    from event_registrations r
                    where r.event_id in (:ids) and r.status = 'CONFIRMED'
                ) p on p.event_id = c.event_id and p.rn <= :limit
                order by c.event_id asc, p.id asc
                """)
                .setParameter("ids", eventIds)
                .setParameter("limit", effectiveLimit)
                .getResultList();
        Map<Long, Long> counts = new LinkedHashMap<>();
        Map<Long, List<Long>> previews = new LinkedHashMap<>();
        for (Long eventId : eventIds) {
            counts.put(eventId, 0L);
            previews.put(eventId, new ArrayList<>());
        }
        for (Object[] row : rows) {
            Long eventId = ((Number) row[0]).longValue();
            Long total = ((Number) row[1]).longValue();
            counts.put(eventId, total);
            if (row[2] != null) {
                Long userId = ((Number) row[2]).longValue();
                List<Long> list = previews.computeIfAbsent(eventId, ignored -> new ArrayList<>());
                if (!list.contains(userId)) {
                    list.add(userId);
                }
            }
        }
        Map<Long, ConfirmedSummary> result = new LinkedHashMap<>();
        for (Long eventId : eventIds) {
            result.put(eventId, new ConfirmedSummary(counts.getOrDefault(eventId, 0L), previews.getOrDefault(eventId, List.of())));
        }
        return Map.copyOf(result);
    }
}
