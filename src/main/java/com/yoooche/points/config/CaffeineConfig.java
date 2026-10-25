package com.yoooche.points.config;

import com.github.benmanes.caffeine.cache.Caffeine;
import org.springframework.cache.caffeine.CaffeineCacheManager;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.util.List;

@Configuration
public class CaffeineConfig {

    @Bean("caffeineCacheManager")
    public CaffeineCacheManager caffeineCacheManager(CaffeineCacheProperties properties) {
        var manager = new CaffeineCacheManager();
        manager.setAllowNullValues(false);
        manager.setCacheNames(List.of());
        properties.getCaches().forEach(cache ->
                manager.registerCustomCache(cache.getCacheName(),
                        Caffeine.from(cache.getSpec()).build())
        );
        return manager;
    }
}
