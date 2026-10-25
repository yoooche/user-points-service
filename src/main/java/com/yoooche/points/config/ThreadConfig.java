package com.yoooche.points.config;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;

@Configuration
public class ThreadConfig {

    @Bean(name = "outboxRelayExecutor")
    public ThreadPoolTaskExecutor outboxRelayExecutor() {
        var executor = new ThreadPoolTaskExecutor();
        executor.setThreadNamePrefix("outbox-relay-");
        executor.setCorePoolSize(1);
        executor.setMaxPoolSize(1);
        executor.setWaitForTasksToCompleteOnShutdown(true);
        executor.setAwaitTerminationSeconds(10);
        return executor;
    }
}
