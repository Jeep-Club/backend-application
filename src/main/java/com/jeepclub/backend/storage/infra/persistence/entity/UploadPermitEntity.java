package com.jeepclub.backend.storage.infra.persistence.entity;

import jakarta.persistence.*;
import lombok.Getter;
import lombok.Setter;
import java.time.Instant;

@Entity @Table(name="storage_upload_permits", uniqueConstraints=@UniqueConstraint(
        name="uq_storage_upload_request", columnNames={"actor_user_id", "request_scope_hash", "request_key_hash"}))
@Getter @Setter
public class UploadPermitEntity {
    @Id @Column(name="id",length=36) private String id;
    @Column(name="actor_user_id",nullable=false) private long actorUserId;
    @Column(name="accounted_bytes",nullable=false) private long accountedBytes;
    @Column(name="charged",nullable=false) private boolean charged;
    @Column(name="completed",nullable=false) private boolean completed;
    @Column(name="catalog_asset_id") private Long catalogAssetId;
    @Column(name="request_scope_hash", length=64) private String requestScopeHash;
    @Column(name="request_key_hash", length=64) private String requestKeyHash;
    @Column(name="request_fingerprint", length=64) private String requestFingerprint;
    @org.hibernate.annotations.JdbcTypeCode(org.hibernate.type.SqlTypes.TIMESTAMP)
    @Column(name="created_at",nullable=false) private Instant createdAt;
}
