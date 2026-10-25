package com.yoooche.points.config;

import jakarta.validation.Valid;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Positive;
import lombok.Getter;
import lombok.Setter;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.validation.annotation.Validated;

@Getter
@Setter
@Validated
@ConfigurationProperties(prefix = "outbox")
public class OutboxProperties {

    @Valid
    private Relay relay = new Relay();

    @Getter
    @Setter
    public static class Relay {

        @Positive
        @Max(1000)
        private int batchSize;
    }
}
