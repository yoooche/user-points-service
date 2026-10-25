package com.yoooche.points.cache;

import com.yoooche.points.constant.CacheNames;
import com.yoooche.points.dto.UserPointsResponse;
import com.yoooche.points.event.PointsAddedEvent;
import com.yoooche.points.event.PointsDeletedEvent;
import com.yoooche.points.event.PointsEvent;
import com.yoooche.points.model.UserPoints;
import com.yoooche.points.repository.UserPointsRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.cache.annotation.CacheConfig;
import org.springframework.cache.annotation.CacheEvict;
import org.springframework.cache.annotation.Cacheable;
import org.springframework.context.event.EventListener;
import org.springframework.dao.DataAccessException;
import org.springframework.data.domain.Limit;
import org.springframework.stereotype.Component;
import org.springframework.transaction.event.TransactionalEventListener;

import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.stream.Collectors;

@Slf4j
@Component
@RequiredArgsConstructor
@CacheConfig(cacheNames = CacheNames.USER_POINTS_TOTAL)
public class UserPointsCache {

    private static final int TOP_N = 10;
    private static final int LEADERBOARD_CAPACITY = 1000;

    private final UserPointsRepository pointsRepository;
    private final RedisCacheHelper redisCacheHelper;

    @Cacheable(key = "#userId", unless = "#result == null")
    public Optional<UserPointsResponse> getTotal(String userId) {
        return pointsRepository.findById(userId).map(UserPointsResponse::from);
    }

    @TransactionalEventListener
    @CacheEvict(key = "#event.userId")
    public void evictTotal(PointsEvent event) {
        log.debug("Evicted cached total of {}", event.userId());
    }

    /**
     * The leaderboard is a sorted set with score = -total: ZRANGE then returns the highest total first and breaks ties
     * by userId ascending, matching the MySQL ordering. ZREVRANGE would break ties by userId descending.
     */
    @Cacheable(cacheManager = "caffeineCacheManager", cacheNames = CacheNames.USER_POINTS_LEADERBOARD, sync = true)
    public List<UserPointsResponse> getLeaderboard() {
        try {
            var top = readLeaderboard();
            if (!top.isEmpty()) {
                return top;
            }
            rebuildLeaderboard();
            return readLeaderboard();
        } catch (DataAccessException ex) {
            log.warn("Leaderboard unavailable in Redis, reading from MySQL: {}", ex.getMessage());
            return pointsRepository.findTop10ByOrderByTotalDescUserIdAsc().stream()
                    .map(UserPointsResponse::from)
                    .toList();
        }
    }

    @TransactionalEventListener
    public void updateLeaderboard(PointsAddedEvent event) {
        try {
            writeLeaderboard(Map.of(event.userId(), score(event.total())));
        } catch (DataAccessException ex) {
            log.warn("Failed to update leaderboard for {}: {}", event.userId(), ex.getMessage());
        }
    }

    @TransactionalEventListener
    public void removeFromLeaderboard(PointsDeletedEvent event) {
        try {
            redisCacheHelper.zRemove(CacheNames.USER_POINTS_LEADERBOARD, event.userId());
        } catch (DataAccessException ex) {
            log.warn("Failed to remove {} from leaderboard: {}", event.userId(), ex.getMessage());
        }
    }

    @EventListener(ApplicationReadyEvent.class)
    public void warmUpLeaderboard() {
        try {
            if (redisCacheHelper.zSize(CacheNames.USER_POINTS_LEADERBOARD) == 0) {
                rebuildLeaderboard();
            }
        } catch (DataAccessException ex) {
            log.warn("Leaderboard warm-up skipped: {}", ex.getMessage());
        }
    }

    private void rebuildLeaderboard() {
        writeLeaderboard(pointsRepository.findByOrderByTotalDescUserIdAsc(Limit.of(LEADERBOARD_CAPACITY)).stream()
                .collect(Collectors.toMap(UserPoints::getUserId, points -> score(points.getTotal()))));
    }

    private void writeLeaderboard(Map<String, Double> scores) {
        redisCacheHelper.zAddIfLower(CacheNames.USER_POINTS_LEADERBOARD, scores);
        redisCacheHelper.zKeepFirst(CacheNames.USER_POINTS_LEADERBOARD, LEADERBOARD_CAPACITY);
    }

    private List<UserPointsResponse> readLeaderboard() {
        return redisCacheHelper.zRangeWithScores(CacheNames.USER_POINTS_LEADERBOARD, 0, TOP_N - 1).entrySet().stream()
                .map(entry -> new UserPointsResponse(entry.getKey(), (long) -entry.getValue()))
                .toList();
    }

    private static double score(long total) {
        return -total;
    }
}
