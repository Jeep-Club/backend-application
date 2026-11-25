package com.jeepclub.backend.billing.api.http.dto.refund;

import com.jeepclub.backend.billing.core.application.result.MemberRefundResult;
import com.jeepclub.backend.billing.core.domain.enums.refund.MemberRefundStatus;
import com.jeepclub.backend.billing.core.domain.enums.refund.RefundReason;
import io.swagger.v3.oas.annotations.media.Schema;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.Objects;

@Schema(description = "Detalhe administrativo de reembolso com contexto financeiro e nomes autorizados.")
public record AdminMemberRefundResponse(

        @Schema(description = "Identificador do reembolso.", example = "1")
        Long id,

        @Schema(description = "Identificador da cobrança vinculada ao reembolso.", example = "10")
        Long memberChargeId,

        @Schema(description = "Identificador do pagamento vinculado ao reembolso.", example = "20")
        Long memberPaymentId,

        @Schema(description = "Identificador do ciclo de cobrança vinculado ao reembolso.", example = "5")
        Long chargeCycleId,

        @Schema(description = "Identificador do usuário dono do reembolso.", example = "3")
        Long userId,

        @Schema(description = "Valor previsto para reembolso.", example = "250.00")
        BigDecimal amount,

        @Schema(description = "Motivo do reembolso.", example = "CYCLE_CANCELED_BY_ADMIN")
        RefundReason reason,

        @Schema(description = "Status atual do reembolso.", example = "ELIGIBLE")
        MemberRefundStatus status,

        @Schema(description = "Data em que o reembolso se tornou elegível.", nullable = true)
        Instant eligibleAt,

        @Schema(description = "Data limite para aproveitamento da elegibilidade.", nullable = true)
        Instant eligibleUntil,

        @Schema(description = "Usuário que criou a elegibilidade do reembolso.", example = "1", nullable = true)
        Long createdByUserId,

        @Schema(description = "Data em que o membro solicitou o reembolso.", nullable = true)
        Instant requestedAt,

        @Schema(description = "Usuário que solicitou o reembolso.", example = "3", nullable = true)
        Long requestedByUserId,

        @Schema(description = "Data de aprovação do reembolso.", nullable = true)
        Instant approvedAt,

        @Schema(description = "Usuário que aprovou o reembolso.", example = "1", nullable = true)
        Long approvedByUserId,

        @Schema(description = "Data de rejeição do reembolso.", nullable = true)
        Instant rejectedAt,

        @Schema(description = "Usuário que rejeitou o reembolso.", example = "1", nullable = true)
        Long rejectedByUserId,

        @Schema(description = "Motivo da rejeição do reembolso.", nullable = true)
        String rejectionReason,

        @Schema(description = "Data em que o reembolso foi marcado como realizado.", nullable = true)
        Instant refundedAt,

        @Schema(description = "Usuário que marcou o reembolso como realizado.", example = "1", nullable = true)
        Long refundedByUserId,

        @Schema(description = "Data de cancelamento do processo de reembolso.", nullable = true)
        Instant canceledAt,

        @Schema(description = "Usuário que cancelou o processo de reembolso.", example = "1", nullable = true)
        Long canceledByUserId,

        @Schema(description = "Data de criação do registro de reembolso.")
        Instant createdAt,

        @Schema(description = "Data da última atualização do registro de reembolso.", nullable = true)
        Instant updatedAt,

        @Schema(nullable = true)
        String userName,
        @Schema(nullable = true)
        String membershipNumber,
        @Schema(nullable = true)
        Long chargeDefinitionId,
        @Schema(nullable = true)
        String chargeDefinitionNameSnapshot,
        @Schema(nullable = true)
        String chargeCycleCode,
        @Schema(nullable = true)
        String createdByUserName,
        @Schema(nullable = true)
        String requestedByUserName,
        @Schema(nullable = true)
        String approvedByUserName,
        @Schema(nullable = true)
        String rejectedByUserName,
        @Schema(nullable = true)
        String refundedByUserName,
        @Schema(nullable = true)
        String canceledByUserName
) {

    public static AdminMemberRefundResponse from(MemberRefundResult result,
            String userName,
            String membershipNumber,
            Long chargeDefinitionId,
            String chargeDefinitionNameSnapshot,
            String chargeCycleCode,
            String createdByUserName,
            String requestedByUserName,
            String approvedByUserName,
            String rejectedByUserName,
            String refundedByUserName,
            String canceledByUserName) {
        Objects.requireNonNull(result, "result cannot be null");

        return new AdminMemberRefundResponse(
                result.id(),
                result.memberChargeId(),
                result.memberPaymentId(),
                result.chargeCycleId(),
                result.userId(),
                result.amount(),
                result.reason(),
                result.status(),
                result.eligibleAt(),
                result.eligibleUntil(),
                result.createdByUserId(),
                result.requestedAt(),
                result.requestedByUserId(),
                result.approvedAt(),
                result.approvedByUserId(),
                result.rejectedAt(),
                result.rejectedByUserId(),
                result.rejectionReason(),
                result.refundedAt(),
                result.refundedByUserId(),
                result.canceledAt(),
                result.canceledByUserId(),
                result.createdAt(),
                result.updatedAt(),
                userName,
                membershipNumber,
                chargeDefinitionId,
                chargeDefinitionNameSnapshot,
                chargeCycleCode,
                createdByUserName,
                requestedByUserName,
                approvedByUserName,
                rejectedByUserName,
                refundedByUserName,
                canceledByUserName
        );
    }
}