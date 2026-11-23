package com.jeepclub.backend.publications.core.application.service;

import com.jeepclub.backend.iam.identity.api.module.UserDetails;
import com.jeepclub.backend.iam.identity.api.module.UserQuery;
import com.jeepclub.backend.iam.identity.api.module.UserStatus;
import com.jeepclub.backend.publications.api.http.dto.PublicationFeedDTO;
import com.jeepclub.backend.publications.core.domain.enums.NoticePriority;
import com.jeepclub.backend.publications.core.domain.enums.PublicationStatus;
import com.jeepclub.backend.publications.core.domain.exception.PublicationNotFoundException;
import com.jeepclub.backend.publications.core.domain.model.*;
import com.jeepclub.backend.publications.core.repository.EventParticipantRepository;
import com.jeepclub.backend.publications.core.repository.PublicationRepository;
import org.junit.jupiter.api.Test;
import org.springframework.data.domain.*;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.*;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

class PublicationFeedServiceTest {
    private static final Instant NOW = Instant.parse("2026-09-27T12:00:00Z");
    private static final String KEY = "images/2026/09/27/550e8400-e29b-41d4-a716-446655440000.jpg";

    @Test void feedUsesDeterministicSortAndOneBatchSummary() {
        var publications = mock(PublicationRepository.class);
        var social = mock(PublicationSocialSummaryQuery.class);
        var notice = Notice.reconstitute(1L, 7L, "Notice", "Body", com.jeepclub.backend.publications.core.domain.enums.PublicationStatus.PUBLISHED,
                NOW.minusSeconds(1), NOW, NOW, null, gallery());
        when(publications.findPublished(eq("NOTICE"), isNull(), isNull(), any())).thenAnswer(call ->
                new PageImpl<Publication>(List.of(notice), call.getArgument(3), 1));
        when(social.get(List.of(1L), 9L)).thenReturn(Map.of(1L, new PublicationSocialSummaryQuery.Summary(3, 2, true)));
        var result = new PublicationFeedService(publications, social, mock(com.jeepclub.backend.publications.core.repository.EventParticipantRepository.class)).feed("NOTICE", null, null, PageRequest.of(0, 20), 9L);
        assertThat(result.getContent()).hasSize(1);
        var dto = PublicationFeedDTO.Item.from(result.getContent().get(0), NOW);
        assertThat(dto.type()).isEqualTo("NOTICE");
        assertThat(dto.likeCount()).isEqualTo(3);
        assertThat(dto.commentCount()).isEqualTo(2);
        assertThat(dto.likedByMe()).isTrue();
        var sort = org.mockito.ArgumentCaptor.forClass(Pageable.class);
        verify(publications).findPublished(eq("NOTICE"), isNull(), isNull(), sort.capture());
        assertThat(sort.getValue().getSort()).isEqualTo(Sort.by(Sort.Order.desc("publishedAt"), Sort.Order.desc("id")));
        verify(social).get(List.of(1L), 9L);
    }

