package com.jeepclub.backend.billing.core.application.result.charge;

/** Self-service projection with historical metadata owned by the charge cycle. */
public record MemberChargeSelfServiceResult(
        MemberChargeResult charge,
        String chargeDefinitionNameSnapshot,
        String chargeCycleCode
) {}
