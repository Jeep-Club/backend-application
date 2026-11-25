package com.jeepclub.backend.publications.api.http;

import com.jeepclub.backend.iam.authentication.core.application.service.security.AccessTokenAuthenticationService;
import com.jeepclub.backend.iam.identity.api.module.UserStatus;
import com.jeepclub.backend.iam.identity.infra.persistence.entity.UserEntity;
import com.jeepclub.backend.memberships.api.module.MembershipAccessQuery;
import com.jeepclub.backend.memberships.api.module.MembershipAccessResult;
import com.jeepclub.backend.platform.security.authorization.UserAuthoritiesProvider;
import com.jeepclub.backend.platform.security.jwt.JwtAuthenticatedUser;
import com.jeepclub.backend.platform.security.jwt.JwtTokenParser;
import com.jeepclub.backend.platform.storage.image.ImageMediaService;
import com.jeepclub.backend.publications.core.domain.enums.PublicationStatus;
import com.jeepclub.backend.publications.infra.persistence.entity.ServicePublicationEntity;
import com.jeepclub.backend.publications.infra.persistence.entity.ServicePublicationHistoryEntity;
import com.jeepclub.backend.publications.infra.persistence.jpa.PublicationHistoryJpaRepository;
import com.jeepclub.backend.publications.infra.persistence.jpa.PublicationJpaRepository;
import com.jeepclub.backend.publications.infra.persistence.jpa.ServicePublicationChangeRequestJpaRepository;
import com.jeepclub.backend.publications.infra.persistence.jpa.ServicePublicationRequestJpaRepository;
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
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.databind.ObjectMapper;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.*;
import static org.springframework.http.HttpHeaders.AUTHORIZATION;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@SpringBootTest(properties = "spring.jpa.properties.hibernate.session_factory.statement_inspector=com.jeepclub.backend.publications.api.http.ServicePublicationHttpIntegrationTest$Sql")
@AutoConfigureMockMvc
@ActiveProfiles("test")
class ServicePublicationHttpIntegrationTest {
    private static final String KEY = "images/2026/09/27/550e8400-e29b-41d4-a716-446655440000.jpg";
    private static final String NEXT = "images/2026/09/27/550e8400-e29b-41d4-a716-446655440001.jpg";
    private static final String CREATE = """
            {"title":" Original ","content":" Body ","amount":100,"contactPhone":"123",
             "images":[{"storageKey":"%s","position":0,"primary":true}]}
            """;
    @Autowired private MockMvc mvc;
    @Autowired private ObjectMapper json;
    @Autowired private PublicationJpaRepository publications;
    @Autowired private PublicationHistoryJpaRepository history;
    @Autowired private ServicePublicationRequestJpaRepository initialRequests;
    @Autowired private ServicePublicationChangeRequestJpaRepository changes;
    @Autowired private EntityManager em;
    @MockitoBean private JwtTokenParser jwtTokenParser;
    @MockitoBean private UserAuthoritiesProvider authoritiesProvider;
    @MockitoBean private AccessTokenAuthenticationService authenticationService;
    @MockitoBean private MembershipAccessQuery membershipAccess;
    @MockitoBean private ImageMediaService media;

