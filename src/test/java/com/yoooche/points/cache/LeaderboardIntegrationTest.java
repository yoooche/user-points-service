package com.yoooche.points.cache;

import com.yoooche.points.TestDataCleaner;
import com.yoooche.points.TestTransactionFailure;
import com.yoooche.points.TestcontainersConfiguration;
import com.yoooche.points.constant.CacheNames;
import com.yoooche.points.config.RedisCacheProperties;
import com.yoooche.points.dto.AddPointsRequest;
import com.yoooche.points.dto.UserPointsResponse;
import com.yoooche.points.event.PointsAddedEvent;
import com.yoooche.points.service.UserPointsService;
import com.yoooche.points.repository.UserPointsRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.cache.CacheManager;
import org.springframework.cache.interceptor.SimpleKey;
import org.springframework.context.annotation.Import;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.transaction.support.TransactionTemplate;

import java.time.Duration;
import java.util.List;
import java.util.concurrent.TimeUnit;
import java.util.stream.IntStream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assertions.tuple;

@SpringBootTest
@Import({TestcontainersConfiguration.class, TestDataCleaner.class})
class LeaderboardIntegrationTest {

    @Autowired
    private UserPointsService service;

    @Autowired
    private UserPointsRepository pointsRepository;

    @Autowired
    private RedisCacheProperties redisCacheProperties;

    @Autowired
    private UserPointsCache userPointsCache;

    @Autowired
    private StringRedisTemplate redisTemplate;

    @Autowired
    @Qualifier("caffeineCacheManager")
    private CacheManager caffeineCacheManager;

    @Autowired
    private TransactionTemplate transactionTemplate;

    @Autowired
    private TestDataCleaner testDataCleaner;

    @Autowired
    private TestTransactionFailure transactionFailure;

    @Value("${spring.application.name}:" + CacheNames.USER_POINTS_LEADERBOARD)
    private String zsetKey;

    @BeforeEach
    void resetData() {
        testDataCleaner.reset();
    }

    @Test
    void ranksByTotalDescendingThenUserIdAscending() {
        service.addPoints(new AddPointsRequest("user_b", 100L, "a"), java.util.UUID.randomUUID().toString());
        service.addPoints(new AddPointsRequest("user_a", 100L, "a"), java.util.UUID.randomUUID().toString());
        service.addPoints(new AddPointsRequest("user_c", 300L, "a"), java.util.UUID.randomUUID().toString());

        assertThat(top())
                .extracting(UserPointsResponse::userId, UserPointsResponse::total)
                .containsExactly(tuple("user_c", 300L), tuple("user_a", 100L), tuple("user_b", 100L));
    }

    @Test
    void addingPointsInvalidatesBothLeaderboardCaches() {
        service.addPoints(new AddPointsRequest("user_1", 100L, "a"), java.util.UUID.randomUUID().toString());
        assertThat(service.getLeaderboard()).extracting(UserPointsResponse::total).containsExactly(100L);

        service.addPoints(new AddPointsRequest("user_1", 50L, "b"), java.util.UUID.randomUUID().toString());

        assertThat(redisTemplate.hasKey(zsetKey)).isFalse();
        assertThat(service.getLeaderboard()).extracting(UserPointsResponse::total).containsExactly(150L);
    }

    @Test
    void redisSnapshotUsesTheTtlConfiguredForItsCacheName() {
        service.addPoints(new AddPointsRequest("user_1", 100L, "a"), java.util.UUID.randomUUID().toString());
        service.getLeaderboard();

        long configuredTtl = redisCacheProperties.getTtl(CacheNames.USER_POINTS_LEADERBOARD).toMillis();
        assertThat(redisTemplate.getExpire(zsetKey, TimeUnit.MILLISECONDS)).isBetween(1L, configuredTtl);
    }

    @Test
    void lateEventCannotResurrectADeletedUser() {
        service.addPoints(new AddPointsRequest("user_1", 100L, "a"), java.util.UUID.randomUUID().toString());
        service.getLeaderboard();
        service.deleteUserPoints("user_1");

        userPointsCache.invalidateLeaderboard(new PointsAddedEvent("user_1", 100L, 100L));

        assertThat(service.getLeaderboard()).isEmpty();
    }

    @Test
    void deletingFromTopTenRefillsFromOutsideTheSnapshot() {
        IntStream.rangeClosed(1, 11).forEach(i ->
                service.addPoints(new AddPointsRequest("user_" + i, (long) i, "seed"), java.util.UUID.randomUUID().toString()));
        assertThat(service.getLeaderboard()).hasSize(10);
        assertThat(redisTemplate.opsForZSet().zCard(zsetKey)).isEqualTo(10L);

        service.deleteUserPoints("user_11");

        assertThat(service.getLeaderboard()).extracting(UserPointsResponse::userId)
                .containsExactly("user_10", "user_9", "user_8", "user_7", "user_6",
                        "user_5", "user_4", "user_3", "user_2", "user_1");
    }

