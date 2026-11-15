package com.jeepclub.backend.billing.api.http.exception;

import com.jeepclub.backend.billing.core.application.exception.charge.MemberChargeAccessDeniedException;
import com.jeepclub.backend.billing.core.application.exception.charge.MemberChargeCannotUpdateFinalAmountException;
import com.jeepclub.backend.billing.core.application.exception.charge.MemberChargeCycleIntegrityException;
import com.jeepclub.backend.billing.core.application.exception.charge.MemberChargeNotFoundException;
import com.jeepclub.backend.billing.core.domain.exception.charge.InvalidMemberChargeStateException;
import com.jeepclub.backend.platform.web.exception.ApiErrorResponse;
import com.jeepclub.backend.platform.web.exception.ApiExceptionHandler;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

@RestControllerAdvice(basePackages = "com.jeepclub.backend.billing")
public class BillingMemberChargeExceptionHandler extends ApiExceptionHandler {
    private static final Logger log = LoggerFactory.getLogger(BillingMemberChargeExceptionHandler.class);

    @ExceptionHandler(MemberChargeCycleIntegrityException.class)
    public ResponseEntity<ApiErrorResponse> handleCycleIntegrity(MemberChargeCycleIntegrityException exception) {
        log.error("Billing member charge references a missing or inconsistent cycle");
        return buildErrorResponse("BILLING_DATA_INTEGRITY_ERROR", "Billing data integrity error.", HttpStatus.INTERNAL_SERVER_ERROR);
    }

    @ExceptionHandler(MemberChargeNotFoundException.class)
    public ResponseEntity<ApiErrorResponse> handleMemberChargeNotFound(
            MemberChargeNotFoundException exception
    ) {
        return buildErrorResponse(
                "MEMBER_CHARGE_NOT_FOUND",
                exception.getMessage(),
                HttpStatus.NOT_FOUND
        );
    }

    @ExceptionHandler(MemberChargeAccessDeniedException.class)
    public ResponseEntity<ApiErrorResponse> handleMemberChargeAccessDenied(
            MemberChargeAccessDeniedException exception
    ) {
        return buildErrorResponse(
                "MEMBER_CHARGE_ACCESS_DENIED",
                exception.getMessage(),
                HttpStatus.FORBIDDEN
        );
    }

    @ExceptionHandler(MemberChargeCannotUpdateFinalAmountException.class)
    public ResponseEntity<ApiErrorResponse> handleMemberChargeCannotUpdateFinalAmount(
            MemberChargeCannotUpdateFinalAmountException exception
    ) {
        return buildErrorResponse(
                "MEMBER_CHARGE_CANNOT_UPDATE_FINAL_AMOUNT",
                exception.getMessage(),
                HttpStatus.CONFLICT
        );
    }

    @ExceptionHandler(InvalidMemberChargeStateException.class)
    public ResponseEntity<ApiErrorResponse> handleInvalidMemberChargeState(
            InvalidMemberChargeStateException exception
    ) {
        return buildErrorResponse(
                "INVALID_MEMBER_CHARGE_STATE",
                exception.getMessage(),
                HttpStatus.CONFLICT
        );
    }
}
