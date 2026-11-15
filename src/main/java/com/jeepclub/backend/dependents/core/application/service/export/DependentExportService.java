package com.jeepclub.backend.dependents.core.application.service.export;
import com.jeepclub.backend.dependents.core.repository.DependentExportQuery;
import com.jeepclub.backend.shared.export.*;
import com.jeepclub.backend.iam.identity.api.module.UserQuery;
import java.util.stream.Collectors;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.*;
import java.util.*;
@Service @RequiredArgsConstructor
public class DependentExportService {
    public static final int MAX_IDS = 200;
    private final DependentExportQuery query;
    private final ExportRenderer renderer;
    private final UserQuery users;

    @Transactional(readOnly=true, isolation=Isolation.REPEATABLE_READ)
    public ExportFile export(ExportFormat format, Long id, Long ownerId, String name, String status, java.time.Instant from, java.time.Instant to) {
        return export(format, id, null, ownerId, null, name, status, from, to);
    }

    @Transactional(readOnly=true, isolation=Isolation.REPEATABLE_READ)
    public ExportFile export(
            ExportFormat format,
            Long id,
            List<Long> ids,
            Long ownerId,
            List<Long> ownerIds,
            String name,
            String status,
            java.time.Instant from,
            java.time.Instant to
    ) {
        if (id != null && ids != null || ownerId != null && ownerIds != null) {
            throw new ExportException(ExportException.Reason.INVALID_FILTER);
        }
        if (id != null && id <= 0 || ownerId != null && ownerId <= 0 || from != null && to != null && from.isAfter(to)) {
            throw new ExportException(ExportException.Reason.INVALID_FILTER);
        }
        List<Long> effectiveIds = normalizeIds(id, ids);
        List<Long> effectiveOwnerIds = normalizeIds(ownerId, ownerIds);
        String normalizedName = name == null || name.isBlank() ? null : name.trim();
        if (normalizedName != null && normalizedName.length() > 150) {
            throw new ExportException(ExportException.Reason.INVALID_FILTER);
        }
        if (status != null && !List.of("ACTIVE", "INACTIVE", "ARCHIVED").contains(status)) {
            throw new ExportException(ExportException.Reason.INVALID_FILTER);
        }
        if (effectiveOwnerIds != null) {
            if (effectiveOwnerIds.size() == 1) {
                if (!users.existsById(effectiveOwnerIds.get(0))) {
                    throw new ExportException(ExportException.Reason.NOT_FOUND);
                }
            } else if (users.findByIds(effectiveOwnerIds).size() != effectiveOwnerIds.size()) {
                throw new ExportException(ExportException.Reason.NOT_FOUND);
            }
        }
        if (effectiveIds != null && query.countByIds(effectiveIds) != effectiveIds.size()) {
            throw new ExportException(ExportException.Reason.NOT_FOUND);
        }

        var filters = new ArrayList<String>();
        if (id != null) {
            filters.add("ID: " + id);
        } else if (effectiveIds != null) {
            filters.add("IDs: " + effectiveIds.stream().map(String::valueOf).collect(Collectors.joining(", ")));
        }
        if (ownerId != null) {
            filters.add("Titular: " + ownerId);
        } else if (effectiveOwnerIds != null) {
            filters.add("Titulares: " + effectiveOwnerIds.stream().map(String::valueOf).collect(Collectors.joining(", ")));
        }
        if (normalizedName != null) filters.add("Nome: " + normalizedName);
        if (status != null) filters.add("Situação: " + status);
        if (from != null) filters.add("De: " + ExportValues.text(from));
        if (to != null) filters.add("Até: " + ExportValues.text(to));

        Long singleOwnerId = effectiveOwnerIds != null && effectiveOwnerIds.size() == 1 ? effectiveOwnerIds.get(0) : null;
        return renderer.render(new ExportDocument("dependentes", "Dependentes", List.of("ID", "Nome", "CPF", "Data de nascimento", "Parentesco", "Telefone", "ID do usuário", "Situação", "Criado em", "Atualizado em", "Nome do titular", "CPF do titular"), filters, sink -> {
            for (int offset = 0; ; offset += ExportPages.CHUNK) {
                var batch = query.read(effectiveIds, effectiveOwnerIds, normalizedName, status, from, to, offset);
                var identities = users.findByIds(batch.stream().map(DependentExportQuery.Entry::ownerId).filter(Objects::nonNull).distinct().toList())
                        .stream().collect(Collectors.toMap(u -> u.id(), u -> u));
                for (var entry : batch) {
                    var u = identities.get(entry.ownerId());
                    var cells = new ArrayList<>(entry.row().cells());
                    cells.add(u == null ? "Cadastro indisponível" : u.name());
                    cells.add(u == null ? "" : ExportValues.text(u.cpf()));
                    var row = new ExportRow(null, cells);
                    sink.accept(singleOwnerId == null ? row : row.grouped("Titular: " + (u == null ? singleOwnerId : u.name()) + " | ID: " + singleOwnerId + " | CPF: " + (u == null ? "" : u.cpf()), 10, 11, 6));
                }
                if (batch.size() < ExportPages.CHUNK) break;
            }
        }), format);
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
