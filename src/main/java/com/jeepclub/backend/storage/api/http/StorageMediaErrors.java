package com.jeepclub.backend.storage.api.http;

import org.springframework.web.bind.annotation.*;
import org.springframework.http.*;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;

@org.springframework.core.annotation.Order(org.springframework.core.Ordered.HIGHEST_PRECEDENCE)
@RestControllerAdvice(assignableTypes={StorageMediaController.class,StorageAdminController.class})
@ConditionalOnProperty(name="storage.media.enabled",havingValue="true")
public class StorageMediaErrors {
    private final com.jeepclub.backend.platform.web.exception.ApiProblemFactory problems;
    public StorageMediaErrors(com.jeepclub.backend.platform.web.exception.ApiProblemFactory problems) {this.problems=problems;}
    @ExceptionHandler(com.jeepclub.backend.storage.core.repository.UploadAdmission.LimitExceeded.class) public ResponseEntity<ProblemDetail> limited() {return error(429);}
    @ExceptionHandler(com.jeepclub.backend.storage.core.repository.UploadAdmission.PayloadConflict.class) public ResponseEntity<ProblemDetail> conflict() {return error(409);}
    @ExceptionHandler({org.springframework.dao.PessimisticLockingFailureException.class,
        org.springframework.dao.DataAccessResourceFailureException.class,
        org.springframework.transaction.CannotCreateTransactionException.class,
        org.springframework.orm.jpa.JpaSystemException.class,
        com.jeepclub.backend.storage.core.application.MediaUploadService.ProcessingDeadlineExceeded.class,
        org.springframework.dao.QueryTimeoutException.class,org.springframework.transaction.TransactionTimedOutException.class,
        jakarta.persistence.LockTimeoutException.class,jakarta.persistence.QueryTimeoutException.class})
    public ResponseEntity<ProblemDetail> timedOut() { return error(503); }
    @ExceptionHandler(AccessDeniedException.class) public ResponseEntity<ProblemDetail> denied(jakarta.servlet.http.HttpServletRequest request) {
        return request.getRequestURI().startsWith(request.getContextPath()+"/admin/storage/") ? error(403) : error(404);
    }
    @ExceptionHandler(com.jeepclub.backend.shared.storage.exception.StorageObjectNotFoundException.class) public ResponseEntity<ProblemDetail> missing() { return error(404); }
    @ExceptionHandler(IllegalArgumentException.class) public ResponseEntity<ProblemDetail> invalid() { return error(400); }
    @ExceptionHandler({IllegalStateException.class,java.io.IOException.class,java.io.UncheckedIOException.class,com.jeepclub.backend.shared.storage.exception.StorageException.class}) public ResponseEntity<ProblemDetail> failed() { return error(503); }
    private ResponseEntity<ProblemDetail> error(int status) {
        String code=switch(status) {case 400 -> "INVALID_MEDIA_REQUEST";case 403 -> "PERMISSION_DENIED";case 404 -> "CONTENT_UNAVAILABLE";case 409 -> "STORAGE_UPLOAD_KEY_CONFLICT";case 429 -> "STORAGE_UPLOAD_LIMIT";default -> "STORAGE_UNAVAILABLE";};
        var problem=problems.create(HttpStatus.valueOf(status),code,org.springframework.context.i18n.LocaleContextHolder.getLocale());
        if(status==404) problem.setProperty("reasonCode","CONTENT_UNAVAILABLE");
        return ResponseEntity.status(status).header(HttpHeaders.CACHE_CONTROL,"private, no-store").body(problem);
    }
}
