package com.jeepclub.backend.dependents.infra.persistence.query;
import com.jeepclub.backend.dependents.core.repository.DependentExportQuery;
import com.jeepclub.backend.shared.export.*;
import jakarta.persistence.EntityManager;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Repository;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

@Repository @RequiredArgsConstructor
public class DependentExportJpaQuery implements DependentExportQuery {
    private final EntityManager em;

    @Override
    public long countByIds(List<Long> ids) {
        if (ids == null || ids.isEmpty()) {
            return 0L;
        }
        return em.createQuery("select count(e.id) from DependentEntity e where e.id in :ids", Long.class)
                .setParameter("ids", ids)
                .getSingleResult();
    }

    @Override
    public List<Entry> read(List<Long> ids, List<Long> ownerIds, String name, String status, java.time.Instant from, java.time.Instant to, int offset) {
        String normalizedName = name == null || name.isBlank() ? null : literal(name.trim());
        List<String> clauses = new ArrayList<>();
        if (ids != null && !ids.isEmpty()) {
            clauses.add("e.id in :ids");
        }
        if (ownerIds != null && !ownerIds.isEmpty()) {
            clauses.add("e.userId in :owners");
        }
        if (normalizedName != null) {
            clauses.add("lower(e.name) like :name escape '!'");
        }
        if (status != null) {
            clauses.add("cast(e.status as string) = :status");
        }
        String where = clauses.isEmpty() ? "" : " where " + String.join(" and ", clauses);
        var query = em.createQuery(
                "select e.id, e.name, e.cpf, e.birthDate, e.relationshipType, e.phoneNumber, e.userId, e.status, e.createdAt, e.updatedAt, e.userId from DependentEntity e"
                        + where + " order by e.id",
                Object[].class
        );
        if (ids != null && !ids.isEmpty()) {
            query.setParameter("ids", ids);
        }
        if (ownerIds != null && !ownerIds.isEmpty()) {
            query.setParameter("owners", ownerIds);
        }
        if (normalizedName != null) {
            query.setParameter("name", normalizedName);
        }
        if (status != null) {
            query.setParameter("status", status);
        }
        return query.setFirstResult(offset)
                .setMaxResults(ExportPages.CHUNK)
                .getResultList()
                .stream()
                .map(t -> new Entry((Long) t[10], ExportRow.of(t[0], t[1], t[2], t[3], t[4], t[5], t[6], t[7], t[8], t[9])))
                .toList();
    }

    private static String literal(String value) {
        return "%" + value.toLowerCase(Locale.ROOT)
                .replace("!", "!!")
                .replace("%", "!%")
                .replace("_", "!_") + "%";
    }
}
