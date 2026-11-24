package com.jeepclub.backend.publications.api.http.dto;

import com.jeepclub.backend.publications.core.domain.enums.PublicationStatus;
import com.jeepclub.backend.publications.core.domain.model.ServicePublication;
import io.swagger.v3.oas.annotations.media.Schema;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;

public record ServicePublicationResponseDTO(
        Long id,
        Long authorUserId,
        @Schema(description = "Nome cadastral atual do autor ativo na superfície administrativa, quando autorizado com IDENTITY_USER_READ.", nullable = true)
        String authorName,
        Long sourceRequestId,
        String title,
        String content,
        BigDecimal amount,
        String contactPhone,
        PublicationStatus status,
        List<PublicationImageResponseDTO> images,
        Instant createdAt,
        Instant updatedAt,
        Instant publishedAt,
        Instant archivedAt
) {
    public static ServicePublicationResponseDTO from(ServicePublication service) {
        return from(service, null);
    }

    public static ServicePublicationResponseDTO from(ServicePublication service, String authorName) {
        return new ServicePublicationResponseDTO(service.getId(), service.getAuthorUserId(), authorName, service.getSourceRequestId(),
                service.getTitle(), service.getContent(), service.getAmount(), service.getContactPhone(),
                service.getStatus(), service.getImages().stream().map(PublicationImageResponseDTO::from).toList(),
                service.getCreatedAt(), service.getUpdatedAt(), service.getPublishedAt(), service.getArchivedAt());
    }
}
