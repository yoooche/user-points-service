package com.yoooche.points.config;

import lombok.Getter;
import lombok.Setter;
import org.springframework.boot.context.properties.ConfigurationProperties;

import java.util.ArrayList;
import java.util.List;

@Getter
@Setter
@ConfigurationProperties(prefix = "caffeine")
public class CaffeineCacheProperties {

    private List<CacheSpec> caches = new ArrayList<>();

    @Getter
    @Setter
    public static class CacheSpec {

        private String cacheName;
        private String spec;
    }
}
