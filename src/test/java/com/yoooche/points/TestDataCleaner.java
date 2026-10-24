package com.yoooche.points;

import com.yoooche.points.constant.CacheNames;
import com.yoooche.points.repository.UserPointsRecordRepository;
import com.yoooche.points.repository.UserPointsRepository;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.test.context.TestComponent;
import org.springframework.cache.CacheManager;
import org.springframework.data.redis.core.RedisCallback;
import org.springframework.data.redis.core.StringRedisTemplate;

/** Integration test classes share one context, so MySQL, Redis and the local cache must be reset per test. */
@TestComponent
public class TestDataCleaner {

    @Autowired
    private UserPointsRecordRepository recordRepository;

    @Autowired
    private UserPointsRepository pointsRepository;

    @Autowired
    private StringRedisTemplate redisTemplate;

    @Autowired
    @Qualifier("caffeineCacheManager")
    private CacheManager caffeineCacheManager;

    public void reset() {
        recordRepository.deleteAll();
        pointsRepository.deleteAll();
        redisTemplate.execute((RedisCallback<Void>) connection -> {
            connection.serverCommands().flushDb();
            return null;
        });
        caffeineCacheManager.getCache(CacheNames.USER_POINTS_LEADERBOARD).clear();
    }
}
