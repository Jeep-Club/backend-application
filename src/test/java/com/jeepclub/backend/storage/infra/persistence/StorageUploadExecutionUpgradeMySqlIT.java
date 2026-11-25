package com.jeepclub.backend.storage.infra.persistence;

import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.Test;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.mysql.MySQLContainer;

import java.sql.DriverManager;

import static org.assertj.core.api.Assertions.assertThat;

@Testcontainers
class StorageUploadExecutionUpgradeMySqlIT {
    @Container static final MySQLContainer MYSQL = new MySQLContainer("mysql:8.0.46");

    @Test void v27PreservesExistingPermitAndItsIdempotencyBinding() throws Exception {
        var flyway = Flyway.configure().dataSource(MYSQL.getJdbcUrl(), MYSQL.getUsername(), MYSQL.getPassword());
        flyway.target("26").load().migrate();
        try (var connection = DriverManager.getConnection(MYSQL.getJdbcUrl(), MYSQL.getUsername(), MYSQL.getPassword());
             var sql = connection.createStatement()) {
            sql.executeUpdate("""
                    insert into storage_upload_permits
                    (id,actor_user_id,accounted_bytes,charged,completed,catalog_asset_id,created_at,
                     request_scope_hash,request_key_hash,request_fingerprint)
                    values ('legacy',41,1200,true,true,777,'2026-01-01 00:00:00',
                            repeat('a',64),repeat('b',64),repeat('c',64))
                    """);
            flyway.target("27").load().migrate();
            try (var rows = sql.executeQuery("""
                    select actor_user_id,accounted_bytes,catalog_asset_id,request_key_hash,execution_id
                    from storage_upload_permits where id='legacy'
                    """)) {
                assertThat(rows.next()).isTrue();
                assertThat(rows.getLong(1)).isEqualTo(41);
                assertThat(rows.getLong(2)).isEqualTo(1200);
                assertThat(rows.getLong(3)).isEqualTo(777);
                assertThat(rows.getString(4)).isEqualTo("b".repeat(64));
                assertThat(rows.getString(5)).isNull();
                assertThat(rows.next()).isFalse();
            }
        }
    }
}