    @Test @Transactional
    void initialApprovalChangeApprovalAndOwnerDeleteKeepAuditAndPublishedIdentity() throws Exception {
        authenticate(99L, "PUBLICATIONS_SERVICE_REQUEST_CREATE");
        MvcResult created = mvc.perform(token(post("/service-publication-requests")
                        .contentType(MediaType.APPLICATION_JSON).content(CREATE.formatted(KEY))))
                .andExpect(status().isCreated()).andExpect(jsonPath("$.requestedByUserId").value(99))
                .andExpect(jsonPath("$.status").value("PENDING")).andReturn();
        long requestId = id(created);
        verify(media).requireExisting(KEY);
        assertThat(publications.count()).isZero();

        authenticate(99L, "PUBLICATIONS_SERVICE_REQUEST_READ");
        mvc.perform(token(get("/service-publication-requests/{id}", requestId)))
                .andExpect(status().isOk()).andExpect(jsonPath("$.title").value("Original"))
                .andExpect(jsonPath("$.reviewedByUserId").doesNotExist());
        authenticate(500L, "PUBLICATIONS_SERVICE_REQUEST_ADMIN_READ");
        mvc.perform(token(get("/admin/service-publication-requests?status=PENDING")))
                .andExpect(status().isOk()).andExpect(jsonPath("$.content[0].id").value(requestId))
                .andExpect(jsonPath("$.totalElements").value(1));
        authenticate(500L, "PUBLICATIONS_SERVICE_REQUEST_APPROVE");
        MvcResult approval = mvc.perform(token(post("/admin/service-publication-requests/{id}/approve", requestId)))
                .andExpect(status().isOk()).andExpect(jsonPath("$.status").value("APPROVED"))
                .andExpect(jsonPath("$.reviewedByUserId").value(500))
                .andExpect(jsonPath("$.reviewedByUserName").value(org.hamcrest.Matchers.nullValue()))
                .andExpect(jsonPath("$.createdPublicationTitle").value(org.hamcrest.Matchers.nullValue()))
                .andReturn();
        long serviceId = json.readTree(approval.getResponse().getContentAsString()).get("createdPublicationId").asLong();
        assertThat(serviceId).isPositive();
        mvc.perform(token(post("/admin/service-publication-requests/{id}/approve", requestId)))
                .andExpect(status().isConflict()).andExpect(jsonPath("$.code").value("SERVICE_REQUEST_ALREADY_PROCESSED"));

        authenticate(99L, "PUBLICATIONS_SERVICE_READ");
        MvcResult published = mvc.perform(token(get("/services/{id}", serviceId)))
                .andExpect(status().isOk()).andExpect(jsonPath("$.status").value("PUBLISHED"))
                .andExpect(jsonPath("$.title").value("Original"))
                .andExpect(jsonPath("$.sourceRequestId").value(requestId)).andReturn();
        String publishedAt = json.readTree(published.getResponse().getContentAsString()).get("publishedAt").asString();

        authenticate(99L, "PUBLICATIONS_SERVICE_CHANGE_REQUEST_CREATE");
        MvcResult proposed = mvc.perform(token(patch("/services/{id}", serviceId).contentType(MediaType.APPLICATION_JSON)
                        .content("{\"title\":\"Revised\",\"amount\":150,\"images\":[{\"storageKey\":\"" + NEXT
                                + "\",\"position\":0,\"primary\":true}]}")))
                .andExpect(status().isCreated()).andExpect(jsonPath("$.status").value("PENDING"))
                .andExpect(jsonPath("$.proposedContent").value("Body"))
                .andExpect(jsonPath("$.proposedContactPhone").value("123")).andReturn();
        long changeId = id(proposed);
        verify(media).requireExisting(NEXT);
        authenticate(99L, "PUBLICATIONS_SERVICE_CHANGE_REQUEST_READ");
        mvc.perform(token(get("/service-publication-change-requests/{id}", changeId)))
                .andExpect(status().isOk()).andExpect(jsonPath("$.proposedTitle").value("Revised"))
                .andExpect(jsonPath("$.reviewedByUserId").doesNotExist());
        authenticate(99L, "PUBLICATIONS_SERVICE_CHANGE_REQUEST_CREATE");
        mvc.perform(token(patch("/services/{id}", serviceId).contentType(MediaType.APPLICATION_JSON)
                        .content("{\"amount\":180}")))
                .andExpect(status().isConflict()).andExpect(jsonPath("$.code").value("SERVICE_CHANGE_REQUEST_ALREADY_PENDING"));
        authenticate(99L, "PUBLICATIONS_SERVICE_READ");
        mvc.perform(token(get("/services/{id}", serviceId)))
                .andExpect(status().isOk()).andExpect(jsonPath("$.title").value("Original"))
                .andExpect(jsonPath("$.amount").value(100));

        authenticate(500L, "PUBLICATIONS_SERVICE_CHANGE_REQUEST_ADMIN_READ");
        mvc.perform(token(get("/admin/service-publication-change-requests?status=PENDING")))
                .andExpect(status().isOk()).andExpect(jsonPath("$.content[0].id").value(changeId));
        mvc.perform(token(get("/admin/service-publication-change-requests/{id}", changeId)))
                .andExpect(status().isOk()).andExpect(jsonPath("$.proposedAmount").value(150))
                .andExpect(jsonPath("$.servicePublicationTitle").value(org.hamcrest.Matchers.nullValue()));
        authenticate(500L, "PUBLICATIONS_SERVICE_CHANGE_REQUEST_APPROVE");
        mvc.perform(token(post("/admin/service-publication-change-requests/{id}/approve", changeId)))
                .andExpect(status().isOk()).andExpect(jsonPath("$.status").value("APPROVED"))
                .andExpect(jsonPath("$.servicePublicationTitle").value(org.hamcrest.Matchers.nullValue()))
                .andExpect(jsonPath("$.reviewedByUserName").value(org.hamcrest.Matchers.nullValue()));
        mvc.perform(token(post("/admin/service-publication-change-requests/{id}/approve", changeId)))
                .andExpect(status().isConflict()).andExpect(jsonPath("$.code").value("SERVICE_CHANGE_REQUEST_ALREADY_PROCESSED"));
        authenticate(99L, "PUBLICATIONS_SERVICE_READ");
        mvc.perform(token(get("/services/{id}", serviceId)))
                .andExpect(status().isOk()).andExpect(jsonPath("$.id").value(serviceId))
                .andExpect(jsonPath("$.title").value("Revised"))
                .andExpect(jsonPath("$.amount").value(150))
                .andExpect(jsonPath("$.publishedAt").value(publishedAt));

        authenticate(99L, "PUBLICATIONS_SERVICE_DELETE");
        mvc.perform(token(delete("/services/{id}", serviceId))).andExpect(status().isNoContent());
        assertThat(initialRequests.findById(requestId)).isPresent();
        assertThat(changes.findById(changeId)).isPresent();
        var snapshot = history.findAll().stream().filter(item -> item.getPublicationId().equals(serviceId))
                .map(ServicePublicationHistoryEntity.class::cast).findFirst().orElseThrow();
        assertThat(snapshot.getDeletedByUserId()).isEqualTo(99L);
        assertThat(snapshot.getTitle()).isEqualTo("Revised");
        assertThat(snapshot.getAmount()).isEqualByComparingTo("150.00");
        assertThat(snapshot.getSourceRequestId()).isEqualTo(requestId);
    }

