package com.jeepclub.backend.publications.infra.persistence.entity;

import com.jeepclub.backend.publications.core.domain.enums.NoticePriority;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.PrimaryKeyJoinColumn;
import jakarta.persistence.Table;
import java.time.Instant;
import lombok.Getter;
import lombok.Setter;

@Entity
@Table(name = "publication_notices")
@PrimaryKeyJoinColumn(name = "publication_id")
@Getter
@Setter
public class NoticeEntity extends PublicationEntity {
    @Column(name = "priority", nullable = false, length = 20)
    private String priority = NoticePriority.NORMAL.name();

    @Column(name = "valid_from")
    private Instant validFrom;

    @Column(name = "valid_until")
    private Instant validUntil;
}
