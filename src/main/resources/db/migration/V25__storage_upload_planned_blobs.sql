CREATE TABLE storage_upload_planned_blobs (
 id BIGINT NOT NULL AUTO_INCREMENT PRIMARY KEY,
 permit_id VARCHAR(36) NOT NULL,
 position_index INT NOT NULL,
 sha256 VARCHAR(64) NOT NULL,
 size_bytes BIGINT NOT NULL,
 CONSTRAINT uq_storage_upload_plan_position UNIQUE (permit_id, position_index),
 CONSTRAINT fk_storage_upload_plan_permit FOREIGN KEY (permit_id) REFERENCES storage_upload_permits(id),
 CONSTRAINT ck_storage_upload_plan CHECK (position_index >= 0 AND size_bytes > 0),
 INDEX ix_storage_upload_plan_sha256 (sha256)
) ENGINE=InnoDB;

ALTER TABLE storage_upload_permits ADD COLUMN catalog_asset_id BIGINT NULL;
