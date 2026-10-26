package com.yoooche.points.config;

import lombok.Getter;
import lombok.Setter;
import org.springframework.boot.context.properties.ConfigurationProperties;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;

@Getter
@Setter
@ConfigurationProperties(prefix = "redis")
public class RedisCacheProperties {

    private List<CacheSpec> caches = new ArrayList<>();

    public Duration getTtl(String cacheName) {
        return caches.stream()
                .filter(cache -> cacheName.equals(cache.getCacheName()))
                .map(CacheSpec::getTtl)
                .findFirst()
                .orElseThrow(() -> new IllegalArgumentException("No Redis TTL configured for cache: " + cacheName));
    }

    @Getter
    @Setter
    public static class CacheSpec {

        private String cacheName;
        private Duration ttl;
    }
}