    @Test @Transactional
    void rejectionOwnershipAndPatchValidationAreControlled() throws Exception {
        authenticate(99L, "PUBLICATIONS_SERVICE_REQUEST_CREATE");
        long initial = id(mvc.perform(token(post("/service-publication-requests")
                .contentType(MediaType.APPLICATION_JSON).content(CREATE.formatted(KEY)))).andExpect(status().isCreated()).andReturn());
        authenticate(88L, "PUBLICATIONS_SERVICE_REQUEST_READ");
        mvc.perform(token(get("/service-publication-requests/{id}", initial)))
                .andExpect(status().isNotFound()).andExpect(jsonPath("$.code").value("SERVICE_REQUEST_NOT_FOUND"));
        authenticate(500L, "PUBLICATIONS_SERVICE_REQUEST_REJECT");
        mvc.perform(token(post("/admin/service-publication-requests/{id}/reject", initial)
                        .contentType(MediaType.APPLICATION_JSON).content("{\"rejectionReason\":\" no \"}")))
                .andExpect(status().isOk()).andExpect(jsonPath("$.status").value("REJECTED"))
                .andExpect(jsonPath("$.rejectionReason").value("no"))
                .andExpect(jsonPath("$.createdPublicationTitle").value(org.hamcrest.Matchers.nullValue()))
                .andExpect(jsonPath("$.reviewedByUserName").value(org.hamcrest.Matchers.nullValue()));
        mvc.perform(token(post("/admin/service-publication-requests/{id}/reject", initial)
                        .contentType(MediaType.APPLICATION_JSON).content("{}")))
                .andExpect(status().isConflict());
        assertThat(publications.count()).isZero();

        authenticate(99L, "PUBLICATIONS_SERVICE_REQUEST_CREATE");
        long nextInitial = id(mvc.perform(token(post("/service-publication-requests")
                .contentType(MediaType.APPLICATION_JSON).content(CREATE.formatted(KEY)))).andExpect(status().isCreated()).andReturn());
        authenticate(500L, "PUBLICATIONS_SERVICE_REQUEST_APPROVE");
        long serviceId = json.readTree(mvc.perform(token(post("/admin/service-publication-requests/{id}/approve", nextInitial)))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsString()).get("createdPublicationId").asLong();
        authenticate(88L, "PUBLICATIONS_SERVICE_CHANGE_REQUEST_CREATE");
        mvc.perform(token(patch("/services/{id}", serviceId).contentType(MediaType.APPLICATION_JSON)
                        .content("{\"title\":\"Other\"}")))
                .andExpect(status().isForbidden()).andExpect(jsonPath("$.code").value("SERVICE_NOT_OWNER"));
        authenticate(99L, "PUBLICATIONS_SERVICE_CHANGE_REQUEST_CREATE");
        for (String body : new String[]{"{}", "{\"title\":null}", "{\"amount\":null}",
                "{\"images\":null}", "{\"images\":[]}", "{\"unknown\":1}"}) {
            mvc.perform(token(patch("/services/{id}", serviceId).contentType(MediaType.APPLICATION_JSON).content(body)))
                    .andExpect(status().isBadRequest());
        }
        doThrow(new StorageObjectNotFoundException()).when(media).requireExisting(NEXT);
        mvc.perform(token(patch("/services/{id}", serviceId).contentType(MediaType.APPLICATION_JSON)
                        .content("{\"images\":[{\"storageKey\":\"" + NEXT + "\",\"position\":0,\"primary\":true}]}")))
                .andExpect(status().isNotFound()).andExpect(jsonPath("$.code").value("IMAGE_NOT_FOUND"));
        authenticate(88L, "PUBLICATIONS_SERVICE_DELETE");
        mvc.perform(token(delete("/services/{id}", serviceId)))
                .andExpect(status().isForbidden()).andExpect(jsonPath("$.code").value("SERVICE_NOT_OWNER"));
    }

