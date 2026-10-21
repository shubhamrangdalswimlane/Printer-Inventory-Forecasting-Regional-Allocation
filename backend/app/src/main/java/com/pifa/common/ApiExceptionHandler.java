package com.pifa.common;

import org.springframework.http.HttpStatus;
import org.springframework.http.ProblemDetail;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

/** Converts exceptions thrown anywhere in a controller into JSON error responses (RFC 9457). */
@RestControllerAdvice
public class ApiExceptionHandler {

    /**
     * Handles {@link ApiException}.
     *
     * @param e the business failure
     * @return a problem-detail body with the HTTP status of the exception and an extra {@code code} property
     */
    @ExceptionHandler(ApiException.class)
    public ProblemDetail handle(ApiException e) {
        ProblemDetail p = ProblemDetail.forStatusAndDetail(e.getStatus(), e.getMessage());
        p.setProperty("code", e.getCode());
        e.getExtra().forEach(p::setProperty);
        // Correlation id: lets support match a user's error to the logs and audit rows.
        String requestId = org.slf4j.MDC.get("requestId");
        if (requestId != null) p.setProperty("requestId", requestId);
        return p;
    }

    /**
     * Handles bad input that reaches a service as an {@link IllegalArgumentException} (e.g. unknown dataset type).
     *
     * @param e the exception
     * @return a 400 problem-detail body
     */
    @ExceptionHandler(IllegalArgumentException.class)
    public ProblemDetail badRequest(IllegalArgumentException e) {
        ProblemDetail p = ProblemDetail.forStatusAndDetail(HttpStatus.BAD_REQUEST, e.getMessage());
        p.setProperty("code", "BAD_REQUEST");
        return p;
    }
}
