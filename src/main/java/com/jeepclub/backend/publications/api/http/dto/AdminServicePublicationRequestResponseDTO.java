package com.jeepclub.backend.publications.api.http.dto;

import com.jeepclub.backend.publications.core.domain.enums.ServicePublicationRequestStatus;
import com.jeepclub.backend.publications.core.domain.model.ServicePublicationRequest;
import io.swagger.v3.oas.annotations.media.Schema;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;

public record AdminServicePublicationRequestResponseDTO(
        Long id,
        Long requestedByUserId,
        @Schema(description = "Nome cadastral atual do solicitante ativo, quando autorizado com IDENTITY_USER_READ.", nullable = true)
        String requestedByUserName,
        String title,
        String content,
        BigDecimal amount,
        String contactPhone,
        List<PublicationImageResponseDTO> images,
        ServicePublicationRequestStatus status,
        String rejectionReason,
        Long reviewedByUserId,
        @Schema(description = "Nome cadastral atual do revisor ativo, quando autorizado com IDENTITY_USER_READ.", nullable = true)
        String reviewedByUserName,
        Long createdPublicationId,
        @Schema(description = "Título atual do serviço criado, quando aprovado e disponível.", nullable = true)
        String createdPublicationTitle,
        Instant requestedAt,
        Instant reviewedAt,
        Instant updatedAt,
        Long version
) {
    public static AdminServicePublicationRequestResponseDTO from(ServicePublicationRequest request) {
        return from(request, null, null, null);
    }

    public static AdminServicePublicationRequestResponseDTO from(
            ServicePublicationRequest request,
            String requestedByUserName,
            String reviewedByUserName,
            String createdPublicationTitle
    ) {
        return new AdminServicePublicationRequestResponseDTO(
                request.getId(),
                request.getRequestedByUserId(),
                requestedByUserName,
                request.getTitle(),
                request.getContent(),
                request.getAmount(),
                request.getContactPhone(),
                request.getImages().stream().map(PublicationImageResponseDTO::from).toList(),
                request.getStatus(),
                request.getRejectionReason(),
                request.getReviewedByUserId(),
                reviewedByUserName,
                request.getCreatedPublicationId(),
                createdPublicationTitle,
                request.getRequestedAt(),
                request.getReviewedAt(),
                request.getUpdatedAt(),
                request.getVersion()
        );
    }
}
