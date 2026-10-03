package com.example.seats.api;

import java.util.stream.Collectors;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.http.HttpStatusCode;
import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.core.NestedRuntimeException;
import org.springframework.dao.DataAccessResourceFailureException;
import org.springframework.dao.RecoverableDataAccessException;
import org.springframework.dao.TransientDataAccessException;
import org.springframework.jdbc.CannotGetJdbcConnectionException;
import org.springframework.transaction.CannotCreateTransactionException;
import org.springframework.transaction.TransactionSystemException;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.method.annotation.MethodArgumentTypeMismatchException;

@RestControllerAdvice
public class GlobalExceptionHandler {

    private static final Logger log = LoggerFactory.getLogger(GlobalExceptionHandler.class);

    @ExceptionHandler(ApiException.class)
    ResponseEntity<ErrorResponse> handleApi(ApiException e) {
        return ResponseEntity.status(e.status()).body(new ErrorResponse(e.code(), e.getMessage()));
    }

    @ExceptionHandler(MethodArgumentNotValidException.class)
    ResponseEntity<ErrorResponse> handleValidation(MethodArgumentNotValidException e) {
        String msg = e.getBindingResult().getFieldErrors().stream()
                .map(f -> f.getField() + ": " + f.getDefaultMessage())
                .collect(Collectors.joining("; "));
        return ResponseEntity.badRequest().body(new ErrorResponse("bad_request", msg));
    }

    @ExceptionHandler({HttpMessageNotReadableException.class, MethodArgumentTypeMismatchException.class})
    ResponseEntity<ErrorResponse> handleUnreadable(Exception e) {
        return ResponseEntity.badRequest().body(new ErrorResponse("bad_request", "malformed request"));
    }


    /**
     * Transient database trouble: no pooled connection within the timeout (overload), the DB unreachable, or a
     * connection dropped mid-transaction. Answer 503 + Retry-After, not a generic 500. Retrying is safe because
     * reserve is idempotent: if the commit did land, the retry with the same key replays it.
     */
    @ExceptionHandler({CannotGetJdbcConnectionException.class, CannotCreateTransactionException.class,
            DataAccessResourceFailureException.class, TransientDataAccessException.class,
            RecoverableDataAccessException.class, TransactionSystemException.class})
    ResponseEntity<ErrorResponse> handleNoConnection(NestedRuntimeException e) {
        log.warn("no database connection available: {}", e.getMostSpecificCause().toString());
        return ResponseEntity.status(HttpStatus.SERVICE_UNAVAILABLE).header("Retry-After", "1")
                .body(new ErrorResponse("unavailable", "service overloaded, retry shortly"));
    }

    // Anything reaching here is a real bug or infrastructure fault: log it loudly, keep the body generic.
    @ExceptionHandler(Exception.class)
    ResponseEntity<ErrorResponse> handleUnexpected(Exception e) {
        // ...except Spring MVC's own exceptions (unknown route, wrong method, bad media type, ...), which already
        // carry their 4xx status. Without this an unknown URL would surface as a 500.
        if (e instanceof org.springframework.web.ErrorResponse framework) {
            HttpStatusCode status = framework.getStatusCode();
            String code = status instanceof HttpStatus hs ? hs.name().toLowerCase() : "error";
            return ResponseEntity.status(status).body(new ErrorResponse(code, e.getMessage()));
        }
        log.error("unhandled error", e);
        return ResponseEntity.internalServerError().body(new ErrorResponse("internal_error", "internal error"));
    }
}
