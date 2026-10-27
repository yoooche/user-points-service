package com.yoooche.points.constant;

public final class Validation {

    /** Excludes whitespace, control characters and the exact lowercase name reserved by GET /points/leaderboard. */
    public static final String USER_ID_PATTERN = "^(?!leaderboard$)[A-Za-z0-9_-]{1,64}$";
    public static final String USER_ID_MESSAGE = "must be 1-64 characters of letters, digits, '_' or '-'; 'leaderboard' is reserved";

    /** Per-request cap; keeps a single request far from overflowing the BIGINT total. */
    public static final long MAX_AMOUNT = 1_000_000;

    private Validation() {
    }
}
