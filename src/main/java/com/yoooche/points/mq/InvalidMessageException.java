package com.yoooche.points.mq;

public class InvalidMessageException extends RuntimeException {

    public InvalidMessageException(String message, Throwable cause) {
        super(message, cause);
    }
}
