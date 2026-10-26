package com.yoooche.points.cache;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.yoooche.points.constant.CacheNames;
import com.yoooche.points.dto.UserPointsResponse;
import com.yoooche.points.event.PointsEvent;
import com.yoooche.points.repository.UserPointsRepository;
import com.yoooche.points.utils.JsonUtils;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.cache.annotation.CacheConfig;
import org.springframework.cache.annotation.CacheEvict;
import org.springframework.cache.annotation.Cacheable;
import org.springframework.dao.DataAccessException;
import org.springframework.stereotype.Component;
import org.springframework.transaction.event.TransactionalEventListener;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

@Slf4j
@Component
@RequiredArgsConstructor
@CacheConfig(cacheNames = CacheNames.USER_POINTS_TOTAL)
public class UserPointsCache {

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

    @Cacheable(cacheManager = "caffeineCacheManager", cacheNames = CacheNames.USER_POINTS_LEADERBOARD, sync = true)
    public List<UserPointsResponse> getLeaderboard() {
        try {
            var cached = readLeaderboard();
            if (cached.isPresent()) {
                return cached.get();
            }
            var result = loadLeaderboard();
            publishLeaderboard(result);
            return result;
        } catch (DataAccessException ex) {
            log.warn("Leaderboard unavailable in Redis, reading from MySQL: {}", ex.getMessage());
            return loadLeaderboard();
        }
    }

    @TransactionalEventListener
    @CacheEvict(cacheManager = "caffeineCacheManager", cacheNames = CacheNames.USER_POINTS_LEADERBOARD, allEntries = true)
    public void invalidateLeaderboard(PointsEvent event) {
        try {
            invalidateLeaderboard();
        } catch (DataAccessException ex) {
            log.warn("Failed to invalidate leaderboard for {}: {}", event.userId(), ex.getMessage());
        }
    }

    private Optional<List<UserPointsResponse>> readLeaderboard() {
        var cached = redisCacheHelper.zrange(CacheNames.USER_POINTS_LEADERBOARD, 0, 9);
        if (cached.isEmpty()) {
            return Optional.empty();
        }
        var result = new ArrayList<UserPointsResponse>();
        try {
            for (String member : cached) {
                result.add(JsonUtils.fromJson(member, UserPointsResponse.class));
            }
        } catch (JsonProcessingException ex) {
            invalidateLeaderboard();
            return Optional.empty();
        }
        return Optional.of(List.copyOf(result));
    }

    private void invalidateLeaderboard() {
        redisCacheHelper.delete(CacheNames.USER_POINTS_LEADERBOARD);
    }

    private void publishLeaderboard(List<UserPointsResponse> leaderboard) {
        var members = new ArrayList<String>();
        try {
            for (UserPointsResponse user : leaderboard) {
                members.add(JsonUtils.toJson(user));
            }
        } catch (JsonProcessingException ex) {
            throw new IllegalStateException("Cannot serialize leaderboard", ex);
        }
        redisCacheHelper.replaceZSet(CacheNames.USER_POINTS_LEADERBOARD, members);
    }

    private List<UserPointsResponse> loadLeaderboard() {
        return pointsRepository.findTop10ByOrderByTotalDescUserIdAsc().stream()
                .map(UserPointsResponse::from)
                .toList();
    }
}
