package com.jeepclub.backend.storage.infra.persistence.adapter;

import jakarta.persistence.EntityManager;
import org.hibernate.Session;

/** Bounds communication on the pooled JDBC connection used by a Storage mutation. */
final class StorageJdbcLimits {
    // Give the five-second JDBC statement timeout a chance to complete its cancellation first.
    private static final int MYSQL_NETWORK_TIMEOUT_MILLISECONDS = 7_000;

    private StorageJdbcLimits() {}

    static void boundMySqlNetwork(EntityManager entityManager) {
        entityManager.unwrap(Session.class).doWork(connection -> {
            if (!"MySQL".equals(connection.getMetaData().getDatabaseProductName())) return;
            int existing = connection.getNetworkTimeout();
            if (existing == 0 || existing > MYSQL_NETWORK_TIMEOUT_MILLISECONDS) {
                // Keep the pool proxy so Hikari can restore this state when recycling the connection.
                connection.setNetworkTimeout(Runnable::run, MYSQL_NETWORK_TIMEOUT_MILLISECONDS);
            }
        });
    }
}
