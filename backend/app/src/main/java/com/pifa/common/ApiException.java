package com.pifa.common;

import org.springframework.http.HttpStatus;

/**
 * A business-rule failure that should become an HTTP error response.
 *
 * <p>Thrown by services; {@link ApiExceptionHandler} turns it into an RFC 9457
 * {@code application/problem+json} body containing the stable {@code code}.
 */
public class ApiException extends RuntimeException {

    private final HttpStatus status;
    private final String code;

    /**
     * @param status  HTTP status to return (e.g. 403 forbidden, 404 not found, 409 conflict)
     * @param code    stable machine-readable error code, e.g. {@code VERSION_ACTIVE}
     * @param message human-readable explanation
     */
    public ApiException(HttpStatus status, String code, String message) {
        super(message);
        this.status = status;
        this.code = code;
    }

    /** @return the HTTP status */
    public HttpStatus getStatus() { return status; }
    /** @return the stable error code */
    public String getCode() { return code; }
}
