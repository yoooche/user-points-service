package com.yoooche.points.utils;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.yoooche.points.dto.AddPointsRequest;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.time.Instant;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class JsonUtilsTest {

    @Test
    void preservesIsoDateFormattingAndJavaTimeSupport() throws Exception {
        var value = new Sample("test", Instant.parse("2026-10-27T00:00:00Z"));

        String json = JsonUtils.toJson(value);

        assertThat(JsonUtils.readTree(json).get("createdAt").asText()).isEqualTo("2026-10-27T00:00:00Z");
        assertThat(JsonUtils.fromJson(json, Sample.class)).isEqualTo(value);
    }

    @ParameterizedTest
    @ValueSource(strings = {"\"100\"", "100.5"})
    void preservesStrictNumberSettings(String amount) {
        String json = """
                {"userId":"user_1","amount":%s,"reason":"test"}""".formatted(amount);

        assertThatThrownBy(() -> JsonUtils.fromJson(json, AddPointsRequest.class))
                .isInstanceOf(JsonProcessingException.class);
    }

    @Test
    void configuringACopyDoesNotChangeSharedSerialization() throws Exception {
        var copy = JsonUtils.copyMapper().enable(SerializationFeature.INDENT_OUTPUT);

        assertThat(copy.writeValueAsString(Map.of("value", 1))).contains("\n");
        assertThat(JsonUtils.toJson(Map.of("value", 1))).isEqualTo("{\"value\":1}");
    }

    private record Sample(String name, Instant createdAt) {
    }
}
