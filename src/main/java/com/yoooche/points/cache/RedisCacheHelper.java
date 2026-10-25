package com.yoooche.points.cache;

import lombok.RequiredArgsConstructor;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.redis.connection.RedisZSetCommands.ZAddArgs;
import org.springframework.data.redis.connection.zset.Tuple;
import org.springframework.data.redis.core.RedisCallback;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.ZSetOperations.TypedTuple;
import org.springframework.stereotype.Component;

import java.nio.charset.StandardCharsets;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

@Component
@RequiredArgsConstructor
public class RedisCacheHelper {

    private final StringRedisTemplate redisTemplate;

    @Value("${spring.application.name}")
    private String system;

    public void zAddIfLower(String key, Map<String, Double> scores) {
        if (scores.isEmpty()) {
            return;
        }
        Set<Tuple> tuples = scores.entrySet().stream()
                .map(entry -> Tuple.of(bytes(entry.getKey()), entry.getValue()))
                .collect(Collectors.toSet());
        byte[] rawKey = bytes(prefixed(key));
        redisTemplate.execute((RedisCallback<Long>) connection ->
                connection.zSetCommands().zAdd(rawKey, tuples, ZAddArgs.empty().lt()));
    }

    public void zKeepFirst(String key, long count) {
        redisTemplate.opsForZSet().removeRange(prefixed(key), count, -1);
    }

    public void zRemove(String key, String member) {
        redisTemplate.opsForZSet().remove(prefixed(key), member);
    }

    public Map<String, Double> zRangeWithScores(String key, long start, long end) {
        Set<TypedTuple<String>> entries = redisTemplate.opsForZSet().rangeWithScores(prefixed(key), start, end);
        var result = new LinkedHashMap<String, Double>();
        if (entries != null) {
            entries.forEach(entry -> result.put(entry.getValue(), entry.getScore()));
        }
        return result;
    }

    public long zSize(String key) {
        Long size = redisTemplate.opsForZSet().zCard(prefixed(key));
        return size == null ? 0 : size;
    }

    private String prefixed(String key) {
        return system + ":" + key;
    }

    private static byte[] bytes(String value) {
        return value.getBytes(StandardCharsets.UTF_8);
    }
}
