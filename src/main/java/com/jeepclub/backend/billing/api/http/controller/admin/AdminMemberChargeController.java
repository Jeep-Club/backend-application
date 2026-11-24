package com.jeepclub.backend.billing.api.http.controller.admin;

import com.jeepclub.backend.billing.api.http.dto.charge.MemberChargeResponse;
import com.jeepclub.backend.billing.api.http.dto.BillingPageSchemas;
import com.jeepclub.backend.billing.api.http.dto.charge.MemberChargeSummaryResponse;
import com.jeepclub.backend.billing.api.http.dto.charge.UpdateMemberChargeFinalAmountRequest;
import com.jeepclub.backend.billing.core.application.result.charge.MemberChargeCycleResult;
import com.jeepclub.backend.billing.core.application.service.AdminBillingCommandService;
import com.jeepclub.backend.billing.core.application.service.membercharge.AdminMemberChargeService;
import com.jeepclub.backend.billing.core.domain.enums.charge.MemberChargeStatus;
import com.jeepclub.backend.platform.openapi.security.RequiredPermission;
import com.jeepclub.backend.platform.web.pagination.PageResponse;
import com.jeepclub.backend.platform.web.exception.ApiErrorResponse;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.responses.ApiResponses;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Positive;
import com.jeepclub.backend.memberships.api.module.MemberRegistrationQuery;
import java.util.List;
import java.util.Map;
import lombok.RequiredArgsConstructor;
import org.springdoc.core.annotations.ParameterObject;
import org.springframework.data.domain.Pageable;
import org.springframework.http.ResponseEntity;
import org.springframework.http.MediaType;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequiredArgsConstructor
@Validated
@Tag(
        name = "Billing - Member Charges",
        description = "Endpoints para consulta e gerenciamento de cobranças de membros."
)
@ApiResponses({
        @ApiResponse(responseCode = "401", description = "Usuário não autenticado.", content = @Content(mediaType = MediaType.APPLICATION_PROBLEM_JSON_VALUE, schema = @Schema(implementation = ApiErrorResponse.class))),
        @ApiResponse(responseCode = "403", description = "Permissão administrativa ausente.", content = @Content(mediaType = MediaType.APPLICATION_PROBLEM_JSON_VALUE, schema = @Schema(implementation = ApiErrorResponse.class)))
})
public class AdminMemberChargeController {

    private final AdminBillingCommandService commands;
    private final AdminMemberChargeService adminMemberChargeService;
    private final BillingUserNamePresentation userNames;
    private final MemberRegistrationQuery members;

    @GetMapping("/billing/member-charges")
    @PreAuthorize("hasAuthority('BILLING_MEMBER_CHARGE_READ')")
    @RequiredPermission("BILLING_MEMBER_CHARGE_READ")
    @ApiResponse(responseCode = "400", description = "Filtro userId inválido conforme Bean Validation.", content = @Content(mediaType = MediaType.APPLICATION_PROBLEM_JSON_VALUE, schema = @Schema(implementation = ApiErrorResponse.class)))
    @Operation(
            summary = "Listar cobranças de membros",
            description = "Lista cobranças com filtros opcionais por userId e status persistido. Usa page zero-based, size 20 por padrão e limite global de 50.",
            responses = @ApiResponse(responseCode = "200", description = "Página de cobranças retornada.", content = @Content(mediaType = MediaType.APPLICATION_JSON_VALUE, schema = @Schema(implementation = BillingPageSchemas.MemberCharges.class)))
    )
    public ResponseEntity<PageResponse<MemberChargeSummaryResponse>> findAll(
            @RequestParam(required = false) @Positive(message = "ID do usuário deve ser maior que zero.") Long userId,
            @RequestParam(required = false) MemberChargeStatus status,
            @ParameterObject Pageable pageable
    ) {
        var results = adminMemberChargeService.findAll(
                userId,
                status,
                pageable
        );

        var ids = results.getContent().stream().map(result -> result.charge().userId()).distinct().toList();
        var names = userNames.names(ids);
        var numbers = membershipNumbers(ids);
        return ResponseEntity.ok(PageResponse.from(results.map(result -> MemberChargeSummaryResponse.from(
                result.charge(), names.get(result.charge().userId()), numbers.get(result.charge().userId()),
                result.chargeDefinitionNameSnapshot(), result.chargeCycleCode()))));
    }