    @Test void feedBatchesRegistrationCountsForEvents() {
        var publications = mock(PublicationRepository.class);
        var social = mock(PublicationSocialSummaryQuery.class);
        var counts = mock(com.jeepclub.backend.publications.core.repository.EventParticipantRepository.class);
        var event = Event.reconstitute(2L, 7L, "Trail", "Body",
                com.jeepclub.backend.publications.core.domain.enums.PublicationStatus.PUBLISHED,
                NOW.minusSeconds(100), NOW.minusSeconds(90), NOW.minusSeconds(90), null, gallery(),
                NOW.plusSeconds(80), null, com.jeepclub.backend.publications.core.domain.enums.EventStatus.OPEN);
        var other = Event.reconstitute(3L, 7L, "Trail 2", "Body",
                com.jeepclub.backend.publications.core.domain.enums.PublicationStatus.PUBLISHED,
                NOW.minusSeconds(100), NOW.minusSeconds(90), NOW.minusSeconds(90), null, gallery(),
                NOW.plusSeconds(80), null, com.jeepclub.backend.publications.core.domain.enums.EventStatus.OPEN);
        when(publications.findPublished(isNull(), isNull(), isNull(), any())).thenAnswer(call ->
                new PageImpl<Publication>(List.of(event, other), call.getArgument(3), 2));
        when(social.get(List.of(2L, 3L), 9L)).thenReturn(Map.of(2L, new PublicationSocialSummaryQuery.Summary(0, 0, false),
                3L, new PublicationSocialSummaryQuery.Summary(0, 0, false)));
        when(counts.confirmedSummaries(List.of(2L, 3L), 25)).thenReturn(Map.of(
                2L, new EventParticipantRepository.ConfirmedSummary(4L, List.of()),
                3L, new EventParticipantRepository.ConfirmedSummary(0L, List.of())
        ));
        var page = new PublicationFeedService(publications, social, counts).feed(null, null, null, PageRequest.of(0, 20), 9L);
        assertThat(((PublicationFeedDTO.EventDetails) PublicationFeedDTO.Item.from(page.getContent().get(0), NOW).details()).registrationCount()).isEqualTo(4);
        assertThat(((PublicationFeedDTO.EventDetails) PublicationFeedDTO.Item.from(page.getContent().get(1), NOW).details()).registrationCount()).isZero();
        verify(counts, times(1)).confirmedSummaries(List.of(2L, 3L), 25);
        verifyNoMoreInteractions(counts);
    }

    @Test void draftDetailIsHidden() {
        var publications = mock(PublicationRepository.class);
        var social = mock(PublicationSocialSummaryQuery.class);
        when(publications.findById(1L)).thenReturn(Optional.of(Notice.create(7L, "Draft", "Body", gallery(), NOW)));
        assertThatThrownBy(() -> new PublicationFeedService(publications, social, mock(com.jeepclub.backend.publications.core.repository.EventParticipantRepository.class)).detail(1L, 9L))
                .isInstanceOf(PublicationNotFoundException.class);
        verifyNoInteractions(social);
    }

    @Test void finishedPublishedEventRemainsVisibleAndCarriesSpecificStatus() {
        var publications = mock(PublicationRepository.class);
        var social = mock(PublicationSocialSummaryQuery.class);
        var event = Event.reconstitute(2L, 7L, "Trail", "Body",
                com.jeepclub.backend.publications.core.domain.enums.PublicationStatus.PUBLISHED,
                NOW.minusSeconds(100), NOW.minusSeconds(90), NOW.minusSeconds(90), null, gallery(),
                NOW.minusSeconds(80), NOW.minusSeconds(10),
                com.jeepclub.backend.publications.core.domain.enums.EventStatus.OPEN);
        event.setEditorialDetails("Meeting point", 42);
        when(publications.findById(2L)).thenReturn(Optional.of(event));
        when(social.get(List.of(2L), 9L)).thenReturn(Map.of(2L, new PublicationSocialSummaryQuery.Summary(0, 0, false)));
        var participants = mock(com.jeepclub.backend.publications.core.repository.EventParticipantRepository.class);
        when(participants.confirmedSummaries(List.of(2L), 25)).thenReturn(Map.of(
                2L, new EventParticipantRepository.ConfirmedSummary(3L, List.of())
        ));
        var dto = PublicationFeedDTO.Detail.from(new PublicationFeedService(publications, social, participants).detail(2L, 9L), NOW);
        verify(participants).confirmedSummaries(List.of(2L), 25);
        assertThat(dto.type()).isEqualTo("EVENT");
        assertThat(dto.details()).isEqualTo(new PublicationFeedDTO.EventDetails(event.getStartsAt(), event.getEndsAt(), "FINISHED", 3L, "Meeting point", 42));
    }

