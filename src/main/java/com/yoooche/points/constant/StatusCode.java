package com.yoooche.points.constant;

import lombok.Getter;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;

@Getter
@RequiredArgsConstructor
public enum StatusCode {

    INVALID_PARAMETER(HttpStatus.BAD_REQUEST),
    API_NOT_FOUND(HttpStatus.NOT_FOUND),
    USER_NOT_FOUND(HttpStatus.NOT_FOUND),
    RECORD_NOT_FOUND(HttpStatus.NOT_FOUND),
    METHOD_NOT_ALLOWED(HttpStatus.METHOD_NOT_ALLOWED),
    UNSUPPORTED_MEDIA_TYPE(HttpStatus.UNSUPPORTED_MEDIA_TYPE),
    TOTAL_LIMIT_EXCEEDED(HttpStatus.UNPROCESSABLE_ENTITY),
    IDEMPOTENCY_CONFLICT(HttpStatus.CONFLICT),
    REQUEST_BUSY(HttpStatus.SERVICE_UNAVAILABLE),
    UNKNOWN_ERR(HttpStatus.INTERNAL_SERVER_ERROR);

    private final HttpStatus httpStatus;

    public String getCode() {
        return name().toLowerCase();
    }

    public static StatusCode fromHttpStatus(int status) {
        return switch (status) {
            case 400 -> INVALID_PARAMETER;
            case 404 -> API_NOT_FOUND;
            case 405 -> METHOD_NOT_ALLOWED;
            case 415 -> UNSUPPORTED_MEDIA_TYPE;
            default -> UNKNOWN_ERR;
        };
    }
}
