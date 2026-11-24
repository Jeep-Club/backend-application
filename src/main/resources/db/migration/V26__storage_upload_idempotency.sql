ALTER TABLE storage_upload_permits
    ADD COLUMN request_scope_hash VARCHAR(64) NULL,
    ADD COLUMN request_key_hash VARCHAR(64) NULL,
    ADD COLUMN request_fingerprint VARCHAR(64) NULL,
    ADD CONSTRAINT uq_storage_upload_request UNIQUE (actor_user_id, request_scope_hash, request_key_hash),
    ADD CONSTRAINT ck_storage_upload_request_complete CHECK (
        (request_scope_hash IS NULL AND request_key_hash IS NULL AND request_fingerprint IS NULL)
        OR (request_scope_hash IS NOT NULL AND request_key_hash IS NOT NULL AND request_fingerprint IS NOT NULL)
    );
