package com.jeepclub.backend.publications.core.application.service;

import com.jeepclub.backend.iam.identity.api.module.UserDetails;
import com.jeepclub.backend.iam.identity.api.module.UserQuery;
import com.jeepclub.backend.iam.identity.api.module.UserStatus;
import com.jeepclub.backend.publications.core.domain.enums.PublicationStatus;
import com.jeepclub.backend.publications.core.domain.exception.PublicationNotFoundException;
import com.jeepclub.backend.publications.core.domain.model.Event;
import com.jeepclub.backend.publications.core.domain.model.Notice;
import com.jeepclub.backend.publications.core.domain.model.Publication;
import com.jeepclub.backend.publications.core.repository.EventParticipantRepository;
import com.jeepclub.backend.publications.core.repository.PublicationRepository;
import java.time.Clock;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.stream.Collectors;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class PublicationFeedService {
    public static final int MAX_PARTICIPANTS_PREVIEW = 5;
    private static final int PREVIEW_CANDIDATE_LIMIT = 25;
    private static final int PARTICIPANT_BACKFILL_WINDOW_SIZE = 25;
    private static final int IDENTITY_BATCH_SIZE = 500;
    private static final int MAX_QUERY_LENGTH = 200;
    private static final int MAX_PAGE_SIZE = 50;
    private static final Set<String> SUPPORTED_TYPES = Set.of("NOTICE", "EVENT", "SERVICE");
    private static final Set<String> ALLOWED_SORT_FIELDS = Set.of(
            "publishedAt", "createdAt", "updatedAt", "title", "id"
    );

    private final PublicationRepository publications;
    private final PublicationSocialSummaryQuery social;
    private final EventParticipantRepository participants;
    private final UserQuery users;
    private final Clock clock;

    @Autowired
    public PublicationFeedService(
            PublicationRepository publications,
            PublicationSocialSummaryQuery social,
            EventParticipantRepository participants,
            UserQuery users,
            Clock clock
    ) {
        this.publications = publications;
        this.social = social;
        this.participants = participants;
        this.users = users;
        this.clock = clock;
    }

    public PublicationFeedService(
            PublicationRepository publications,
            PublicationSocialSummaryQuery social,
            EventParticipantRepository participants,
            Clock clock
    ) {
        this(publications, social, participants, null, clock);
    }

    public PublicationFeedService(
            PublicationRepository publications,
            PublicationSocialSummaryQuery social,
            EventParticipantRepository participants
    ) {
        this(publications, social, participants, null, Clock.systemUTC());
    }

    public record AuthorInfo(Long userId, String name, String profilePhotoStorageKey) {}
    public record ParticipantInfo(Long userId, String name, String profilePhotoStorageKey) {}

    public record Item(
            Publication publication,
            PublicationSocialSummaryQuery.Summary summary,
            long registrationCount,
            AuthorInfo author,
            List<ParticipantInfo> participantsPreview
    ) {
        public Item {
            participantsPreview = participantsPreview == null ? List.of() : List.copyOf(participantsPreview);
        }

        public Item(Publication publication, PublicationSocialSummaryQuery.Summary summary, long registrationCount) {
            this(publication, summary, registrationCount, null, List.of());
        }
    }

    @Transactional(readOnly = true)
    public Page<Item> feed(String type, Instant from, Instant to, Pageable pageable, Long memberUserId) {
        return feed(type, null, null, null, from, to, pageable, memberUserId);
    }

    @Transactional(readOnly = true)
    public Page<Item> feed(
            String type,
            Collection<String> types,
            Long authorUserId,
            String query,
            Instant from,
            Instant to,
            Pageable pageable,
            Long memberUserId
    ) {
        if (from != null && to != null && from.isAfter(to)) {
            throw new IllegalArgumentException("Published period is reversed.");
        }
        Set<String> effectiveTypes = resolveTypes(type, types);
        if (authorUserId != null && authorUserId <= 0) {
            throw new IllegalArgumentException("authorUserId must be positive.");
        }
        String normalizedQuery = query == null || query.isBlank() ? null : query.trim();
        if (normalizedQuery != null && normalizedQuery.length() > MAX_QUERY_LENGTH) {
            throw new IllegalArgumentException("Feed search query exceeds maximum length.");
        }
        Pageable stable = validateAndStabilize(pageable);

        Page<Publication> page;
        if (effectiveTypes.size() <= 1 && authorUserId == null && normalizedQuery == null) {
            String singleType = effectiveTypes.isEmpty() ? null : effectiveTypes.iterator().next();
            page = publications.findPublished(singleType, from, to, stable);
            if (page == null) {
                page = publications.findPublished(effectiveTypes, null, null, from, to, stable);
            }
        } else {
            page = publications.findPublished(effectiveTypes, authorUserId, normalizedQuery, from, to, stable);
        }

        if (page == null) {
            return Page.empty(stable);
        }
        if (page.isEmpty()) {
            return new org.springframework.data.domain.PageImpl<>(List.of(), stable, page.getTotalElements());
        }

        List<Long> publicationIds = page.getContent().stream().map(Publication::getId).toList();
        Map<Long, PublicationSocialSummaryQuery.Summary> summaries = social.get(publicationIds, memberUserId);
        List<Long> eventIds = page.getContent().stream()
                .filter(Event.class::isInstance)
                .map(Publication::getId)
                .toList();
        Map<Long, EventParticipantRepository.ConfirmedSummary> eventSummaries = loadEventSummaries(eventIds);

        Set<Long> userIdsToResolve = new LinkedHashSet<>();
        for (Publication publication : page.getContent()) {
            if (publication.getAuthorUserId() != null && publication.getAuthorUserId() > 0) {
                userIdsToResolve.add(publication.getAuthorUserId());
            }
        }
        for (EventParticipantRepository.ConfirmedSummary eventSummary : eventSummaries.values()) {
            for (Long previewUserId : eventSummary.previewUserIds()) {
                if (previewUserId != null && previewUserId > 0) {
                    userIdsToResolve.add(previewUserId);
                }
            }
        }
        Map<Long, UserDetails> activeUsers = new HashMap<>(resolveActiveUsers(userIdsToResolve));
        Set<Long> resolvedUserIds = new LinkedHashSet<>(userIdsToResolve);
        Map<Long, List<ParticipantInfo>> resolvedPreviews = resolveParticipantPreviews(eventSummaries, activeUsers, resolvedUserIds);

        return page.map(publication -> toItem(
                publication,
                summaries.get(publication.getId()),
                eventSummaries.get(publication.getId()),
                activeUsers,
                resolvedPreviews.getOrDefault(publication.getId(), List.of())
        ));
    }

    @Transactional(readOnly = true)
    public Item detail(Long id, Long memberUserId) {
        return detail(id, memberUserId, Instant.now(clock));
    }

    @Transactional(readOnly = true)
    public Item detail(Long id, Long memberUserId, Instant now) {
        var publication = publications.findById(id).orElseThrow(() -> new PublicationNotFoundException(id));
        if (publication.getStatus() != PublicationStatus.PUBLISHED) throw new PublicationNotFoundException(id);
        if (publication instanceof Notice notice && !notice.isTemporallyVisibleAt(now)) {
            throw new PublicationNotFoundException(id);
        }
        PublicationSocialSummaryQuery.Summary summary = social.get(List.of(id), memberUserId).get(id);
        EventParticipantRepository.ConfirmedSummary eventSummary = publication instanceof Event
                ? loadEventSummaries(List.of(id)).get(id)
                : null;

        Set<Long> userIdsToResolve = new LinkedHashSet<>();
        if (publication.getAuthorUserId() != null && publication.getAuthorUserId() > 0) {
            userIdsToResolve.add(publication.getAuthorUserId());
        }
        if (eventSummary != null) {
            for (Long previewUserId : eventSummary.previewUserIds()) {
                if (previewUserId != null && previewUserId > 0) {
                    userIdsToResolve.add(previewUserId);
                }
            }
        }
        Map<Long, UserDetails> activeUsers = new HashMap<>(resolveActiveUsers(userIdsToResolve));
        Set<Long> resolvedUserIds = new LinkedHashSet<>(userIdsToResolve);
        Map<Long, EventParticipantRepository.ConfirmedSummary> singleEventMap =
                eventSummary == null ? Map.of() : Map.of(id, eventSummary);
        Map<Long, List<ParticipantInfo>> resolvedPreviews = resolveParticipantPreviews(singleEventMap, activeUsers, resolvedUserIds);
        return toItem(publication, summary, eventSummary, activeUsers, resolvedPreviews.getOrDefault(id, List.of()));
    }

    private Map<Long, EventParticipantRepository.ConfirmedSummary> loadEventSummaries(List<Long> eventIds) {
        if (eventIds.isEmpty()) {
            return Map.of();
        }
        Map<Long, EventParticipantRepository.ConfirmedSummary> summaries =
                participants.confirmedSummaries(eventIds, PREVIEW_CANDIDATE_LIMIT);
        if (summaries != null && !summaries.isEmpty()) {
            return summaries;
        }
        Map<Long, Long> fallbackCounts = participants.confirmedCounts(eventIds);
        if (fallbackCounts == null || fallbackCounts.isEmpty()) {
            return Map.of();
        }
        return fallbackCounts.entrySet().stream().collect(Collectors.toMap(
                Map.Entry::getKey,
                e -> new EventParticipantRepository.ConfirmedSummary(e.getValue(), List.of())
        ));
    }

    private Map<Long, UserDetails> resolveActiveUsers(Set<Long> userIds) {
        if (users == null || userIds.isEmpty()) {
            return Map.of();
        }
        List<Long> ordered = new ArrayList<>(userIds);
        Map<Long, UserDetails> active = new HashMap<>();
        for (int offset = 0; offset < ordered.size(); offset += IDENTITY_BATCH_SIZE) {
            List<Long> batch = ordered.subList(offset, Math.min(offset + IDENTITY_BATCH_SIZE, ordered.size()));
            List<UserDetails> resolved = users.findByIds(batch);
            if (resolved == null) {
                continue;
            }
            for (UserDetails user : resolved) {
                if (user != null
                        && user.id() != null
                        && user.administrativelyActive()
                        && user.status() == UserStatus.ACTIVE
                        && user.archivedAt() == null
                        && user.name() != null
                        && !user.name().isBlank()) {
                    active.put(user.id(), user);
                }
            }
        }
        return active;
    }

    private Map<Long, List<ParticipantInfo>> resolveParticipantPreviews(
            Map<Long, EventParticipantRepository.ConfirmedSummary> eventSummaries,
            Map<Long, UserDetails> activeUsers,
            Set<Long> resolvedUserIds
    ) {
        if (eventSummaries.isEmpty()) {
            return Map.of();
        }
        Map<Long, List<ParticipantInfo>> itemsByEvent = new HashMap<>();
        Map<Long, Set<Long>> seenByEvent = new HashMap<>();
        Map<Long, Integer> fetchedRowsByEvent = new HashMap<>();
        Set<Long> exhaustedEvents = new LinkedHashSet<>();

        for (Map.Entry<Long, EventParticipantRepository.ConfirmedSummary> entry : eventSummaries.entrySet()) {
            Long eventId = entry.getKey();
            EventParticipantRepository.ConfirmedSummary eventSummary = entry.getValue();
            List<ParticipantInfo> items = new ArrayList<>(MAX_PARTICIPANTS_PREVIEW);
            Set<Long> seen = new LinkedHashSet<>();
            itemsByEvent.put(eventId, items);
            seenByEvent.put(eventId, seen);
            if (eventSummary == null || eventSummary.previewUserIds().isEmpty()) {
                fetchedRowsByEvent.put(eventId, 0);
                exhaustedEvents.add(eventId);
                continue;
            }
            int initialCount = eventSummary.previewUserIds().size();
            fetchedRowsByEvent.put(eventId, initialCount);
            collectActiveParticipants(eventSummary.previewUserIds(), activeUsers, seen, items);
            if (items.size() >= MAX_PARTICIPANTS_PREVIEW || initialCount >= eventSummary.count()) {
                exhaustedEvents.add(eventId);
            }
        }

        if (users != null) {
            int afterRowNumber = PREVIEW_CANDIDATE_LIMIT;
            while (true) {
                List<Long> eventsNeedingMore = new ArrayList<>();
                for (Map.Entry<Long, EventParticipantRepository.ConfirmedSummary> entry : eventSummaries.entrySet()) {
                    Long eventId = entry.getKey();
                    EventParticipantRepository.ConfirmedSummary summary = entry.getValue();
                    if (!exhaustedEvents.contains(eventId)
                            && summary != null
                            && itemsByEvent.get(eventId).size() < MAX_PARTICIPANTS_PREVIEW
                            && fetchedRowsByEvent.getOrDefault(eventId, 0) < summary.count()) {
                        eventsNeedingMore.add(eventId);
                    }
                }
                if (eventsNeedingMore.isEmpty()) {
                    break;
                }
                eventsNeedingMore.sort(Long::compareTo);
                Map<Long, List<Long>> windowByEvent = participants.confirmedUserIdWindow(
                        eventsNeedingMore,
                        afterRowNumber,
                        PARTICIPANT_BACKFILL_WINDOW_SIZE
                );
                Set<Long> unseenIds = new LinkedHashSet<>();
                for (Long eventId : eventsNeedingMore) {
                    List<Long> candidates = windowByEvent == null
                            ? List.of()
                            : windowByEvent.getOrDefault(eventId, List.of());
                    for (Long userId : candidates) {
                        if (userId != null && userId > 0 && !resolvedUserIds.contains(userId)) {
                            unseenIds.add(userId);
                        }
                    }
                }
                if (!unseenIds.isEmpty()) {
                    activeUsers.putAll(resolveActiveUsers(unseenIds));
                    resolvedUserIds.addAll(unseenIds);
                }
                for (Long eventId : eventsNeedingMore) {
                    List<Long> candidates = windowByEvent == null
                            ? List.of()
                            : windowByEvent.getOrDefault(eventId, List.of());
                    if (candidates.isEmpty()) {
                        exhaustedEvents.add(eventId);
                        continue;
                    }
                    int updatedFetched = fetchedRowsByEvent.getOrDefault(eventId, 0) + candidates.size();
                    fetchedRowsByEvent.put(eventId, updatedFetched);
                    collectActiveParticipants(candidates, activeUsers, seenByEvent.get(eventId), itemsByEvent.get(eventId));
                    if (candidates.size() < PARTICIPANT_BACKFILL_WINDOW_SIZE
                            || itemsByEvent.get(eventId).size() >= MAX_PARTICIPANTS_PREVIEW) {
                        exhaustedEvents.add(eventId);
                    }
                }
                afterRowNumber += PARTICIPANT_BACKFILL_WINDOW_SIZE;
            }
        }

        Map<Long, List<ParticipantInfo>> result = new HashMap<>();
        for (Map.Entry<Long, List<ParticipantInfo>> entry : itemsByEvent.entrySet()) {
            result.put(entry.getKey(), List.copyOf(entry.getValue()));
        }
        return result;
    }

    private static void collectActiveParticipants(
            Collection<Long> candidateUserIds,
            Map<Long, UserDetails> activeUsers,
            Set<Long> seen,
            List<ParticipantInfo> items
    ) {
        for (Long userId : candidateUserIds) {
            if (items.size() == MAX_PARTICIPANTS_PREVIEW) {
                return;
            }
            if (userId == null || !seen.add(userId)) {
                continue;
            }
            UserDetails participant = activeUsers.get(userId);
            if (participant != null) {
                items.add(new ParticipantInfo(
                        participant.id(),
                        participant.name().trim(),
                        participant.profilePhotoStorageKey()
                ));
            }
        }
    }

    private static Item toItem(
            Publication publication,
            PublicationSocialSummaryQuery.Summary summary,
            EventParticipantRepository.ConfirmedSummary eventSummary,
            Map<Long, UserDetails> activeUsers,
            List<ParticipantInfo> participantsPreview
    ) {
        UserDetails authorUser = activeUsers.get(publication.getAuthorUserId());
        AuthorInfo author = authorUser == null
                ? null
                : new AuthorInfo(authorUser.id(), authorUser.name().trim(), authorUser.profilePhotoStorageKey());

        long registrationCount = eventSummary == null ? 0L : eventSummary.count();
        List<ParticipantInfo> preview = publication instanceof Event ? participantsPreview : List.of();
        return new Item(publication, summary, registrationCount, author, preview);
    }

    private static Set<String> resolveTypes(String type, Collection<String> types) {
        boolean hasSingle = type != null && !type.isBlank();
        boolean hasMulti = types != null && !types.isEmpty();
        if (hasSingle && hasMulti) {
            throw new IllegalArgumentException("Specify either type or types, not both.");
        }
        if (type != null && type.isBlank()) {
            throw new IllegalArgumentException("Unsupported publication type.");
        }
        Set<String> resolved = new LinkedHashSet<>();
        if (hasSingle) {
            String normalized = type.trim();
            if (!SUPPORTED_TYPES.contains(normalized)) {
                throw new IllegalArgumentException("Unsupported publication type.");
            }
            resolved.add(normalized);
        } else if (hasMulti) {
            for (String item : types) {
                if (item == null || item.isBlank()) {
                    throw new IllegalArgumentException("Unsupported publication type.");
                }
                for (String token : item.split(",")) {
                    String normalized = token.trim();
                    if (normalized.isEmpty() || !SUPPORTED_TYPES.contains(normalized)) {
                        throw new IllegalArgumentException("Unsupported publication type.");
                    }
                    resolved.add(normalized);
                }
            }
        }
        return Set.copyOf(resolved);
    }

    private static Pageable validateAndStabilize(Pageable pageable) {
        if (pageable == null || pageable.isUnpaged()
                || pageable.getPageNumber() < 0
                || pageable.getPageSize() < 1
                || pageable.getPageSize() > MAX_PAGE_SIZE
                || pageable.getOffset() > Integer.MAX_VALUE) {
            throw new IllegalArgumentException("Invalid feed pagination parameters.");
        }
        Sort sort = pageable.getSort();
        if (sort.isUnsorted()) {
            sort = Sort.by(Sort.Order.desc("publishedAt"), Sort.Order.desc("id"));
        } else {
            for (Sort.Order order : sort) {
                if (!ALLOWED_SORT_FIELDS.contains(order.getProperty())) {
                    throw new IllegalArgumentException("Unsupported feed sort field: " + order.getProperty());
                }
            }
            if (sort.getOrderFor("id") == null) {
                Sort.Direction tieDirection = sort.iterator().next().getDirection();
                sort = sort.and(Sort.by(tieDirection, "id"));
            }
        }
        return PageRequest.of(pageable.getPageNumber(), pageable.getPageSize(), sort);
    }
}
