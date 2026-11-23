package com.jeepclub.backend.publications.api.http;

import com.jeepclub.backend.iam.authentication.core.application.service.security.AccessTokenAuthenticationService;
import com.jeepclub.backend.iam.identity.api.module.UserStatus;
import com.jeepclub.backend.iam.identity.infra.persistence.entity.UserEntity;
import com.jeepclub.backend.memberships.api.module.*;
import com.jeepclub.backend.platform.security.authorization.UserAuthoritiesProvider;
import com.jeepclub.backend.platform.security.jwt.JwtAuthenticatedUser;
import com.jeepclub.backend.platform.security.jwt.JwtTokenParser;
import com.jeepclub.backend.platform.storage.image.ImageMediaService;
import com.jeepclub.backend.publications.core.domain.model.*;
import com.jeepclub.backend.publications.core.repository.PublicationRepository;
import com.jeepclub.backend.publications.core.repository.ServicePublicationRequestRepository;
import com.jeepclub.backend.publications.core.application.service.AdminServicePublicationRequestService;
import com.jeepclub.backend.publications.infra.persistence.entity.EventRegistrationEntity;
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
import org.springframework.transaction.annotation.Transactional;
import java.time.Instant;
import java.math.BigDecimal;
import java.util.List;
import java.util.UUID;
import static org.hamcrest.Matchers.*;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.*;
import static org.springframework.http.HttpHeaders.AUTHORIZATION;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@SpringBootTest(properties = "spring.jpa.properties.hibernate.session_factory.statement_inspector=com.jeepclub.backend.publications.api.http.PublicationSocialFeedHttpIntegrationTest$Sql")
@AutoConfigureMockMvc
@ActiveProfiles("test")
class PublicationSocialFeedHttpIntegrationTest {
    private static final String KEY = "images/2026/09/27/550e8400-e29b-41d4-a716-446655440000.jpg";
    private static final Instant NOW = Instant.parse("2026-09-27T12:00:00Z");
    @Autowired private MockMvc mvc;
    @Autowired private EntityManager em;
    @Autowired private PublicationRepository publications;
    @Autowired private ServicePublicationRequestRepository requests;
    @Autowired private AdminServicePublicationRequestService adminRequests;
    @MockitoBean private JwtTokenParser jwt;
    @MockitoBean private UserAuthoritiesProvider authorities;
    @MockitoBean private AccessTokenAuthenticationService authentication;
    @MockitoBean private MembershipAccessQuery membership;
    @MockitoBean private ImageMediaService media;

