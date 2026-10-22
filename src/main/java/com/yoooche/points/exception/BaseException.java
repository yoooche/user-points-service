package com.yoooche.points.exception;

import com.yoooche.points.constant.StatusCode;
import lombok.Getter;

@Getter
public class BaseException extends RuntimeException {

    private final StatusCode statusCode;

    public BaseException(StatusCode statusCode, String message) {
        super(message);
        this.statusCode = statusCode;
    }

    public BaseException(StatusCode statusCode, String message, Throwable cause) {
        super(message, cause);
        this.statusCode = statusCode;
    }
}
