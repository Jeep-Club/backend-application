package com.jeepclub.backend.publications.infra.persistence.adapter;

import com.jeepclub.backend.publications.core.domain.exception.PublicationAlreadyDeletedException;
import com.jeepclub.backend.publications.core.domain.model.Publication;
import com.jeepclub.backend.publications.core.repository.PublicationRepository;
import com.jeepclub.backend.publications.infra.persistence.entity.PublicationEntity;
import com.jeepclub.backend.publications.infra.persistence.jpa.*;
import com.jeepclub.backend.publications.infra.persistence.mapper.PublicationHistoryMapper;
import com.jeepclub.backend.publications.infra.persistence.mapper.PublicationMapper;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Repository;
import java.time.Instant;
import java.util.Optional;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.domain.Specification;
import com.jeepclub.backend.publications.core.domain.enums.NoticePriority;
import com.jeepclub.backend.publications.core.domain.enums.PublicationStatus;
import com.jeepclub.backend.publications.infra.persistence.entity.NoticeEntity;
import com.jeepclub.backend.publications.infra.persistence.entity.EventEntity;
import com.jeepclub.backend.publications.infra.persistence.entity.ServicePublicationEntity;

@Repository
@RequiredArgsConstructor
public class PublicationRepositoryAdapter implements PublicationRepository {
    @Override
    public Page<Publication> findPublished(String type, Instant from, Instant to, Pageable pageable) {
        return findPublished(type == null ? java.util.Set.of() : java.util.Set.of(type), null, null, from, to, pageable);
    }

    @Override
    public Page<Publication> findPublished(java.util.Set<String> types, Long authorUserId, String queryText, Instant from, Instant to, Pageable pageable) {
        Instant now = Instant.now(clock);
        Specification<PublicationEntity> filter = (root, query, cb) -> {
            var conditions = new java.util.ArrayList<jakarta.persistence.criteria.Predicate>();
            conditions.add(cb.equal(root.get("status"), PublicationStatus.PUBLISHED));
            if (from != null) conditions.add(cb.greaterThanOrEqualTo(root.get("publishedAt"), from));
            if (to != null) conditions.add(cb.lessThanOrEqualTo(root.get("publishedAt"), to));
            if (types != null && !types.isEmpty()) {
                var subtypes = types.stream().map(PublicationRepositoryAdapter::resolveSubtype).toList();
                if (subtypes.size() == 1) {
                    conditions.add(cb.equal(root.type(), subtypes.get(0)));
                } else {
                    conditions.add(root.type().in(subtypes));
                }
            }
            if (authorUserId != null) {
                conditions.add(cb.equal(root.get("authorUserId"), authorUserId));
            }
            String pattern = likePattern(queryText);
            if (pattern != null) {
                conditions.add(cb.or(
                        cb.like(cb.lower(root.get("title")), pattern, '!'),
                        cb.like(cb.lower(root.get("content")), pattern, '!')
                ));
            }
            var invalidNotice = query.subquery(Long.class);
            var notice = invalidNotice.from(NoticeEntity.class);
            invalidNotice.select(notice.get("id")).where(
                    cb.equal(notice.get("id"), root.get("id")),
                    cb.or(
                            cb.and(cb.isNotNull(notice.get("validFrom")), cb.greaterThan(notice.get("validFrom"), now)),
                            cb.and(cb.isNotNull(notice.get("validUntil")), cb.lessThan(notice.get("validUntil"), now))
                    )
            );
            conditions.add(cb.not(cb.exists(invalidNotice)));
            return cb.and(conditions.toArray(jakarta.persistence.criteria.Predicate[]::new));
        };
        return publications.findAll(filter, pageable).map(mapper::toDomain);
    }

    private static Class<? extends PublicationEntity> resolveSubtype(String type) {
        return switch (type) {
            case "NOTICE" -> NoticeEntity.class;
            case "EVENT" -> EventEntity.class;
            case "SERVICE" -> ServicePublicationEntity.class;
            default -> throw new IllegalArgumentException("Unsupported publication type.");
        };
    }
    private final PublicationJpaRepository publications;
    private final PublicationHistoryJpaRepository history;
    private final PublicationLikeJpaRepository likes;
    private final PublicationCommentJpaRepository comments;
    private final PublicationMapper mapper;
    private final PublicationHistoryMapper historyMapper;
    private final com.jeepclub.backend.publications.infra.persistence.mapper.CurrentEventMapper currentEventMapper;
    private final jakarta.persistence.EntityManager entityManager;
    private final java.time.Clock clock;
    @Override
    public org.springframework.data.domain.Page<com.jeepclub.backend.publications.core.domain.model.Event> findEvents(org.springframework.data.domain.Pageable pageable) {
        return publications.findEvents(pageable).map(e -> (com.jeepclub.backend.publications.core.domain.model.Event) mapper.toDomain(e));
    }

