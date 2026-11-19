package com.jeepclub.backend.vehicles.api.http.controller.admin;

import com.jeepclub.backend.platform.export.ExportDownload;
import com.jeepclub.backend.platform.export.ExportResponse;
import com.jeepclub.backend.platform.openapi.security.RequiredPermission;
import com.jeepclub.backend.shared.export.ExportFormat;
import com.jeepclub.backend.vehicles.core.application.service.export.VehicleExportService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.constraints.Positive;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

@RestController
@RequiredArgsConstructor
@Tag(name = "Veículos - Exportações")
public class AdminVehicleExportController {

    private final VehicleExportService service;

    @GetMapping(value = "/vehicles/admin/export", produces = {"text/csv", "application/pdf"})
    @PreAuthorize("hasAuthority('VEHICLES_VEHICLE_EXPORT')")
    @RequiredPermission("VEHICLES_VEHICLE_EXPORT")
    @Operation(
            summary = "Exportar veículos",
            description = "Exporta o conjunto operacional ACTIVE correspondente aos filtros, sem paginação HTTP. Sem filtros, exporta todos os veículos ativos. Suporta seleção unitária (id, ownerId) ou múltipla repetível (vehicleIds, ownerIds, máximo 200 por lista, deduplicados) e filtro booleano opcional towing (true para apenas veículos com reboque, false para apenas sem reboque, omitido para ambos). Aplica OR dentro de cada lista e AND entre famílias de filtros. Combinar id+vehicleIds ou ownerId+ownerIds retorna 400. Arquivo attachment sem dados pessoais no nome. Datas em America/Sao_Paulo. Histórico separado do operacional."
    )
    @ExportResponse
    public ResponseEntity<byte[]> export(
            @RequestParam(defaultValue = "CSV") ExportFormat format,
            @Parameter(description = "Identificador único do veículo (compatibilidade escalar; não combinar com vehicleIds).")
            @RequestParam(required = false) @Positive Long id,
            @Parameter(description = "Identificadores de veículos para seleção múltipla (repetível: vehicleIds=1&vehicleIds=2, máximo 200; não combinar com id).")
            @RequestParam(required = false) List<@Positive Long> vehicleIds,
            @Parameter(description = "Identificador único do proprietário (compatibilidade escalar; não combinar com ownerIds).")
            @RequestParam(required = false) @Positive Long ownerId,
            @Parameter(description = "Identificadores de proprietários para seleção múltipla (repetível: ownerIds=1&ownerIds=2, máximo 200; não combinar com ownerId).")
            @RequestParam(required = false) List<@Positive Long> ownerIds,
            @Parameter(description = "Filtro opcional por capacidade de reboque (true = possui reboque, false = não possui reboque, omitido = ambos).")
            @RequestParam(required = false) Boolean towing
    ) {
        return ExportDownload.response(service.export(format, id, vehicleIds, ownerId, ownerIds, towing, null, null, null, null));
    }
}