    @Test @Transactional void publishedNoticeSupportsLikeCommentAndFeedSummaryForTwoMembers() throws Exception {
        var notice = Notice.create(7L, "Trail notice", "Body", List.of(new PublicationImage(KEY, 0, true)), NOW);
        notice.publish(NOW.plusSeconds(1));
        long id = publications.save(notice).getId();
        var event = Event.create(7L, "Trail event", "Body", List.of(new PublicationImage(KEY, 0, true)),
                NOW.plusSeconds(3600), NOW);
        event.publish(NOW.plusSeconds(1));
        long eventId = publications.save(event).getId();
        var request = requests.save(ServicePublicationRequest.create(7L, "Trail service", "Body",
                new BigDecimal("12.00"), "123456789", List.of(new PublicationImage(KEY, 0, true)), NOW));
        long serviceId = adminRequests.approve(request.getId(), 8L).getCreatedPublicationId();
        when(membership.evaluate(anyLong())).thenReturn(MembershipAccessResult.ALLOWED);
        authenticate(9L);
        mvc.perform(post("/publications/{id}/likes", id).header(AUTHORIZATION, "Bearer social-token"))
                .andExpect(status().isOk());
        mvc.perform(post("/publications/{id}/likes", id).header(AUTHORIZATION, "Bearer social-token"))
                .andExpect(status().isOk());
        mvc.perform(post("/publications/{id}/comments", id).header(AUTHORIZATION, "Bearer social-token")
                        .contentType(MediaType.APPLICATION_JSON).content("{\"text\":\"Hello\",\"images\":[]}"))
                .andExpect(status().isCreated());
        mvc.perform(post("/publications/{id}/comments", eventId).header(AUTHORIZATION, "Bearer social-token")
                        .contentType(MediaType.APPLICATION_JSON).content("{\"text\":\"Event discussion\"}"))
                .andExpect(status().isCreated());
        mvc.perform(post("/publications/{id}/likes", serviceId).header(AUTHORIZATION, "Bearer social-token"))
                .andExpect(status().isOk());
        mvc.perform(get("/publications/feed").header(AUTHORIZATION, "Bearer social-token"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.content[?(@.id==" + id + ")].type").value(org.hamcrest.Matchers.hasItem("NOTICE")))
                .andExpect(jsonPath("$.content[?(@.id==" + eventId + ")].type").value(org.hamcrest.Matchers.hasItem("EVENT")))
                .andExpect(jsonPath("$.content[?(@.id==" + serviceId + ")].type").value(org.hamcrest.Matchers.hasItem("SERVICE")))
                .andExpect(jsonPath("$.content[?(@.id==" + serviceId + ")].likedByMe").value(org.hamcrest.Matchers.hasItem(true)));
        mvc.perform(get("/publications/feed").header(AUTHORIZATION, "Bearer social-token").param("type", "NOTICE"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.content[?(@.id==" + id + ")].likeCount").value(org.hamcrest.Matchers.hasItem(1)))
                .andExpect(jsonPath("$.content[?(@.id==" + id + ")].commentCount").value(org.hamcrest.Matchers.hasItem(1)))
                .andExpect(jsonPath("$.content[?(@.id==" + id + ")].likedByMe").value(org.hamcrest.Matchers.hasItem(true)));
        mvc.perform(get("/publications/{id}/comments", id).header(AUTHORIZATION, "Bearer social-token"))
                .andExpect(status().isOk()).andExpect(jsonPath("$.content[0].text").value("Hello"));
        authenticate(10L);
        mvc.perform(get("/publications/{id}", id).header(AUTHORIZATION, "Bearer social-token"))
                .andExpect(status().isOk()).andExpect(jsonPath("$.likedByMe").value(false))
                .andExpect(jsonPath("$.images[0].storageKey").value(KEY));
        mvc.perform(get("/publications/{id}", eventId).header(AUTHORIZATION, "Bearer social-token"))
                .andExpect(status().isOk()).andExpect(jsonPath("$.type").value("EVENT"))
                .andExpect(jsonPath("$.details.status").exists());
    }

    @Test @Transactional void draftIsHiddenAndCommentValidationUsesProblemDetails() throws Exception {
        var draft = (Notice) publications.save(Notice.create(7L, "Private", "Body", List.of(new PublicationImage(KEY, 0, true)), NOW));
        long id = draft.getId();
        when(membership.evaluate(anyLong())).thenReturn(MembershipAccessResult.ALLOWED);
        authenticate(9L);
        mvc.perform(post("/publications/{id}/likes", id).header(AUTHORIZATION, "Bearer social-token"))
                .andExpect(status().isNotFound()).andExpect(jsonPath("$.code").value("PUBLICATION_NOT_FOUND"));
        mvc.perform(get("/publications/{id}", id).header(AUTHORIZATION, "Bearer social-token"))
                .andExpect(status().isNotFound()).andExpect(jsonPath("$.code").value("PUBLICATION_NOT_FOUND"));
        draft.publish(NOW.plusSeconds(1));
        publications.save(draft);
        mvc.perform(post("/publications/{id}/comments", id).header(AUTHORIZATION, "Bearer social-token")
                        .contentType(MediaType.APPLICATION_JSON).content("{\"text\":\"  \",\"images\":[]}"))
                .andExpect(status().isBadRequest()).andExpect(jsonPath("$.code").value("PUBLICATION_COMMENT_INVALID"));
        doThrow(new StorageObjectNotFoundException()).when(media).requireExisting(KEY);
        mvc.perform(post("/publications/{id}/comments", id).header(AUTHORIZATION, "Bearer social-token")
                        .contentType(MediaType.APPLICATION_JSON).content("{\"images\":[{\"storageKey\":\"" + KEY + "\",\"position\":0}]}"))
                .andExpect(status().isNotFound()).andExpect(jsonPath("$.code").value("PUBLICATION_COMMENT_IMAGE_NOT_FOUND"));
    }

    @Test @Transactional void memberWithoutPublicationPermissionsCanReadAndInteract() throws Exception {
        var notice = Notice.create(7L, "Published", "Body", List.of(new PublicationImage(KEY, 0, true)), NOW);
        notice.publish(NOW.plusSeconds(1));
        long id = publications.save(notice).getId();
        var event = Event.create(7L, "Event", "Body", List.of(new PublicationImage(KEY, 0, true)), NOW.plusSeconds(3600), NOW);
        event.publish(NOW.plusSeconds(1));
        long eventId = publications.save(event).getId();
        var request = requests.save(ServicePublicationRequest.create(7L, "Service", "Body", new BigDecimal("12.00"), "123456789", List.of(new PublicationImage(KEY, 0, true)), NOW));
        long serviceId = adminRequests.approve(request.getId(), 8L).getCreatedPublicationId();
        long draftId = publications.save(Notice.create(7L, "Draft", "Body", List.of(new PublicationImage(KEY, 0, true)), NOW)).getId();
        var archived = Notice.create(7L, "Archived", "Body", List.of(new PublicationImage(KEY, 0, true)), NOW);
        archived.publish(NOW.plusSeconds(1));
        archived.archive(NOW.plusSeconds(2));
        long archivedId = publications.save(archived).getId();
        when(membership.evaluate(anyLong())).thenReturn(MembershipAccessResult.ALLOWED);
        authenticate(9L);
        when(authorities.findAuthorityCodesByUserId(9L)).thenReturn(List.of());
        for (String path : List.of("/publications/feed?size=1", "/publications/" + id, "/publications/" + id + "/comments", "/events/" + eventId, "/services/" + serviceId)) {
            mvc.perform(get(path).header(AUTHORIZATION, "Bearer social-token")).andExpect(status().isOk());
            mvc.perform(get(path)).andExpect(status().isUnauthorized());
        }
        mvc.perform(get("/publications/{id}", draftId).header(AUTHORIZATION, "Bearer social-token")).andExpect(status().isNotFound());
        mvc.perform(get("/publications/{id}/comments", draftId).header(AUTHORIZATION, "Bearer social-token")).andExpect(status().isNotFound());
        mvc.perform(get("/publications/{id}", archivedId).header(AUTHORIZATION, "Bearer social-token")).andExpect(status().isNotFound());
        mvc.perform(get("/publications/{id}/comments", archivedId).header(AUTHORIZATION, "Bearer social-token")).andExpect(status().isNotFound());
        mvc.perform(post("/publications/{id}/likes", id).header(AUTHORIZATION, "Bearer social-token")).andExpect(status().isOk());
        mvc.perform(post("/publications/{id}/likes", id).header(AUTHORIZATION, "Bearer social-token")).andExpect(status().isOk());
        mvc.perform(post("/publications/{id}/comments", id).header(AUTHORIZATION, "Bearer social-token")
                .contentType(MediaType.APPLICATION_JSON).content("{\"text\":\"Member comment\"}"))
                .andExpect(status().isCreated()).andExpect(jsonPath("$.authorUserId").value(9));
        authenticate(10L);
        when(authorities.findAuthorityCodesByUserId(10L)).thenReturn(List.of());
        mvc.perform(post("/publications/{id}/likes", id).header(AUTHORIZATION, "Bearer social-token")).andExpect(status().isOk());
        authenticate(9L);
        when(authorities.findAuthorityCodesByUserId(9L)).thenReturn(List.of());
        for (int i = 0; i < 2; i++) {
            mvc.perform(delete("/publications/{id}/likes/me", id).header(AUTHORIZATION, "Bearer social-token")).andExpect(status().isNoContent());
        }
        mvc.perform(get("/publications/{id}", id).header(AUTHORIZATION, "Bearer social-token"))
                .andExpect(status().isOk()).andExpect(jsonPath("$.likeCount").value(1)).andExpect(jsonPath("$.likedByMe").value(false));
        mvc.perform(post("/publications/{id}/likes", id)).andExpect(status().isUnauthorized());
        mvc.perform(delete("/publications/{id}/likes/me", id)).andExpect(status().isUnauthorized());
        mvc.perform(post("/publications/{id}/comments", id).contentType(MediaType.APPLICATION_JSON).content("{\"text\":\"x\"}")).andExpect(status().isUnauthorized());
        var archivedEvent = publications.findById(eventId).orElseThrow();
        archivedEvent.archive(NOW.plusSeconds(2)); publications.save(archivedEvent);
        var archivedService = publications.findById(serviceId).orElseThrow();
        archivedService.archive(archivedService.getUpdatedAt().plusSeconds(1)); publications.save(archivedService);
        mvc.perform(get("/events/{id}", eventId).header(AUTHORIZATION, "Bearer social-token")).andExpect(status().isNotFound());
        mvc.perform(get("/services/{id}", serviceId).header(AUTHORIZATION, "Bearer social-token")).andExpect(status().isNotFound());
        when(membership.evaluate(anyLong())).thenReturn(MembershipAccessResult.PAYMENT_REQUIRED);
        for (String path : List.of("/publications/feed", "/publications/" + id,
                "/publications/" + id + "/comments", "/events/" + eventId, "/services/" + serviceId)) {
            mvc.perform(get(path).header(AUTHORIZATION, "Bearer social-token")).andExpect(status().isPaymentRequired());
        }
    }

    @Test @Transactional void socialWritesHonorMembershipAndPublicationVisibility() throws Exception {
        var notice = Notice.create(7L, "Published", "Body", List.of(new PublicationImage(KEY, 0, true)), NOW);
        notice.publish(NOW.plusSeconds(1));
        long id = publications.save(notice).getId();
        long draftId = publications.save(Notice.create(7L, "Draft", "Body", List.of(new PublicationImage(KEY, 0, true)), NOW)).getId();
        var archived = Notice.create(7L, "Archived", "Body", List.of(new PublicationImage(KEY, 0, true)), NOW);
        archived.publish(NOW.plusSeconds(1)); archived.archive(NOW.plusSeconds(2));
        long archivedId = publications.save(archived).getId();
        authenticate(9L);
        when(authorities.findAuthorityCodesByUserId(9L)).thenReturn(List.of());
        when(membership.evaluate(anyLong())).thenReturn(MembershipAccessResult.ALLOWED);
        long requestId;
        do {
            requestId = requests.save(ServicePublicationRequest.create(7L, "Pending", "Body", new BigDecimal("12.00"),
                    "123456789", List.of(new PublicationImage(KEY, 0, true)), NOW)).getId();
        } while (publications.findById(requestId).isPresent());
        mvc.perform(post("/admin/notices").header(AUTHORIZATION, "Bearer social-token")
                .contentType(MediaType.APPLICATION_JSON).content("{}"))
                .andExpect(status().isForbidden());
        for (long inaccessible : List.of(draftId, archivedId, Long.MAX_VALUE, requestId)) {
            mvc.perform(post("/publications/{id}/likes", inaccessible).header(AUTHORIZATION, "Bearer social-token")).andExpect(status().isNotFound());
            mvc.perform(delete("/publications/{id}/likes/me", inaccessible).header(AUTHORIZATION, "Bearer social-token")).andExpect(status().isNotFound());
            mvc.perform(post("/publications/{id}/comments", inaccessible).header(AUTHORIZATION, "Bearer social-token")
                    .contentType(MediaType.APPLICATION_JSON).content("{\"text\":\"x\"}")).andExpect(status().isNotFound());
        }
        for (var blocked : List.of(MembershipAccessResult.PAYMENT_REQUIRED, MembershipAccessResult.CHARGE_NOT_FOUND)) {
            when(membership.evaluate(anyLong())).thenReturn(blocked);
            int expected = blocked == MembershipAccessResult.PAYMENT_REQUIRED ? 402 : 503;
            String code = blocked == MembershipAccessResult.PAYMENT_REQUIRED ? "MEMBERSHIP_PAYMENT_REQUIRED" : "MEMBERSHIP_CHARGE_UNAVAILABLE";
            mvc.perform(post("/publications/{id}/likes", id).header(AUTHORIZATION, "Bearer social-token"))
                    .andExpect(status().is(expected)).andExpect(jsonPath("$.code").value(code));
            mvc.perform(delete("/publications/{id}/likes/me", id).header(AUTHORIZATION, "Bearer social-token"))
                    .andExpect(status().is(expected)).andExpect(jsonPath("$.code").value(code));
            mvc.perform(post("/publications/{id}/comments", id).header(AUTHORIZATION, "Bearer social-token")
                    .contentType(MediaType.APPLICATION_JSON).content("{\"text\":\"x\"}"))
                    .andExpect(status().is(expected)).andExpect(jsonPath("$.code").value(code));
        }
        when(membership.evaluate(anyLong())).thenReturn(MembershipAccessResult.ALLOWED);
        mvc.perform(get("/publications/{id}", id).header(AUTHORIZATION, "Bearer social-token"))
                .andExpect(status().isOk()).andExpect(jsonPath("$.likeCount").value(0)).andExpect(jsonPath("$.commentCount").value(0));
    }

    @Test @Transactional void feedHumanizesAuthorsEventParticipantsAndAppliesMultiTypeAuthorTextAndSortFilters() throws Exception {
        long activeAuthor = persistUser("Autora Feed", "images/2026/09/27/550e8400-e29b-41d4-a716-446655440009.jpg", UserStatus.ACTIVE);
        long inactiveAuthor = persistUser("Autor Inativo Feed", null, UserStatus.INACTIVE);

        long[] confirmedMembers = new long[6];
        for (int i = 0; i < 6; i++) {
            confirmedMembers[i] = persistUser("Confirmado " + (i + 1), i % 2 == 0 ? KEY : null, UserStatus.ACTIVE);
        }
        long pendingMember = persistUser("Pendente Pagamento", KEY, UserStatus.ACTIVE);
        long cancelledMember = persistUser("Cancelado Membro", KEY, UserStatus.ACTIVE);

        var notice = Notice.create(activeAuthor, "Aviso %_! Trilha", "Conteúdo exclusivo", List.of(new PublicationImage(KEY, 0, true)), NOW);
        notice.publish(NOW.plusSeconds(1));
        long noticeId = publications.save(notice).getId();

        var event = Event.create(inactiveAuthor, "Expedição Serra", "Evento 4x4", List.of(new PublicationImage(KEY, 0, true)), NOW.plusSeconds(3600), NOW);
        event.publish(NOW.plusSeconds(2));
        long eventId = publications.save(event).getId();

        var request = requests.save(ServicePublicationRequest.create(activeAuthor, "Oficina %_! 4x4", "Serviço revisado",
                new BigDecimal("180.00"), "11999990000", List.of(new PublicationImage(KEY, 0, true)), NOW));
        long serviceId = adminRequests.approve(request.getId(), activeAuthor).getCreatedPublicationId();

        // Register 6 CONFIRMED, 1 PENDING_PAYMENT, 1 CANCELLED on event
        for (long memberId : confirmedMembers) {
            persistRegistration(eventId, memberId, "CONFIRMED");
        }
        persistRegistration(eventId, pendingMember, "PENDING_PAYMENT");
        persistRegistration(eventId, cancelledMember, "CANCELLED");
        em.flush();
        em.clear();

        when(membership.evaluate(anyLong())).thenReturn(MembershipAccessResult.ALLOWED);
        authenticate(confirmedMembers[0]);

        // 1. Multi-type filter + authorUserId + literal q search + sort
        mvc.perform(get("/publications/feed")
                        .header(AUTHORIZATION, "Bearer social-token")
                        .param("types", "NOTICE,SERVICE")
                        .param("authorUserId", Long.toString(activeAuthor))
                        .param("q", "%_!")
                        .param("sort", "title,asc"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.totalElements").value(2))
                .andExpect(jsonPath("$.content[0].id").value(noticeId))
                .andExpect(jsonPath("$.content[0].authorName").value("Autora Feed"))
                .andExpect(jsonPath("$.content[0].authorProfilePhotoStorageKey").value("images/2026/09/27/550e8400-e29b-41d4-a716-446655440009.jpg"))
                .andExpect(jsonPath("$.content[0].author.userId").value(activeAuthor))
                .andExpect(jsonPath("$.content[0].author.name").value("Autora Feed"))
                .andExpect(jsonPath("$.content[0].author.cpf").doesNotExist())
                .andExpect(jsonPath("$.content[1].id").value(serviceId));

        // 2. Event in feed and detail: inactive author -> null fallback; 6 CONFIRMED -> registrationCount=6 and preview capped at 5
        mvc.perform(get("/publications/feed")
                        .header(AUTHORIZATION, "Bearer social-token")
                        .param("type", "EVENT")
                        .param("authorUserId", Long.toString(inactiveAuthor)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.totalElements").value(1))
                .andExpect(jsonPath("$.content[0].id").value(eventId))
                .andExpect(jsonPath("$.content[0].authorUserId").value(inactiveAuthor))
                .andExpect(jsonPath("$.content[0].authorName").value(nullValue()))
                .andExpect(jsonPath("$.content[0].author").value(nullValue()))
                .andExpect(jsonPath("$.content[0].details.registrationCount").value(6))
                .andExpect(jsonPath("$.content[0].details.participantsPreview.length()").value(5))
                .andExpect(jsonPath("$.content[0].details.participants.length()").value(5))
                .andExpect(jsonPath("$.content[0].details.participantsPreview[0].userId").value(confirmedMembers[0]))
                .andExpect(jsonPath("$.content[0].details.participantsPreview[0].name").value("Confirmado 1"))
                .andExpect(jsonPath("$.content[0].details.participantsPreview[*].name", not(hasItem("Pendente Pagamento"))))
                .andExpect(jsonPath("$.content[0].details.participantsPreview[*].name", not(hasItem("Cancelado Membro"))));

        mvc.perform(get("/publications/{id}", eventId).header(AUTHORIZATION, "Bearer social-token"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.authorName").value(nullValue()))
                .andExpect(jsonPath("$.details.registrationCount").value(6))
                .andExpect(jsonPath("$.details.participantsPreview.length()").value(5))
                .andExpect(jsonPath("$.details.participants.length()").value(5));

        // 3. Out-of-range page preserves totalElements and totalPages with empty content (BACK-569)
        mvc.perform(get("/publications/feed")
                        .header(AUTHORIZATION, "Bearer social-token")
                        .param("page", "5")
                        .param("size", "2"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.content.length()").value(0))
                .andExpect(jsonPath("$.totalElements").value(3))
                .andExpect(jsonPath("$.totalPages").value(2));

        // 4. Invalid filters and conflicts return 400 PUBLICATION_FEED_INVALID
        mvc.perform(get("/publications/feed")
                        .header(AUTHORIZATION, "Bearer social-token")
                        .param("type", "NOTICE")
                        .param("types", "EVENT"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("PUBLICATION_FEED_INVALID"));

        for (String[] bad : new String[][]{
                {"types", "INVALID"},
                {"authorUserId", "0"},
                {"sort", "amount,asc"},
                {"size", "51"},
                {"q", "x".repeat(201)}
        }) {
            mvc.perform(get("/publications/feed")
                            .header(AUTHORIZATION, "Bearer social-token")
                            .param(bad[0], bad[1]))
                    .andExpect(status().isBadRequest())
                    .andExpect(jsonPath("$.code").value("PUBLICATION_FEED_INVALID"));
        }
    }

    @Test @Transactional void feedBackfillsActiveParticipantsInBatchAcrossMultipleEventsWithoutNPlus1AndUpdatesAfterCancel() throws Exception {
        long author = persistUser("Organizador Trilha", KEY, UserStatus.ACTIVE);
        long[] inactiveUsers = new long[27];
        for (int i = 0; i < 27; i++) {
            inactiveUsers[i] = persistUser("Inativo " + (i + 1), null, UserStatus.INACTIVE);
        }
        long[] activeUsersA = new long[5];
        for (int i = 0; i < 5; i++) {
            activeUsersA[i] = persistUser("AtivoA " + (i + 1), i == 0 ? null : KEY, UserStatus.ACTIVE);
        }
        long[] activeUsersB = new long[5];
        for (int i = 0; i < 5; i++) {
            activeUsersB[i] = persistUser("AtivoB " + (i + 1), KEY, UserStatus.ACTIVE);
        }

        var eventA = Event.create(author, "Trilha Lote A", "Evento A", List.of(new PublicationImage(KEY, 0, true)), NOW.plusSeconds(3600), NOW);
        eventA.publish(NOW.plusSeconds(3));
        long eventAId = publications.save(eventA).getId();

        var eventB = Event.create(author, "Trilha Lote B", "Evento B", List.of(new PublicationImage(KEY, 0, true)), NOW.plusSeconds(7200), NOW);
        eventB.publish(NOW.plusSeconds(2));
        long eventBId = publications.save(eventB).getId();

        var eventEmpty = Event.create(author, "Trilha Sem Inscritos", "Evento C", List.of(new PublicationImage(KEY, 0, true)), NOW.plusSeconds(10800), NOW);
        eventEmpty.publish(NOW.plusSeconds(1));
        long eventEmptyId = publications.save(eventEmpty).getId();

        // Both eventA and eventB have 27 leading INACTIVE confirmed registrants followed by 5 ACTIVE confirmed registrants
        for (long inactiveId : inactiveUsers) {
            persistRegistration(eventAId, inactiveId, "CONFIRMED");
            persistRegistration(eventBId, inactiveId, "CONFIRMED");
        }
        EventRegistrationEntity firstActiveRegA = null;
        for (int i = 0; i < activeUsersA.length; i++) {
            EventRegistrationEntity reg = persistRegistration(eventAId, activeUsersA[i], "CONFIRMED");
            if (i == 0) firstActiveRegA = reg;
        }
        for (long activeId : activeUsersB) {
            persistRegistration(eventBId, activeId, "CONFIRMED");
        }
        em.flush();
        em.clear();

        when(membership.evaluate(anyLong())).thenReturn(MembershipAccessResult.ALLOWED);
        authenticate(activeUsersA[0]);

        Sql.statements.get().clear();
        mvc.perform(get("/publications/feed")
                        .header(AUTHORIZATION, "Bearer social-token")
                        .param("type", "EVENT"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.totalElements").value(3))
                .andExpect(jsonPath("$.content[0].id").value(eventAId))
                .andExpect(jsonPath("$.content[0].details.registrationCount").value(32))
                .andExpect(jsonPath("$.content[0].details.participantsPreview.length()").value(5))
                .andExpect(jsonPath("$.content[0].details.participantsPreview[0].userId").value(activeUsersA[0]))
                .andExpect(jsonPath("$.content[0].details.participantsPreview[0].profilePhotoStorageKey").value(nullValue()))
                .andExpect(jsonPath("$.content[0].details.participantsPreview[4].userId").value(activeUsersA[4]))
                .andExpect(jsonPath("$.content[1].id").value(eventBId))
                .andExpect(jsonPath("$.content[1].details.registrationCount").value(32))
                .andExpect(jsonPath("$.content[1].details.participantsPreview.length()").value(5))
                .andExpect(jsonPath("$.content[1].details.participantsPreview[0].userId").value(activeUsersB[0]))
                .andExpect(jsonPath("$.content[2].id").value(eventEmptyId))
                .andExpect(jsonPath("$.content[2].details.registrationCount").value(0))
                .andExpect(jsonPath("$.content[2].details.participantsPreview.length()").value(0));

        // Prove zero N+1: strictly 2 SQL queries on event_registrations (1 confirmedSummaries + 1 batch confirmedUserIdWindow for both events)
        List<String> regQueries = Sql.statements.get().stream()
                .filter(sql -> sql.toLowerCase().contains("event_registrations"))
                .toList();
        org.assertj.core.api.Assertions.assertThat(regQueries).hasSize(2);
        // Backfill query has NO count(*)
        org.assertj.core.api.Assertions.assertThat(regQueries.get(1).toLowerCase()).doesNotContain("count(");

        // Cancel first active registration on eventA -> registrationCount drops 32 -> 31 and preview updates consistently in feed & detail
        EventRegistrationEntity managed = em.find(EventRegistrationEntity.class, firstActiveRegA.getId());
        managed.setStatus("CANCELLED");
        managed.setCancelledAt(NOW.plusSeconds(10));
        em.flush();
        em.clear();

        mvc.perform(get("/publications/{id}", eventAId).header(AUTHORIZATION, "Bearer social-token"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.details.registrationCount").value(31))
                .andExpect(jsonPath("$.details.participantsPreview.length()").value(4))
                .andExpect(jsonPath("$.details.participantsPreview[0].userId").value(activeUsersA[1]));
    }

    private long persistUser(String name, String photoKey, UserStatus status) {
        var user = new UserEntity();
        user.setName(name);
        user.setCpf(String.format("%011d", Math.abs(UUID.randomUUID().getMostSignificantBits() % 100000000000L)));
        user.setProfilePhotoStorageKey(photoKey);
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

    private EventRegistrationEntity persistRegistration(long eventId, long userId, String status) {
        var reg = new EventRegistrationEntity();
        reg.setEventId(eventId);
        reg.setUserId(userId);
        reg.setStatus(status);
        reg.setCreatedAt(NOW);
        if ("CONFIRMED".equals(status)) reg.setConfirmedAt(NOW);
        if ("CANCELLED".equals(status)) reg.setCancelledAt(NOW);
        em.persist(reg);
        return reg;
    }

    private void authenticate(long userId) {
        when(jwt.parseAndValidate("social-token")).thenReturn(
                new JwtAuthenticatedUser(userId, 199L, "Member", Instant.now().plusSeconds(3600)));
        when(authorities.findAuthorityCodesByUserId(userId)).thenReturn(List.of(
                "PUBLICATIONS_INTERACTION_LIKE", "PUBLICATIONS_COMMENT_CREATE", "PUBLICATIONS_COMMENT_READ",
                "PUBLICATIONS_FEED_READ", "PUBLICATIONS_PUBLICATION_READ"));
    }

    public static class Sql implements org.hibernate.resource.jdbc.spi.StatementInspector {
        static final ThreadLocal<List<String>> statements = ThreadLocal.withInitial(java.util.ArrayList::new);

        @Override
        public String inspect(String sql) {
            if (sql != null && sql.trim().toLowerCase().startsWith("select ")) {
                statements.get().add(sql);
            }
            return sql;
        }
    }
}
