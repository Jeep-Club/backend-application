package com.jeepclub.backend.dependents.core.repository;
import java.util.List;
import com.jeepclub.backend.shared.export.ExportRow;
public interface DependentExportQuery {
    record Entry(Long ownerId, ExportRow row) {}
    long countByIds(List<Long> ids);
    List<Entry> read(List<Long> ids, List<Long> ownerIds, String name, String status, java.time.Instant from, java.time.Instant to, int offset);
    default List<Entry> read(Long id, Long ownerId, String name, String status, java.time.Instant from, java.time.Instant to, int offset) {
        return read(id == null ? null : List.of(id), ownerId == null ? null : List.of(ownerId), name, status, from, to, offset);
    }
}
