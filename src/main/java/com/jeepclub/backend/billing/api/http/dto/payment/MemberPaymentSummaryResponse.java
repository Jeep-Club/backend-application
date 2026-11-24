package com.jeepclub.backend.billing.api.http.dto.payment;

import com.jeepclub.backend.billing.core.application.result.MemberPaymentChargeContext;
import com.jeepclub.backend.billing.core.application.result.MemberPaymentResult;
import com.jeepclub.backend.billing.core.domain.enums.payment.MemberPaymentStatus;
import com.jeepclub.backend.billing.core.domain.enums.payment.PaymentMethod;
import io.swagger.v3.oas.annotations.media.Schema;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.Objects;

@Schema(description = "Resumo de um pagamento de membro para listagem.")
public record MemberPaymentSummaryResponse(

        @Schema(description = "Identificador do pagamento.", example = "1")
        Long id,

        @Schema(description = "Identificador da cobrança de membro.", example = "10")
        Long memberChargeId,

        @Schema(description = "Valor informado no pagamento.", example = "250.00")
        BigDecimal amount,

        @Schema(description = "Método de pagamento.", example = "PIX")
        PaymentMethod paymentMethod,

        @Schema(description = "Status do pagamento.", example = "PENDING_VALIDATION")
        MemberPaymentStatus status,

        @Schema(description = "Data informada em que o pagamento foi realizado.")
        Instant paidAt,

        @Schema(description = "Rota lógica autorizada para download do comprovante.", example = "/billing/member-payments/1/receipt")
        String receiptUrl,

        @Schema(description = "Data de criação do pagamento.")
        Instant createdAt,

        @Schema(description = "Titular da cobrança vinculada.", nullable = true)
        Long userId,
        @Schema(description = "Nome atual do titular, quando autorizado e disponível.", nullable = true)
        String userName,
        @Schema(description = "Matrícula vigente do titular, quando autorizada e disponível.", nullable = true)
        String membershipNumber,
        @Schema(nullable = true)
        Long chargeDefinitionId,
        @Schema(nullable = true)
        Long chargeCycleId,
        @Schema(description = "Nome histórico da definição no ciclo.", nullable = true)
        String chargeDefinitionNameSnapshot,
        @Schema(description = "Referência histórica do ciclo.", nullable = true)
        String chargeCycleCode,
        @Schema(description = "Vencimento da cobrança vinculada.", nullable = true)
        LocalDate dueDate
) {

    public static MemberPaymentSummaryResponse from(MemberPaymentResult result, MemberPaymentChargeContext charge,
                                                    String userName, String membershipNumber) {
        Objects.requireNonNull(result, "result cannot be null");

        return new MemberPaymentSummaryResponse(
                result.id(),
                result.memberChargeId(),
                result.amount(),
                result.paymentMethod(),
                result.status(),
                result.paidAt(),
                "/billing/member-payments/" + result.id() + "/receipt",
                result.createdAt(),
                charge.userId(), userName, membershipNumber, charge.chargeDefinitionId(), charge.chargeCycleId(),
                charge.chargeDefinitionNameSnapshot(), charge.chargeCycleCode(), charge.dueDate()
        );
    }
}
