package com.yoooche.points.event;

import com.fasterxml.jackson.annotation.JsonInclude;

import java.time.Instant;

@JsonInclude(JsonInclude.Include.NON_NULL)
public record PointsAddedMessage(
        String eventId,
        String userId,
        long amount,
        long total,
        String requestId,
        Instant occurredAt) {

    public static PointsAddedMessage of(PointsAddedEvent event, String eventId, String requestId, Instant occurredAt) {
        return new PointsAddedMessage(eventId, event.userId(), event.amount(), event.total(), requestId, occurredAt);
    }
}
