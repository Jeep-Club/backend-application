package com.jeepclub.backend.publications.api.http;

import com.jeepclub.backend.iam.authentication.core.application.service.security.AccessTokenAuthenticationService;
import com.jeepclub.backend.memberships.api.module.*;
import com.jeepclub.backend.platform.security.authorization.UserAuthoritiesProvider;
import com.jeepclub.backend.platform.security.jwt.JwtAuthenticatedUser;
import com.jeepclub.backend.platform.security.jwt.JwtTokenParser;
import com.jeepclub.backend.platform.storage.image.ImageMediaService;
import com.jeepclub.backend.publications.core.domain.model.*;
import com.jeepclub.backend.publications.core.repository.PublicationRepository;
import com.jeepclub.backend.publications.core.repository.ServicePublicationRequestRepository;
import com.jeepclub.backend.publications.core.application.service.AdminServicePublicationRequestService;
import com.jeepclub.backend.shared.storage.exception.StorageObjectNotFoundException;
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
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.*;
import static org.springframework.http.HttpHeaders.AUTHORIZATION;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
class PublicationSocialFeedHttpIntegrationTest {
    private static final String KEY = "images/2026/09/27/550e8400-e29b-41d4-a716-446655440000.jpg";
    private static final Instant NOW = Instant.parse("2026-09-27T12:00:00Z");
    @Autowired private MockMvc mvc;
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

    private void authenticate(long userId) {
        when(jwt.parseAndValidate("social-token")).thenReturn(
                new JwtAuthenticatedUser(userId, 199L, "Member", Instant.now().plusSeconds(3600)));
        when(authorities.findAuthorityCodesByUserId(userId)).thenReturn(List.of(
                "PUBLICATIONS_INTERACTION_LIKE", "PUBLICATIONS_COMMENT_CREATE", "PUBLICATIONS_COMMENT_READ",
                "PUBLICATIONS_FEED_READ", "PUBLICATIONS_PUBLICATION_READ"));
    }
}
