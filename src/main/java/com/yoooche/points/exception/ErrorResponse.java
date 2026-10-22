package com.yoooche.points.exception;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.yoooche.points.constant.StatusCode;
import com.yoooche.points.filter.RequestIdFilter;
import io.swagger.v3.oas.annotations.media.Schema;
import org.slf4j.MDC;

import java.util.Map;

@JsonInclude(JsonInclude.Include.NON_NULL)
public record ErrorResponse(
        String system,

        @Schema(description = "From the `X-Request-Id` response header")
        String requestId,

        String statusCode,

        @Schema(example = "Detail error message")
        String message,

        @Schema(description = "Field-level validation messages. Present only for `invalid_parameter`.",
                example = "{\"amount\": \"must be between 1 and 1000000\"}")
        Map<String, String> errors) {

    public static ErrorResponse of(String system, StatusCode statusCode, String message) {
        return of(system, statusCode, message, null);
    }

    public static ErrorResponse of(String system, StatusCode statusCode, String message, Map<String, String> errors) {
        return new ErrorResponse(system, MDC.get(RequestIdFilter.MDC_KEY), statusCode.getCode(), message, errors);
    }
}
