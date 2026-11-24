package com.jeepclub.backend.publications.api.http;
import com.jeepclub.backend.iam.identity.api.module.UserDetails;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.stream.LongStream;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.mysql.MySQLContainer;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import tools.jackson.databind.JsonNode;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyCollection;
import static org.mockito.Mockito.clearInvocations;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@Testcontainers

@org.springframework.test.annotation.DirtiesContext(classMode=org.springframework.test.annotation.DirtiesContext.ClassMode.AFTER_CLASS)
class EventParticipantHttpMySqlIT extends EventParticipantDatabaseContract {
    @Container static final MySQLContainer DATABASE = new MySQLContainer("mysql:8.0.46");
    @DynamicPropertySource static void database(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", DATABASE::getJdbcUrl);
        registry.add("spring.datasource.username", DATABASE::getUsername);
        registry.add("spring.datasource.password", DATABASE::getPassword);
        registry.add("spring.datasource.driver-class-name", () -> "com.mysql.cj.jdbc.Driver");
        registry.add("spring.jpa.database-platform", () -> "org.hibernate.dialect.MySQLDialect");
        registry.add("spring.flyway.enabled", () -> "true");
        registry.add("spring.jpa.hibernate.ddl-auto", () -> "validate");
        registry.add("spring.jpa.properties.hibernate.hbm2ddl.halt_on_error", () -> "true");
    }

    @Test
    void feedBackfillsConfirmedParticipantsInTwoMySqlWindows() throws Exception {
        long deep = guestEvent();
        long shallow = guestEvent();
        long empty = guestEvent();
        try {
            jdbc.update("update publications set title = 'BACK565 window trail' where id in (?, ?, ?)",
                    deep, shallow, empty);
            jdbc.update("update event_registrations set status = 'CANCELLED' where event_id in (?, ?, ?)",
                    deep, shallow, empty);

            for (long userId = 100; userId < 152; userId++) {
                insertRegistration(deep, userId, "CONFIRMED");
                if (userId < 127) {
                    insertRegistration(shallow, userId, "CONFIRMED");
                }
            }
            for (long userId : List.of(205L, 201L, 204L, 202L, 203L)) {
                insertRegistration(deep, userId, "CONFIRMED");
            }
            insertRegistration(shallow, 205L, "CONFIRMED");
            insertRegistration(shallow, 201L, "CONFIRMED");
            insertRegistration(deep, 250L, "PENDING_PAYMENT");
            insertRegistration(empty, 250L, "PENDING_PAYMENT");

            auth(10L, "PUBLICATIONS_FEED_READ", "PUBLICATIONS_PUBLICATION_READ");
            when(users.findByIds(anyCollection())).thenAnswer(call -> detailsFor(call.getArgument(0)));
            var beforeCancellation = read("/publications/" + deep).get("details");

            var cancellationCommitted = new AtomicBoolean();
            when(users.findByIds(anyCollection())).thenAnswer(call -> {
                if (cancellationCommitted.compareAndSet(false, true)) {
                    // The initial SQL has established the reader snapshot. Commit before the first backfill.
                    assertThat(TransactionSynchronizationManager.isActualTransactionActive()).isTrue();
                    assertThat(TransactionSynchronizationManager.isCurrentTransactionReadOnly()).isTrue();
                    assertThat(jdbc.queryForObject("select @@transaction_isolation", String.class))
                            .isEqualTo("REPEATABLE-READ");
                    cancelFromAnotherConnection(deep, 205L);
                }
                return detailsFor(call.getArgument(0));
            });
            clearInvocations(users);
            SqlCapture.statements.clear();

            var page = feedPage();
            assertThat(page.get("totalElements").asInt()).isEqualTo(3);
            assertPreview(eventDetails(page, deep), 57, 205L, 201L, 204L, 202L, 203L);
            assertPreview(eventDetails(page, shallow), 29, 205L, 201L);
            assertPreview(eventDetails(page, empty), 0);

            var registrationSql = SqlCapture.statements.stream()
                    .filter(sql -> sql.contains("event_registrations")).toList();
            assertThat(registrationSql).hasSize(3);
            assertThat(registrationSql.get(0)).contains("count(", "group by");
            assertThat(registrationSql.subList(1, 3)).allSatisfy(sql -> assertThat(sql)
                    .contains("row_number()", "partition by", "p.rn >", "p.rn <=")
                    .doesNotContain("count("));
            assertThat(registrationSql.get(1)).containsPattern("event_id in \\(\\s*\\?,\\s*\\?\\s*\\)");

            @SuppressWarnings("unchecked")
            ArgumentCaptor<Collection<Long>> batches = ArgumentCaptor.forClass(Collection.class);
            verify(users, times(3)).findByIds(batches.capture());
            assertThat(batches.getAllValues()).extracting(Collection::size).containsExactly(26, 27, 5);
            var resolved = batches.getAllValues().stream().flatMap(Collection::stream).toList();
            var expected = new ArrayList<>(LongStream.range(100, 152).boxed().toList());
            expected.addAll(List.of(99L, 201L, 202L, 203L, 204L, 205L));
            assertThat(resolved).doesNotHaveDuplicates().containsExactlyInAnyOrderElementsOf(expected);

            assertThat(cancellationCommitted).isTrue();
            assertThat(eventDetails(page, deep)).isEqualTo(beforeCancellation);
            var updated = eventDetails(feedPage(), deep);
            assertPreview(updated, 56, 201L, 204L, 202L, 203L);
            assertThat(read("/publications/" + deep).get("details")).isEqualTo(updated);
            System.out.println("BACK-565 MySQL 8.0.46: events=3, confirmed=57/29/0, "
                    + "windows=2, registration queries=3, Identity batches=26/27/5, concurrent cancellation visible next request, confirmed=56");
        } finally {
            jdbc.update("update publications set status = 'ARCHIVED', archived_at = ? where id in (?, ?, ?)",
                    Timestamp.from(Instant.now(clock)), deep, shallow, empty);
        }
    }

