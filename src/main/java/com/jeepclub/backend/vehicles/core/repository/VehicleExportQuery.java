package com.jeepclub.backend.vehicles.core.repository;

import com.jeepclub.backend.shared.export.ExportRow;

import java.time.Instant;
import java.util.List;

public interface VehicleExportQuery {

    record Entry(Long ownerId, ExportRow row) {}

    long countByIds(List<Long> ids);

    List<Entry> read(
            List<Long> vehicleIds,
            List<Long> ownerIds,
            Boolean towing,
            String name,
            String status,
            Instant from,
            Instant to,
            int offset
    );
}
