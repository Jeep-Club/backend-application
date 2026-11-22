package com.jeepclub.backend.publications.api.http.dto;

import com.jeepclub.backend.publications.core.domain.enums.ServicePublicationChangeRequestStatus;
import com.jeepclub.backend.publications.core.domain.model.ServicePublicationChangeRequest;
import io.swagger.v3.oas.annotations.media.Schema;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;

public record AdminServicePublicationChangeRequestResponseDTO(
        Long id,
        Long servicePublicationId,
        @Schema(description = "Título atual do serviço publicado vinculado, quando disponível.", nullable = true)
        String servicePublicationTitle,
        Long requestedByUserId,
        @Schema(description = "Nome cadastral atual do solicitante ativo, quando autorizado com IDENTITY_USER_READ.", nullable = true)
        String requestedByUserName,
        String proposedTitle,
        String proposedContent,
        BigDecimal proposedAmount,
        String proposedContactPhone,
        List<PublicationImageResponseDTO> proposedImages,
        ServicePublicationChangeRequestStatus status,
        String rejectionReason,
        Long reviewedByUserId,
        @Schema(description = "Nome cadastral atual do revisor ativo, quando autorizado com IDENTITY_USER_READ.", nullable = true)
        String reviewedByUserName,
        Instant requestedAt,
        Instant reviewedAt,
        Instant updatedAt,
        Long version
) {
    public static AdminServicePublicationChangeRequestResponseDTO from(ServicePublicationChangeRequest request) {
        return from(request, null, null, null);
    }

    public static AdminServicePublicationChangeRequestResponseDTO from(
            ServicePublicationChangeRequest request,
            String servicePublicationTitle,
            String requestedByUserName,
            String reviewedByUserName
    ) {
        return new AdminServicePublicationChangeRequestResponseDTO(
                request.getId(),
                request.getServicePublicationId(),
                servicePublicationTitle,
                request.getRequestedByUserId(),
                requestedByUserName,
                request.getProposedTitle(),
                request.getProposedContent(),
                request.getProposedAmount(),
                request.getProposedContactPhone(),
                request.getProposedImages().stream().map(PublicationImageResponseDTO::from).toList(),
                request.getStatus(),
                request.getRejectionReason(),
                request.getReviewedByUserId(),
                reviewedByUserName,
                request.getRequestedAt(),
                request.getReviewedAt(),
                request.getUpdatedAt(),
                request.getVersion()
        );
    }
}
