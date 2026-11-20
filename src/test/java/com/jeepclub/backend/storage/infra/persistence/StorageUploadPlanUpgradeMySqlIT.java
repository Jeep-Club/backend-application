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
class StorageUploadPlanUpgradeMySqlIT {
    @Container static final MySQLContainer MYSQL = new MySQLContainer("mysql:8.0.46");

    @Test void v25PreservesExistingBudgetsAndPermitsAndEnforcesPlanConstraints() throws Exception {
        Flyway.configure().dataSource(MYSQL.getJdbcUrl(), MYSQL.getUsername(), MYSQL.getPassword())
            .target("24").load().migrate();
        try (var connection = DriverManager.getConnection(MYSQL.getJdbcUrl(), MYSQL.getUsername(), MYSQL.getPassword());
             var sql = connection.createStatement()) {
            sql.executeUpdate("insert into storage_upload_budgets (scope_id,used_bytes,in_flight,window_attempts,charged_uploads,window_start) values (0,1200,0,0,0,'2026-01-01 00:00:00'),(41,1200,1,3,1,'2026-01-01 00:00:00')");
            sql.executeUpdate("insert into storage_upload_permits (id,actor_user_id,accounted_bytes,charged,completed,created_at) values ('00000000-0000-0000-0000-000000000041',41,1200,true,false,'2026-01-01 00:00:00')");
        }
        Flyway.configure().dataSource(MYSQL.getJdbcUrl(), MYSQL.getUsername(), MYSQL.getPassword())
            .target("25").load().migrate();
        try (var connection = DriverManager.getConnection(MYSQL.getJdbcUrl(), MYSQL.getUsername(), MYSQL.getPassword());
             var sql = connection.createStatement()) {
            try (var rows = sql.executeQuery("select used_bytes,in_flight,window_attempts,charged_uploads,window_start from storage_upload_budgets where scope_id=41")) {
                assertThat(rows.next()).isTrue();
                assertThat(rows.getLong(1)).isEqualTo(1200);
                assertThat(rows.getInt(2)).isEqualTo(1);
                assertThat(rows.getInt(3)).isEqualTo(3);
                assertThat(rows.getLong(4)).isEqualTo(1);
                assertThat(rows.getTimestamp(5).toLocalDateTime().toString()).isEqualTo("2026-01-01T00:00");
                assertThat(rows.next()).isFalse();
            }
            try (var rows = sql.executeQuery("select actor_user_id,accounted_bytes,charged,completed,catalog_asset_id from storage_upload_permits where id='00000000-0000-0000-0000-000000000041'")) {
                assertThat(rows.next()).isTrue();
                assertThat(rows.getLong(1)).isEqualTo(41);
                assertThat(rows.getLong(2)).isEqualTo(1200);
                assertThat(rows.getBoolean(3)).isTrue();
                assertThat(rows.getBoolean(4)).isFalse();
                assertThat(rows.getObject(5)).isNull();
                assertThat(rows.next()).isFalse();
            }
            try (var rows = sql.executeQuery("select used_bytes from storage_upload_budgets where scope_id=0")) {
                assertThat(rows.next()).isTrue(); assertThat(rows.getLong(1)).isEqualTo(1200);
            }
            sql.executeUpdate("insert into storage_upload_planned_blobs (permit_id,position_index,sha256,size_bytes) values ('00000000-0000-0000-0000-000000000041',0,repeat('a',64),1200)");
            assertThatThrownBy(() -> sql.executeUpdate("insert into storage_upload_planned_blobs (permit_id,position_index,sha256,size_bytes) values ('00000000-0000-0000-0000-000000000041',0,repeat('b',64),1200)"))
                .isInstanceOf(SQLException.class);
            assertThatThrownBy(() -> sql.executeUpdate("insert into storage_upload_planned_blobs (permit_id,position_index,sha256,size_bytes) values ('00000000-0000-0000-0000-000000000099',1,repeat('b',64),1200)"))
                .isInstanceOf(SQLException.class);
            assertThatThrownBy(() -> sql.executeUpdate("delete from storage_upload_permits where id='00000000-0000-0000-0000-000000000041'"))
                .isInstanceOf(SQLException.class);
            try (var rows = sql.executeQuery("select count(*) from storage_upload_planned_blobs")) {
                assertThat(rows.next()).isTrue(); assertThat(rows.getLong(1)).isEqualTo(1);
            }
        }
    }
}
