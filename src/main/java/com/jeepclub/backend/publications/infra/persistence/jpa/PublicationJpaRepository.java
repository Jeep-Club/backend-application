package com.jeepclub.backend.publications.infra.persistence.jpa;

import com.jeepclub.backend.publications.core.domain.enums.NoticePriority;
import com.jeepclub.backend.publications.core.domain.enums.PublicationStatus;
import com.jeepclub.backend.publications.infra.persistence.entity.NoticeEntity;
import com.jeepclub.backend.publications.infra.persistence.entity.PublicationEntity;
import com.jeepclub.backend.publications.infra.persistence.entity.ServicePublicationEntity;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import java.time.Instant;
import java.util.Collection;
import java.util.List;
import java.util.Optional;

public interface PublicationJpaRepository extends JpaRepository<PublicationEntity, Long>, org.springframework.data.jpa.repository.JpaSpecificationExecutor<PublicationEntity> {
    @Query("select e from EventEntity e")
    org.springframework.data.domain.Page<PublicationEntity> findEvents(org.springframework.data.domain.Pageable pageable);

    @Query("""
        select n from NoticeEntity n
        where (:status is null or n.status = :status)
          and (:priority is null or n.priority = :priority)
          and (:expired is null
               or (:expired = true and n.validUntil is not null and n.validUntil < :referenceNow)
               or (:expired = false and (n.validUntil is null or n.validUntil >= :referenceNow)))
          and (:pattern is null or lower(n.title) like :pattern escape '!' or lower(n.content) like :pattern escape '!')
          and (:from is null or n.createdAt >= :from)
          and (:to is null or n.createdAt <= :to)
          and (:publishedFrom is null or n.publishedAt >= :publishedFrom)
          and (:publishedTo is null or n.publishedAt <= :publishedTo)
        """)
    Page<NoticeEntity> findNotices(
            @Param("status") PublicationStatus status,
            @Param("priority") String priority,
            @Param("expired") Boolean expired,
            @Param("referenceNow") Instant referenceNow,
            @Param("pattern") String pattern,
            @Param("from") Instant from,
            @Param("to") Instant to,
            @Param("publishedFrom") Instant publishedFrom,
            @Param("publishedTo") Instant publishedTo,
            Pageable pageable
    );

    @Query("""
        select s from ServicePublicationEntity s
        where (:status is null or s.status = :status)
          and (:pattern is null or lower(s.title) like :pattern escape '!' or lower(s.content) like :pattern escape '!')
          and (:from is null or s.createdAt >= :from)
          and (:to is null or s.createdAt <= :to)
          and (:publishedFrom is null or s.publishedAt >= :publishedFrom)
          and (:publishedTo is null or s.publishedAt <= :publishedTo)
        """)
    Page<ServicePublicationEntity> findServices(
            @Param("status") PublicationStatus status,
            @Param("pattern") String pattern,
            @Param("from") Instant from,
            @Param("to") Instant to,
            @Param("publishedFrom") Instant publishedFrom,
            @Param("publishedTo") Instant publishedTo,
            Pageable pageable
    );

    @Query("select s from ServicePublicationEntity s where s.id in :ids")
    List<ServicePublicationEntity> findServicesByIdIn(@Param("ids") Collection<Long> ids);
    // Lock the root row first. Hibernate's follow-on locking of a polymorphic JOINED
    // select can fail when a concurrent delete removes the row while it waits.
    @Query(value = "select id from publications where id = :id for update", nativeQuery = true)
    Optional<Long> lockRootId(@Param("id") Long id);
    @Query(value="""
        select p.id,p.author_user_id,p.title,p.content,p.status,p.created_at,p.updated_at,p.published_at,p.archived_at,
            e.starts_at,e.ends_at,e.event_status,e.location,e.participant_limit
        from publications p join publication_events e on e.publication_id=p.id where p.id=:id for update
        """,nativeQuery=true)
    java.util.List<Object[]> currentEvent(@Param("id") Long id);
    @Query(value="select storage_key,position,is_primary from publication_images where publication_id=:id order by position for update",nativeQuery=true)
    java.util.List<Object[]> currentImages(@Param("id") Long id);
}