    @Test @Transactional
    void rejectedChangeDoesNotModifyServiceAndAdminDeleteLeavesPendingChange() throws Exception {
        authenticate(99L, "PUBLICATIONS_SERVICE_REQUEST_CREATE");
        long requestId = id(mvc.perform(token(post("/service-publication-requests")
                .contentType(MediaType.APPLICATION_JSON).content(CREATE.formatted(KEY)))).andExpect(status().isCreated()).andReturn());
        authenticate(500L, "PUBLICATIONS_SERVICE_REQUEST_APPROVE");
        long serviceId = json.readTree(mvc.perform(token(post("/admin/service-publication-requests/{id}/approve", requestId)))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsString()).get("createdPublicationId").asLong();
        authenticate(99L, "PUBLICATIONS_SERVICE_CHANGE_REQUEST_CREATE");
        long rejectedId = id(mvc.perform(token(patch("/services/{id}", serviceId).contentType(MediaType.APPLICATION_JSON)
                .content("{\"title\":\"Rejected title\"}"))).andExpect(status().isCreated()).andReturn());
        authenticate(500L, "PUBLICATIONS_SERVICE_CHANGE_REQUEST_REJECT");
        mvc.perform(token(post("/admin/service-publication-change-requests/{id}/reject", rejectedId)
                .contentType(MediaType.APPLICATION_JSON).content("{\"rejectionReason\":\"no\"}")))
                .andExpect(status().isOk()).andExpect(jsonPath("$.status").value("REJECTED"))
                .andExpect(jsonPath("$.servicePublicationTitle").value(org.hamcrest.Matchers.nullValue()))
                .andExpect(jsonPath("$.reviewedByUserName").value(org.hamcrest.Matchers.nullValue()))
                .andExpect(jsonPath("$.proposedTitle").value("Rejected title"));

        authenticate(500L, "PUBLICATIONS_SERVICE_CHANGE_REQUEST_ADMIN_READ", "PUBLICATIONS_SERVICE_ADMIN_READ");
        mvc.perform(token(get("/admin/service-publication-change-requests/{id}", rejectedId)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("REJECTED"))
                .andExpect(jsonPath("$.servicePublicationTitle").value("Original"))
                .andExpect(jsonPath("$.proposedTitle").value("Rejected title"))
                .andExpect(jsonPath("$.reviewedByUserName").value(org.hamcrest.Matchers.nullValue()));
        authenticate(500L, "PUBLICATIONS_SERVICE_CHANGE_REQUEST_REJECT");
        mvc.perform(token(post("/admin/service-publication-change-requests/{id}/reject", rejectedId)
                .contentType(MediaType.APPLICATION_JSON).content("{}"))).andExpect(status().isConflict());
        authenticate(99L, "PUBLICATIONS_SERVICE_READ");
        mvc.perform(token(get("/services/{id}", serviceId))).andExpect(jsonPath("$.title").value("Original"));
        authenticate(99L, "PUBLICATIONS_SERVICE_CHANGE_REQUEST_CREATE");
        long pendingId = id(mvc.perform(token(patch("/services/{id}", serviceId).contentType(MediaType.APPLICATION_JSON)
                .content("{\"title\":\"Pending title\"}"))).andExpect(status().isCreated()).andReturn());
        authenticate(500L, "PUBLICATIONS_SERVICE_ADMIN_DELETE");
        authenticate(500L, "PUBLICATIONS_SERVICE_ADMIN_READ");
        mvc.perform(token(get("/admin/services/{id}", serviceId)))
                .andExpect(status().isOk()).andExpect(jsonPath("$.title").value("Original"));
        authenticate(500L, "PUBLICATIONS_SERVICE_ADMIN_DELETE");
        mvc.perform(token(delete("/admin/services/{id}", serviceId))).andExpect(status().isNoContent());
        authenticate(500L, "PUBLICATIONS_SERVICE_CHANGE_REQUEST_APPROVE");
        mvc.perform(token(post("/admin/service-publication-change-requests/{id}/approve", pendingId)))
                .andExpect(status().isNotFound()).andExpect(jsonPath("$.code").value("SERVICE_NOT_FOUND"));
        assertThat(changes.findById(pendingId).orElseThrow().getStatus())
                .isEqualTo(com.jeepclub.backend.publications.core.domain.enums.ServicePublicationChangeRequestStatus.PENDING);
    }

    @Test void permissionsMembershipAndNoticeAuthorityAreIsolated() throws Exception {
        mvc.perform(post("/service-publication-requests").contentType(MediaType.APPLICATION_JSON).content(CREATE.formatted(KEY)))
                .andExpect(status().isUnauthorized());
        mvc.perform(get("/admin/service-publication-requests")).andExpect(status().isUnauthorized());
        authenticate(99L, "PUBLICATIONS_NOTICE_CREATE");
        var serviceActions = new org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder[]{
                post("/service-publication-requests").contentType(MediaType.APPLICATION_JSON).content(CREATE.formatted(KEY)),
                get("/service-publication-requests/1"),
                get("/admin/service-publication-requests"),
                get("/admin/service-publication-requests/1"),
                post("/admin/service-publication-requests/1/approve"),
                post("/admin/service-publication-requests/1/reject").contentType(MediaType.APPLICATION_JSON).content("{}"),
                patch("/services/1").contentType(MediaType.APPLICATION_JSON).content("{\"title\":\"Next\"}"),
                delete("/services/1"),
                get("/service-publication-change-requests/1"),
                get("/admin/service-publication-change-requests"),
                get("/admin/service-publication-change-requests/1"),
                post("/admin/service-publication-change-requests/1/approve"),
                post("/admin/service-publication-change-requests/1/reject").contentType(MediaType.APPLICATION_JSON).content("{}"),
                get("/admin/services"),
                get("/admin/services/1"),
                delete("/admin/services/1")
        };
        for (var action : serviceActions) mvc.perform(token(action)).andExpect(status().isForbidden());
        mvc.perform(get("/admin/services")).andExpect(status().isUnauthorized());
        mvc.perform(get("/services/1")).andExpect(status().isUnauthorized());
        mvc.perform(token(get("/services/1"))).andExpect(status().isNotFound());
        authenticate(99L, "PUBLICATIONS_SERVICE_REQUEST_READ");
        mvc.perform(token(post("/admin/service-publication-requests/1/approve"))).andExpect(status().isForbidden());
        authenticate(500L, "PUBLICATIONS_SERVICE_CHANGE_REQUEST_APPROVE");
        mvc.perform(token(post("/admin/service-publication-change-requests/1/reject")
                .contentType(MediaType.APPLICATION_JSON).content("{}"))).andExpect(status().isForbidden());
        authenticate(99L, "PUBLICATIONS_SERVICE_REQUEST_CREATE");
        when(membershipAccess.evaluate(anyLong())).thenReturn(MembershipAccessResult.PAYMENT_REQUIRED);
        mvc.perform(token(post("/service-publication-requests").contentType(MediaType.APPLICATION_JSON).content(CREATE.formatted(KEY))))
                .andExpect(status().isPaymentRequired());
    }

    @Test @Transactional
    void initialPayloadAndMediaFailuresDoNotPersistRequests() throws Exception {
        authenticate(99L, "PUBLICATIONS_SERVICE_REQUEST_CREATE");
        for (String body : new String[]{"{}", "{\"title\":\" \"}",
                CREATE.formatted(KEY).replace("\"title\"", "\"requestedByUserId\":88,\"title\""),
                CREATE.formatted(KEY).replace("\"amount\":100", "\"amount\":0"),
                "{\"title\":\"A\",\"content\":\"B\",\"amount\":100,\"contactPhone\":\"123\",\"images\":[]}"}) {
            mvc.perform(token(post("/service-publication-requests").contentType(MediaType.APPLICATION_JSON).content(body)))
                    .andExpect(status().isBadRequest());
        }
        assertThat(initialRequests.count()).isZero();
        doThrow(new StorageObjectNotFoundException()).when(media).requireExisting(KEY);
        mvc.perform(token(post("/service-publication-requests").contentType(MediaType.APPLICATION_JSON)
                        .content(CREATE.formatted(KEY))))
                .andExpect(status().isNotFound()).andExpect(jsonPath("$.code").value("IMAGE_NOT_FOUND"));
        assertThat(initialRequests.count()).isZero();
    }

    @Test @Transactional
    void adminServiceCatalogAndModerationResolveNamesAndTitlesInBatchWithPermissionAndLifecycleSafety() throws Exception {
        long authorA = persistUser("Oficina Silva", UserStatus.ACTIVE);
        long authorB = persistUser("Guincho Souza", UserStatus.ACTIVE);
        long reviewer = persistUser("Moderador Chefe", UserStatus.ACTIVE);
        long inactiveUser = persistUser("Associado Inativo", UserStatus.INACTIVE);

        // Author A submits request 1 (approved -> Service 1) and request 2 (rejected)
        authenticate(authorA, "PUBLICATIONS_SERVICE_REQUEST_CREATE");
        long req1 = id(mvc.perform(token(post("/service-publication-requests")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"title":"Mecânica %%_! 4x4","content":"Preparação off-road","amount":250,"contactPhone":"11911112222",
                                 "images":[{"storageKey":"%s","position":0,"primary":true}]}
                                """.formatted(KEY))))
                .andExpect(status().isCreated()).andReturn());
        long req2 = id(mvc.perform(token(post("/service-publication-requests")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"title":"Pedido Rejeitado","content":"Conteúdo","amount":120,"contactPhone":"11911113333",
                                 "images":[{"storageKey":"%s","position":0,"primary":true}]}
                                """.formatted(KEY))))
                .andExpect(status().isCreated()).andReturn());