    @Override
    public Page<com.jeepclub.backend.publications.core.domain.model.Notice> findNotices(
            PublicationStatus status,
            NoticePriority priority,
            Boolean expired,
            Instant referenceNow,
            String query,
            Instant from,
            Instant to,
            Instant publishedFrom,
            Instant publishedTo,
            Pageable pageable
    ) {
        Instant effectiveNow = referenceNow != null ? referenceNow : Instant.now(clock);
        String pattern = likePattern(query);
        var cb = entityManager.getCriteriaBuilder();

        var countQuery = cb.createQuery(Long.class);
        var countRoot = countQuery.from(NoticeEntity.class);
        countQuery.select(cb.count(countRoot)).where(noticePredicates(
                cb, countRoot, status, priority, expired, effectiveNow, pattern, from, to, publishedFrom, publishedTo
        ));
        long total = entityManager.createQuery(countQuery).getSingleResult();
        if (total == 0 || pageable.getOffset() >= total) {
            return new org.springframework.data.domain.PageImpl<>(java.util.List.of(), pageable, total);
        }

        var dataQuery = cb.createQuery(NoticeEntity.class);
        var dataRoot = dataQuery.from(NoticeEntity.class);
        dataQuery.select(dataRoot).where(noticePredicates(
                cb, dataRoot, status, priority, expired, effectiveNow, pattern, from, to, publishedFrom, publishedTo
        ));
        var orders = new java.util.ArrayList<jakarta.persistence.criteria.Order>();
        for (org.springframework.data.domain.Sort.Order sortOrder : pageable.getSort()) {
            jakarta.persistence.criteria.Expression<?> expr;
            if ("priority".equals(sortOrder.getProperty())) {
                expr = cb.<String, Integer>selectCase(dataRoot.get("priority"))
                        .when(NoticePriority.LOW.name(), 1)
                        .when(NoticePriority.NORMAL.name(), 2)
                        .when(NoticePriority.HIGH.name(), 3)
                        .when(NoticePriority.URGENT.name(), 4)
                        .otherwise(0);
            } else {
                expr = dataRoot.get(sortOrder.getProperty());
            }
            orders.add(sortOrder.isAscending() ? cb.asc(expr) : cb.desc(expr));
        }
        if (!orders.isEmpty()) {
            dataQuery.orderBy(orders);
        }
        var items = entityManager.createQuery(dataQuery)
                .setFirstResult(Math.toIntExact(pageable.getOffset()))
                .setMaxResults(pageable.getPageSize())
                .getResultList()
                .stream()
                .map(e -> (com.jeepclub.backend.publications.core.domain.model.Notice) mapper.toDomain(e))
                .toList();
        return new org.springframework.data.domain.PageImpl<>(items, pageable, total);
    }

    private static jakarta.persistence.criteria.Predicate[] noticePredicates(
            jakarta.persistence.criteria.CriteriaBuilder cb,
            jakarta.persistence.criteria.Root<NoticeEntity> root,
            PublicationStatus status,
            NoticePriority priority,
            Boolean expired,
            Instant referenceNow,
            String pattern,
            Instant from,
            Instant to,
            Instant publishedFrom,
            Instant publishedTo
    ) {
        var predicates = new java.util.ArrayList<jakarta.persistence.criteria.Predicate>();
        if (status != null) {
            predicates.add(cb.equal(root.get("status"), status));
        }
        if (priority != null) {
            predicates.add(cb.equal(root.get("priority"), priority.name()));
        }
        if (expired != null) {
            if (expired) {
                predicates.add(cb.and(
                        cb.isNotNull(root.get("validUntil")),
                        cb.lessThan(root.get("validUntil"), referenceNow)
                ));
            } else {
                predicates.add(cb.or(
                        cb.isNull(root.get("validUntil")),
                        cb.greaterThanOrEqualTo(root.get("validUntil"), referenceNow)
                ));
            }
        }
        if (pattern != null) {
            predicates.add(cb.or(
                    cb.like(cb.lower(root.get("title")), pattern, '!'),
                    cb.like(cb.lower(root.get("content")), pattern, '!')
            ));
        }
        if (from != null) {
            predicates.add(cb.greaterThanOrEqualTo(root.get("createdAt"), from));
        }
        if (to != null) {
            predicates.add(cb.lessThanOrEqualTo(root.get("createdAt"), to));
        }
        if (publishedFrom != null) {
            predicates.add(cb.greaterThanOrEqualTo(root.get("publishedAt"), publishedFrom));
        }
        if (publishedTo != null) {
            predicates.add(cb.lessThanOrEqualTo(root.get("publishedAt"), publishedTo));
        }
        return predicates.toArray(jakarta.persistence.criteria.Predicate[]::new);
    }

