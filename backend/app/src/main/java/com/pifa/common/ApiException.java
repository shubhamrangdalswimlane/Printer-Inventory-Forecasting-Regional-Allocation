package com.pifa.common;

import java.util.Map;
import org.springframework.http.HttpStatus;

/**
 * A business-rule failure that should become an HTTP error response.
 *
 * <p>Thrown by services; {@link ApiExceptionHandler} turns it into an RFC 9457
 * {@code application/problem+json} body containing the stable {@code code} and any
 * {@code extra} properties (for example the list of conflicting rows on a 409).
 */
public class ApiException extends RuntimeException {

    private final HttpStatus status;
    private final String code;
    private final Map<String, Object> extra;

    /**
     * @param status  HTTP status to return (e.g. 403 forbidden, 404 not found, 409 conflict)
     * @param code    stable machine-readable error code, e.g. {@code VERSION_ACTIVE}
     * @param message human-readable explanation
     */
    public ApiException(HttpStatus status, String code, String message) {
        this(status, code, message, Map.of());
    }

    /**
     * @param status  HTTP status to return
     * @param code    stable machine-readable error code
     * @param message human-readable explanation
     * @param extra   additional JSON properties added to the error body (never null)
     */
    public ApiException(HttpStatus status, String code, String message, Map<String, Object> extra) {
        super(message);
        this.status = status;
        this.code = code;
        this.extra = extra;
    }

    /** @return the HTTP status */
    public HttpStatus getStatus() { return status; }
    /** @return the stable error code */
    public String getCode() { return code; }
    /** @return extra properties for the error body; empty if none */
    public Map<String, Object> getExtra() { return extra; }
}
