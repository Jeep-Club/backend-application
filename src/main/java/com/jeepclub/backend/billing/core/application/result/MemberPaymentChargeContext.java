package com.jeepclub.backend.billing.core.application.result;

import java.time.LocalDate;

/** Identifies the obligation behind an administrative payment without copying Identity data. */
public record MemberPaymentChargeContext(
        Long userId,
        Long chargeDefinitionId,
        Long chargeCycleId,
        String chargeDefinitionNameSnapshot,
        String chargeCycleCode,
        LocalDate dueDate
) {
}
