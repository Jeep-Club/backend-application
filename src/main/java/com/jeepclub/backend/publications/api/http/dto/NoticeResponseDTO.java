package com.jeepclub.backend.publications.api.http.dto;

import com.jeepclub.backend.publications.core.domain.enums.NoticePriority;
import com.jeepclub.backend.publications.core.domain.enums.PublicationStatus;
import com.jeepclub.backend.publications.core.domain.model.Notice;
import io.swagger.v3.oas.annotations.media.Schema;
import java.time.Instant;
import java.util.List;

@Schema(description = "Aviso operacional, inclusive rascunho ou arquivado na superfície administrativa.")
public record NoticeResponseDTO(
        Long id,
        Long authorUserId,
        @Schema(description = "Nome cadastral atual do autor ativo, quando autorizado com IDENTITY_USER_READ.", nullable = true)
        String authorName,
        String title,
        String content,
        @Schema(allowableValues = {"DRAFT", "PUBLISHED", "ARCHIVED"}) PublicationStatus status,
        @Schema(description = "Prioridade editorial do aviso.", allowableValues = {"LOW", "NORMAL", "HIGH", "URGENT"})
        NoticePriority priority,
        @Schema(description = "Início inclusivo opcional da janela de validade temporal no feed.", nullable = true)
        Instant validFrom,
        @Schema(description = "Fim inclusivo opcional da janela de validade temporal no feed.", nullable = true)
        Instant validUntil,
        List<PublicationImageResponseDTO> images,
        Instant createdAt,
        Instant updatedAt,
        Instant publishedAt,
        Instant archivedAt
) {
    public static NoticeResponseDTO from(Notice notice) {
        return from(notice, null);
    }

    public static NoticeResponseDTO from(Notice notice, String authorName) {
        return new NoticeResponseDTO(notice.getId(), notice.getAuthorUserId(), authorName, notice.getTitle(), notice.getContent(),
                notice.getStatus(), notice.getPriority(), notice.getValidFrom(), notice.getValidUntil(),
                notice.getImages().stream().map(PublicationImageResponseDTO::from).toList(),
                notice.getCreatedAt(), notice.getUpdatedAt(), notice.getPublishedAt(), notice.getArchivedAt());
    }
}
