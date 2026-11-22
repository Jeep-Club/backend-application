package com.jeepclub.backend.publications.api.http.dto;

import com.jeepclub.backend.publications.core.application.service.PublicationFeedService;
import com.jeepclub.backend.publications.core.domain.enums.NoticePriority;
import com.jeepclub.backend.publications.core.domain.model.*;
import io.swagger.v3.oas.annotations.media.Schema;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;

public final class PublicationFeedDTO {
    private PublicationFeedDTO() {}

    public record Image(String storageKey, int position, boolean primary) {
        static Image from(PublicationImage image) { return new Image(image.storageKey(), image.position(), image.primary()); }
    }

    @Schema(description = "Minimal safe author representation without private PII.")
    public record Author(
            Long userId,
            String name,
            @Schema(description = "Profile photo storage key when configured; null otherwise.", nullable = true)
            String profilePhotoStorageKey
    ) {
        static Author from(PublicationFeedService.AuthorInfo author) {
            return author == null ? null : new Author(author.userId(), author.name(), author.profilePhotoStorageKey());
        }
    }

    @Schema(description = "Confirmed member participant preview item (excludes dependents, guests, CPF, financial and health data).")
    public record ParticipantPreview(
            Long userId,
            String name,
            @Schema(description = "Profile photo storage key when configured; null otherwise.", nullable = true)
            String profilePhotoStorageKey
    ) {
        static ParticipantPreview from(PublicationFeedService.ParticipantInfo participant) {
            return new ParticipantPreview(participant.userId(), participant.name(), participant.profilePhotoStorageKey());
        }
    }

    @Schema(oneOf = {NoticeDetails.class, EventDetails.class, ServiceDetails.class},
            description = "Subtype details selected by the type field. Service includes its advertised amount; no Billing debt, payment, guest or Health data is included.")
    public sealed interface Details permits NoticeDetails, EventDetails, ServiceDetails {}
    public record NoticeDetails(
            @Schema(description = "Editorial priority of the notice", allowableValues = {"LOW", "NORMAL", "HIGH", "URGENT"})
            NoticePriority priority,
            @Schema(description = "Inclusive start of temporal validity window; null when unbounded", nullable = true)
            Instant validFrom,
            @Schema(description = "Inclusive end of temporal validity window; null when unbounded", nullable = true)
            Instant validUntil
    ) implements Details {
        public NoticeDetails() {
            this(NoticePriority.NORMAL, null, null);
        }
    }
    public record EventDetails(
            Instant startsAt,
            Instant endsAt,
            String status,
            long registrationCount,
            @Schema(description = "Address; null for legacy events") String location,
            @Schema(description = "Informational limit in people, unrelated to vehicle seats", nullable = true) Integer participantLimit,
            @Schema(description = "Up to 5 confirmed active member participants in registration order") List<ParticipantPreview> participantsPreview,
            @Schema(description = "Compatibility alias of participantsPreview for frontend feed consumers") List<ParticipantPreview> participants
    ) implements Details {
        public EventDetails(Instant startsAt, Instant endsAt, String status, long registrationCount, String location, Integer participantLimit) {
            this(startsAt, endsAt, status, registrationCount, location, participantLimit, List.of(), List.of());
        }

        public EventDetails(Instant startsAt, Instant endsAt, String status, long registrationCount, String location, Integer participantLimit, List<ParticipantPreview> participantsPreview) {
            this(startsAt, endsAt, status, registrationCount, location, participantLimit,
                    participantsPreview == null ? List.of() : List.copyOf(participantsPreview),
                    participantsPreview == null ? List.of() : List.copyOf(participantsPreview));
        }
    }
    public record ServiceDetails(BigDecimal amount, String contactPhone) implements Details {}

    public record Item(Long id, String type, Long authorUserId,
                       @Schema(description = "Active author display name; null when author is inactive, archived or unavailable", nullable = true) String authorName,
                       @Schema(description = "Active author profile photo storage key; null when unavailable", nullable = true) String authorProfilePhotoStorageKey,
                       @Schema(description = "Minimal safe author representation; null when author is inactive, archived or unavailable", nullable = true) Author author,
                       String title, String content,
                       Image primaryImage, Instant publishedAt, long likeCount, long commentCount,
                       boolean likedByMe, Details details) {
        public static Item from(PublicationFeedService.Item source, Instant now) {
            var publication = source.publication();
            var summary = source.summary();
            var author = Author.from(source.author());
            return new Item(publication.getId(), PublicationFeedDTO.type(publication), publication.getAuthorUserId(),
                    author == null ? null : author.name(),
                    author == null ? null : author.profilePhotoStorageKey(),
                    author,
                    publication.getTitle(), publication.getContent(), publication.getImages().stream()
                    .filter(PublicationImage::primary).findFirst().map(Image::from).orElse(null),
                    publication.getPublishedAt(), summary.likeCount(), summary.commentCount(), summary.likedByMe(),
                    PublicationFeedDTO.details(publication, now, source.registrationCount(), source.participantsPreview()));
        }
    }
    public record Detail(Long id, String type, Long authorUserId,
                         @Schema(description = "Active author display name; null when author is inactive, archived or unavailable", nullable = true) String authorName,
                         @Schema(description = "Active author profile photo storage key; null when unavailable", nullable = true) String authorProfilePhotoStorageKey,
                         @Schema(description = "Minimal safe author representation; null when author is inactive, archived or unavailable", nullable = true) Author author,
                         String title, String content,
                         List<Image> images, Instant publishedAt, long likeCount, long commentCount,
                         boolean likedByMe, Details details) {
        public static Detail from(PublicationFeedService.Item source, Instant now) {
            var publication = source.publication();
            var summary = source.summary();
            var author = Author.from(source.author());
            return new Detail(publication.getId(), PublicationFeedDTO.type(publication), publication.getAuthorUserId(),
                    author == null ? null : author.name(),
                    author == null ? null : author.profilePhotoStorageKey(),
                    author,
                    publication.getTitle(), publication.getContent(), publication.getImages().stream().map(Image::from).toList(),
                    publication.getPublishedAt(), summary.likeCount(), summary.commentCount(), summary.likedByMe(),
                    PublicationFeedDTO.details(publication, now, source.registrationCount(), source.participantsPreview()));
        }
    }
    private static String type(Publication publication) {
        if (publication instanceof Notice) return "NOTICE";
        if (publication instanceof Event) return "EVENT";
        if (publication instanceof ServicePublication) return "SERVICE";
        throw new IllegalStateException("Unsupported publication subtype.");
    }
    private static Details details(Publication publication, Instant now, long registrationCount, List<PublicationFeedService.ParticipantInfo> participantsPreview) {
        if (publication instanceof Notice notice) return new NoticeDetails(notice.getPriority(), notice.getValidFrom(), notice.getValidUntil());
        if (publication instanceof Event event) {
            List<ParticipantPreview> preview = participantsPreview == null
                    ? List.of()
                    : participantsPreview.stream().map(ParticipantPreview::from).toList();
            return new EventDetails(event.getStartsAt(), event.getEndsAt(), event.effectiveStatus(now).name(),
                    registrationCount, event.getLocation(), event.getParticipantLimit(), preview);
        }
        if (publication instanceof ServicePublication service) return new ServiceDetails(service.getAmount(), service.getContactPhone());
        throw new IllegalStateException("Unsupported publication subtype.");
    }
}
