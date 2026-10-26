package com.yoooche.points.config;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.yoooche.points.utils.JsonUtils;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
public class JacksonConfig {

    @Bean
    public ObjectMapper objectMapper() {
        return JsonUtils.copyMapper();
    }
}
