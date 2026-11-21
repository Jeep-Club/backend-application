package com.jeepclub.backend.publications.infra.persistence.entity;

import jakarta.persistence.*;
import lombok.*;
import java.time.Instant;

@Entity @Table(name="event_vehicle_capacity_history", indexes=@Index(name="idx_event_vehicle_capacity_history",columnList="event_id,vehicle_id,id"))
@org.hibernate.annotations.Check(name="ck_event_vehicle_capacity_positive",
    constraints="capacity > 0 AND (previous_capacity IS NULL OR previous_capacity > 0)")
@Getter @Setter @NoArgsConstructor
public class EventVehicleCapacityHistoryEntity {
    @Id @GeneratedValue(strategy=GenerationType.IDENTITY) private Long id;
    @Column(name="event_id",nullable=false,updatable=false) private Long eventId;
    @Column(name="registration_id",nullable=false,updatable=false) private Long registrationId;
    @Column(name="vehicle_id",nullable=false,updatable=false) private Long vehicleId;
    @Column(name="previous_capacity",updatable=false) private Integer previousCapacity;
    @Column(nullable=false,updatable=false) private int capacity;
    @Column(name="occurred_at",nullable=false,updatable=false) private Instant occurredAt;
}
