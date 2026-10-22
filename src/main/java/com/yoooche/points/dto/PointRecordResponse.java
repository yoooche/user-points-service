package com.yoooche.points.dto;

import com.yoooche.points.model.UserPointsRecord;
import io.swagger.v3.oas.annotations.media.Schema;

import java.time.Instant;

public record PointRecordResponse(
        @Schema(description = "Record id; use it to change the reason.", example = "1")
        Long id,
        String userId,
        long amount,
        String reason,
        Instant createdAt) {

    public static PointRecordResponse from(UserPointsRecord record) {
        return new PointRecordResponse(
                record.getRecordId(), record.getUserId(), record.getAmount(), record.getReason(), record.getCreatedAt());
    }
}
