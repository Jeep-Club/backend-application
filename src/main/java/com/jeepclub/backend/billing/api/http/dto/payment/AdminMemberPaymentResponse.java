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

@Schema(description = "Detalhe administrativo de pagamento e contexto da cobrança vinculada.")
public record AdminMemberPaymentResponse(

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

        @Schema(description = "Data de confirmação do pagamento.", nullable = true)
        Instant confirmedAt,

        @Schema(description = "Usuário que confirmou o pagamento.", example = "1", nullable = true)
        Long confirmedByUserId,

        @Schema(description = "Data de rejeição do pagamento.", nullable = true)
        Instant rejectedAt,

        @Schema(description = "Usuário que rejeitou o pagamento.", example = "1", nullable = true)
        Long rejectedByUserId,

        @Schema(description = "Motivo da rejeição.", nullable = true)
        String rejectionReason,

        @Schema(description = "Data de cancelamento do pagamento.", nullable = true)
        Instant canceledAt,

        @Schema(description = "Observações do pagamento.", nullable = true)
        String notes,

        @Schema(description = "Data de criação do pagamento.")
        Instant createdAt,

        @Schema(description = "Data da última atualização.", nullable = true)
        Instant updatedAt,

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
        LocalDate dueDate,

        @Schema(description = "Nome atual de quem confirmou, quando autorizado e disponível.", nullable = true)
        String confirmedByUserName,
        @Schema(description = "Nome atual de quem rejeitou, quando autorizado e disponível.", nullable = true)
        String rejectedByUserName
) {

    public static AdminMemberPaymentResponse from(MemberPaymentResult result, MemberPaymentChargeContext charge,
                                                  String userName, String membershipNumber,
                                                  String confirmedByUserName, String rejectedByUserName) {
        Objects.requireNonNull(result, "result cannot be null");

        return new AdminMemberPaymentResponse(
                result.id(),
                result.memberChargeId(),
                result.amount(),
                result.paymentMethod(),
                result.status(),
                result.paidAt(),
                receiptUrl(result.id()),
                result.confirmedAt(),
                result.confirmedByUserId(),
                result.rejectedAt(),
                result.rejectedByUserId(),
                result.rejectionReason(),
                result.canceledAt(),
                result.notes(),
                result.createdAt(),
                result.updatedAt(),
                charge.userId(), userName, membershipNumber, charge.chargeDefinitionId(), charge.chargeCycleId(),
                charge.chargeDefinitionNameSnapshot(), charge.chargeCycleCode(), charge.dueDate(),
                confirmedByUserName, rejectedByUserName
        );
    }

    private static String receiptUrl(Long paymentId) {
        return "/billing/member-payments/" + paymentId + "/receipt";
    }
}
