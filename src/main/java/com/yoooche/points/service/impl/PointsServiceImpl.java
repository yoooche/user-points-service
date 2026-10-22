package com.yoooche.points.service.impl;

import com.yoooche.points.constant.StatusCode;
import com.yoooche.points.dto.AddPointsRequest;
import com.yoooche.points.dto.PointRecordResponse;
import com.yoooche.points.dto.UserPointsResponse;
import com.yoooche.points.exception.BaseException;
import com.yoooche.points.model.UserPointsRecord;
import com.yoooche.points.repository.UserPointsRecordRepository;
import com.yoooche.points.repository.UserPointsRepository;
import com.yoooche.points.service.PointsService;
import lombok.RequiredArgsConstructor;
import org.springframework.core.NestedExceptionUtils;
import org.springframework.dao.DataAccessException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.sql.SQLException;
import java.util.List;

@Service
@RequiredArgsConstructor
public class PointsServiceImpl implements PointsService {

    private static final int MYSQL_ER_DATA_OUT_OF_RANGE = 1690;

    private final UserPointsRecordRepository recordRepository;
    private final UserPointsRepository pointsRepository;

    @Transactional
    @Override
    public PointRecordResponse addPoints(AddPointsRequest request) {
        var record = new UserPointsRecord();
        record.setUserId(request.userId());
        record.setAmount(request.amount());
        record.setReason(request.reason());
        var saved = recordRepository.save(record);

        try {
            pointsRepository.addToTotal(request.userId(), request.amount());
        } catch (DataAccessException ex) {
            if (isTotalOverflow(ex)) {
                throw new BaseException(StatusCode.TOTAL_LIMIT_EXCEEDED,
                        "Total points would exceed the maximum for user: " + request.userId(), ex);
            }
            throw ex;
        }
        return PointRecordResponse.from(saved);
    }

    @Transactional(readOnly = true)
    @Override
    public UserPointsResponse getTotalUserPoints(String userId) {
        return pointsRepository.findById(userId)
                .map(UserPointsResponse::from)
                .orElseThrow(() -> new BaseException(StatusCode.USER_NOT_FOUND, "User not found: " + userId));
    }

    @Transactional(readOnly = true)
    @Override
    public List<UserPointsResponse> getLeaderboard() {
        return pointsRepository.findTop10ByOrderByTotalDescUserIdAsc().stream()
                .map(UserPointsResponse::from)
                .toList();
    }

    @Transactional
    @Override
    public PointRecordResponse updateReason(Long recordId, String reason) {
        UserPointsRecord record = recordRepository.findById(recordId)
                .orElseThrow(() -> new BaseException(StatusCode.RECORD_NOT_FOUND, "Point record not found: " + recordId));
        record.setReason(reason);
        return PointRecordResponse.from(record);
    }

    @Transactional
    @Override
    public void deleteUserPoints(String userId) {
        int records = recordRepository.deleteAllByUserId(userId);
        int totals = pointsRepository.deleteAllByUserId(userId);
        if (records == 0 && totals == 0) {
            throw new BaseException(StatusCode.USER_NOT_FOUND, "User not found: " + userId);
        }
    }

    private static boolean isTotalOverflow(DataAccessException ex) {
        return NestedExceptionUtils.getMostSpecificCause(ex) instanceof SQLException sql
                && sql.getErrorCode() == MYSQL_ER_DATA_OUT_OF_RANGE;
    }
}
