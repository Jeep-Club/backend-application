package com.jeepclub.backend.billing.core.application.result.charge;

public record MemberChargeCycleResult(
        MemberChargeResult charge,
        String chargeDefinitionNameSnapshot,
        String chargeCycleCode
) {}
