package com.yoooche.points.cache;

import com.yoooche.points.config.RedisCacheProperties;
import lombok.RequiredArgsConstructor;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.DefaultRedisScript;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;

@Component
@RequiredArgsConstructor
public class RedisCacheHelper {

    private static final DefaultRedisScript<Long> REPLACE_ZSET = new DefaultRedisScript<>("""
            redis.call('DEL', KEYS[1])
            for i = 2, #ARGV do
                redis.call('ZADD', KEYS[1], i - 2, ARGV[i])
            end
            if #ARGV > 1 and tonumber(ARGV[1]) > 0 then redis.call('PEXPIRE', KEYS[1], ARGV[1]) end
            return redis.call('ZCARD', KEYS[1])
            """, Long.class);

    private final StringRedisTemplate redisTemplate;
    private final RedisCacheProperties properties;

    @Value("${spring.application.name}")
    private String system;

    public List<String> zrange(String cacheName, long start, long end) {
        return List.copyOf(redisTemplate.opsForZSet().range(prefixed(cacheName), start, end));
    }

    public void replaceZSet(String cacheName, List<String> members) {
        var args = new ArrayList<String>();
        args.add(Long.toString(properties.getTtl(cacheName).toMillis()));
        args.addAll(members);
        redisTemplate.execute(REPLACE_ZSET, List.of(prefixed(cacheName)), args.toArray());
    }

    public void delete(String cacheName) {
        redisTemplate.delete(prefixed(cacheName));
    }

    private String prefixed(String cacheName) {
        return system + ":" + cacheName;
    }
}