    @GetMapping("/billing/member-charges/{memberChargeId}")
    @PreAuthorize("hasAuthority('BILLING_MEMBER_CHARGE_READ')")
    @RequiredPermission("BILLING_MEMBER_CHARGE_READ")
    @ApiResponse(responseCode = "404", description = "Cobrança não encontrada.", content = @Content(mediaType = MediaType.APPLICATION_PROBLEM_JSON_VALUE, schema = @Schema(implementation = ApiErrorResponse.class)))
    @Operation(
            summary = "Buscar cobrança de membro por ID",
            description = "Consulta os dados completos de uma cobrança de membro específica, incluindo o status calculado na data atual.",
            responses = @ApiResponse(responseCode = "200", description = "Cobrança retornada.", content = @Content(mediaType = MediaType.APPLICATION_JSON_VALUE, schema = @Schema(implementation = MemberChargeResponse.class)))
    )
    public ResponseEntity<MemberChargeResponse> findById(
            @PathVariable @Positive(message = "ID da cobrança deve ser maior que zero.") Long memberChargeId
    ) {
        return ResponseEntity.ok(present(adminMemberChargeService.findById(memberChargeId)));
    }

    private MemberChargeResponse present(MemberChargeCycleResult result) {
        var userId = result.charge().userId();
        var ids = List.of(userId);
        return MemberChargeResponse.from(result.charge(), userNames.names(ids).get(userId),
                membershipNumbers(ids).get(userId), result.chargeDefinitionNameSnapshot(), result.chargeCycleCode());
    }

    private Map<Long, String> membershipNumbers(List<Long> ids) {
        return ids.isEmpty() || !BillingUserNamePresentation.hasAuthority("MEMBERSHIP_MEMBER_READ")
                ? Map.of() : BillingUserNamePresentation.lookup(() -> members.currentNumbers(ids));
    }

    @PatchMapping("/billing/member-charges/{memberChargeId}/final-amount")
    @PreAuthorize("hasAuthority('BILLING_MEMBER_CHARGE_UPDATE')")
    @RequiredPermission("BILLING_MEMBER_CHARGE_UPDATE")
    @ApiResponse(responseCode = "400", description = "Valor final inválido.", content = @Content(mediaType = MediaType.APPLICATION_PROBLEM_JSON_VALUE, schema = @Schema(implementation = ApiErrorResponse.class)))
    @ApiResponse(responseCode = "404", description = "Cobrança não encontrada.", content = @Content(mediaType = MediaType.APPLICATION_PROBLEM_JSON_VALUE, schema = @Schema(implementation = ApiErrorResponse.class)))
    @ApiResponse(responseCode = "409", description = "Estado da cobrança ou pagamento pendente impede a alteração.", content = @Content(mediaType = MediaType.APPLICATION_PROBLEM_JSON_VALUE, schema = @Schema(implementation = ApiErrorResponse.class)))
    @Operation(
            summary = "Atualizar valor final da cobrança",
            description = "Atualiza o valor final de uma cobrança pendente, desde que não esteja expirada e não possua pagamento pendente de validação."
    )
    public ResponseEntity<MemberChargeResponse> updateFinalAmount(
            @PathVariable @Positive(message = "ID da cobrança deve ser maior que zero.") Long memberChargeId,
            @Valid @RequestBody UpdateMemberChargeFinalAmountRequest request
    ) {
        var response = commands.execute(
                () -> adminMemberChargeService.updateFinalAmount(memberChargeId, request.finalAmount()),
                result -> present(adminMemberChargeService.present(result)));
        return ResponseEntity.ok(response);
    }

    @PatchMapping("/billing/member-charges/{memberChargeId}/cancel")
    @PreAuthorize("hasAuthority('BILLING_MEMBER_CHARGE_CANCEL')")
    @RequiredPermission("BILLING_MEMBER_CHARGE_CANCEL")
    @ApiResponse(responseCode = "404", description = "Cobrança não encontrada.", content = @Content(mediaType = MediaType.APPLICATION_PROBLEM_JSON_VALUE, schema = @Schema(implementation = ApiErrorResponse.class)))
    @ApiResponse(responseCode = "409", description = "Cobrança paga ou já cancelada.", content = @Content(mediaType = MediaType.APPLICATION_PROBLEM_JSON_VALUE, schema = @Schema(implementation = ApiErrorResponse.class)))
    @Operation(
            summary = "Cancelar cobrança de membro",
            description = "Cancela uma cobrança de membro que ainda não foi paga."
    )
    public ResponseEntity<MemberChargeResponse> cancel(
            @PathVariable @Positive(message = "ID da cobrança deve ser maior que zero.") Long memberChargeId
    ) {
        var response = commands.execute(() -> adminMemberChargeService.cancel(memberChargeId),
                result -> present(adminMemberChargeService.present(result)));
        return ResponseEntity.ok(response);
    }
}
