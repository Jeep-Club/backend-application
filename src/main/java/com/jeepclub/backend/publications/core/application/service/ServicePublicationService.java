package com.jeepclub.backend.publications.core.application.service;

import com.jeepclub.backend.publications.core.application.exception.ServiceOperationException;
import com.jeepclub.backend.publications.core.domain.enums.PublicationStatus;
import com.jeepclub.backend.publications.core.domain.exception.PublicationAlreadyDeletedException;
import com.jeepclub.backend.publications.core.domain.model.Publication;
import com.jeepclub.backend.publications.core.domain.model.ServicePublication;
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
import java.util.Set;
import static com.jeepclub.backend.publications.core.application.exception.ServiceOperationException.Reason.*;

@Service
@RequiredArgsConstructor
public class ServicePublicationService {
    private static final int MAX_QUERY_LENGTH = 200;
    private static final int MAX_PAGE_SIZE = 50;
    private static final Set<String> ALLOWED_SORT_FIELDS = Set.of(
            "id", "title", "amount", "status", "createdAt", "updatedAt", "publishedAt", "archivedAt", "authorUserId", "sourceRequestId"
    );

    private final PublicationRepository publications;
    private final Clock clock;

    @Transactional(readOnly = true)
    public Page<ServicePublication> findAllAdmin(
            PublicationStatus status,
            String query,
            Instant from,
            Instant to,
            Instant publishedFrom,
            Instant publishedTo,
            Pageable pageable
    ) {
        if (status == PublicationStatus.DRAFT) {
            throw new ServiceOperationException(INVALID_REQUEST);
        }
        if (from != null && to != null && from.isAfter(to)) {
            throw new ServiceOperationException(INVALID_REQUEST);
        }
        if (publishedFrom != null && publishedTo != null && publishedFrom.isAfter(publishedTo)) {
            throw new ServiceOperationException(INVALID_REQUEST);
        }
        String normalizedQuery = query == null || query.isBlank() ? null : query.trim();
        if (normalizedQuery != null && normalizedQuery.length() > MAX_QUERY_LENGTH) {
            throw new ServiceOperationException(INVALID_REQUEST);
        }
        Pageable stable = validateAndStabilize(pageable);
        return publications.findServices(status, normalizedQuery, from, to, publishedFrom, publishedTo, stable);
    }

    private static Pageable validateAndStabilize(Pageable pageable) {
        if (pageable == null || pageable.isUnpaged()
                || pageable.getPageNumber() < 0
                || pageable.getPageSize() < 1
                || pageable.getPageSize() > MAX_PAGE_SIZE
                || pageable.getOffset() > Integer.MAX_VALUE) {
            throw new ServiceOperationException(INVALID_REQUEST);
        }
        Sort sort = pageable.getSort();
        if (sort.isUnsorted()) {
            sort = Sort.by(Sort.Order.desc("createdAt"), Sort.Order.desc("id"));
        } else {
            for (Sort.Order order : sort) {
                if (!ALLOWED_SORT_FIELDS.contains(order.getProperty())) {
                    throw new ServiceOperationException(INVALID_REQUEST);
                }
            }
            if (sort.getOrderFor("id") == null) {
                Sort.Direction tieDirection = sort.iterator().next().getDirection();
                sort = sort.and(Sort.by(tieDirection, "id"));
            }
        }
        return PageRequest.of(pageable.getPageNumber(), pageable.getPageSize(), sort);
    }

    @Transactional(readOnly = true)
    public ServicePublication findPublished(Long id) {
        ServicePublication service = asService(publications.findById(id).orElse(null));
        if (service.getStatus() != PublicationStatus.PUBLISHED) throw new ServiceOperationException(SERVICE_NOT_FOUND);
        return service;
    }

    @Transactional(readOnly = true)
    public ServicePublication findAdmin(Long id) {
        return asService(publications.findById(id).orElse(null));
    }

    @Transactional
    public void deleteOwn(Long id, Long userId) {
        ServicePublication service = locked(id);
        if (!service.getAuthorUserId().equals(userId)) throw new ServiceOperationException(SERVICE_NOT_OWNER);
        delete(id, userId);
    }

    @Transactional
    public void deleteAdmin(Long id, Long adminUserId) {
        locked(id);
        delete(id, adminUserId);
    }

    private void delete(Long id, Long actor) {
        try {
            publications.delete(id, actor, Instant.now(clock));
        } catch (PublicationAlreadyDeletedException exception) {
            throw new ServiceOperationException(SERVICE_NOT_FOUND);
        }
    }

    private ServicePublication locked(Long id) {
        return asService(publications.findByIdForUpdate(id).orElse(null));
    }

    private static ServicePublication asService(Publication publication) {
        if (publication instanceof ServicePublication service) return service;
        throw new ServiceOperationException(SERVICE_NOT_FOUND);
    }
}
