package com.jeepclub.backend.storage.infra.persistence;

import org.testcontainers.junit.jupiter.*;
import org.testcontainers.mysql.MySQLContainer;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

@Testcontainers
class StorageBlobMySqlIT extends StorageUploadFlowTest {
    @org.junit.jupiter.api.Test void blobLockTimeoutRetainsExistingCustody() throws Exception {
        byte[] bytes=image(222);
        var original=blobs.store(bytes);
        var executor=java.util.concurrent.Executors.newSingleThreadExecutor();
        try (var blocker=java.sql.DriverManager.getConnection(MYSQL.getJdbcUrl(),MYSQL.getUsername(),MYSQL.getPassword());
             var observer=java.sql.DriverManager.getConnection(MYSQL.getJdbcUrl(),"root",MYSQL.getPassword())) {
            blocker.setAutoCommit(false); long blockerId;
            try(var statement=blocker.createStatement();var row=statement.executeQuery("select connection_id()")){row.next();blockerId=row.getLong(1);}
            try(var lock=blocker.prepareStatement("select sha256 from storage_blobs where sha256=? for update")) {
                lock.setString(1,original.sha256()); try(var row=lock.executeQuery()){org.assertj.core.api.Assertions.assertThat(row.next()).isTrue();}
            }
            var pending=executor.submit(() -> blobs.store(bytes)); boolean observed=false;
            try(var query=observer.prepareStatement("select count(*) from performance_schema.data_lock_waits w join performance_schema.data_locks l on l.ENGINE_LOCK_ID=w.REQUESTING_ENGINE_LOCK_ID join performance_schema.data_locks b on b.ENGINE_LOCK_ID=w.BLOCKING_ENGINE_LOCK_ID join performance_schema.threads t on t.THREAD_ID=b.THREAD_ID where l.OBJECT_SCHEMA=database() and l.OBJECT_NAME='storage_blobs' and t.PROCESSLIST_ID=?")) {
                query.setLong(1,blockerId); long deadline=System.nanoTime()+java.util.concurrent.TimeUnit.SECONDS.toNanos(4);
                while(System.nanoTime()<deadline && !observed){try(var rows=query.executeQuery()){rows.next();observed=rows.getLong(1)>0;}if(!observed) Thread.sleep(50);}
                org.assertj.core.api.Assertions.assertThat(observed).as("MySQL reports blob lock contention").isTrue();
                var failure=org.assertj.core.api.Assertions.catchThrowable(() -> pending.get(12,java.util.concurrent.TimeUnit.SECONDS));
                org.assertj.core.api.Assertions.assertThat(failure).isInstanceOf(java.util.concurrent.ExecutionException.class);
                var causes=new java.util.ArrayList<String>();
                for(Throwable cause=failure;cause!=null;cause=cause.getCause()) causes.add(cause.getClass().getName());
                org.assertj.core.api.Assertions.assertThat(causes).anyMatch(name -> name.contains("Timeout") || name.contains("QueryInterrupted"));
            } finally {blocker.rollback();}
            org.assertj.core.api.Assertions.assertThat(blobs.read(original)).containsExactly(bytes);
            org.assertj.core.api.Assertions.assertThat(jdbc.queryForObject("select count(*) from storage_blobs where sha256=? and ready=true",Long.class,original.sha256())).isEqualTo(1);
        } finally {executor.shutdownNow();}
    }
    @org.junit.jupiter.api.Test void identicalStoreWaitsForExistingBlobLockAndReusesTheSameBytes() throws Exception {
        byte[] bytes=image(221);
        var original=blobs.store(bytes);
        var executor=java.util.concurrent.Executors.newSingleThreadExecutor();
        try (var blocker=java.sql.DriverManager.getConnection(MYSQL.getJdbcUrl(),MYSQL.getUsername(),MYSQL.getPassword());
             var observer=java.sql.DriverManager.getConnection(MYSQL.getJdbcUrl(),"root",MYSQL.getPassword())) {
            blocker.setAutoCommit(false);
            long blockerId;
            try (var statement=blocker.createStatement();var row=statement.executeQuery("select connection_id()")) {
                row.next();blockerId=row.getLong(1);
            }
            try (var lock=blocker.prepareStatement("select sha256 from storage_blobs where sha256=? for update")) {
                lock.setString(1,original.sha256());
                try (var row=lock.executeQuery()) {org.assertj.core.api.Assertions.assertThat(row.next()).isTrue();}
            }
            var pending=executor.submit(() -> blobs.store(bytes));
            boolean observed=false;
            try (var query=observer.prepareStatement("select count(*) from performance_schema.data_lock_waits w join performance_schema.data_locks l on l.ENGINE_LOCK_ID=w.REQUESTING_ENGINE_LOCK_ID join performance_schema.data_locks b on b.ENGINE_LOCK_ID=w.BLOCKING_ENGINE_LOCK_ID join performance_schema.threads t on t.THREAD_ID=b.THREAD_ID where l.OBJECT_SCHEMA=database() and l.OBJECT_NAME='storage_blobs' and t.PROCESSLIST_ID=?")) {
                query.setLong(1,blockerId);
                long deadline=System.nanoTime()+java.util.concurrent.TimeUnit.SECONDS.toNanos(15);
                while(System.nanoTime()<deadline && !observed) {
                    try(var rows=query.executeQuery()) {rows.next();observed=rows.getLong(1)>0;}
                    if(!observed) Thread.sleep(50);
                }
                org.assertj.core.api.Assertions.assertThat(observed).as("MySQL reports a wait on storage_blobs").isTrue();
                org.assertj.core.api.Assertions.assertThat(pending.isDone()).isFalse();
            } finally {blocker.rollback();}
            org.assertj.core.api.Assertions.assertThat(pending.get(20,java.util.concurrent.TimeUnit.SECONDS)).isEqualTo(original);
            org.assertj.core.api.Assertions.assertThat(blobs.read(original)).containsExactly(bytes);
            org.assertj.core.api.Assertions.assertThat(jdbc.queryForObject("select count(*) from storage_blobs where sha256=?",Long.class,original.sha256())).isEqualTo(1);
        } finally {executor.shutdownNow();}
    }
    @org.junit.jupiter.api.Test void admissionLockTimeoutRollsBackReservation() throws Exception {
        long actor=987780L;
        var warm=admission.reserve(actor);admission.finish(warm);
        long before=jdbc.queryForObject("select used_bytes from storage_upload_budgets where scope_id=?",Long.class,actor);
        var executor=java.util.concurrent.Executors.newSingleThreadExecutor();
        try (var blocker=java.sql.DriverManager.getConnection(MYSQL.getJdbcUrl(),MYSQL.getUsername(),MYSQL.getPassword());
             var observer=java.sql.DriverManager.getConnection(MYSQL.getJdbcUrl(),"root",MYSQL.getPassword())) {
            blocker.setAutoCommit(false);long blockerId;
            try(var statement=blocker.createStatement();var row=statement.executeQuery("select connection_id()")){row.next();blockerId=row.getLong(1);}
            try(var statement=blocker.createStatement();var row=statement.executeQuery("select scope_id from storage_upload_budgets where scope_id=0 for update")){
                org.assertj.core.api.Assertions.assertThat(row.next()).isTrue();
            }
            long started=System.nanoTime();var pending=executor.submit(() -> admission.reserve(actor));boolean observed=false;
            try(var query=observer.prepareStatement("select count(*) from performance_schema.data_lock_waits w join performance_schema.data_locks l on l.ENGINE_LOCK_ID=w.REQUESTING_ENGINE_LOCK_ID join performance_schema.data_locks b on b.ENGINE_LOCK_ID=w.BLOCKING_ENGINE_LOCK_ID join performance_schema.threads t on t.THREAD_ID=b.THREAD_ID where l.OBJECT_SCHEMA=database() and l.OBJECT_NAME='storage_upload_budgets' and t.PROCESSLIST_ID=?")){
                query.setLong(1,blockerId);long deadline=System.nanoTime()+java.util.concurrent.TimeUnit.SECONDS.toNanos(4);
                while(System.nanoTime()<deadline && !observed){try(var rows=query.executeQuery()){rows.next();observed=rows.getLong(1)>0;}if(!observed) Thread.sleep(50);}
                org.assertj.core.api.Assertions.assertThat(observed).as("MySQL must report real lock contention").isTrue();
                var failed=org.assertj.core.api.Assertions.catchThrowable(() -> pending.get(12,java.util.concurrent.TimeUnit.SECONDS));
                org.assertj.core.api.Assertions.assertThat(failed).isInstanceOf(java.util.concurrent.ExecutionException.class);
                var causes=new java.util.ArrayList<String>();
                for(Throwable cause=failed;cause!=null;cause=cause.getCause()) causes.add(cause.getClass().getName());
                System.out.println("BACK639_LOCK_TIMEOUT_CAUSES="+causes);
                org.assertj.core.api.Assertions.assertThat(causes).anyMatch(name -> name.contains("Timeout") || name.contains("QueryInterrupted"));
                org.assertj.core.api.Assertions.assertThat(java.util.concurrent.TimeUnit.NANOSECONDS.toMillis(System.nanoTime()-started)).isLessThan(12000);
            } finally {blocker.rollback();}
            org.assertj.core.api.Assertions.assertThat(jdbc.queryForObject("select used_bytes from storage_upload_budgets where scope_id=?",Long.class,actor)).isEqualTo(before);
            org.assertj.core.api.Assertions.assertThat(jdbc.queryForObject("select in_flight from storage_upload_budgets where scope_id=?",Integer.class,actor)).isZero();
            org.assertj.core.api.Assertions.assertThat(jdbc.queryForObject("select count(*) from storage_upload_permits where actor_user_id=? and completed=false",Long.class,actor)).isZero();
        } finally {executor.shutdownNow();}
    }
    @org.junit.jupiter.api.Test void uppercaseConstraintIsEnforcedAfterFlywayEvolution() throws Exception {
        var a=upload.upload(88,com.jeepclub.backend.storage.core.domain.enums.MediaContext.IDENTITY_PROFILE,"88",image(88));
        var v=catalog.findVariant(a.mediaId(),"AVATAR_SMALL").orElseThrow();
        org.assertj.core.api.Assertions.assertThatThrownBy(() -> jdbc.update("update storage_media_variants set variant_code='avatar_small' where id=?",v.variantId()))
            .isInstanceOf(RuntimeException.class);
    }
    @Container static final MySQLContainer MYSQL=new MySQLContainer("mysql:8.0.46");
    @DynamicPropertySource static void properties(DynamicPropertyRegistry r) {
        r.add("spring.datasource.url",MYSQL::getJdbcUrl);r.add("spring.datasource.username",MYSQL::getUsername);
        r.add("spring.datasource.password",MYSQL::getPassword);r.add("spring.datasource.driver-class-name",() -> "com.mysql.cj.jdbc.Driver");
        r.add("spring.jpa.database-platform",() -> "org.hibernate.dialect.MySQLDialect");
        r.add("spring.jpa.hibernate.ddl-auto",() -> "validate");r.add("spring.flyway.enabled",() -> true);
    }
}
