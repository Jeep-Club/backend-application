package com.jeepclub.backend.dependents.api.controller;

import com.jeepclub.backend.dependents.core.domain.enums.DependentStatus;
import com.jeepclub.backend.dependents.core.domain.enums.RelationshipType;
import com.jeepclub.backend.dependents.infra.persistence.entity.DependentEntity;
import com.jeepclub.backend.iam.identity.api.module.UserStatus;
import com.jeepclub.backend.iam.identity.infra.persistence.entity.UserEntity;
import com.jeepclub.backend.shared.export.ExportPages;
import org.junit.jupiter.api.Test;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.mysql.MySQLContainer;

import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.Locale;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@Testcontainers
@DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_CLASS)
class AdminDependentSearchMySqlIT extends AdminDependentSearchHttpIntegrationTest {

    @Container
    static final MySQLContainer MYSQL = new MySQLContainer("mysql:8.0.46");

    @DynamicPropertySource
    static void database(DynamicPropertyRegistry r) {
        r.add("spring.datasource.url", MYSQL::getJdbcUrl);
        r.add("spring.datasource.username", MYSQL::getUsername);
        r.add("spring.datasource.password", MYSQL::getPassword);
        r.add("spring.datasource.driver-class-name", () -> "com.mysql.cj.jdbc.Driver");
        r.add("spring.jpa.database-platform", () -> "org.hibernate.dialect.MySQLDialect");
        r.add("spring.jpa.hibernate.ddl-auto", () -> "validate");
        r.add("spring.flyway.enabled", () -> true);
    }

    /**
     * BACK-610: prove that export retrieves owners once per non-empty chunk,
     * not once per dependent, with the production MySQL dialect and HTTP flow.
     */
    @Test
    void exportSqlQueriesScaleWithChunksNotDependents() throws Exception {
        long ownerA = persistOwner("BACK610 SQL Owner A", "91919191919");
        long ownerB = persistOwner("BACK610 SQL Owner B", "92929292929");
        int seeded = 0;

        for (int size : new int[]{10, 100, 200, 201, 401}) {
            while (seeded < size) {
                DependentEntity dependent = new DependentEntity();
                dependent.setName("BACK610 SQL Dependent " + seeded);
                dependent.setCpf(String.format(Locale.ROOT, "6%010d", seeded));
                dependent.setBirthDate(LocalDate.of(2012, 4, 15));
                dependent.setRelationshipType(RelationshipType.CHILD);
                dependent.setUserId(seeded % 2 == 0 ? ownerA : ownerB);
                dependent.setStatus(DependentStatus.ACTIVE);
                dependent.setCreatedAt(Instant.parse("2026-06-30T12:00:00Z"));
                em.persist(dependent);
                seeded++;
            }
            em.flush();
            em.clear();

            when(authorities.findAuthorityCodesByUserId(99L))
                    .thenReturn(List.of("DEPENDENTS_DEPENDENT_EXPORT"));
            Sql.statements.get().clear();

            var response = mvc.perform(get("/admin/dependents/export")
                            .param("format", "CSV")
                            .param("userIds", Long.toString(ownerA), Long.toString(ownerB))
                            .header("Authorization", "Bearer search-token"))
                    .andExpect(status().isOk())
                    .andReturn().getResponse();

            String csv = response.getContentAsString(StandardCharsets.UTF_8);
            assertThat(csv.lines().count()).as("CSV rows, size " + size).isEqualTo(size + 1L);
            assertThat(csv).contains("BACK610 SQL Dependent 0", "BACK610 SQL Dependent " + (size - 1),
                    "BACK610 SQL Owner A", "BACK610 SQL Owner B");

            List<String> sql = List.copyOf(Sql.statements.get());
            long dependentSelects = sql.stream().filter(q -> q.contains("dependents_dependent")).count();
            long ownerSelects = sql.stream().filter(q -> q.contains("identity_users")).count();
            int nonEmptyChunks = (size + ExportPages.CHUNK - 1) / ExportPages.CHUNK;
            int dependentPageQueries = size / ExportPages.CHUNK + 1;

            // Exact multiples require an additional empty page to terminate the chunk loop.
            assertThat(dependentSelects).as("paged dependent SELECTs for size " + size)
                    .isEqualTo(dependentPageQueries);
            // One eligibility SELECT for the two userIds plus one batch SELECT per non-empty chunk.
            assertThat(ownerSelects).as("batched owner SELECTs for size " + size)
                    .isEqualTo(nonEmptyChunks + 1L);
            assertThat(sql.stream().filter(q -> q.contains("dependents_dependent")))
                    .as("dependent SQL must be paginated")
                    .allSatisfy(q -> assertThat(q.toLowerCase(Locale.ROOT)).contains("limit"));

            System.out.printf(Locale.ROOT,
                    "BACK-610 export SQL: rows=%d dependentSelects=%d ownerSelects=%d chunks=%d%n",
                    size, dependentSelects, ownerSelects, nonEmptyChunks);
        }
    }

    private long persistOwner(String name, String cpf) {
        UserEntity owner = new UserEntity();
        owner.setName(name);
        owner.setCpf(cpf);
        owner.setStatus(UserStatus.ACTIVE);
        owner.setCreatedAt(Instant.parse("2026-06-30T12:00:00Z"));
        em.persist(owner);
        return owner.getId();
    }
}
