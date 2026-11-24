package com.jeepclub.backend.publications.api.http.controller.member;

import com.jeepclub.backend.memberships.api.security.RequiresMembership;
import com.jeepclub.backend.platform.security.principal.UserPrincipal;
import com.jeepclub.backend.platform.web.pagination.PageResponse;
import com.jeepclub.backend.publications.api.http.dto.PublicationFeedDTO;
import com.jeepclub.backend.publications.core.application.service.PublicationFeedService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.responses.ApiResponses;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.media.Schema;
import com.jeepclub.backend.platform.web.exception.ApiErrorResponse;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import org.springdoc.core.annotations.ParameterObject;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;
import org.springframework.data.web.PageableDefault;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;
import java.time.Clock;
import java.time.Instant;
import java.util.List;

@RestController
@RequestMapping("/publications")
@RequiredArgsConstructor
@RequiresMembership
@Tag(name = "Publication feed", description = "Chronological published Notice, Event and Service publications")
@ApiResponses({
        @ApiResponse(responseCode = "400", description = "Invalid filter", content = @Content(mediaType = "application/problem+json", schema = @Schema(implementation = ApiErrorResponse.class))),
        @ApiResponse(responseCode = "401", description = "Authentication required", content = @Content(mediaType = "application/problem+json", schema = @Schema(implementation = ApiErrorResponse.class))),
        @ApiResponse(responseCode = "403", description = "Membership or permission required", content = @Content(mediaType = "application/problem+json", schema = @Schema(implementation = ApiErrorResponse.class))),
        @ApiResponse(responseCode = "404", description = "Publication inaccessible", content = @Content(mediaType = "application/problem+json", schema = @Schema(implementation = ApiErrorResponse.class)))
})
public class PublicationFeedController {
    private final PublicationFeedService feed;
    private final Clock clock;

    @GetMapping("/feed")
    @Operation(
            summary = "Read unified publication feed",
            description = "Only PUBLISHED concrete publications within their temporal validity window. Supports single type (type=NOTICE|EVENT|SERVICE) or multiple types (types=NOTICE,EVENT,SERVICE; conflicting type and types returns 400), authorUserId, literal case-insensitive text search in title/content (q or search), inclusive publishedFrom/publishedTo filters, and sort allowlist (publishedAt, createdAt, updatedAt, title, id) with publishedAt DESC, id DESC as default. Social counts, active author presentation and up to 5 confirmed event participants preview are resolved in batch."
    )
    @ApiResponse(responseCode = "200", description = "Page of visible publications")
    public PageResponse<PublicationFeedDTO.Item> feed(
            @Parameter(description = "Single publication subtype filter (NOTICE, EVENT or SERVICE). Mutually exclusive with types.")
            @RequestParam(required = false) String type,
            @Parameter(description = "Multiple publication subtype filter (NOTICE, EVENT, SERVICE). Mutually exclusive with type.")
            @RequestParam(required = false) List<String> types,
            @Parameter(description = "Filter by author user ID (positive integer).")
            @RequestParam(required = false) Long authorUserId,
            @Parameter(description = "Literal case-insensitive text search in title or content (max 200 characters).")
            @RequestParam(required = false) String q,
            @Parameter(hidden = true) @RequestParam(required = false) String search,
            @Parameter(description = "Inclusive lower bound for publishedAt.")
            @RequestParam(required = false) Instant publishedFrom,
            @Parameter(description = "Inclusive upper bound for publishedAt.")
            @RequestParam(required = false) Instant publishedTo,
            @Parameter(hidden = true) @RequestParam(required = false, name = "page") Integer requestedPage,
            @Parameter(hidden = true) @RequestParam(required = false, name = "size") Integer requestedSize,
            @ParameterObject @PageableDefault(size = 20, sort = {"publishedAt", "id"}, direction = Sort.Direction.DESC) Pageable pageable,
            @AuthenticationPrincipal UserPrincipal principal
    ) {
        if (requestedPage != null && requestedPage < 0 || requestedSize != null && (requestedSize < 1 || requestedSize > 50)) {
            throw new IllegalArgumentException("Invalid feed pagination parameters.");
        }
        if (q != null && !q.isBlank() && search != null && !search.isBlank()) {
            throw new IllegalArgumentException("Specify either q or search, not both.");
        }
        String effectiveQuery = q != null && !q.isBlank() ? q : search;
        var now = Instant.now(clock);
        return PageResponse.from(feed.feed(type, types, authorUserId, effectiveQuery, publishedFrom, publishedTo, pageable, principal.getUserId())
                .map(item -> PublicationFeedDTO.Item.from(item, now)));
    }

    @GetMapping("/{publicationId}")
    @Operation(summary = "Read publication detail", description = "Only PUBLISHED concrete publications within their temporal validity window. Returns ordered full gallery and subtype details; Event FINISHED remains visible while editorially published.")
    @ApiResponse(responseCode = "200", description = "Visible publication detail")
    public PublicationFeedDTO.Detail detail(@PathVariable Long publicationId,
                                            @AuthenticationPrincipal UserPrincipal principal) {
        Instant now = Instant.now(clock);
        return PublicationFeedDTO.Detail.from(feed.detail(publicationId, principal.getUserId(), now), now);
    }
}
