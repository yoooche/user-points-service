package com.yoooche.points.service;

import com.yoooche.points.dto.AddPointsRequest;
import com.yoooche.points.dto.PointRecordResponse;
import com.yoooche.points.dto.UserPointsResponse;

import java.util.List;

public interface UserPointsService {

    /**
     * @throws com.yoooche.points.exception.BaseException {@code TOTAL_LIMIT_EXCEEDED} if the total would overflow
     */
    PointRecordResponse addPoints(AddPointsRequest request);

    /** @throws com.yoooche.points.exception.BaseException {@code USER_NOT_FOUND} if the user has no points */
    UserPointsResponse getTotalUserPoints(String userId);

    List<UserPointsResponse> getLeaderboard();

    /**
     * @throws com.yoooche.points.exception.BaseException {@code RECORD_NOT_FOUND} if the record does not exist
     */
    PointRecordResponse updateReason(Long recordId, String reason);

    /** @throws com.yoooche.points.exception.BaseException {@code USER_NOT_FOUND} if there was nothing to delete */
    void deleteUserPoints(String userId);
}
