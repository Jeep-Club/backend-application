package com.jeepclub.backend.billing.api.http.dto.refund;

import com.jeepclub.backend.billing.core.application.result.MemberRefundResult;
import com.jeepclub.backend.billing.core.domain.enums.refund.MemberRefundStatus;
import com.jeepclub.backend.billing.core.domain.enums.refund.RefundReason;
import io.swagger.v3.oas.annotations.media.Schema;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.Objects;

@Schema(description = "Resumo administrativo de reembolso com contexto financeiro e dados autorizados do titular.")
public record AdminMemberRefundSummaryResponse(

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

        @Schema(description = "Data limite para aproveitamento da elegibilidade.", nullable = true)
        Instant eligibleUntil,

        @Schema(description = "Data em que o membro solicitou o reembolso.", nullable = true)
        Instant requestedAt,

        @Schema(description = "Data de criação do registro de reembolso.")
        Instant createdAt,

        @Schema(nullable = true)
        String userName,
        @Schema(nullable = true)
        String membershipNumber,
        @Schema(nullable = true)
        Long chargeDefinitionId,
        @Schema(nullable = true)
        String chargeDefinitionNameSnapshot,
        @Schema(nullable = true)
        String chargeCycleCode
) {

    public static AdminMemberRefundSummaryResponse from(MemberRefundResult result,
            String userName,
            String membershipNumber,
            Long chargeDefinitionId,
            String chargeDefinitionNameSnapshot,
            String chargeCycleCode) {
        Objects.requireNonNull(result, "result cannot be null");

        return new AdminMemberRefundSummaryResponse(
                result.id(),
                result.memberChargeId(),
                result.memberPaymentId(),
                result.chargeCycleId(),
                result.userId(),
                result.amount(),
                result.reason(),
                result.status(),
                result.eligibleUntil(),
                result.requestedAt(),
                result.createdAt(),
                userName,
                membershipNumber,
                chargeDefinitionId,
                chargeDefinitionNameSnapshot,
                chargeCycleCode
        );
    }
}