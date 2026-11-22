package com.jeepclub.backend.publications.infra.persistence.entity;

import com.jeepclub.backend.publications.core.domain.enums.NoticePriority;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.PrimaryKeyJoinColumn;
import jakarta.persistence.Table;
import java.time.Instant;
import lombok.Getter;
import lombok.Setter;
import org.hibernate.annotations.ColumnDefault;

@Entity
@Table(name = "publication_notices")
@PrimaryKeyJoinColumn(name = "publication_id")
@Getter
@Setter
public class NoticeEntity extends PublicationEntity {
    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    @ColumnDefault("'NORMAL'")
    private NoticePriority priority = NoticePriority.NORMAL;

    @Column(name = "valid_from")
    private Instant validFrom;

    @Column(name = "valid_until")
    private Instant validUntil;
}
