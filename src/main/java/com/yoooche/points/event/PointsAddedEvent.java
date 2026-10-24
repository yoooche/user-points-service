package com.yoooche.points.event;

public record PointsAddedEvent(String userId, long amount, long total) implements PointsEvent {
}
