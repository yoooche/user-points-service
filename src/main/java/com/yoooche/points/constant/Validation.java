package com.yoooche.points.constant;

public final class Validation {

    /** No whitespace or control characters: a padded id would be a different user, and a newline would forge log lines. */
    public static final String USER_ID_PATTERN = "^[A-Za-z0-9_-]{1,64}$";
    public static final String USER_ID_MESSAGE = "must be 1-64 characters of letters, digits, '_' or '-'";

    /** Per-request cap; keeps a single request far from overflowing the BIGINT total. */
    public static final long MAX_AMOUNT = 1_000_000;

    private Validation() {
    }
}
