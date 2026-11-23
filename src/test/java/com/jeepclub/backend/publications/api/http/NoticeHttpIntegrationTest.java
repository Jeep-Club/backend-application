package com.jeepclub.backend.publications.api.http;

import com.jeepclub.backend.iam.authentication.core.application.service.security.AccessTokenAuthenticationService;
import com.jeepclub.backend.iam.identity.api.module.UserStatus;
import com.jeepclub.backend.iam.identity.infra.persistence.entity.UserEntity;
import com.jeepclub.backend.platform.security.authorization.UserAuthoritiesProvider;
import com.jeepclub.backend.platform.security.jwt.JwtAuthenticatedUser;
import com.jeepclub.backend.platform.security.jwt.JwtTokenParser;
import com.jeepclub.backend.platform.storage.image.ImageMediaService;
import com.jeepclub.backend.publications.infra.persistence.entity.NoticeHistoryEntity;
import com.jeepclub.backend.publications.infra.persistence.jpa.PublicationHistoryJpaRepository;
import com.jeepclub.backend.shared.storage.exception.StorageObjectNotFoundException;
import jakarta.persistence.EntityManager;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.RequestBuilder;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.databind.ObjectMapper;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;
import static org.springframework.http.HttpHeaders.AUTHORIZATION;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@SpringBootTest(properties = "spring.jpa.properties.hibernate.session_factory.statement_inspector=com.jeepclub.backend.publications.api.http.NoticeHttpIntegrationTest$Sql")
@AutoConfigureMockMvc
@ActiveProfiles("test")
class NoticeHttpIntegrationTest {
    private static final String KEY = "images/2026/09/27/550e8400-e29b-41d4-a716-446655440000.jpg";
    private static final String NEXT = "images/2026/09/27/550e8400-e29b-41d4-a716-446655440001.jpg";
    private static final String CREATE = """
            {"title":" First notice ","content":" Body ","images":[{"storageKey":"%s","position":0,"primary":true}]}
            """;
    @Autowired private MockMvc mvc;
    @Autowired private ObjectMapper json;
    @Autowired private PublicationHistoryJpaRepository history;
    @Autowired private EntityManager em;
    @MockitoBean private JwtTokenParser jwtTokenParser;
    @MockitoBean private UserAuthoritiesProvider authoritiesProvider;
    @MockitoBean private AccessTokenAuthenticationService authenticationService;
    @MockitoBean private ImageMediaService media;

    @Test
    void everyNoticeActionRequiresAuthenticationAndItsOwnAuthority() throws Exception {
        RequestBuilder[] actions = {
                get("/admin/notices"),
                post("/admin/notices").contentType(MediaType.APPLICATION_JSON).content(CREATE.formatted(KEY)),
                get("/admin/notices/1"),
                patch("/admin/notices/1").contentType(MediaType.APPLICATION_JSON).content("{}"),
                post("/admin/notices/1/publish"),
                post("/admin/notices/1/archive"),
                delete("/admin/notices/1")
        };
        for (RequestBuilder action : actions) {
            mvc.perform(action).andExpect(status().isUnauthorized());
        }
        authenticate(List.of("PUBLICATIONS_EVENT_CREATE", "PUBLICATIONS_NOTICE_READ"));
        // READ alone cannot create, update, publish, archive or delete Notice.
        for (int index : new int[]{1, 3, 4, 5, 6}) {
            mvc.perform(withToken(actions[index])).andExpect(status().isForbidden())
                    .andExpect(jsonPath("$.code").value("ACCESS_DENIED"));
        }
        authenticate(List.of("PUBLICATIONS_NOTICE_CREATE"));
        mvc.perform(withToken(get("/admin/notices"))).andExpect(status().isForbidden());
        mvc.perform(withToken(get("/admin/notices/1"))).andExpect(status().isForbidden());
        mvc.perform(withToken(delete("/admin/notices/1"))).andExpect(status().isForbidden());
        authenticate(List.of("PUBLICATIONS_NOTICE_UPDATE"));
        mvc.perform(withToken(post("/admin/notices/1/publish"))).andExpect(status().isForbidden());
        authenticate(List.of("PUBLICATIONS_NOTICE_PUBLISH"));
        mvc.perform(withToken(post("/admin/notices/1/archive"))).andExpect(status().isForbidden());
    }

