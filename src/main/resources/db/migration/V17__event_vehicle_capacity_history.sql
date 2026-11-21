CREATE TABLE event_vehicle_capacity_history (
    id BIGINT NOT NULL AUTO_INCREMENT PRIMARY KEY,
    event_id BIGINT NOT NULL,
    registration_id BIGINT NOT NULL,
    vehicle_id BIGINT NOT NULL,
    previous_capacity INT NULL,
    capacity INT NOT NULL,
    occurred_at DATETIME(6) NOT NULL,
    INDEX idx_event_vehicle_capacity_history (event_id, vehicle_id, id),
    CONSTRAINT ck_event_vehicle_capacity_positive CHECK (capacity > 0 AND (previous_capacity IS NULL OR previous_capacity > 0))
) ENGINE=InnoDB;
