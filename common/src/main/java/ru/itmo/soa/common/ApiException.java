package ru.itmo.soa.common;

import java.time.Instant;
import java.util.List;
import ru.itmo.soa.common.Models.*;

public class ApiException extends RuntimeException {
    public final int status;
    public final String code;
    public final List<Violation> violations;
    public ApiException(int status, String code, String message) { this(status, code, message, null); }
    public ApiException(int status, String code, String message, List<Violation> violations) {
        super(message); this.status = status; this.code = code; this.violations = violations;
    }
    public ErrorBody body(String path) {
        var body = new ErrorBody(); body.status = status; body.code = code;
        body.message = getMessage(); body.path = path; body.timestamp = Instant.now().toString(); body.violations = violations;
        return body;
    }
    public static ApiException bad(String message) { return new ApiException(400, "BAD_REQUEST", message); }
    public static ApiException missing(String message) { return new ApiException(404, "RESOURCE_NOT_FOUND", message); }
}