    private static List<UserDetails> detailsFor(Collection<Long> ids) {
        return ids.stream().map(id -> new UserDetails(id, "Member " + id, null, null, null, null,
                null, null, id < 100 || id >= 200, null, null, null)).toList();
    }

    private void cancelFromAnotherConnection(long event, long user) throws Exception {
        try (var writer = dataSource.getConnection();
             var statement = writer.prepareStatement(
                     "update event_registrations set status = 'CANCELLED', cancelled_at = ? "
                             + "where event_id = ? and user_id = ? and status = 'CONFIRMED'")) {
            writer.setAutoCommit(false);
            statement.setTimestamp(1, Timestamp.from(Instant.now(clock)));
            statement.setLong(2, event);
            statement.setLong(3, user);
            assertThat(statement.executeUpdate()).isEqualTo(1);
            writer.commit();
        }
    }

    private void insertRegistration(long event, long user, String status) {
        jdbc.update("insert into event_registrations(event_id,user_id,status,created_at,version) "
                        + "values (?,?,?,?,0)", event, user, status, Timestamp.from(Instant.now(clock)));
    }

    private JsonNode feedPage() throws Exception {
        var response = mvc.perform(token(get("/publications/feed")
                        .param("type", "EVENT").param("q", "BACK565 window trail")))
                .andExpect(status().isOk()).andReturn().getResponse();
        return json.readTree(response.getContentAsString());
    }

    private JsonNode read(String path) throws Exception {
        var response = mvc.perform(token(get(path)))
                .andExpect(status().isOk()).andReturn().getResponse();
        return json.readTree(response.getContentAsString());
    }

    private static JsonNode eventDetails(JsonNode page, long id) {
        for (var publication : page.get("content")) {
            if (publication.get("id").asLong() == id) {
                return publication.get("details");
            }
        }
        throw new AssertionError("Event missing from feed: " + id);
    }

    private static void assertPreview(JsonNode details, int confirmed, Long... expected) {
        assertThat(details.get("registrationCount").asInt()).isEqualTo(confirmed);
        var ids = new ArrayList<Long>();
        for (var participant : details.get("participantsPreview")) {
            ids.add(participant.get("userId").asLong());
        }
        assertThat(ids).containsExactly(expected);
    }
}
