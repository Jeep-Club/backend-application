package com.jeepclub.backend.publications.api.http.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.math.BigDecimal;

@Schema(description="Event editorial fields and agenda. PATCH preserves omitted fields; explicit null clears endsAt or participantLimit. Charges lock after the first registration.")
public record EventRequestDTO(String title, String content, List<PublicationImageRequestDTO> images,
        Instant startsAt, Instant endsAt, List<Charge> charges, @Schema(description="Address text, 1?500 characters; required for creation") String location, @Schema(description="Optional informational limit in people; not vehicle seats and does not block registration", nullable=true, minimum="1") Integer participantLimit) {
    @Schema(description="Select chargeDefinitionId OR provide name/description/amount for inline ONE_TIME creation. Inline policy is AFTER_DUE_DATE. Billing required is independent from requiredForParticipation.")
    public record Charge(Long chargeDefinitionId, String name, String description, BigDecimal amount,
        @Schema(description="Billing obligation, used only for inline creation; omitted defaults to false.", defaultValue="false") Boolean billingRequired,
        @Schema(description="Whether this charge gates participation; omitted defaults to false.", defaultValue="false") Boolean requiredForParticipation,
        @Schema(description="Participation deadline, defaults to startsAt; independent of financialDueDate.") Instant participationCutoff,
        @Schema(description="Optional financial due date in YYYY-MM-DD. Null or omitted means no Event-defined final regularization date: Billing uses the Event start date as a reference dueDate and requires AFTER_DUE_DATE acceptance.", example="2026-11-30", nullable=true)
        LocalDate financialDueDate) {}
}
