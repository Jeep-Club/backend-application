package com.jeepclub.backend.publications.core.repository;

import com.jeepclub.backend.publications.core.domain.enums.PublicationStatus;
import com.jeepclub.backend.publications.core.domain.model.Notice;
import com.jeepclub.backend.publications.core.domain.model.Publication;
import com.jeepclub.backend.publications.core.domain.model.ServicePublication;
import java.time.Instant;
import java.util.Collection;
import java.util.List;
import java.util.Optional;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;

public interface PublicationRepository {
    Page<Publication> findPublished(String type, Instant from, Instant to, Pageable pageable);
    Page<Notice> findNotices(PublicationStatus status, String query, Instant from, Instant to, Instant publishedFrom, Instant publishedTo, Pageable pageable);
    Page<ServicePublication> findServices(PublicationStatus status, String query, Instant from, Instant to, Instant publishedFrom, Instant publishedTo, Pageable pageable);
    List<ServicePublication> findServicesByIds(Collection<Long> ids);
    org.springframework.data.domain.Page<com.jeepclub.backend.publications.core.domain.model.Event> findEvents(org.springframework.data.domain.Pageable pageable);
    Publication save(Publication publication);
    Optional<Publication> findById(Long id);
    Optional<Publication> findByIdForUpdate(Long id);
    Optional<com.jeepclub.backend.publications.core.domain.model.Event> findEventByIdForUpdate(Long id);
    void delete(Long id, Long deletedByUserId, Instant deletedAt);
}
