package com.yoooche.points;

import org.apache.rocketmq.spring.core.RocketMQTemplate;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.context.annotation.Bean;
import org.springframework.test.context.bean.override.mockito.MockReset;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.containers.MySQLContainer;
import org.testcontainers.utility.DockerImageName;
import org.testcontainers.utility.MountableFile;

import static org.mockito.Mockito.mock;

@TestConfiguration(proxyBeanMethods = false)
public class TestcontainersConfiguration {

    @Bean
    @ServiceConnection
    MySQLContainer<?> mysqlContainer() {
        return new MySQLContainer<>(DockerImageName.parse("mysql:8.0"))
                .withCopyFileToContainer(MountableFile.forHostPath("init.sql"), "/docker-entrypoint-initdb.d/init.sql");
    }

    @Bean
    @ServiceConnection(name = "redis")
    GenericContainer<?> redisContainer() {
        return new GenericContainer<>(DockerImageName.parse("redis:7")).withExposedPorts(6379);
    }

    /** Replaces the auto-configured template by name, so tests never start a producer or need a broker. */
    @Bean
    RocketMQTemplate rocketMQTemplate() {
        return mock(RocketMQTemplate.class, MockReset.withSettings(MockReset.AFTER));
    }
}