    @Test void noticeDetailEnforcesTemporalValidityWindowAndProjectsPriorityAndValidity() {
        var publications = mock(PublicationRepository.class);
        var social = mock(PublicationSocialSummaryQuery.class);
        var participants = mock(com.jeepclub.backend.publications.core.repository.EventParticipantRepository.class);
        Instant start = NOW.plusSeconds(10);
        Instant end = NOW.plusSeconds(60);
        var notice = Notice.reconstitute(10L, 7L, "Urgent Notice", "Body", PublicationStatus.PUBLISHED,
                NOW.minusSeconds(20), NOW.minusSeconds(10), NOW.minusSeconds(10), null, gallery(),
                NoticePriority.URGENT, start, end);
        when(publications.findById(10L)).thenReturn(Optional.of(notice));
        when(social.get(List.of(10L), 9L)).thenReturn(Map.of(10L, new PublicationSocialSummaryQuery.Summary(1, 0, false)));
        var service = new PublicationFeedService(publications, social, participants);

        assertThatThrownBy(() -> service.detail(10L, 9L, start.minusNanos(1)))
                .isInstanceOf(PublicationNotFoundException.class);
        assertThatThrownBy(() -> service.detail(10L, 9L, end.plusNanos(1)))
                .isInstanceOf(PublicationNotFoundException.class);

        var atStart = PublicationFeedDTO.Detail.from(service.detail(10L, 9L, start), start);
        assertThat(atStart.details()).isEqualTo(new PublicationFeedDTO.NoticeDetails(NoticePriority.URGENT, start, end));
        var atEnd = PublicationFeedDTO.Detail.from(service.detail(10L, 9L, end), end);
        assertThat(atEnd.details()).isEqualTo(new PublicationFeedDTO.NoticeDetails(NoticePriority.URGENT, start, end));
    }

    @Test void feedResolvesAuthorsAndConfirmedParticipantPreviewInSingleIdentityBatchWithActiveFallback() {
        var publications = mock(PublicationRepository.class);
        var social = mock(PublicationSocialSummaryQuery.class);
        var participants = mock(EventParticipantRepository.class);
        var users = mock(UserQuery.class);

        var notice = Notice.reconstitute(1L, 7L, "Notice", "Body", PublicationStatus.PUBLISHED,
                NOW.minusSeconds(20), NOW.minusSeconds(10), NOW.minusSeconds(10), null, gallery());
        var event = Event.reconstitute(2L, 8L, "Trail", "Body", PublicationStatus.PUBLISHED,
                NOW.minusSeconds(20), NOW.minusSeconds(10), NOW.minusSeconds(10), null, gallery(),
                NOW.plusSeconds(80), null, com.jeepclub.backend.publications.core.domain.enums.EventStatus.OPEN);

        when(publications.findPublished(eq(Set.of("NOTICE", "EVENT")), eq(7L), eq("Trail"), isNull(), isNull(), any()))
                .thenAnswer(call -> new PageImpl<Publication>(List.of(notice, event), call.getArgument(5), 2));
        when(social.get(List.of(1L, 2L), 9L)).thenReturn(Map.of(
                1L, new PublicationSocialSummaryQuery.Summary(1, 0, false),
                2L, new PublicationSocialSummaryQuery.Summary(2, 1, true)
        ));
        // 7 confirmed total, preview candidate IDs include 1 inactive (12L), 1 duplicate (11L), and 6 active -> preview caps at 5
        when(participants.confirmedSummaries(List.of(2L), 25)).thenReturn(Map.of(
                2L, new EventParticipantRepository.ConfirmedSummary(7L, List.of(11L, 12L, 11L, 13L, 14L, 15L, 16L, 17L))
        ));
        when(users.findByIds(anyCollection())).thenReturn(List.of(
                activeUser(7L, "Ana Silva", "profiles/ana.jpg"),
                inactiveUser(8L, "Autor Inativo"),
                activeUser(11L, "João Um", "profiles/11.jpg"),
                inactiveUser(12L, "Membro Inativo"),
                activeUser(13L, "Maria Três", null),
                activeUser(14L, "Pedro Quatro", "profiles/14.jpg"),
                activeUser(15L, "Lucas Cinco", "profiles/15.jpg"),
                activeUser(16L, "Carla Seis", null),
                activeUser(17L, "Marcos Sete", null)
        ));

        var service = new PublicationFeedService(publications, social, participants, users, Clock.fixed(NOW, ZoneOffset.UTC));
        var page = service.feed(null, List.of("NOTICE,EVENT"), 7L, " Trail ", null, null,
                PageRequest.of(0, 20, Sort.by(Sort.Order.asc("title"))), 9L);

        var sortCaptor = org.mockito.ArgumentCaptor.forClass(Pageable.class);
        verify(publications).findPublished(eq(Set.of("NOTICE", "EVENT")), eq(7L), eq("Trail"), isNull(), isNull(), sortCaptor.capture());
        assertThat(sortCaptor.getValue().getSort()).isEqualTo(Sort.by(Sort.Order.asc("title"), Sort.Order.asc("id")));
        verify(users, times(1)).findByIds(anyCollection());

        var noticeDto = PublicationFeedDTO.Item.from(page.getContent().get(0), NOW);
        assertThat(noticeDto.authorUserId()).isEqualTo(7L);
        assertThat(noticeDto.authorName()).isEqualTo("Ana Silva");
        assertThat(noticeDto.authorProfilePhotoStorageKey()).isEqualTo("profiles/ana.jpg");
        assertThat(noticeDto.author()).isEqualTo(new PublicationFeedDTO.Author(7L, "Ana Silva", "profiles/ana.jpg"));

        var eventDto = PublicationFeedDTO.Item.from(page.getContent().get(1), NOW);
        assertThat(eventDto.authorUserId()).isEqualTo(8L);
        assertThat(eventDto.authorName()).isNull();
        assertThat(eventDto.author()).isNull();
        var details = (PublicationFeedDTO.EventDetails) eventDto.details();
        assertThat(details.registrationCount()).isEqualTo(7L);
        assertThat(details.participantsPreview()).hasSize(5)
                .extracting(PublicationFeedDTO.ParticipantPreview::userId)
                .containsExactly(11L, 13L, 14L, 15L, 16L);
        assertThat(details.participants()).isEqualTo(details.participantsPreview());
    }