    @Test
    void deletedUserLeavesTheLeaderboard() {
        service.addPoints(new AddPointsRequest("user_1", 100L, "a"), java.util.UUID.randomUUID().toString());
        service.addPoints(new AddPointsRequest("user_2", 50L, "a"), java.util.UUID.randomUUID().toString());

        service.deleteUserPoints("user_1");

        assertThat(top()).extracting(UserPointsResponse::userId).containsExactly("user_2");
    }

    @Test
    void rolledBackAddDoesNotReachTheLeaderboard() {
        assertThatThrownBy(() -> transactionFailure.runWithRollback(
                () -> service.addPoints(new AddPointsRequest("user_1", 100L, "a"), java.util.UUID.randomUUID().toString()),
                () -> assertThat(redisTemplate.hasKey(zsetKey)).isFalse()))
                .hasMessage("force rollback");

        assertThat(pointsRepository.count()).isZero();
        assertThat(service.getLeaderboard()).isEmpty();
        assertThat(redisTemplate.hasKey(zsetKey)).isFalse();
    }

    @Test
    void emptySortedSetIsRebuiltFromMySql() {
        service.addPoints(new AddPointsRequest("user_1", 100L, "a"), java.util.UUID.randomUUID().toString());
        redisTemplate.delete(zsetKey);

        assertThat(top()).extracting(UserPointsResponse::userId).containsExactly("user_1");
        assertThat(redisTemplate.opsForZSet().zCard(zsetKey)).isEqualTo(1L);
    }

    @Test
    void addingAfterRedisIsClearedDoesNotHideExistingUsers() {
        service.addPoints(new AddPointsRequest("user_a", 1000L, "a"), java.util.UUID.randomUUID().toString());
        service.addPoints(new AddPointsRequest("user_b", 500L, "b"), java.util.UUID.randomUUID().toString());
        top();
        redisTemplate.delete(zsetKey);

        service.addPoints(new AddPointsRequest("user_c", 10L, "c"), java.util.UUID.randomUUID().toString());

        assertThat(top()).extracting(UserPointsResponse::userId)
                .containsExactly("user_a", "user_b", "user_c");
    }

    @Test
    void deletingUserInvalidatesAnAlreadyCachedLeaderboard() {
        service.addPoints(new AddPointsRequest("user_a", 100L, "a"), java.util.UUID.randomUUID().toString());
        service.addPoints(new AddPointsRequest("user_b", 50L, "b"), java.util.UUID.randomUUID().toString());
        service.getLeaderboard();

        service.deleteUserPoints("user_a");

        assertThat(service.getLeaderboard()).extracting(UserPointsResponse::userId)
                .containsExactly("user_b");
    }

    @Test
    void emptyLeaderboardIsCachedWithoutCreatingASortedSet() {
        assertThat(service.getLeaderboard()).isEmpty();
        assertThat(redisTemplate.hasKey(zsetKey)).isFalse();
        assertThat(caffeineCacheManager.getCache(CacheNames.USER_POINTS_LEADERBOARD)
                .get(SimpleKey.EMPTY, List.class)).isNotNull().isEmpty();
    }

    @Test
    void expiredSnapshotForcesARefreshOfNonemptyRedisData() {
        service.addPoints(new AddPointsRequest("user_a", 100L, "a"), java.util.UUID.randomUUID().toString());
        service.getLeaderboard();
        // Simulate a missed invalidation, then expiry of the Redis snapshot.
        transactionTemplate.executeWithoutResult(status ->
                pointsRepository.addToTotal("user_b", 200L));
        redisTemplate.expire(zsetKey, Duration.ZERO);

        assertThat(top()).extracting(UserPointsResponse::userId).containsExactly("user_b", "user_a");
    }

    @Test
    void redisSnapshotPreservesLargeIntegerTotalsAndTheirOrdering() {
        service.addPoints(new AddPointsRequest("user_a", 9_007_199_254_740_992L, "a"), java.util.UUID.randomUUID().toString());
        service.addPoints(new AddPointsRequest("user_z", 9_007_199_254_740_993L, "z"), java.util.UUID.randomUUID().toString());
        var snapshot = service.getLeaderboard();

        assertThat(top()).isEqualTo(snapshot).extracting(UserPointsResponse::userId, UserPointsResponse::total)
                .containsExactly(tuple("user_z", 9_007_199_254_740_993L), tuple("user_a", 9_007_199_254_740_992L));
    }

    @Test
    void rolledBackDeleteKeepsTheCachedLeaderboard() {
        service.addPoints(new AddPointsRequest("user_1", 100L, "a"), java.util.UUID.randomUUID().toString());
        var snapshot = service.getLeaderboard();

        assertThatThrownBy(() -> transactionTemplate.executeWithoutResult(status -> {
            service.deleteUserPoints("user_1");
            throw new IllegalStateException("rollback");
        })).isInstanceOf(IllegalStateException.class);

        assertThat(service.getLeaderboard()).isEqualTo(snapshot);
        // Clear only the local cache, then verify the public API can still read the Redis result.
        assertThat(top()).isEqualTo(snapshot);
    }

    private List<UserPointsResponse> top() {
        caffeineCacheManager.getCache(CacheNames.USER_POINTS_LEADERBOARD).clear();
        return service.getLeaderboard();
    }
}
