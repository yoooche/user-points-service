package com.yoooche.points.service.impl;

import com.yoooche.points.cache.UserPointsCache;
import com.yoooche.points.constant.StatusCode;
import com.yoooche.points.dto.AddPointsRequest;
import com.yoooche.points.dto.PointRecordResponse;
import com.yoooche.points.dto.UserPointsResponse;
import com.yoooche.points.event.PointsAddedEvent;
import com.yoooche.points.event.PointsDeletedEvent;
import com.yoooche.points.exception.BaseException;
import com.yoooche.points.model.UserPointsRecord;
import com.yoooche.points.repository.UserPointsRecordRepository;
import com.yoooche.points.repository.UserPointsRepository;
import com.yoooche.points.service.UserPointsService;
import com.yoooche.points.utils.JsonUtils;
import com.fasterxml.jackson.core.JsonProcessingException;
import lombok.RequiredArgsConstructor;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.core.NestedExceptionUtils;
import org.springframework.dao.DataAccessException;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.support.TransactionTemplate;

import java.sql.SQLException;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.List;

@Service
@RequiredArgsConstructor
public class UserPointsServiceImpl implements UserPointsService {

    private static final int MYSQL_ER_DATA_OUT_OF_RANGE = 1690;

    private final UserPointsRecordRepository recordRepository;
    private final UserPointsRepository pointsRepository;
    private final UserPointsCache userPointsCache;
    private final ApplicationEventPublisher eventPublisher;
    private final TransactionTemplate transactionTemplate;

    @Transactional(propagation = Propagation.NOT_SUPPORTED)
    @Override
    public PointRecordResponse addPoints(AddPointsRequest request, String key) {
        if (key == null || key.isBlank()) {
            throw new BaseException(StatusCode.INVALID_PARAMETER, "Idempotency-Key is required");
        }
        try {
            String hash = hash(JsonUtils.toJson(request));
            var existing = recordRepository.findByIdempotencyKey(key);
            if (existing.isPresent()) {
                return replay(existing.get(), hash);
            }
            try {
                return transactionTemplate.execute(status -> createPoints(request, key, hash));
            } catch (DataIntegrityViolationException ex) {
                // A concurrent first request may win the unique key. Its competitor's transaction has rolled back
                // before this catch, so load the winner in a fresh transaction, never a rollback-only one.
                var winner = recordRepository.findByIdempotencyKey(key);
                if (winner.isEmpty()) {
                    throw ex;
                }
                return replay(winner.get(), hash);
            }
        } catch (JsonProcessingException ex) {
            throw new IllegalStateException("Cannot encode idempotent points request or response", ex);
        }
    }

    private PointRecordResponse createPoints(AddPointsRequest request, String idempotencyKey, String requestHash) {
        var record = new UserPointsRecord();
        record.setUserId(request.userId());
        record.setAmount(request.amount());
        record.setReason(request.reason());
        record.setIdempotencyKey(idempotencyKey);
        record.setRequestHash(requestHash);
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
        long total = pointsRepository.findTotalByUserId(request.userId());
        eventPublisher.publishEvent(new PointsAddedEvent(request.userId(), request.amount(), total));
        var response = PointRecordResponse.from(saved);
        try {
            saved.setOriginalResponse(JsonUtils.toJson(response));
            // addToTotal clears the persistence context; merge the detached record back before commit.
            recordRepository.save(saved);
        } catch (JsonProcessingException ex) {
            throw new IllegalStateException("Cannot serialize original points response", ex);
        }
        return response;
    }

    @Override
    public UserPointsResponse getTotalUserPoints(String userId) {
        return userPointsCache.getTotal(userId)
                .orElseThrow(() -> new BaseException(StatusCode.USER_NOT_FOUND, "User not found: " + userId));
    }

    @Override
    public List<UserPointsResponse> getLeaderboard() {
        return userPointsCache.getLeaderboard();
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
        eventPublisher.publishEvent(new PointsDeletedEvent(userId));
    }

    private static PointRecordResponse replay(UserPointsRecord record, String hash) throws JsonProcessingException {
        if (!hash.equals(record.getRequestHash())) {
            throw new BaseException(StatusCode.IDEMPOTENCY_CONFLICT,
                    "Idempotency-Key was already used with a different request");
        }
        return JsonUtils.fromJson(record.getOriginalResponse(), PointRecordResponse.class);
    }

    private static String hash(String json) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                    .digest(json.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException ex) {
            throw new IllegalStateException("SHA-256 is unavailable", ex);
        }
    }

    private static boolean isTotalOverflow(DataAccessException ex) {
        return NestedExceptionUtils.getMostSpecificCause(ex) instanceof SQLException sql
                && sql.getErrorCode() == MYSQL_ER_DATA_OUT_OF_RANGE;
    }
}
