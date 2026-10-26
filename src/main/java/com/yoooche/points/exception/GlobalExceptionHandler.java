package com.yoooche.points.exception;

import com.yoooche.points.constant.StatusCode;
import java.util.TreeMap;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.dao.PessimisticLockingFailureException;
import org.springframework.dao.QueryTimeoutException;
import org.springframework.transaction.TransactionTimedOutException;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.HttpStatusCode;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.validation.FieldError;
import org.springframework.validation.method.ParameterValidationResult;
import org.springframework.validation.method.ParameterErrors;
import org.springframework.web.HttpMediaTypeNotSupportedException;
import org.springframework.web.HttpRequestMethodNotSupportedException;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.MissingRequestHeaderException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.context.request.WebRequest;
import org.springframework.web.method.annotation.HandlerMethodValidationException;
import org.springframework.web.method.annotation.MethodArgumentTypeMismatchException;
import org.springframework.web.servlet.mvc.method.annotation.ResponseEntityExceptionHandler;
import org.springframework.web.servlet.resource.NoResourceFoundException;

@Slf4j
@RestControllerAdvice
public class GlobalExceptionHandler extends ResponseEntityExceptionHandler {

    @Value("${spring.application.name}")
    private String system;

    @ExceptionHandler(BaseException.class)
    public ResponseEntity<ErrorResponse> handleBaseException(BaseException ex) {
        var statusCode = ex.getStatusCode();
        if (statusCode.getHttpStatus().is5xxServerError()) {
            log.error(ex.getMessage(), ex);
        } else {
            log.warn("[{}] {}", statusCode.getCode(), ex.getMessage());
        }
        return respond(statusCode.getHttpStatus(), ErrorResponse.of(system, statusCode, ex.getMessage()));
    }

    @Override
    protected ResponseEntity<Object> handleMethodArgumentNotValid(
            MethodArgumentNotValidException ex, HttpHeaders headers, HttpStatusCode status, WebRequest request) {
        var errors = new TreeMap<String, String>();
        for (FieldError error : ex.getBindingResult().getFieldErrors()) {
            errors.putIfAbsent(error.getField(), error.getDefaultMessage());
        }
        log.warn("[{}] {}", StatusCode.INVALID_PARAMETER.getCode(), errors);
        return respond(status, ErrorResponse.of(system, StatusCode.INVALID_PARAMETER, "Request validation failed", errors));
    }

    @Override
    protected ResponseEntity<Object> handleHandlerMethodValidationException(
            HandlerMethodValidationException ex, HttpHeaders headers, HttpStatusCode status, WebRequest request) {
        var errors = new TreeMap<String, String>();
        for (ParameterValidationResult result : ex.getParameterValidationResults()) {
            if (result instanceof ParameterErrors bodyErrors) {
                for (FieldError error : bodyErrors.getFieldErrors()) {
                    errors.putIfAbsent(error.getField(), error.getDefaultMessage());
                }
                continue;
            }
            String name = result.getMethodParameter().getParameterName();
            result.getResolvableErrors().stream().findFirst()
                    .ifPresent(error -> errors.putIfAbsent(name, error.getDefaultMessage()));
        }
        log.warn("[{}] {}", StatusCode.INVALID_PARAMETER.getCode(), errors);
        return respond(status, ErrorResponse.of(system, StatusCode.INVALID_PARAMETER, "Request validation failed", errors));
    }

    /** Never returns {@code ex.getMessage()}: Spring's messages name internal classes and methods. */
    @Override
    protected ResponseEntity<Object> handleExceptionInternal(
            Exception ex, Object body, HttpHeaders headers, HttpStatusCode status, WebRequest request) {
        var statusCode = StatusCode.fromHttpStatus(status.value());
        if (status.is5xxServerError()) {
            log.error(ex.getMessage(), ex);
        } else {
            log.warn("[{}] {}", statusCode.getCode(), ex.getMessage());
        }
        return respond(status, ErrorResponse.of(system, statusCode, clientMessage(ex, status)));
    }

    @ExceptionHandler(Exception.class)
    public ResponseEntity<ErrorResponse> handleUnexpected(Exception ex) {
        log.error(ex.getMessage(), ex);
        var statusCode = StatusCode.UNKNOWN_ERR;
        return respond(statusCode.getHttpStatus(), ErrorResponse.of(system, statusCode, "Internal server error"));
    }

    @ExceptionHandler({PessimisticLockingFailureException.class, QueryTimeoutException.class,
            TransactionTimedOutException.class})
    public ResponseEntity<ErrorResponse> handleBusyDatabase(Exception ex) {
        log.warn("Database transaction could not finish: {}", ex.getMessage());
        var code = StatusCode.REQUEST_BUSY;
        return respond(code.getHttpStatus(), ErrorResponse.of(system, code,
                "Database is busy; retry the request with the same Idempotency-Key if supplied"));
    }

    private static String clientMessage(Exception ex, HttpStatusCode status) {
        if (status.is5xxServerError()) {
            return "Internal server error";
        }
        return switch (ex) {
            case MissingRequestHeaderException e -> "Missing required header '" + e.getHeaderName() + "'";
            case HttpMessageNotReadableException e -> "Request body is missing or is not valid JSON for this endpoint";
            case MethodArgumentTypeMismatchException e -> "Invalid value for '" + e.getName() + "'";
            case NoResourceFoundException e -> "API not found";
            case HttpRequestMethodNotSupportedException e -> "Method " + e.getMethod() + " is not supported for this path";
            case HttpMediaTypeNotSupportedException e -> "Content type must be application/json";
            default -> HttpStatus.valueOf(status.value()).getReasonPhrase();
        };
    }

    @SuppressWarnings("unchecked")
    private <T> ResponseEntity<T> respond(HttpStatusCode status, ErrorResponse body) {
        return (ResponseEntity<T>) ResponseEntity.status(status).contentType(MediaType.APPLICATION_JSON).body(body);
    }
}
