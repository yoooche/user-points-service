package com.yoooche.points.event;

public sealed interface PointsEvent permits PointsAddedEvent, PointsDeletedEvent {

    String userId();
}