    @Override
    public Page<com.jeepclub.backend.publications.core.domain.model.ServicePublication> findServices(
            PublicationStatus status,
            String query,
            Instant from,
            Instant to,
            Instant publishedFrom,
            Instant publishedTo,
            Pageable pageable
    ) {
        return publications.findServices(status, likePattern(query), from, to, publishedFrom, publishedTo, pageable)
                .map(e -> (com.jeepclub.backend.publications.core.domain.model.ServicePublication) mapper.toDomain(e));
    }

    @Override
    public java.util.List<com.jeepclub.backend.publications.core.domain.model.ServicePublication> findServicesByIds(java.util.Collection<Long> ids) {
        if (ids == null || ids.isEmpty()) {
            return java.util.List.of();
        }
        return publications.findServicesByIdIn(ids).stream()
                .map(e -> (com.jeepclub.backend.publications.core.domain.model.ServicePublication) mapper.toDomain(e))
                .toList();
    }

    private static String likePattern(String query) {
        if (query == null || query.isBlank()) {
            return null;
        }
        return "%" + query.trim().toLowerCase(java.util.Locale.ROOT)
                .replace("!", "!!")
                .replace("%", "!%")
                .replace("_", "!_") + "%";
    }

    @Override
    public Publication save(Publication publication) {
        PublicationEntity entity;
        if (publication.getId() == null) entity = mapper.newEntity(publication);
        else {
            entity = lockedEntity(publication.getId())
                    .orElseThrow(() -> new PublicationAlreadyDeletedException(publication.getId()));
            mapper.copy(publication, entity);
        }
        return mapper.toDomain(publications.saveAndFlush(entity));
    }

    @Override
    public Optional<Publication> findById(Long id) {
        return publications.findById(id).map(mapper::toDomain);
    }

    @Override
    public Optional<Publication> findByIdForUpdate(Long id) {
        return lockedEntity(id).map(mapper::toDomain);
    }
    @Override
    public Optional<com.jeepclub.backend.publications.core.domain.model.Event> findEventByIdForUpdate(Long id) {
        if(publications.lockRootId(id).isEmpty())return Optional.empty();
        return currentEvent(id);
    }

    @Override
    public void delete(Long id, Long deletedByUserId, Instant deletedAt) {
        if (deletedByUserId == null || deletedByUserId <= 0 || deletedAt == null) {
            throw new IllegalArgumentException("A valid deletion actor and instant are required.");
        }
        PublicationEntity entity = lockedEntity(id)
                .orElseThrow(() -> new PublicationAlreadyDeletedException(id));
        var snapshot=entity;
        if(entity instanceof EventEntity) {
            // Preserve the current Event/images even when the deleting transaction
            // cached a pre-lock polymorphic entity under MySQL REPEATABLE READ.
            snapshot=mapper.newEntity(currentEvent(id).orElseThrow());snapshot.setId(id);
        }
        history.saveAndFlush(historyMapper.snapshot(snapshot, deletedByUserId, deletedAt));
        comments.deleteAll(comments.findAllByPublication_Id(id));
        comments.flush();
        likes.deleteAll(likes.findAllByPublication_Id(id));
        likes.flush();
        publications.delete(entity);
        publications.flush();
    }

    private Optional<PublicationEntity> lockedEntity(Long id) {
        return publications.lockRootId(id).flatMap(publications::findById);
    }
    private Optional<com.jeepclub.backend.publications.core.domain.model.Event> currentEvent(Long id) {
        return publications.currentEvent(id).stream().findFirst().map(row -> currentEventMapper.toDomain(row,publications.currentImages(id)));
    }
}
