package com.jeepclub.backend.vehicles.core.application.service.export;

import com.jeepclub.backend.iam.identity.api.module.UserQuery;
import com.jeepclub.backend.shared.export.ExportDocument;
import com.jeepclub.backend.shared.export.ExportException;
import com.jeepclub.backend.shared.export.ExportFile;
import com.jeepclub.backend.shared.export.ExportFormat;
import com.jeepclub.backend.shared.export.ExportPages;
import com.jeepclub.backend.shared.export.ExportRenderer;
import com.jeepclub.backend.shared.export.ExportRow;
import com.jeepclub.backend.shared.export.ExportValues;
import com.jeepclub.backend.vehicles.core.repository.VehicleExportQuery;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Isolation;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Objects;
import java.util.stream.Collectors;

@Service
@RequiredArgsConstructor
public class VehicleExportService {

    public static final int MAX_IDS = 200;
    private static final List<String> HEADERS = List.of(
            "ID", "Apelido", "Possui foto", "Placa", "RENAVAM", "Marca", "Modelo",
            "Ano de fabricação", "Ano do modelo", "Cor", "Capacidade de passageiros",
            "Combustível", "Cilindrada", "Situação", "Possui reboque",
            "ID do proprietário", "Criado em", "Atualizado em",
            "Nome do titular", "CPF do titular"
    );

    private final VehicleExportQuery query;
    private final ExportRenderer renderer;
    private final UserQuery users;

    @Transactional(readOnly = true, isolation = Isolation.REPEATABLE_READ)
    public ExportFile export(ExportFormat format, Long id, Long ownerId, String name, String status, Instant from, Instant to) {
        return export(format, id, null, ownerId, null, null, name, status, from, to);
    }

    @Transactional(readOnly = true, isolation = Isolation.REPEATABLE_READ)
    public ExportFile export(
            ExportFormat format,
            Long id,
            List<Long> vehicleIds,
            Long ownerId,
            List<Long> ownerIds,
            Boolean towing,
            String name,
            String status,
            Instant from,
            Instant to
    ) {
        if (id != null && vehicleIds != null || ownerId != null && ownerIds != null) {
            throw new ExportException(ExportException.Reason.INVALID_FILTER);
        }
        if (id != null && id <= 0 || ownerId != null && ownerId <= 0 || from != null && to != null && from.isAfter(to)) {
            throw new ExportException(ExportException.Reason.INVALID_FILTER);
        }
        List<Long> effectiveVehicleIds = normalizeIds(id, vehicleIds);
        List<Long> effectiveOwnerIds = normalizeIds(ownerId, ownerIds);
        String normalizedName = name == null || name.isBlank() ? null : name.trim();

        validateTargetsExist(effectiveVehicleIds, effectiveOwnerIds);
        List<String> filters = buildFilterLabels(id, effectiveVehicleIds, ownerId, effectiveOwnerIds, towing, normalizedName, status, from, to);
        Long singleOwnerId = effectiveOwnerIds != null && effectiveOwnerIds.size() == 1 ? effectiveOwnerIds.get(0) : null;

        return renderer.render(new ExportDocument("veiculos", "Veículos", HEADERS, filters, sink -> {
            for (int offset = 0; ; offset += ExportPages.CHUNK) {
                var batch = query.read(effectiveVehicleIds, effectiveOwnerIds, towing, normalizedName, status, from, to, offset);
                var identities = users.findByIds(
                                batch.stream()
                                        .map(VehicleExportQuery.Entry::ownerId)
                                        .filter(Objects::nonNull)
                                        .distinct()
                                        .toList()
                        )
                        .stream()
                        .collect(Collectors.toMap(u -> u.id(), u -> u));

                for (var entry : batch) {
                    var owner = identities.get(entry.ownerId());
                    var cells = new ArrayList<>(entry.row().cells());
                    cells.add(owner == null ? "Cadastro indisponível" : owner.name());
                    cells.add(owner == null ? "" : ExportValues.text(owner.cpf()));
                    var row = new ExportRow(null, cells);
                    sink.accept(singleOwnerId == null
                            ? row
                            : row.grouped("Titular: " + (owner == null ? singleOwnerId : owner.name()) + " | ID: " + singleOwnerId + " | CPF: " + (owner == null ? "" : owner.cpf()), 18, 19, 15));
                }
                if (batch.size() < ExportPages.CHUNK) {
                    break;
                }
            }
        }), format);
    }

    private void validateTargetsExist(List<Long> effectiveVehicleIds, List<Long> effectiveOwnerIds) {
        if (effectiveOwnerIds != null) {
            if (effectiveOwnerIds.size() == 1) {
                if (!users.existsById(effectiveOwnerIds.get(0))) {
                    throw new ExportException(ExportException.Reason.NOT_FOUND);
                }
            } else if (users.findByIds(effectiveOwnerIds).size() != effectiveOwnerIds.size()) {
                throw new ExportException(ExportException.Reason.NOT_FOUND);
            }
        }
        if (effectiveVehicleIds != null && query.countByIds(effectiveVehicleIds) != effectiveVehicleIds.size()) {
            throw new ExportException(ExportException.Reason.NOT_FOUND);
        }
    }

    private static List<String> buildFilterLabels(
            Long id,
            List<Long> effectiveVehicleIds,
            Long ownerId,
            List<Long> effectiveOwnerIds,
            Boolean towing,
            String normalizedName,
            String status,
            Instant from,
            Instant to
    ) {
        var filters = new ArrayList<String>();
        if (id != null) {
            filters.add("ID: " + id);
        } else if (effectiveVehicleIds != null) {
            filters.add("IDs: " + effectiveVehicleIds.stream().map(String::valueOf).collect(Collectors.joining(", ")));
        }
        if (ownerId != null) {
            filters.add("Titular: " + ownerId);
        } else if (effectiveOwnerIds != null) {
            filters.add("Titulares: " + effectiveOwnerIds.stream().map(String::valueOf).collect(Collectors.joining(", ")));
        }
        if (towing != null) {
            filters.add("Possui reboque: " + (towing ? "Sim" : "Não"));
        }
        if (normalizedName != null) {
            filters.add("Nome: " + normalizedName);
        }
        if (status != null) {
            filters.add("Situação: " + status);
        }
        if (from != null) {
            filters.add("De: " + ExportValues.text(from));
        }
        if (to != null) {
            filters.add("Até: " + ExportValues.text(to));
        }
        return filters;
    }

    private static List<Long> normalizeIds(Long scalarId, List<Long> listIds) {
        if (listIds != null) {
            if (listIds.isEmpty() || listIds.size() > MAX_IDS) {
                throw new ExportException(ExportException.Reason.INVALID_FILTER);
            }
            LinkedHashSet<Long> deduplicated = new LinkedHashSet<>();
            for (Long item : listIds) {
                if (item == null || item <= 0) {
                    throw new ExportException(ExportException.Reason.INVALID_FILTER);
                }
                deduplicated.add(item);
            }
            return List.copyOf(deduplicated);
        }
        if (scalarId != null) {
            return List.of(scalarId);
        }
        return null;
    }
}
