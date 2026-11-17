package com.jeepclub.backend.dependents.api.http.controller.admin;
import com.jeepclub.backend.dependents.core.application.service.export.DependentExportService;
import com.jeepclub.backend.shared.export.*;
import com.jeepclub.backend.platform.export.*;
import com.jeepclub.backend.platform.openapi.security.RequiredPermission;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.constraints.Positive;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;

import java.util.List;

@RestController @RequiredArgsConstructor @Tag(name="Dependentes - Exportações")
public class AdminDependentExportController {
    private final DependentExportService service;
    @GetMapping(value="/admin/dependents/export", produces={"text/csv", "application/pdf"})
    @PreAuthorize("hasAuthority('DEPENDENTS_DEPENDENT_EXPORT')") @RequiredPermission("DEPENDENTS_DEPENDENT_EXPORT")
    @Operation(
            summary="Exportar dependentes",
            description="Exporta todo o conjunto operacional correspondente aos filtros, sem paginação HTTP. Sem filtros, exporta todos. Suporta seleção unitária (id, userId) ou múltipla repetível (ids, userIds, máximo 200 por lista, deduplicados), com OR dentro de cada lista e AND entre grupos de filtros. Combinar id+ids ou userId+userIds retorna 400. Arquivo attachment sem dados pessoais no nome. Datas em America/Sao_Paulo. Histórico separado do operacional."
    )
    @ExportResponse
    public ResponseEntity<byte[]> export(
            @RequestParam(defaultValue="CSV") ExportFormat format,
            @Parameter(description="Identificador único do dependente (compatibilidade escalar; não combinar com ids).")
            @RequestParam(required=false) @Positive Long id,
            @Parameter(description="Identificadores de dependentes para seleção múltipla (repetível: ids=1&ids=2, máximo 200; não combinar com id).")
            @RequestParam(required=false) List<@Positive Long> ids,
            @Parameter(description="Identificador único do titular (compatibilidade escalar; não combinar com userIds).")
            @RequestParam(required=false) @Positive Long userId,
            @Parameter(description="Identificadores de titulares para seleção múltipla (repetível: userIds=1&userIds=2, máximo 200; não combinar com userId).")
            @RequestParam(required=false) List<@Positive Long> userIds,
            @Parameter(description="Filtro por nome do dependente (substring literal case-insensitive).")
            @RequestParam(required=false) String name,
            @Parameter(description="Filtro por situação operacional (ACTIVE, INACTIVE, ARCHIVED).")
            @RequestParam(required=false) String status
    ) {
        return ExportDownload.response(service.export(format, id, ids, userId, userIds, name, status, null, null));
    }
}