        // Author B submits request 3 (approved -> Service 2, then archived)
        authenticate(authorB, "PUBLICATIONS_SERVICE_REQUEST_CREATE");
        long req3 = id(mvc.perform(token(post("/service-publication-requests")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"title":"Resgate Pesado","content":"Atendimento 24h","amount":450,"contactPhone":"11922223333",
                                 "images":[{"storageKey":"%s","position":0,"primary":true}]}
                                """.formatted(KEY))))
                .andExpect(status().isCreated()).andReturn());

        // Inactive user submits request 4 (remains PENDING)
        authenticate(inactiveUser, "PUBLICATIONS_SERVICE_REQUEST_CREATE");
        long req4 = id(mvc.perform(token(post("/service-publication-requests")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"title":"Pedido Pendente","content":"Aguardando","amount":90,"contactPhone":"11944445555",
                                 "images":[{"storageKey":"%s","position":0,"primary":true}]}
                                """.formatted(KEY))))
                .andExpect(status().isCreated()).andReturn());

        // Reviewer approves req1 and req3, rejects req2, with IDENTITY_USER_READ + PUBLICATIONS_SERVICE_ADMIN_READ
        authenticate(reviewer, "PUBLICATIONS_SERVICE_REQUEST_APPROVE", "PUBLICATIONS_SERVICE_REQUEST_REJECT",
                "PUBLICATIONS_SERVICE_ADMIN_READ", "IDENTITY_USER_READ");
        MvcResult approved1 = mvc.perform(token(post("/admin/service-publication-requests/{id}/approve", req1)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.requestedByUserId").value(authorA))
                .andExpect(jsonPath("$.requestedByUserName").value("Oficina Silva"))
                .andExpect(jsonPath("$.reviewedByUserId").value(reviewer))
                .andExpect(jsonPath("$.reviewedByUserName").value("Moderador Chefe"))
                .andExpect(jsonPath("$.createdPublicationTitle").value("Mecânica %_! 4x4"))
                .andReturn();
        long service1Id = json.readTree(approved1.getResponse().getContentAsString()).get("createdPublicationId").asLong();

        mvc.perform(token(post("/admin/service-publication-requests/{id}/reject", req2)
                        .contentType(MediaType.APPLICATION_JSON).content("{\"rejectionReason\":\"Incompleto\"}")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.requestedByUserName").value("Oficina Silva"))
                .andExpect(jsonPath("$.reviewedByUserName").value("Moderador Chefe"))
                .andExpect(jsonPath("$.createdPublicationTitle").value(org.hamcrest.Matchers.nullValue()));

        long service2Id = json.readTree(mvc.perform(token(post("/admin/service-publication-requests/{id}/approve", req3)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.requestedByUserName").value("Guincho Souza"))
                .andExpect(jsonPath("$.createdPublicationTitle").value("Resgate Pesado"))
                .andReturn().getResponse().getContentAsString()).get("createdPublicationId").asLong();

        // Member self-service GET /services/{id} never exposes administrative authorName even when caller has IDENTITY_USER_READ
        authenticate(authorA, "PUBLICATIONS_SERVICE_READ", "IDENTITY_USER_READ");
        mvc.perform(token(get("/services/{id}", service1Id)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.title").value("Mecânica %_! 4x4"))
                .andExpect(jsonPath("$.authorName").value(org.hamcrest.Matchers.nullValue()));

        // Author A creates a change request on Service 1 proposing a new title
        authenticate(authorA, "PUBLICATIONS_SERVICE_CHANGE_REQUEST_CREATE");
        long change1Id = id(mvc.perform(token(patch("/services/{id}", service1Id)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"title\":\"Mecânica 4x4 Premium\",\"amount\":300}")))
                .andExpect(status().isCreated()).andReturn());

        // Archive Service 2 directly in persistence to verify ARCHIVED listing and title visibility gating
        ServicePublicationEntity service2Entity = em.find(ServicePublicationEntity.class, service2Id);
        Instant archivedAt = service2Entity.getUpdatedAt().plusSeconds(5);
        service2Entity.setStatus(PublicationStatus.ARCHIVED);
        service2Entity.setArchivedAt(archivedAt);
        service2Entity.setUpdatedAt(archivedAt);
        em.flush();
        em.clear();
        Sql.statements.get().clear();

        // 1. Admin Service Catalog GET /admin/services (BACK-567 + BACK-725)
        authenticate(reviewer, "PUBLICATIONS_SERVICE_ADMIN_READ", "IDENTITY_USER_READ");
        mvc.perform(token(get("/admin/services").param("sort", "amount,asc")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.totalElements").value(2))
                .andExpect(jsonPath("$.content[0].id").value(service1Id))
                .andExpect(jsonPath("$.content[0].title").value("Mecânica %_! 4x4"))
                .andExpect(jsonPath("$.content[0].authorName").value("Oficina Silva"))
                .andExpect(jsonPath("$.content[1].id").value(service2Id))
                .andExpect(jsonPath("$.content[1].status").value("ARCHIVED"))
                .andExpect(jsonPath("$.content[1].authorName").value("Guincho Souza"));
        assertThat(Sql.statements.get().stream().filter(sql -> sql.contains("identity_users")).count()).isEqualTo(1);

        // Literal search and status filter on GET /admin/services; without IDENTITY_USER_READ authorName is null
        authenticate(reviewer, "PUBLICATIONS_SERVICE_ADMIN_READ");
        mvc.perform(token(get("/admin/services").param("status", "PUBLISHED").param("q", "%_!")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.totalElements").value(1))
                .andExpect(jsonPath("$.content[0].id").value(service1Id))
                .andExpect(jsonPath("$.content[0].authorName").value(org.hamcrest.Matchers.nullValue()));

        // Out-of-range page preserves totalElements and totalPages with empty content
        mvc.perform(token(get("/admin/services").param("page", "5").param("size", "1")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.content.length()").value(0))
                .andExpect(jsonPath("$.totalElements").value(2))
                .andExpect(jsonPath("$.totalPages").value(2));

        // Missing PUBLICATIONS_SERVICE_ADMIN_READ returns 403
        authenticate(reviewer, "IDENTITY_USER_READ");
        mvc.perform(token(get("/admin/services")))
                .andExpect(status().isForbidden());
        authenticate(reviewer, "PUBLICATIONS_SERVICE_ADMIN_READ");

        // DRAFT is rejected with 400 on GET /admin/services
        for (String[] bad : new String[][]{
                {"status", "DRAFT"},
                {"status", "INVALID"},
                {"sort", "unknownField,asc"},
                {"page", "-1"},
                {"size", "0"},
                {"size", "51"},
                {"q", "x".repeat(201)}
        }) {
            mvc.perform(token(get("/admin/services").param(bad[0], bad[1])))
                    .andExpect(status().isBadRequest())
                    .andExpect(jsonPath("$.code").value("SERVICE_INVALID_REQUEST"));
        }

        // 2. Service Requests GET /admin/service-publication-requests (BACK-728)
        // With IDENTITY_USER_READ + PUBLICATIONS_SERVICE_ADMIN_READ: sees all active user names + both PUBLISHED and ARCHIVED titles
        Sql.statements.get().clear();
        authenticate(reviewer, "PUBLICATIONS_SERVICE_REQUEST_ADMIN_READ", "PUBLICATIONS_SERVICE_ADMIN_READ", "IDENTITY_USER_READ");
        mvc.perform(token(get("/admin/service-publication-requests").param("sort", "id,asc")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.totalElements").value(4))
                .andExpect(jsonPath("$.content[0].id").value(req1))
                .andExpect(jsonPath("$.content[0].requestedByUserName").value("Oficina Silva"))
                .andExpect(jsonPath("$.content[0].reviewedByUserName").value("Moderador Chefe"))
                .andExpect(jsonPath("$.content[0].createdPublicationTitle").value("Mecânica %_! 4x4"))
                .andExpect(jsonPath("$.content[1].id").value(req2))
                .andExpect(jsonPath("$.content[1].createdPublicationTitle").value(org.hamcrest.Matchers.nullValue()))
                .andExpect(jsonPath("$.content[2].id").value(req3))
                .andExpect(jsonPath("$.content[2].requestedByUserName").value("Guincho Souza"))
                .andExpect(jsonPath("$.content[2].createdPublicationTitle").value("Resgate Pesado"))
                .andExpect(jsonPath("$.content[3].id").value(req4))
                .andExpect(jsonPath("$.content[3].requestedByUserName").value(org.hamcrest.Matchers.nullValue()))
                .andExpect(jsonPath("$.content[3].reviewedByUserName").value(org.hamcrest.Matchers.nullValue()));
        assertThat(Sql.statements.get().stream().filter(sql -> sql.contains("identity_users")).count()).isEqualTo(1);
        assertThat(Sql.statements.get().stream().filter(sql -> sql.contains("publication_services")).count()).isEqualTo(1);

        // Without IDENTITY_USER_READ and without PUBLICATIONS_SERVICE_ADMIN_READ:
        // names are null and createdPublicationTitle is null for BOTH PUBLISHED and ARCHIVED services (fail-closed, no 403, no extra queries)
        Sql.statements.get().clear();
        authenticate(reviewer, "PUBLICATIONS_SERVICE_REQUEST_ADMIN_READ");
        mvc.perform(token(get("/admin/service-publication-requests").param("sort", "id,asc")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.content[0].requestedByUserName").value(org.hamcrest.Matchers.nullValue()))
                .andExpect(jsonPath("$.content[0].reviewedByUserName").value(org.hamcrest.Matchers.nullValue()))
                .andExpect(jsonPath("$.content[0].createdPublicationTitle").value(org.hamcrest.Matchers.nullValue()))
                .andExpect(jsonPath("$.content[2].createdPublicationTitle").value(org.hamcrest.Matchers.nullValue()));
        assertThat(Sql.statements.get().stream().filter(sql -> sql.contains("identity_users")).count()).isZero();
        assertThat(Sql.statements.get().stream().filter(sql -> sql.contains("publication_services")).count()).isZero();

        // 3. Service Change Requests GET/POST /admin/service-publication-change-requests (BACK-729)
        authenticate(reviewer, "PUBLICATIONS_SERVICE_REQUEST_ADMIN_READ", "PUBLICATIONS_SERVICE_ADMIN_READ");
        mvc.perform(token(get("/admin/service-publication-requests/{id}", req3)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.createdPublicationTitle").value("Resgate Pesado"))
                .andExpect(jsonPath("$.requestedByUserName").value(org.hamcrest.Matchers.nullValue()))
                .andExpect(jsonPath("$.reviewedByUserName").value(org.hamcrest.Matchers.nullValue()));

        authenticate(reviewer, "PUBLICATIONS_SERVICE_REQUEST_ADMIN_READ", "IDENTITY_USER_READ");
        mvc.perform(token(get("/admin/service-publication-requests/{id}", req3)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.createdPublicationTitle").value(org.hamcrest.Matchers.nullValue()))
                .andExpect(jsonPath("$.requestedByUserName").value("Guincho Souza"))
                .andExpect(jsonPath("$.reviewedByUserName").value("Moderador Chefe"));

        // Without PUBLICATIONS_SERVICE_ADMIN_READ: servicePublicationTitle is null while proposedTitle remains visible
        authenticate(reviewer, "PUBLICATIONS_SERVICE_CHANGE_REQUEST_ADMIN_READ", "IDENTITY_USER_READ");
        mvc.perform(token(get("/admin/service-publication-change-requests")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.content[0].id").value(change1Id))
                .andExpect(jsonPath("$.content[0].servicePublicationId").value(service1Id))
                .andExpect(jsonPath("$.content[0].servicePublicationTitle").value(org.hamcrest.Matchers.nullValue()))
                .andExpect(jsonPath("$.content[0].proposedTitle").value("Mecânica 4x4 Premium"))
                .andExpect(jsonPath("$.content[0].requestedByUserName").value("Oficina Silva"))
                .andExpect(jsonPath("$.content[0].reviewedByUserName").value(org.hamcrest.Matchers.nullValue()));

        // With PUBLICATIONS_SERVICE_ADMIN_READ before approval: servicePublicationTitle is "Mecânica %_! 4x4" while proposedTitle is "Mecânica 4x4 Premium"
        Sql.statements.get().clear();
        authenticate(reviewer, "PUBLICATIONS_SERVICE_CHANGE_REQUEST_ADMIN_READ", "PUBLICATIONS_SERVICE_ADMIN_READ", "IDENTITY_USER_READ");
        mvc.perform(token(get("/admin/service-publication-change-requests")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.content[0].servicePublicationTitle").value("Mecânica %_! 4x4"))
                .andExpect(jsonPath("$.content[0].proposedTitle").value("Mecânica 4x4 Premium"));
        assertThat(Sql.statements.get().stream().filter(sql -> sql.contains("identity_users")).count()).isEqualTo(1);
        assertThat(Sql.statements.get().stream().filter(sql -> sql.contains("publication_services")).count()).isEqualTo(1);

        // Approve change request: servicePublicationTitle reflects the newly applied title when authorized
        authenticate(reviewer, "PUBLICATIONS_SERVICE_CHANGE_REQUEST_APPROVE", "PUBLICATIONS_SERVICE_ADMIN_READ", "IDENTITY_USER_READ");
        mvc.perform(token(post("/admin/service-publication-change-requests/{id}/approve", change1Id)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("APPROVED"))
                .andExpect(jsonPath("$.servicePublicationTitle").value("Mecânica 4x4 Premium"))
                .andExpect(jsonPath("$.proposedTitle").value("Mecânica 4x4 Premium"))
                .andExpect(jsonPath("$.requestedByUserName").value("Oficina Silva"))
                .andExpect(jsonPath("$.reviewedByUserName").value("Moderador Chefe"));

        // Hard delete Service 1: createdPublicationTitle and servicePublicationTitle become null without inventing labels
        authenticate(reviewer, "PUBLICATIONS_SERVICE_ADMIN_DELETE", "PUBLICATIONS_SERVICE_ADMIN_READ",
                "PUBLICATIONS_SERVICE_REQUEST_ADMIN_READ",
                "PUBLICATIONS_SERVICE_CHANGE_REQUEST_ADMIN_READ", "IDENTITY_USER_READ");
        mvc.perform(token(delete("/admin/services/{id}", service1Id))).andExpect(status().isNoContent());
        mvc.perform(token(get("/admin/service-publication-requests/{id}", req1)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.createdPublicationId").value(service1Id))
                .andExpect(jsonPath("$.createdPublicationTitle").value(org.hamcrest.Matchers.nullValue()));
        mvc.perform(token(get("/admin/service-publication-change-requests/{id}", change1Id)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.servicePublicationId").value(service1Id))
                .andExpect(jsonPath("$.servicePublicationTitle").value(org.hamcrest.Matchers.nullValue()))
                .andExpect(jsonPath("$.proposedTitle").value("Mecânica 4x4 Premium"));
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

    private void authenticate(long userId, String... permissions) {
        when(jwtTokenParser.parseAndValidate("service-token")).thenReturn(
                new JwtAuthenticatedUser(userId, userId + 1000, "Service User", Instant.now().plusSeconds(3600)));
        when(authoritiesProvider.findAuthorityCodesByUserId(userId)).thenReturn(List.of(permissions));
        when(membershipAccess.evaluate(anyLong())).thenReturn(MembershipAccessResult.ALLOWED);
    }
    private static org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder token(
            org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder request) {
        return request.header(AUTHORIZATION, "Bearer service-token");
    }
    private long id(MvcResult result) throws Exception {
        return json.readTree(result.getResponse().getContentAsString()).get("id").asLong();
    }
}
