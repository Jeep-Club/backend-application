package com.jeepclub.backend.publications.core.application.service;

import com.jeepclub.backend.platform.storage.image.ImageMediaService;
import com.jeepclub.backend.publications.core.application.exception.InvalidNoticeStateException;
import com.jeepclub.backend.publications.core.application.exception.NoticeNotFoundException;
import com.jeepclub.backend.publications.core.domain.enums.PublicationStatus;
import com.jeepclub.backend.publications.core.domain.exception.PublicationAlreadyDeletedException;
import com.jeepclub.backend.publications.core.domain.model.Notice;
import com.jeepclub.backend.publications.core.domain.model.Publication;
import com.jeepclub.backend.publications.core.domain.model.PublicationGallery;
import com.jeepclub.backend.publications.core.domain.model.PublicationImage;
import com.jeepclub.backend.publications.core.repository.PublicationRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import java.time.Clock;
import java.time.Instant;
import java.util.List;
import java.util.Set;

@Service
@RequiredArgsConstructor
public class AdminNoticeService {
    private static final int MAX_QUERY_LENGTH = 200;
    private static final int MAX_PAGE_SIZE = 50;
    private static final Set<String> ALLOWED_SORT_FIELDS = Set.of(
            "id", "title", "status", "createdAt", "updatedAt", "publishedAt", "archivedAt", "authorUserId"
    );

    private final PublicationRepository publications;
    private final ImageMediaService media;
    private final Clock clock;

    @Transactional(readOnly = true)
    public Page<Notice> findAll(
            PublicationStatus status,
            String query,
            Instant from,
            Instant to,
            Instant publishedFrom,
            Instant publishedTo,
            Pageable pageable
    ) {
        if (from != null && to != null && from.isAfter(to)) {
            throw new IllegalArgumentException("Notice creation period range is invalid.");
        }
        if (publishedFrom != null && publishedTo != null && publishedFrom.isAfter(publishedTo)) {
            throw new IllegalArgumentException("Notice published period range is invalid.");
        }
        String normalizedQuery = query == null || query.isBlank() ? null : query.trim();
        if (normalizedQuery != null && normalizedQuery.length() > MAX_QUERY_LENGTH) {
            throw new IllegalArgumentException("Notice search query exceeds maximum length.");
        }
        Pageable stable = validateAndStabilize(pageable);
        return publications.findNotices(status, normalizedQuery, from, to, publishedFrom, publishedTo, stable);
    }

    private static Pageable validateAndStabilize(Pageable pageable) {
        if (pageable == null || pageable.isUnpaged()
                || pageable.getPageNumber() < 0
                || pageable.getPageSize() < 1
                || pageable.getPageSize() > MAX_PAGE_SIZE
                || pageable.getOffset() > Integer.MAX_VALUE) {
            throw new IllegalArgumentException("Invalid notice pagination parameters.");
        }
        Sort sort = pageable.getSort();
        if (sort.isUnsorted()) {
            sort = Sort.by(Sort.Order.desc("createdAt"), Sort.Order.desc("id"));
        } else {
            for (Sort.Order order : sort) {
                if (!ALLOWED_SORT_FIELDS.contains(order.getProperty())) {
                    throw new IllegalArgumentException("Invalid notice sort field: " + order.getProperty());
                }
            }
            if (sort.getOrderFor("id") == null) {
                Sort.Direction tieDirection = sort.iterator().next().getDirection();
                sort = sort.and(Sort.by(tieDirection, "id"));
            }
        }
        return PageRequest.of(pageable.getPageNumber(), pageable.getPageSize(), sort);
    }

    @Transactional
    public Notice create(Long authorUserId, String title, String content, List<PublicationImage> images) {
        Notice notice = Notice.create(authorUserId, title, content, images, Instant.now(clock));
        requireMedia(notice.getImages());
        return save(notice);
    }

    @Transactional(readOnly = true)
    public Notice findById(Long id) {
        return asNotice(publications.findById(id).orElse(null), id);
    }

    @Transactional
    public Notice update(Long id, String title, String content, List<PublicationImage> images) {
        Notice notice = locked(id);
        if (notice.getStatus() == PublicationStatus.ARCHIVED) {
            throw new InvalidNoticeStateException("Archived notice cannot be edited.");
        }
        Instant now = Instant.now(clock);
        if (images != null) requireMedia(PublicationGallery.of(images).images());
        try {
            if (title != null || content != null) {
                notice.updateContent(title == null ? notice.getTitle() : title,
                        content == null ? notice.getContent() : content, now);
            }
            if (images != null) notice.replaceImages(images, now);
        } catch (IllegalStateException exception) {
            throw new InvalidNoticeStateException(exception.getMessage());
        }
        return title == null && content == null && images == null ? notice : save(notice);
    }

    @Transactional
    public Notice publish(Long id) {
        Notice notice = locked(id);
        try {
            notice.publish(Instant.now(clock));
        } catch (IllegalStateException exception) {
            throw new InvalidNoticeStateException(exception.getMessage());
        }
        return save(notice);
    }

    @Transactional
    public Notice archive(Long id) {
        Notice notice = locked(id);
        try {
            notice.archive(Instant.now(clock));
        } catch (IllegalStateException exception) {
            throw new InvalidNoticeStateException(exception.getMessage());
        }
        return save(notice);
    }

    @Transactional
    public void delete(Long id, Long deletedByUserId) {
        locked(id);
        try {
            publications.delete(id, deletedByUserId, Instant.now(clock));
        } catch (PublicationAlreadyDeletedException exception) {
            throw new NoticeNotFoundException(id);
        }
    }

    private Notice locked(Long id) {
        return asNotice(publications.findByIdForUpdate(id).orElse(null), id);
    }

    private static Notice asNotice(Publication publication, Long id) {
        if (publication instanceof Notice notice) return notice;
        throw new NoticeNotFoundException(id);
    }

    private Notice save(Notice notice) {
        try {
            return (Notice) publications.save(notice);
        } catch (PublicationAlreadyDeletedException exception) {
            throw new NoticeNotFoundException(notice.getId());
        }
    }

    private void requireMedia(List<PublicationImage> images) {
        images.forEach(image -> media.requireExisting(image.storageKey()));
    }
}
