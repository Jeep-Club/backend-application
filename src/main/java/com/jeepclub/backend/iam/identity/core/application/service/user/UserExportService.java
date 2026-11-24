package com.jeepclub.backend.iam.identity.core.application.service.user;
import com.jeepclub.backend.iam.identity.core.application.query.user.*;
import com.jeepclub.backend.iam.identity.core.repository.AdminUserQueryRepository;
import com.jeepclub.backend.shared.export.*;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.*;
import org.springframework.data.domain.*;
import lombok.RequiredArgsConstructor;
import java.util.*;
import java.util.stream.Collectors;

@Service @RequiredArgsConstructor
public class UserExportService {
    public static final int MAX_IDS = 200;

    private final AdminUserQueryRepository query;
    private final ExportRenderer renderer;
    private final com.jeepclub.backend.iam.identity.api.module.spi.UserMembershipNumberProjection membershipNumbers;

    @Transactional(readOnly=true, isolation=Isolation.REPEATABLE_READ)
    public ExportFile export(AdminUserFilter filter, ExportFormat format) {
        return export(filter, null, format, UserExportLayout.MEMBER);
    }

    @Transactional(readOnly=true, isolation=Isolation.REPEATABLE_READ)
    public ExportFile export(AdminUserFilter filter, ExportFormat format, UserExportLayout layout) {
        return export(filter, null, format, layout);
    }

    @Transactional(readOnly=true, isolation=Isolation.REPEATABLE_READ)
    public ExportFile export(AdminUserFilter filter, List<Long> ids, ExportFormat format, UserExportLayout layout) {
        AdminUserFilter baseFilter = filter == null
                ? new AdminUserFilter(null, null, null, null, null, null, null, null, null, null, null, null, null)
                : filter;
        if (baseFilter.id() != null && (ids != null || baseFilter.ids() != null)) {
            throw new ExportException(ExportException.Reason.INVALID_FILTER);
        }
        if (baseFilter.id() != null && baseFilter.id() <= 0) {
            throw new ExportException(ExportException.Reason.INVALID_FILTER);
        }
        List<Long> effectiveIds = normalizeIds(baseFilter.id(), ids != null ? ids : baseFilter.ids());
        if (effectiveIds != null) {
            if (effectiveIds.size() == 1) {
                if (query.findById(effectiveIds.get(0)).isEmpty()) {
                    throw new ExportException(ExportException.Reason.NOT_FOUND);
                }
            } else if (query.countByIds(effectiveIds) != effectiveIds.size()) {
                throw new ExportException(ExportException.Reason.NOT_FOUND);
            }
        }
        var filters = buildFilterLabels(baseFilter, effectiveIds);
        AdminUserFilter effectiveFilter = effectiveIds != null && baseFilter.id() == null
                ? baseFilter.withIds(effectiveIds)
                : baseFilter;
        var headers = layout == UserExportLayout.TECHNICAL
                ? List.of("ID","Matrícula","Nome","Data de nascimento","E-mail","CPF","RG","Telefone","Possui foto","Situação","Criado em","Desativado em","Atualizado em")
                : List.of("Matrícula","Nome","Data de nascimento","E-mail","CPF","RG","Telefone","Possui foto","Situação","Criado em","Desativado em","Atualizado em","ID técnico");
        return renderer.render(new ExportDocument("usuarios","Usuários",headers,filters,
            sink -> ExportPages.read(offset -> {
                var batch = query.findAll(effectiveFilter, EnumSet.allOf(AdminUserField.class), PageRequest.of(offset / ExportPages.CHUNK, ExportPages.CHUNK, Sort.by("id"))).getContent();
                var numbers = batch.isEmpty() ? Map.<Long, String>of() : membershipNumbers.currentNumbers(batch.stream().map(u -> u.id()).toList());
                return batch.stream().map(u -> layout == UserExportLayout.TECHNICAL
                        ? ExportRow.of(u.id(),numbers.get(u.id()),u.name(),u.birthDate(),u.email(),u.cpf(),u.rg(),u.phoneNumber(),u.profilePhotoStorageKey()!=null,u.status(),u.createdAt(),u.disabledAt(),u.updatedAt())
                        : ExportRow.of(numbers.get(u.id()),u.name(),u.birthDate(),u.email(),u.cpf(),u.rg(),u.phoneNumber(),u.profilePhotoStorageKey()!=null,u.status(),u.createdAt(),u.disabledAt(),u.updatedAt(),u.id())).toList();
            },sink::accept)),format);
    }

    private static List<String> buildFilterLabels(AdminUserFilter filter, List<Long> effectiveIds) {
        var filters=new ArrayList<String>();
        if(filter.id()!=null) filters.add("ID técnico: "+filter.id());
        else if(effectiveIds!=null) filters.add("IDs técnicos: "+effectiveIds.stream().map(String::valueOf).collect(Collectors.joining(", ")));
        if(filter.membershipNumber()!=null) filters.add("Matrícula: "+filter.membershipNumber());
        if(filter.name()!=null) filters.add("Nome: "+filter.name());
        if(filter.birthDate()!=null) filters.add("Nascimento: "+ExportValues.text(filter.birthDate()));
        if(filter.email()!=null) filters.add("E-mail: "+filter.email());
        if(filter.cpf()!=null) filters.add("CPF: "+filter.cpf());
        if(filter.rg()!=null) filters.add("RG: "+filter.rg());
        if(filter.phoneNumber()!=null) filters.add("Telefone: "+filter.phoneNumber());
        if(filter.status()!=null) filters.add("Situação: "+filter.status());
        if(filter.createdFrom()!=null) filters.add("Criação desde: "+ExportValues.text(filter.createdFrom()));
        if(filter.createdTo()!=null) filters.add("Criação até: "+ExportValues.text(filter.createdTo()));
        if(filter.updatedFrom()!=null) filters.add("Atualização desde: "+ExportValues.text(filter.updatedFrom()));
        if(filter.updatedTo()!=null) filters.add("Atualização até: "+ExportValues.text(filter.updatedTo()));
        if(filter.query()!=null) filters.add("Busca: "+filter.query());
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
