package com.yoooche.points.dto;

import com.yoooche.points.model.UserPoints;

public record UserPointsResponse(String userId, long total) {

    public static UserPointsResponse from(UserPoints points) {
        return new UserPointsResponse(points.getUserId(), points.getTotal());
    }
}
