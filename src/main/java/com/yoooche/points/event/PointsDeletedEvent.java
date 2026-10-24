package com.yoooche.points.event;

public record PointsDeletedEvent(String userId) implements PointsEvent {
}
