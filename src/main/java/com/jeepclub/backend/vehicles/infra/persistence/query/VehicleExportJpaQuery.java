package com.jeepclub.backend.vehicles.infra.persistence.query;

import com.jeepclub.backend.shared.export.ExportPages;
import com.jeepclub.backend.shared.export.ExportRow;
import com.jeepclub.backend.vehicles.core.repository.VehicleExportQuery;
import jakarta.persistence.EntityManager;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Repository;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;

@Repository
@RequiredArgsConstructor
public class VehicleExportJpaQuery implements VehicleExportQuery {

    private final EntityManager em;

    @Override
    public long countByIds(List<Long> ids) {
        if (ids == null || ids.isEmpty()) {
            return 0L;
        }
        return em.createQuery(
                        "select count(e.id) from VehicleEntity e where e.legacyDeleted = false and cast(e.status as string) = 'ACTIVE' and e.id in :ids",
                        Long.class
                )
                .setParameter("ids", ids)
                .getSingleResult();
    }

    @Override
    public List<Entry> read(
            List<Long> vehicleIds,
            List<Long> ownerIds,
            Boolean towing,
            String name,
            String status,
            Instant from,
            Instant to,
            int offset
    ) {
        List<String> clauses = new ArrayList<>();
        clauses.add("e.legacyDeleted = false");
        clauses.add("cast(e.status as string) = 'ACTIVE'");
        if (vehicleIds != null && !vehicleIds.isEmpty()) {
            clauses.add("e.id in :ids");
        }
        if (ownerIds != null && !ownerIds.isEmpty()) {
            clauses.add("e.ownerId in :owners");
        }
        if (towing != null) {
            clauses.add("e.towing = :towing");
        }

        String where = " where " + String.join(" and ", clauses);
        var query = em.createQuery(
                "select e.id, e.nickname, case when e.images is not empty then true else false end, "
                        + "e.plate, e.renavam, e.brand, e.model, e.manufacturingYear, e.modelYear, "
                        + "e.color, e.seatingCapacity, e.fuelType, e.engineDisplacement, e.status, "
                        + "e.towing, e.ownerId, e.createdAt, e.updatedAt, e.ownerId "
                        + "from VehicleEntity e" + where + " order by e.id",
                Object[].class
        );
        if (vehicleIds != null && !vehicleIds.isEmpty()) {
            query.setParameter("ids", vehicleIds);
        }
        if (ownerIds != null && !ownerIds.isEmpty()) {
            query.setParameter("owners", ownerIds);
        }
        if (towing != null) {
            query.setParameter("towing", towing);
        }

        return query.setFirstResult(offset)
                .setMaxResults(ExportPages.CHUNK)
                .getResultList()
                .stream()
                .map(t -> new Entry(
                        (Long) t[18],
                        ExportRow.of(t[0], t[1], t[2], t[3], t[4], t[5], t[6], t[7], t[8], t[9], t[10], t[11], t[12], t[13], t[14], t[15], t[16], t[17])
                ))
                .toList();
    }
}
