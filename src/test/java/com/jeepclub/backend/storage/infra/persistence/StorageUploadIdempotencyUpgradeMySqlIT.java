package com.jeepclub.backend.storage.infra.persistence;

import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.Test;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.mysql.MySQLContainer;

import java.sql.DriverManager;
import java.sql.SQLException;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@Testcontainers
class StorageUploadIdempotencyUpgradeMySqlIT {
    @Container static final MySQLContainer MYSQL = new MySQLContainer("mysql:8.0.46");

    @Test void v26PreservesPermitsAndEnforcesScopedKeys() throws Exception {
        var flyway = Flyway.configure().dataSource(MYSQL.getJdbcUrl(), MYSQL.getUsername(), MYSQL.getPassword());
        flyway.target("25").load().migrate();
        try (var connection = DriverManager.getConnection(MYSQL.getJdbcUrl(), MYSQL.getUsername(), MYSQL.getPassword());
             var sql = connection.createStatement()) {
            sql.executeUpdate("""
                    insert into storage_upload_permits (id,actor_user_id,accounted_bytes,charged,completed,catalog_asset_id,created_at)
                    values ('existing',41,1200,true,true,777,'2026-01-01 00:00:00')
                    """);
            flyway.target("26").load().migrate();
            try (var rows = sql.executeQuery("""
                    select accounted_bytes,catalog_asset_id,request_scope_hash,request_key_hash,request_fingerprint
                    from storage_upload_permits where id='existing'
                    """)) {
                assertThat(rows.next()).isTrue();
                assertThat(rows.getLong(1)).isEqualTo(1200);
                assertThat(rows.getLong(2)).isEqualTo(777);
                for (int field = 3; field <= 5; field++) assertThat(rows.getString(field)).isNull();
            }
            String insert = """
                    insert into storage_upload_permits
                    (id,actor_user_id,accounted_bytes,charged,completed,created_at,request_scope_hash,request_key_hash,request_fingerprint)
                    values ('%s',%d,0,false,false,'2026-01-01 00:00:00',repeat('%s',64),repeat('b',64),%s)
                    """;
            assertThat(sql.executeUpdate(insert.formatted("first", 41, "a", "repeat('c',64)"))).isEqualTo(1);
            assertThatThrownBy(() -> sql.executeUpdate(insert.formatted("duplicate", 41, "a", "repeat('d',64)")))
                    .isInstanceOf(SQLException.class).hasMessageContaining("uq_storage_upload_request");
            assertThat(sql.executeUpdate(insert.formatted("other-actor", 42, "a", "repeat('c',64)"))).isEqualTo(1);
            assertThat(sql.executeUpdate(insert.formatted("other-scope", 41, "e", "repeat('c',64)"))).isEqualTo(1);
            assertThatThrownBy(() -> sql.executeUpdate(insert.formatted("partial", 43, "e", "null")))
                    .isInstanceOf(SQLException.class).hasMessageContaining("ck_storage_upload_request_complete");
        }
    }
}
