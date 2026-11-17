package com.jeepclub.backend.storage.infra.persistence.entity;

import jakarta.persistence.*;
import lombok.Getter;
import lombok.Setter;

/** Intent only: a row does not assert that physical bytes were written. */
@Entity @Table(name="storage_upload_planned_blobs", uniqueConstraints=@UniqueConstraint(columnNames={"permit_id","position_index"}))
@Getter @Setter
public class UploadPlannedBlobEntity {
    @Id @GeneratedValue(strategy=GenerationType.IDENTITY) private Long id;
    @Column(name="permit_id",nullable=false,length=36) private String permitId;
    @Column(name="position_index",nullable=false) private int position;
    @Column(name="sha256",nullable=false,length=64) private String sha256;
    @Column(name="size_bytes",nullable=false) private long sizeBytes;
}
