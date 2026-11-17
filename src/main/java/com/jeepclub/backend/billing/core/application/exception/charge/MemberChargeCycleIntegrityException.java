package com.jeepclub.backend.billing.core.application.exception.charge;

public class MemberChargeCycleIntegrityException extends RuntimeException {
    public MemberChargeCycleIntegrityException() {
        super("Member charge cycle reference is missing or inconsistent.");
    }
}
