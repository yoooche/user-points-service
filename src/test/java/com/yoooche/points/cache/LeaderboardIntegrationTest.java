package com.yoooche.points.cache;

import com.yoooche.points.TestDataCleaner;
import com.yoooche.points.TestcontainersConfiguration;
import com.yoooche.points.constant.CacheNames;
import com.yoooche.points.dto.AddPointsRequest;
import com.yoooche.points.dto.UserPointsResponse;
import com.yoooche.points.event.PointsAddedEvent;
import com.yoooche.points.service.UserPointsService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.cache.CacheManager;
import org.springframework.context.annotation.Import;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.transaction.support.TransactionTemplate;

import java.util.List;
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

    @Value("${spring.application.name}:" + CacheNames.USER_POINTS_LEADERBOARD)
    private String zsetKey;

    @BeforeEach
    void resetData() {
        testDataCleaner.reset();
    }

    @Test
    void ranksByTotalDescendingThenUserIdAscending() {
        service.addPoints(new AddPointsRequest("user_b", 100L, "a"));
        service.addPoints(new AddPointsRequest("user_a", 100L, "a"));
        service.addPoints(new AddPointsRequest("user_c", 300L, "a"));

        assertThat(top())
                .extracting(UserPointsResponse::userId, UserPointsResponse::total)
                .containsExactly(tuple("user_c", 300L), tuple("user_a", 100L), tuple("user_b", 100L));
    }

    @Test
    void sortedSetHoldsTheLatestTotalAsNegativeScore() {
        service.addPoints(new AddPointsRequest("user_1", 100L, "a"));
        service.addPoints(new AddPointsRequest("user_1", 50L, "b"));

        assertThat(redisTemplate.opsForZSet().score(zsetKey, "user_1")).isEqualTo(-150.0);
    }

    @Test
    void lateEventCannotLowerATotal() {
        userPointsCache.updateLeaderboard(new PointsAddedEvent("user_1", 50L, 200L));
        userPointsCache.updateLeaderboard(new PointsAddedEvent("user_1", 50L, 150L));

        assertThat(redisTemplate.opsForZSet().score(zsetKey, "user_1")).isEqualTo(-200.0);
    }

    @Test
    void sortedSetIsTrimmedToCapacity() {
        IntStream.rangeClosed(1, 1001).forEach(i ->
                userPointsCache.updateLeaderboard(new PointsAddedEvent("user_" + i, i, i)));

        assertThat(redisTemplate.opsForZSet().zCard(zsetKey)).isEqualTo(1000L);
        assertThat(redisTemplate.opsForZSet().score(zsetKey, "user_1")).isNull();
    }

    @Test
    void deletedUserLeavesTheLeaderboard() {
        service.addPoints(new AddPointsRequest("user_1", 100L, "a"));
        service.addPoints(new AddPointsRequest("user_2", 50L, "a"));

        service.deleteUserPoints("user_1");

        assertThat(top()).extracting(UserPointsResponse::userId).containsExactly("user_2");
    }

    @Test
    void rolledBackAddDoesNotReachTheLeaderboard() {
        assertThatThrownBy(() -> transactionTemplate.executeWithoutResult(status -> {
            service.addPoints(new AddPointsRequest("user_1", 100L, "a"));
            throw new IllegalStateException("force rollback");
        })).isInstanceOf(IllegalStateException.class);

        assertThat(redisTemplate.opsForZSet().score(zsetKey, "user_1")).isNull();
    }

    @Test
    void emptySortedSetIsRebuiltFromMySql() {
        service.addPoints(new AddPointsRequest("user_1", 100L, "a"));
        redisTemplate.delete(zsetKey);

        assertThat(top()).extracting(UserPointsResponse::userId).containsExactly("user_1");
        assertThat(redisTemplate.opsForZSet().score(zsetKey, "user_1")).isEqualTo(-100.0);
    }

    @Test
    void leaderboardIsServedFromLocalCacheUntilItExpires() {
        service.addPoints(new AddPointsRequest("user_1", 100L, "a"));
        assertThat(service.getLeaderboard()).extracting(UserPointsResponse::total).containsExactly(100L);

        service.addPoints(new AddPointsRequest("user_1", 50L, "b"));
        assertThat(service.getLeaderboard()).extracting(UserPointsResponse::total).containsExactly(100L);

        caffeineCacheManager.getCache(CacheNames.USER_POINTS_LEADERBOARD).clear();
        assertThat(service.getLeaderboard()).extracting(UserPointsResponse::total).containsExactly(150L);
    }

    private List<UserPointsResponse> top() {
        caffeineCacheManager.getCache(CacheNames.USER_POINTS_LEADERBOARD).clear();
        return service.getLeaderboard();
    }
}
