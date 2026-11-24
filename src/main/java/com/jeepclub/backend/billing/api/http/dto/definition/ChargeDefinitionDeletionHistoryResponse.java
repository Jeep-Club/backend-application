package com.jeepclub.backend.billing.api.http.dto.definition;

import com.jeepclub.backend.billing.core.repository.ChargeDefinitionDeletionHistoryQueryRepository.ChargeDefinitionDeletionHistorySnapshot;
import io.swagger.v3.oas.annotations.media.Schema;
import java.math.BigDecimal;
import java.time.Instant;

public record ChargeDefinitionDeletionHistoryResponse(
        Long originalId,
        String name,
        String description,
        BigDecimal defaultAmount,
        String recurrenceType,
        Boolean required,
        String paymentAcceptancePolicy,
        Integer latePaymentGraceDays,
        String previousStatus,
        Instant createdAt,
        Instant updatedAt,
        Instant archivedAt,
        Long deletedByUserId,
        Instant deletedAt,
        String reason,
        int schemaVersion,
        @Schema(description = "Nome cadastral atual do autor da exclusão, quando autorizado e disponível.", nullable = true)
        String deletedByUserName
) {
    public static ChargeDefinitionDeletionHistoryResponse from(ChargeDefinitionDeletionHistorySnapshot snapshot,
                                                                String deletedByUserName) {
        return new ChargeDefinitionDeletionHistoryResponse(snapshot.originalId(), snapshot.name(), snapshot.description(),
                snapshot.defaultAmount(), snapshot.recurrenceType(), snapshot.required(), snapshot.paymentAcceptancePolicy(),
                snapshot.latePaymentGraceDays(), snapshot.previousStatus(), snapshot.createdAt(), snapshot.updatedAt(),
                snapshot.archivedAt(), snapshot.deletedByUserId(), snapshot.deletedAt(), snapshot.reason(),
                snapshot.schemaVersion(), deletedByUserName);
    }
}