    @Test void feedRejectsConflictingTypesInvalidSortAndBadParameters() {
        var service = new PublicationFeedService(
                mock(PublicationRepository.class),
                mock(PublicationSocialSummaryQuery.class),
                mock(EventParticipantRepository.class),
                mock(UserQuery.class),
                Clock.fixed(NOW, ZoneOffset.UTC)
        );
        assertThatThrownBy(() -> service.feed("NOTICE", List.of("EVENT"), null, null, null, null, PageRequest.of(0, 20), 9L))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> service.feed(null, List.of("UNKNOWN"), null, null, null, null, PageRequest.of(0, 20), 9L))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> service.feed(null, null, 0L, null, null, null, PageRequest.of(0, 20), 9L))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> service.feed(null, null, null, "x".repeat(201), null, null, PageRequest.of(0, 20), 9L))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> service.feed(null, null, null, null, null, null,
                PageRequest.of(0, 20, Sort.by("amount")), 9L))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> service.feed(null, null, null, null, null, null, PageRequest.of(0, 51), 9L))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test void feedPreservesTotalElementsOnOutOfRangeEmptyPage() {
        var publications = mock(PublicationRepository.class);
        var social = mock(PublicationSocialSummaryQuery.class);
        var participants = mock(EventParticipantRepository.class);
        var users = mock(UserQuery.class);

        Pageable requested = PageRequest.of(5, 2, Sort.by(Sort.Order.desc("publishedAt"), Sort.Order.desc("id")));
        when(publications.findPublished(isNull(), isNull(), isNull(), any()))
                .thenReturn(new PageImpl<>(List.of(), requested, 7L));

        var service = new PublicationFeedService(publications, social, participants, users, Clock.fixed(NOW, ZoneOffset.UTC));
        var result = service.feed(null, null, null, null, null, null, PageRequest.of(5, 2), 9L);

        assertThat(result.getContent()).isEmpty();
        assertThat(result.getTotalElements()).isEqualTo(7L);
        assertThat(result.getTotalPages()).isEqualTo(4);
        assertThat(result.getNumber()).isEqualTo(5);
        verifyNoInteractions(social, participants, users);
    }

    @Test void feedBackfillsActiveParticipantsInBatchAcrossMultipleEventsWithoutNPlus1OrRedundantIdentityLookups() {
        var publications = mock(PublicationRepository.class);
        var social = mock(PublicationSocialSummaryQuery.class);
        var participants = mock(EventParticipantRepository.class);
        var users = mock(UserQuery.class);

        var eventA = Event.reconstitute(20L, 7L, "Expedição A", "Conteúdo A", PublicationStatus.PUBLISHED,
                NOW.minusSeconds(10), NOW.minusSeconds(10), NOW.minusSeconds(10), null, gallery(),
                NOW.plusSeconds(3600), null, com.jeepclub.backend.publications.core.domain.enums.EventStatus.OPEN);
        var eventB = Event.reconstitute(21L, 7L, "Expedição B", "Conteúdo B", PublicationStatus.PUBLISHED,
                NOW.minusSeconds(11), NOW.minusSeconds(11), NOW.minusSeconds(11), null, gallery(),
                NOW.plusSeconds(3600), null, com.jeepclub.backend.publications.core.domain.enums.EventStatus.OPEN);
        var eventC = Event.reconstitute(22L, 7L, "Expedição C", "Conteúdo C", PublicationStatus.PUBLISHED,
                NOW.minusSeconds(12), NOW.minusSeconds(12), NOW.minusSeconds(12), null, gallery(),
                NOW.plusSeconds(3600), null, com.jeepclub.backend.publications.core.domain.enums.EventStatus.OPEN);
        var eventD = Event.reconstitute(23L, 7L, "Expedição D", "Conteúdo D", PublicationStatus.PUBLISHED,
                NOW.minusSeconds(13), NOW.minusSeconds(13), NOW.minusSeconds(13), null, gallery(),
                NOW.plusSeconds(3600), null, com.jeepclub.backend.publications.core.domain.enums.EventStatus.OPEN);

        when(publications.findPublished(isNull(), isNull(), isNull(), any()))
                .thenReturn(new PageImpl<>(List.of(eventA, eventB, eventC, eventD), PageRequest.of(0, 20), 4));
        when(social.get(List.of(20L, 21L, 22L, 23L), 9L)).thenReturn(Map.of(
                20L, new PublicationSocialSummaryQuery.Summary(0, 0, false),
                21L, new PublicationSocialSummaryQuery.Summary(0, 0, false),
                22L, new PublicationSocialSummaryQuery.Summary(0, 0, false),
                23L, new PublicationSocialSummaryQuery.Summary(0, 0, false)
        ));

        // Initial 25 candidates:
        // - eventA (55 confirmed): 101..125 (all inactive) -> needs round 1 (126..150 inactive) + round 2 (151..155 active)
        // - eventB (28 confirmed): 101..125 (shared inactive) -> completes in round 1 with 3 active (201..203)
        // - eventC (0 confirmed): empty -> never enters backfill
        // - eventD (30 confirmed): 101..125 (shared inactive) -> round 1 returns 126..130 (all inactive, < 25 -> exhausted)
        List<Long> sharedInitialInactive = java.util.stream.LongStream.rangeClosed(101L, 125L).boxed().toList();
        when(participants.confirmedSummaries(List.of(20L, 21L, 22L, 23L), 25)).thenReturn(Map.of(
                20L, new EventParticipantRepository.ConfirmedSummary(55L, sharedInitialInactive),
                21L, new EventParticipantRepository.ConfirmedSummary(28L, sharedInitialInactive),
                22L, new EventParticipantRepository.ConfirmedSummary(0L, List.of()),
                23L, new EventParticipantRepository.ConfirmedSummary(30L, sharedInitialInactive)
        ));
        List<Long> round1Inactive = java.util.stream.LongStream.rangeClosed(126L, 150L).boxed().toList();
        when(participants.confirmedUserIdWindow(List.of(20L, 21L, 23L), 25, 25)).thenReturn(Map.of(
                20L, round1Inactive,
                21L, List.of(201L, 202L, 203L),
                23L, List.of(126L, 127L, 128L, 129L, 130L)
        ));
        when(participants.confirmedUserIdWindow(List.of(20L), 50, 25)).thenReturn(Map.of(
                20L, List.of(151L, 152L, 153L, 154L, 155L)
        ));

        List<List<Long>> identityBatches = new java.util.ArrayList<>();
        when(users.findByIds(anyCollection())).thenAnswer(invocation -> {
            @SuppressWarnings("unchecked")
            var ids = new java.util.ArrayList<>((java.util.Collection<Long>) invocation.getArgument(0));
            identityBatches.add(ids);
            List<UserDetails> list = new java.util.ArrayList<>();
            for (Long id : ids) {
                if (id == 7L) {
                    list.add(activeUser(7L, "Organizador", "profiles/org.jpg"));
                } else if ((id >= 151L && id <= 155L) || (id >= 201L && id <= 203L)) {
                    list.add(activeUser(id, "Ativo " + id, id % 2 == 0 ? "profiles/" + id + ".jpg" : null));
                } else {
                    list.add(inactiveUser(id, "Inativo " + id));
                }
            }
            return list;
        });

        var service = new PublicationFeedService(publications, social, participants, users, Clock.fixed(NOW, ZoneOffset.UTC));
        var result = service.feed(null, null, null, null, null, null, PageRequest.of(0, 20), 9L);

        // Zero per-event confirmedUserIds calls; strictly 2 batch backfill rounds across all incomplete events
        verify(participants, never()).confirmedUserIds(any(), any());
        verify(participants, times(1)).confirmedSummaries(List.of(20L, 21L, 22L, 23L), 25);
        verify(participants, times(1)).confirmedUserIdWindow(List.of(20L, 21L, 23L), 25, 25);
        verify(participants, times(1)).confirmedUserIdWindow(List.of(20L), 50, 25);

        // No user ID (active OR inactive) is ever queried twice in Identity across events or rounds, and every batch <= 500
        List<Long> allQueriedIds = identityBatches.stream().flatMap(List::stream).toList();
        assertThat(allQueriedIds).doesNotHaveDuplicates();
        assertThat(identityBatches).allSatisfy(batch -> assertThat(batch.size()).isLessThanOrEqualTo(500));

        var detailsA = (PublicationFeedDTO.EventDetails) PublicationFeedDTO.Item.from(result.getContent().get(0), NOW).details();
        assertThat(detailsA.registrationCount()).isEqualTo(55L);
        assertThat(detailsA.participantsPreview()).extracting(PublicationFeedDTO.ParticipantPreview::userId)
                .containsExactly(151L, 152L, 153L, 154L, 155L);

        var detailsB = (PublicationFeedDTO.EventDetails) PublicationFeedDTO.Item.from(result.getContent().get(1), NOW).details();
        assertThat(detailsB.registrationCount()).isEqualTo(28L);
        assertThat(detailsB.participantsPreview()).extracting(PublicationFeedDTO.ParticipantPreview::userId)
                .containsExactly(201L, 202L, 203L);

        var detailsC = (PublicationFeedDTO.EventDetails) PublicationFeedDTO.Item.from(result.getContent().get(2), NOW).details();
        assertThat(detailsC.registrationCount()).isZero();
        assertThat(detailsC.participantsPreview()).isEmpty();

        var detailsD = (PublicationFeedDTO.EventDetails) PublicationFeedDTO.Item.from(result.getContent().get(3), NOW).details();
        assertThat(detailsD.registrationCount()).isEqualTo(30L);
        assertThat(detailsD.participantsPreview()).isEmpty();
    }

    private static UserDetails activeUser(Long id, String name, String photo) {
        return new UserDetails(id, name, null, "private@example.com", "12345678901", "1234567", "11999998888",
                photo, true, NOW.minusSeconds(100), null, NOW.minusSeconds(100), UserStatus.ACTIVE, null);
    }

    private static UserDetails inactiveUser(Long id, String name) {
        return new UserDetails(id, name, null, "inactive@example.com", "12345678902", "1234568", "11999997777",
                "profiles/inactive.jpg", false, NOW.minusSeconds(100), NOW.minusSeconds(50), NOW.minusSeconds(50), UserStatus.INACTIVE, null);
    }

    private static List<PublicationImage> gallery() { return List.of(new PublicationImage(KEY, 0, true)); }
}
