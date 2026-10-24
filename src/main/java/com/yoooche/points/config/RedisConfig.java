package com.yoooche.points.config;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.jsontype.BasicPolymorphicTypeValidator;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.cache.annotation.CachingConfigurer;
import org.springframework.cache.annotation.EnableCaching;
import org.springframework.cache.interceptor.CacheErrorHandler;
import org.springframework.cache.interceptor.LoggingCacheErrorHandler;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Primary;
import org.springframework.data.redis.cache.BatchStrategies;
import org.springframework.data.redis.cache.RedisCacheConfiguration;
import org.springframework.data.redis.cache.RedisCacheManager;
import org.springframework.data.redis.cache.RedisCacheWriter;
import org.springframework.data.redis.connection.RedisConnectionFactory;
import org.springframework.data.redis.serializer.GenericJackson2JsonRedisSerializer;
import org.springframework.data.redis.serializer.RedisSerializationContext.SerializationPair;
import org.springframework.data.redis.serializer.RedisSerializer;

@Configuration
@EnableCaching
@EnableConfigurationProperties(RedisCacheProperties.class)
public class RedisConfig implements CachingConfigurer {

    @Value("${spring.application.name}")
    private String system;

    @Bean("redisCacheManager")
    @Primary
    public RedisCacheManager redisCacheManager(RedisConnectionFactory connectionFactory, ObjectMapper objectMapper,
            RedisCacheProperties properties) {
        var defaults = RedisCacheConfiguration.defaultCacheConfig()
                .prefixCacheNameWith(system + ":")
                .disableCachingNullValues()
                .serializeValuesWith(SerializationPair.fromSerializer(valueSerializer(objectMapper)));

        var builder = RedisCacheManager
                .builder(RedisCacheWriter.nonLockingRedisCacheWriter(connectionFactory, BatchStrategies.scan(1000)))
                .cacheDefaults(defaults)
                .disableCreateOnMissingCache();
        properties.getCaches().forEach(cache ->
                builder.withCacheConfiguration(cache.getCacheName(), defaults.entryTtl(cache.getTtl())));
        return builder.build();
    }

    /** Redis is an accelerator, not a dependency: on cache errors, log and fall through to MySQL. */
    @Override
    public CacheErrorHandler errorHandler() {
        return new LoggingCacheErrorHandler();
    }

    /** Type hints let one serializer serve every cache; the validator restricts them to our own and JDK types. */
    private static RedisSerializer<Object> valueSerializer(ObjectMapper objectMapper) {
        var mapper = objectMapper.copy();
        mapper.setPolymorphicTypeValidator(BasicPolymorphicTypeValidator.builder()
                .allowIfSubType("com.yoooche.points.")
                .allowIfSubType("java.lang.")
                .allowIfSubType("java.util.")
                .allowIfSubType("java.time.")
                .build());
        return GenericJackson2JsonRedisSerializer.builder().objectMapper(mapper).defaultTyping(true).build();
    }
}
