package com.jeepclub.backend.storage.infra.persistence.entity;

import jakarta.persistence.*;
import lombok.Getter;
import lombok.Setter;

/** Intent only: a row does not assert that physical bytes were written. */
@Entity @Table(name="storage_upload_planned_blobs",
    uniqueConstraints=@UniqueConstraint(name="uq_storage_upload_plan_position",columnNames={"permit_id","position_index"}),
    indexes=@Index(name="ix_storage_upload_plan_sha256",columnList="sha256"))
@org.hibernate.annotations.Check(name="ck_storage_upload_plan",constraints="position_index >= 0 AND size_bytes > 0")
@Getter @Setter
public class UploadPlannedBlobEntity {
    @Id @GeneratedValue(strategy=GenerationType.IDENTITY) private Long id;
    @Column(name="permit_id",nullable=false,length=36) private String permitId;
    @ManyToOne(fetch=FetchType.LAZY,optional=false)
    @JoinColumn(name="permit_id",nullable=false,insertable=false,updatable=false,
        foreignKey=@ForeignKey(name="fk_storage_upload_plan_permit"))
    private UploadPermitEntity permit;
    @Column(name="position_index",nullable=false) private int position;
    @Column(name="sha256",nullable=false,length=64) private String sha256;
    @Column(name="size_bytes",nullable=false) private long sizeBytes;
}