    @Test
    @Transactional
    void eachPermissionRunsOnlyItsActionThroughJoinedPersistenceAndDeleteHistory() throws Exception {
        authenticate(List.of("PUBLICATIONS_NOTICE_CREATE"));
        MvcResult created = mvc.perform(withToken(post("/admin/notices")
                        .contentType(MediaType.APPLICATION_JSON).content(CREATE.formatted(KEY))))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.authorUserId").value(99))
                .andExpect(jsonPath("$.status").value("DRAFT"))
                .andExpect(jsonPath("$.title").value("First notice"))
                .andExpect(jsonPath("$.images[0].storageKey").value(KEY))
                .andReturn();
        long id = json.readTree(created.getResponse().getContentAsString()).get("id").asLong();
        verify(media).requireExisting(KEY);

        authenticate(List.of("PUBLICATIONS_NOTICE_READ"));
        mvc.perform(withToken(get("/admin/notices/{id}", id)))
                .andExpect(status().isOk()).andExpect(jsonPath("$.status").value("DRAFT"));

        authenticate(List.of("PUBLICATIONS_NOTICE_UPDATE"));
        mvc.perform(withToken(patch("/admin/notices/{id}", id)
                        .contentType(MediaType.APPLICATION_JSON).content("{\"title\":\"Revised\"}")))
                .andExpect(status().isOk()).andExpect(jsonPath("$.title").value("Revised"))
                .andExpect(jsonPath("$.content").value("Body"));
        mvc.perform(withToken(patch("/admin/notices/{id}", id)
                        .contentType(MediaType.APPLICATION_JSON).content("{\"images\":[{\"storageKey\":\"" + NEXT
                                + "\",\"position\":0,\"primary\":true}]}")))
                .andExpect(status().isOk()).andExpect(jsonPath("$.images[0].storageKey").value(NEXT));
        verify(media).requireExisting(NEXT);

        authenticate(List.of("PUBLICATIONS_NOTICE_PUBLISH"));
        MvcResult published = mvc.perform(withToken(post("/admin/notices/{id}/publish", id)))
                .andExpect(status().isOk()).andExpect(jsonPath("$.status").value("PUBLISHED"))
                .andReturn();
        String publishedAt = json.readTree(published.getResponse().getContentAsString()).get("publishedAt").asString();
        mvc.perform(withToken(post("/admin/notices/{id}/publish", id)))
                .andExpect(status().isConflict()).andExpect(jsonPath("$.code").value("NOTICE_INVALID_STATE"));

        authenticate(List.of("PUBLICATIONS_NOTICE_UPDATE"));
        mvc.perform(withToken(patch("/admin/notices/{id}", id)
                        .contentType(MediaType.APPLICATION_JSON).content("{\"content\":\"Published edit\"}")))
                .andExpect(status().isOk()).andExpect(jsonPath("$.publishedAt").value(publishedAt))
                .andExpect(jsonPath("$.content").value("Published edit"));

        authenticate(List.of("PUBLICATIONS_NOTICE_ARCHIVE"));
        mvc.perform(withToken(post("/admin/notices/{id}/archive", id)))
                .andExpect(status().isOk()).andExpect(jsonPath("$.status").value("ARCHIVED"));
        mvc.perform(withToken(post("/admin/notices/{id}/archive", id)))
                .andExpect(status().isConflict()).andExpect(jsonPath("$.code").value("NOTICE_INVALID_STATE"));

        authenticate(List.of("PUBLICATIONS_NOTICE_UPDATE"));
        mvc.perform(withToken(patch("/admin/notices/{id}", id)
                        .contentType(MediaType.APPLICATION_JSON).content("{\"title\":\"No\"}")))
                .andExpect(status().isConflict()).andExpect(jsonPath("$.code").value("NOTICE_INVALID_STATE"));

        authenticate(List.of("PUBLICATIONS_NOTICE_DELETE"));
        mvc.perform(withToken(delete("/admin/notices/{id}", id))).andExpect(status().isNoContent());
        var snapshot = history.findAll().stream().filter(item -> item.getPublicationId().equals(id))
                .map(NoticeHistoryEntity.class::cast).findFirst().orElseThrow();
        assertThat(snapshot.getDeletedByUserId()).isEqualTo(99L);
        assertThat(snapshot.getTitle()).isEqualTo("Revised");
        assertThat(snapshot.getContent()).isEqualTo("Published edit");
        assertThat(snapshot.getStatus().name()).isEqualTo("ARCHIVED");
        assertThat(snapshot.getImages()).singleElement().satisfies(image -> assertThat(image.getStorageKey()).isEqualTo(NEXT));
        authenticate(List.of("PUBLICATIONS_NOTICE_READ"));
        mvc.perform(withToken(get("/admin/notices/{id}", id)))
                .andExpect(status().isNotFound()).andExpect(jsonPath("$.code").value("NOTICE_NOT_FOUND"));
    }

