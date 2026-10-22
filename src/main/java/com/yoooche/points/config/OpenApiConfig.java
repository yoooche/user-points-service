package com.yoooche.points.config;

import io.swagger.v3.oas.models.OpenAPI;
import io.swagger.v3.oas.models.info.Contact;
import io.swagger.v3.oas.models.info.Info;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
public class OpenApiConfig {

    @Bean
    public OpenAPI userPointsOpenApi() {
        return new OpenAPI().info(new Info()
                .title("User Points Service RESTful API")
                .description("Add and query user reward points, and a top 10 leaderboard.")
                .version("v1")
                .contact(new Contact().name("Louis Yang").url("https://github.com/yoooche/user-points-service")));
    }
}
