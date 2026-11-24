package com.jeepclub.backend.publications.api.http.controller.admin;

import com.jeepclub.backend.platform.openapi.security.RequiredPermission;
import com.jeepclub.backend.platform.security.principal.UserPrincipal;
import com.jeepclub.backend.platform.web.exception.ApiErrorResponse;
import com.jeepclub.backend.platform.web.pagination.PageResponse;
import com.jeepclub.backend.publications.api.http.dto.ServicePublicationPageResponseSchema;
import com.jeepclub.backend.publications.api.http.dto.ServicePublicationResponseDTO;
import com.jeepclub.backend.publications.core.application.exception.ServiceOperationException;
import com.jeepclub.backend.publications.core.application.service.ServicePublicationService;
import com.jeepclub.backend.publications.core.domain.enums.PublicationStatus;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.responses.ApiResponses;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import org.springdoc.core.annotations.ParameterObject;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;
import org.springframework.data.web.PageableDefault;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;
import java.time.Instant;
import static com.jeepclub.backend.publications.core.application.exception.ServiceOperationException.Reason.INVALID_REQUEST;

@RestController
@RequestMapping("/admin/services")
@RequiredArgsConstructor
@Tag(name = "Publications - Service Admin", description = "Consulta e exclusão administrativa de serviços.")
@ApiResponses({
        @ApiResponse(responseCode = "400", description = "Filtro, ordenação ou paginação inválidos.", content = @Content(mediaType = MediaType.APPLICATION_PROBLEM_JSON_VALUE, schema = @Schema(implementation = ApiErrorResponse.class))),
        @ApiResponse(responseCode = "401", description = "Não autenticado.", content = @Content(mediaType = MediaType.APPLICATION_PROBLEM_JSON_VALUE, schema = @Schema(implementation = ApiErrorResponse.class))),
        @ApiResponse(responseCode = "403", description = "Permission ausente.", content = @Content(mediaType = MediaType.APPLICATION_PROBLEM_JSON_VALUE, schema = @Schema(implementation = ApiErrorResponse.class))),
        @ApiResponse(responseCode = "404", description = "Service não encontrado.", content = @Content(mediaType = MediaType.APPLICATION_PROBLEM_JSON_VALUE, schema = @Schema(implementation = ApiErrorResponse.class)))
})
public class AdminServicePublicationController {
    private final ServicePublicationService services;
    private final PublicationAdminPresentation presentation;

    @GetMapping
    @PreAuthorize("hasAuthority('PUBLICATIONS_SERVICE_ADMIN_READ')")
    @RequiredPermission("PUBLICATIONS_SERVICE_ADMIN_READ")
    @Operation(
            summary = "Listar serviços administrativamente",
            description = "Página zero-based de ServicePublication em PUBLISHED ou ARCHIVED (ServicePublication não admite DRAFT; informar status=DRAFT retorna 400). Suporta busca textual literal case-insensitive em título/conteúdo (q ou search) e intervalos inclusivos de criação (from/to) ou publicação (publishedFrom/publishedTo). Ordenação com allowlist e id como desempate determinístico. Quando o administrador também possui IDENTITY_USER_READ, authorName é resolvido em lote para autores ativos."
    )
    @ApiResponse(
            responseCode = "200",
            description = "Página de serviços retornada.",
            content = @Content(mediaType = MediaType.APPLICATION_JSON_VALUE, schema = @Schema(implementation = ServicePublicationPageResponseSchema.class))
    )
    public PageResponse<ServicePublicationResponseDTO> list(
            @Parameter(description = "Filtro por estado editorial suportado (PUBLISHED ou ARCHIVED; DRAFT é inválido).")
            @RequestParam(required = false) PublicationStatus status,
            @Parameter(description = "Busca textual literal case-insensitive em título ou conteúdo (máximo 200 caracteres).")
            @RequestParam(required = false) String q,
            @Parameter(hidden = true) @RequestParam(required = false) String search,
            @Parameter(description = "Limite inferior inclusivo de criação (createdAt).")
            @RequestParam(required = false) Instant from,
            @Parameter(description = "Limite superior inclusivo de criação (createdAt).")
            @RequestParam(required = false) Instant to,
            @Parameter(description = "Limite inferior inclusivo de publicação (publishedAt).")
            @RequestParam(required = false) Instant publishedFrom,
            @Parameter(description = "Limite superior inclusivo de publicação (publishedAt).")
            @RequestParam(required = false) Instant publishedTo,
            @Parameter(hidden = true) @RequestParam(required = false, name = "page") Integer requestedPage,
            @Parameter(hidden = true) @RequestParam(required = false, name = "size") Integer requestedSize,
            @ParameterObject @PageableDefault(size = 20, sort = {"createdAt", "id"}, direction = Sort.Direction.DESC) Pageable pageable
    ) {
        if (requestedPage != null && requestedPage < 0 || requestedSize != null && (requestedSize < 1 || requestedSize > 50)) {
            throw new ServiceOperationException(INVALID_REQUEST);
        }
        if (q != null && !q.isBlank() && search != null && !search.isBlank()) {
            throw new ServiceOperationException(INVALID_REQUEST);
        }
        String effectiveQuery = q != null && !q.isBlank() ? q : search;
        return PageResponse.from(presentation.services(
                services.findAllAdmin(status, effectiveQuery, from, to, publishedFrom, publishedTo, pageable)
        ));
    }

    @GetMapping("/{id}")
    @PreAuthorize("hasAuthority('PUBLICATIONS_SERVICE_ADMIN_READ')")
    @RequiredPermission("PUBLICATIONS_SERVICE_ADMIN_READ")
    @Operation(summary = "Consultar serviço administrativamente", description = "Pode consultar Service em qualquer estado editorial.")
    @ApiResponse(responseCode = "200", description = "Service encontrado.", content = @Content(schema = @Schema(implementation = ServicePublicationResponseDTO.class)))
    public ServicePublicationResponseDTO find(@PathVariable Long id) {
        return presentation.service(services.findAdmin(id));
    }

    @DeleteMapping("/{id}")
    @PreAuthorize("hasAuthority('PUBLICATIONS_SERVICE_ADMIN_DELETE')")
    @RequiredPermission("PUBLICATIONS_SERVICE_ADMIN_DELETE")
    @Operation(summary = "Excluir qualquer serviço", description = "Hard delete com snapshot histórico; requests e objetos globais de mídia permanecem.")
    @ApiResponse(responseCode = "204", description = "Service excluído com histórico.")
    public ResponseEntity<Void> delete(@PathVariable Long id, @AuthenticationPrincipal UserPrincipal principal) {
        services.deleteAdmin(id, principal.getUserId());
        return ResponseEntity.noContent().build();
    }
}