    @Test
    @Transactional
    void invalidPayloadMissingMediaAndMissingIdsReturnControlledProblems() throws Exception {
        authenticate(List.of("PUBLICATIONS_NOTICE_CREATE"));
        mvc.perform(withToken(post("/admin/notices").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"title\":\" \"}")))
                .andExpect(status().isBadRequest())
                .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_PROBLEM_JSON))
                .andExpect(jsonPath("$.code").value("NOTICE_INVALID_REQUEST"));
        mvc.perform(withToken(post("/admin/notices").contentType(MediaType.APPLICATION_JSON)
                        .content(CREATE.formatted(KEY).replace("\"title\"", "\"authorUserId\":123,\"title\""))))
                .andExpect(status().isBadRequest()).andExpect(jsonPath("$.code").value("NOTICE_INVALID_REQUEST"));
        doThrow(new StorageObjectNotFoundException()).when(media).requireExisting(KEY);
        mvc.perform(withToken(post("/admin/notices").contentType(MediaType.APPLICATION_JSON).content(CREATE.formatted(KEY))))
                .andExpect(status().isNotFound()).andExpect(jsonPath("$.code").value("IMAGE_NOT_FOUND"));

        authenticate(List.of("PUBLICATIONS_NOTICE_READ"));
        mvc.perform(withToken(get("/admin/notices/987654321")))
                .andExpect(status().isNotFound()).andExpect(jsonPath("$.code").value("NOTICE_NOT_FOUND"));
        authenticate(List.of("PUBLICATIONS_NOTICE_UPDATE"));
        mvc.perform(withToken(patch("/admin/notices/987654321").contentType(MediaType.APPLICATION_JSON).content("{}")))
                .andExpect(status().isNotFound()).andExpect(jsonPath("$.code").value("NOTICE_NOT_FOUND"));
        authenticate(List.of("PUBLICATIONS_NOTICE_PUBLISH"));
        mvc.perform(withToken(post("/admin/notices/987654321/publish"))).andExpect(status().isNotFound());
        authenticate(List.of("PUBLICATIONS_NOTICE_ARCHIVE"));
        mvc.perform(withToken(post("/admin/notices/987654321/archive"))).andExpect(status().isNotFound());
        authenticate(List.of("PUBLICATIONS_NOTICE_DELETE"));
        mvc.perform(withToken(delete("/admin/notices/987654321"))).andExpect(status().isNotFound());
    }

    @Test
    @Transactional
    void patchDistinguishesOmittedFieldsFromExplicitNullAndValidatesReplacementGallery() throws Exception {
        authenticate(List.of("PUBLICATIONS_NOTICE_CREATE"));
        MvcResult created = mvc.perform(withToken(post("/admin/notices")
                        .contentType(MediaType.APPLICATION_JSON).content(CREATE.formatted(KEY))))
                .andExpect(status().isCreated()).andReturn();
        long id = json.readTree(created.getResponse().getContentAsString()).get("id").asLong();
        String initialUpdatedAt = json.readTree(created.getResponse().getContentAsString()).get("updatedAt").asString();

        authenticate(List.of("PUBLICATIONS_NOTICE_UPDATE"));
        mvc.perform(withToken(patch("/admin/notices/{id}", id).contentType(MediaType.APPLICATION_JSON).content("{}")))
                .andExpect(status().isOk()).andExpect(jsonPath("$.updatedAt").value(initialUpdatedAt))
                .andExpect(jsonPath("$.images[0].storageKey").value(KEY));
        for (String body : new String[]{"{\"title\":null}", "{\"content\":null}", "{\"images\":null}",
                "{\"images\":[]}", "{\"authorUserId\":123}"}) {
            mvc.perform(withToken(patch("/admin/notices/{id}", id).contentType(MediaType.APPLICATION_JSON).content(body)))
                    .andExpect(status().isBadRequest());
        }
        mvc.perform(withToken(patch("/admin/notices/{id}", id).contentType(MediaType.APPLICATION_JSON)
                        .content("{\"images\":[{\"storageKey\":\"" + NEXT + "\",\"position\":0,\"primary\":false}]}")))
                .andExpect(status().isBadRequest()).andExpect(jsonPath("$.code").value("NOTICE_INVALID_REQUEST"));
        doThrow(new StorageObjectNotFoundException()).when(media).requireExisting(NEXT);
        mvc.perform(withToken(patch("/admin/notices/{id}", id).contentType(MediaType.APPLICATION_JSON)
                        .content("{\"images\":[{\"storageKey\":\"" + NEXT + "\",\"position\":0,\"primary\":true}]}")))
                .andExpect(status().isNotFound()).andExpect(jsonPath("$.code").value("IMAGE_NOT_FOUND"));
        authenticate(List.of("PUBLICATIONS_NOTICE_READ"));
        mvc.perform(withToken(get("/admin/notices/{id}", id)))
                .andExpect(status().isOk()).andExpect(jsonPath("$.images[0].storageKey").value(KEY));
    }

    @Test
    @Transactional
    void adminCatalogPaginatesFiltersAndResolvesAuthorNamesInBatchWhenAuthorized() throws Exception {
        long activeAuthor = persistUser("Autora Ativa", UserStatus.ACTIVE);
        long secondAuthor = persistUser("Segundo Autor", UserStatus.ACTIVE);
        long inactiveAuthor = persistUser("Autor Inativo", UserStatus.INACTIVE);

        // Create notices with different authors and editorial states
        authenticateAs(activeAuthor, List.of(
                "PUBLICATIONS_NOTICE_CREATE",
                "PUBLICATIONS_NOTICE_UPDATE",
                "PUBLICATIONS_NOTICE_PUBLISH",
                "PUBLICATIONS_NOTICE_ARCHIVE",
                "PUBLICATIONS_NOTICE_READ",
                "IDENTITY_USER_READ"
        ));
        MvcResult firstCreated = mvc.perform(withToken(post("/admin/notices")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"title":"Aviso %%_! Rascunho","content":"Conteúdo interno","images":[{"storageKey":"%s","position":0,"primary":true}]}
                                """.formatted(KEY))))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.authorUserId").value(activeAuthor))
                .andExpect(jsonPath("$.authorName").value("Autora Ativa"))
                .andReturn();
        long draftId = json.readTree(firstCreated.getResponse().getContentAsString()).get("id").asLong();

        MvcResult secondCreated = mvc.perform(withToken(post("/admin/notices")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"title":"Comunicado Trilha","content":"Expedição especial","images":[{"storageKey":"%s","position":0,"primary":true}]}
                                """.formatted(KEY))))
                .andExpect(status().isCreated())
                .andReturn();
        long publishedId = json.readTree(secondCreated.getResponse().getContentAsString()).get("id").asLong();
        mvc.perform(withToken(post("/admin/notices/{id}/publish", publishedId)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("PUBLISHED"))
                .andExpect(jsonPath("$.authorName").value("Autora Ativa"));

        authenticateAs(secondAuthor, List.of("PUBLICATIONS_NOTICE_CREATE", "PUBLICATIONS_NOTICE_PUBLISH", "PUBLICATIONS_NOTICE_ARCHIVE", "IDENTITY_USER_READ"));
        long archivedId = json.readTree(mvc.perform(withToken(post("/admin/notices")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"title":"Aviso Antigo","content":"Expedição encerrada","images":[{"storageKey":"%s","position":0,"primary":true}]}
                                """.formatted(KEY))))
                .andExpect(status().isCreated()).andReturn().getResponse().getContentAsString()).get("id").asLong();
        mvc.perform(withToken(post("/admin/notices/{id}/publish", archivedId))).andExpect(status().isOk());
        mvc.perform(withToken(post("/admin/notices/{id}/archive", archivedId)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("ARCHIVED"))
                .andExpect(jsonPath("$.authorName").value("Segundo Autor"));

        authenticateAs(inactiveAuthor, List.of("PUBLICATIONS_NOTICE_CREATE", "IDENTITY_USER_READ"));
        long inactiveAuthorNoticeId = json.readTree(mvc.perform(withToken(post("/admin/notices")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"title":"Aviso Autor Inativo","content":"Sem nome ativo","images":[{"storageKey":"%s","position":0,"primary":true}]}
                                """.formatted(KEY))))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.authorName").value(org.hamcrest.Matchers.nullValue()))
                .andReturn().getResponse().getContentAsString()).get("id").asLong();

        em.flush();
        em.clear();
        Sql.statements.get().clear();

        // List with IDENTITY_USER_READ: resolves distinct active authors in a single batch query without N+1
        authenticateAs(activeAuthor, List.of("PUBLICATIONS_NOTICE_READ", "IDENTITY_USER_READ"));
        mvc.perform(withToken(get("/admin/notices").param("sort", "id,asc")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.totalElements").value(4))
                .andExpect(jsonPath("$.content[0].id").value(draftId))
                .andExpect(jsonPath("$.content[0].authorName").value("Autora Ativa"))
                .andExpect(jsonPath("$.content[1].id").value(publishedId))
                .andExpect(jsonPath("$.content[1].authorName").value("Autora Ativa"))
                .andExpect(jsonPath("$.content[2].id").value(archivedId))
                .andExpect(jsonPath("$.content[2].authorName").value("Segundo Autor"))
                .andExpect(jsonPath("$.content[3].id").value(inactiveAuthorNoticeId))
                .andExpect(jsonPath("$.content[3].authorName").value(org.hamcrest.Matchers.nullValue()));
        assertThat(Sql.statements.get().stream().filter(sql -> sql.contains("identity_users")).count()).isEqualTo(1);

        // Without IDENTITY_USER_READ: primary GET succeeds (200) and authorName is null
        authenticateAs(activeAuthor, List.of("PUBLICATIONS_NOTICE_READ"));
        mvc.perform(withToken(get("/admin/notices").param("status", "DRAFT").param("q", "%_!")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.totalElements").value(1))
                .andExpect(jsonPath("$.content[0].id").value(draftId))
                .andExpect(jsonPath("$.content[0].authorUserId").value(activeAuthor))
                .andExpect(jsonPath("$.content[0].authorName").value(org.hamcrest.Matchers.nullValue()));

        // Search by content + status filter
        mvc.perform(withToken(get("/admin/notices").param("q", "EXPEDIÇÃO").param("status", "ARCHIVED")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.totalElements").value(1))
                .andExpect(jsonPath("$.content[0].id").value(archivedId));

        // Invalid filter / sort / pagination returns 400 NOTICE_INVALID_REQUEST
        for (String[] bad : new String[][]{
                {"status", "INVALID"},
                {"priority", "INVALID"},
                {"expired", "not-a-boolean"},
                {"sort", "unknownField,asc"},
                {"page", "-1"},
                {"size", "0"},
                {"size", "51"},
                {"q", "x".repeat(201)}
        }) {
            mvc.perform(withToken(get("/admin/notices").param(bad[0], bad[1])))
                    .andExpect(status().isBadRequest())
                    .andExpect(jsonPath("$.code").value("NOTICE_INVALID_REQUEST"));
        }
        mvc.perform(withToken(get("/admin/notices")
                        .param("from", "2026-10-02T00:00:00Z")
                        .param("to", "2026-10-01T00:00:00Z")))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("NOTICE_INVALID_REQUEST"));

        // Out-of-range page preserves totalElements and totalPages with empty content
        mvc.perform(withToken(get("/admin/notices").param("page", "5").param("size", "2")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.content.length()").value(0))
                .andExpect(jsonPath("$.totalElements").value(4))
                .andExpect(jsonPath("$.totalPages").value(2));

        // Missing PUBLICATIONS_NOTICE_READ returns 403
        authenticate(List.of("IDENTITY_USER_READ"));
        mvc.perform(withToken(get("/admin/notices")))
                .andExpect(status().isForbidden());
    }

    @Test
    @Transactional
    void noticePriorityAndValidityWindowSupportCreatePatchAdminFiltersAndSnapshotHistory() throws Exception {
        long author = persistUser("Editora Avisos", UserStatus.ACTIVE);
        authenticateAs(author, List.of(
                "PUBLICATIONS_NOTICE_CREATE",
                "PUBLICATIONS_NOTICE_UPDATE",
                "PUBLICATIONS_NOTICE_PUBLISH",
                "PUBLICATIONS_NOTICE_READ",
                "PUBLICATIONS_NOTICE_DELETE"
        ));

        // Default priority is NORMAL and validity bounds are null when omitted
        long defaultNoticeId = json.readTree(mvc.perform(withToken(post("/admin/notices")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(CREATE.formatted(KEY))))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.priority").value("NORMAL"))
                .andExpect(jsonPath("$.validFrom").value(org.hamcrest.Matchers.nullValue()))
                .andExpect(jsonPath("$.validUntil").value(org.hamcrest.Matchers.nullValue()))
                .andReturn().getResponse().getContentAsString()).get("id").asLong();

        // Explicit priority and temporal window
        long urgentExpiredId = json.readTree(mvc.perform(withToken(post("/admin/notices")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"title":"Alerta Urgente","content":"Trecho interditado","priority":"URGENT",
                                 "validFrom":"2020-01-01T00:00:00Z","validUntil":"2020-01-02T00:00:00Z",
                                 "images":[{"storageKey":"%s","position":0,"primary":true}]}
                                """.formatted(KEY))))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.priority").value("URGENT"))
                .andExpect(jsonPath("$.validFrom").value("2020-01-01T00:00:00Z"))
                .andExpect(jsonPath("$.validUntil").value("2020-01-02T00:00:00Z"))
                .andReturn().getResponse().getContentAsString()).get("id").asLong();

        mvc.perform(withToken(post("/admin/notices/{id}/publish", urgentExpiredId)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("PUBLISHED"))
                .andExpect(jsonPath("$.priority").value("URGENT"));

        // Invalid create payloads: reversed validity window, null priority, unknown priority
        for (String invalidCreate : new String[]{
                """
                {"title":"Inválido","content":"Janela invertida","priority":"HIGH",
                 "validFrom":"2026-10-10T00:00:00Z","validUntil":"2026-10-01T00:00:00Z",
                 "images":[{"storageKey":"%s","position":0,"primary":true}]}
                """.formatted(KEY),
                """
                {"title":"Inválido","content":"Prioridade nula","priority":null,
                 "images":[{"storageKey":"%s","position":0,"primary":true}]}
                """.formatted(KEY),
                """
                {"title":"Inválido","content":"Prioridade desconhecida","priority":"CRITICAL",
                 "images":[{"storageKey":"%s","position":0,"primary":true}]}
                """.formatted(KEY)
        }) {
            mvc.perform(withToken(post("/admin/notices").contentType(MediaType.APPLICATION_JSON).content(invalidCreate)))
                    .andExpect(status().isBadRequest())
                    .andExpect(jsonPath("$.code").value("NOTICE_INVALID_REQUEST"));
        }

        // PATCH updates priority and window, preserves omitted fields, allows explicit null on validFrom/validUntil, rejects null priority or reversed effective window
        mvc.perform(withToken(patch("/admin/notices/{id}", defaultNoticeId)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"priority\":\"HIGH\",\"validFrom\":\"2030-05-01T10:00:00Z\",\"validUntil\":\"2030-05-10T10:00:00Z\"}")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.priority").value("HIGH"))
                .andExpect(jsonPath("$.validFrom").value("2030-05-01T10:00:00Z"))
                .andExpect(jsonPath("$.validUntil").value("2030-05-10T10:00:00Z"));

        // Reversed against preserved validFrom is rejected
        mvc.perform(withToken(patch("/admin/notices/{id}", defaultNoticeId)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"validUntil\":\"2030-04-01T10:00:00Z\"}")))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("NOTICE_INVALID_REQUEST"));

        // Explicit null priority is rejected
        mvc.perform(withToken(patch("/admin/notices/{id}", defaultNoticeId)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"priority\":null}")))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("NOTICE_INVALID_REQUEST"));

        // Explicit null on validFrom clears the start bound while preserving priority and validUntil
        mvc.perform(withToken(patch("/admin/notices/{id}", defaultNoticeId)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"validFrom\":null}")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.priority").value("HIGH"))
                .andExpect(jsonPath("$.validFrom").value(org.hamcrest.Matchers.nullValue()))
                .andExpect(jsonPath("$.validUntil").value("2030-05-10T10:00:00Z"));

        // Admin filters by priority and expired without losing PUBLISHED status on expired notices
        mvc.perform(withToken(get("/admin/notices").param("priority", "URGENT").param("expired", "true")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.totalElements").value(1))
                .andExpect(jsonPath("$.content[0].id").value(urgentExpiredId))
                .andExpect(jsonPath("$.content[0].status").value("PUBLISHED"))
                .andExpect(jsonPath("$.content[0].priority").value("URGENT"));

        mvc.perform(withToken(get("/admin/notices").param("expired", "false").param("priority", "HIGH")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.totalElements").value(1))
                .andExpect(jsonPath("$.content[0].id").value(defaultNoticeId));

        // Create LOW and NORMAL notices (including equal validFrom == validUntil boundary) to verify semantic priority ordering
        mvc.perform(withToken(post("/admin/notices")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"title":"Aviso Baixo","content":"Informativo geral","priority":"LOW",
                                 "validFrom":"2030-06-01T12:00:00Z","validUntil":"2030-06-01T12:00:00Z",
                                 "images":[{"storageKey":"%s","position":0,"primary":true}]}
                                """.formatted(KEY))))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.priority").value("LOW"))
                .andExpect(jsonPath("$.validFrom").value("2030-06-01T12:00:00Z"))
                .andExpect(jsonPath("$.validUntil").value("2030-06-01T12:00:00Z"));
        mvc.perform(withToken(post("/admin/notices")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"title":"Aviso Normal","content":"Comunicado padrao","priority":"NORMAL",
                                 "images":[{"storageKey":"%s","position":0,"primary":true}]}
                                """.formatted(KEY))))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.priority").value("NORMAL"));

        // Semantic priority sort (not alphabetical): DESC => URGENT, HIGH, NORMAL, LOW; ASC => LOW, NORMAL, HIGH, URGENT
        mvc.perform(withToken(get("/admin/notices").param("sort", "priority,desc")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.content[0].priority").value("URGENT"))
                .andExpect(jsonPath("$.content[1].priority").value("HIGH"))
                .andExpect(jsonPath("$.content[2].priority").value("NORMAL"))
                .andExpect(jsonPath("$.content[3].priority").value("LOW"));

        mvc.perform(withToken(get("/admin/notices").param("sort", "priority,asc")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.content[0].priority").value("LOW"))
                .andExpect(jsonPath("$.content[1].priority").value("NORMAL"))
                .andExpect(jsonPath("$.content[2].priority").value("HIGH"))
                .andExpect(jsonPath("$.content[3].priority").value("URGENT"));

        // Hard delete snapshots priority and validity window in NoticeHistoryEntity
        mvc.perform(withToken(delete("/admin/notices/{id}", urgentExpiredId)))
                .andExpect(status().isNoContent());
        var snapshot = history.findAll().stream()
                .filter(item -> item.getPublicationId().equals(urgentExpiredId))
                .map(NoticeHistoryEntity.class::cast)
                .findFirst().orElseThrow();
        assertThat(snapshot.getPriority()).isEqualTo("URGENT");
        assertThat(snapshot.getValidFrom()).isEqualTo(Instant.parse("2020-01-01T00:00:00Z"));
        assertThat(snapshot.getValidUntil()).isEqualTo(Instant.parse("2020-01-02T00:00:00Z"));
    }

    private long persistUser(String name, UserStatus status) {
        var user = new UserEntity();
        user.setName(name);
        user.setCpf(String.format("%011d", Math.abs(UUID.randomUUID().getMostSignificantBits() % 100000000000L)));
        user.setStatus(status);
        Instant createdAt = Instant.parse("2026-06-30T12:00:00Z");
        user.setCreatedAt(createdAt);
        if (status == UserStatus.INACTIVE) {
            Instant disabledAt = createdAt.plusSeconds(60);
            user.setDisabledAt(disabledAt);
            user.setUpdatedAt(disabledAt);
        }
        em.persist(user);
        em.flush();
        return user.getId();
    }

    private void authenticate(List<String> authorities) {
        authenticateAs(99L, authorities);
    }

    private void authenticateAs(long userId, List<String> authorities) {
        when(jwtTokenParser.parseAndValidate("notice-token")).thenReturn(
                new JwtAuthenticatedUser(userId, 100L + userId, "Notice Admin", Instant.now().plusSeconds(3600)));
        when(authoritiesProvider.findAuthorityCodesByUserId(userId)).thenReturn(authorities);
    }

    private static org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder withToken(
            org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder request) {
        return request.header(AUTHORIZATION, "Bearer notice-token");
    }

    private static RequestBuilder withToken(RequestBuilder request) {
        return ((org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder) request)
                .header(AUTHORIZATION, "Bearer notice-token");
    }

    public static class Sql implements org.hibernate.resource.jdbc.spi.StatementInspector {
        static final ThreadLocal<List<String>> statements = ThreadLocal.withInitial(ArrayList::new);

        @Override
        public String inspect(String sql) {
            if (sql.startsWith("select ")) {
                statements.get().add(sql);
            }
            return sql;
        }
    }
}
