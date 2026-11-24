package com.jeepclub.backend.billing.api.http.controller.admin;
import com.jeepclub.backend.billing.core.application.service.history.ChargeDefinitionDeletionHistoryService;
import com.jeepclub.backend.billing.api.http.dto.definition.ChargeDefinitionDeletionHistoryResponse;
import java.util.List;
import com.jeepclub.backend.platform.openapi.security.RequiredPermission;
import com.jeepclub.backend.platform.security.principal.UserPrincipal;
import org.springframework.web.bind.annotation.*;
import org.springframework.http.*;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import jakarta.validation.constraints.Positive;
@RestController @org.springframework.validation.annotation.Validated @lombok.RequiredArgsConstructor
@io.swagger.v3.oas.annotations.tags.Tag(name="ChargeDefinition - Deletion history")
@io.swagger.v3.oas.annotations.responses.ApiResponses({
    @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode="401",description="Usuário não autenticado.",content=@io.swagger.v3.oas.annotations.media.Content(mediaType="application/problem+json",schema=@io.swagger.v3.oas.annotations.media.Schema(implementation=com.jeepclub.backend.platform.web.exception.ApiErrorResponse.class))),
    @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode="403",description="Permissão de histórico ausente.",content=@io.swagger.v3.oas.annotations.media.Content(mediaType="application/problem+json",schema=@io.swagger.v3.oas.annotations.media.Schema(implementation=com.jeepclub.backend.platform.web.exception.ApiErrorResponse.class)))
})
@RequestMapping("/billing/charge-definitions")
public class ChargeDefinitionDeletionHistoryController {
    private final ChargeDefinitionDeletionHistoryService service;
    private final BillingUserNamePresentation users;

    @GetMapping(value="/{id}/deletion-history",produces=MediaType.APPLICATION_JSON_VALUE)
    @PreAuthorize("hasAuthority('BILLING_CHARGE_DEFINITION_HISTORY_READ')") @RequiredPermission("BILLING_CHARGE_DEFINITION_HISTORY_READ")
    @io.swagger.v3.oas.annotations.Operation(summary="Consultar histórico de exclusão de ChargeDefinition",description="Configuração monetária preservada da definição excluída; não altera fatos financeiros. Consulta administrativa auditada.")
    @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode="200",description="Histórico de exclusão preservado.",content=@io.swagger.v3.oas.annotations.media.Content(mediaType="application/json",schema=@io.swagger.v3.oas.annotations.media.Schema(implementation=ChargeDefinitionDeletionHistoryResponse.class)))
    @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode="404",description="Histórico inexistente.",content=@io.swagger.v3.oas.annotations.media.Content(mediaType="application/problem+json",schema=@io.swagger.v3.oas.annotations.media.Schema(implementation=com.jeepclub.backend.platform.web.exception.ApiErrorResponse.class)))
    public ResponseEntity<ChargeDefinitionDeletionHistoryResponse> find(@PathVariable @Positive Long id, @AuthenticationPrincipal UserPrincipal principal) {
        var snapshot = service.find(id, principal.getUserId());
        var actorId = snapshot.deletedByUserId();
        String actorName = actorId == null ? null : users.names(List.of(actorId)).get(actorId);
        return ResponseEntity.ok().cacheControl(CacheControl.noStore())
                .body(ChargeDefinitionDeletionHistoryResponse.from(snapshot, actorName));
    }
}
