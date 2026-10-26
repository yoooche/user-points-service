package com.yoooche.points.utils;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.MapperFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.fasterxml.jackson.databind.json.JsonMapper;

public final class JsonUtils {

    private static final ObjectMapper MAPPER = JsonMapper.builder()
            .findAndAddModules()
            .disable(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS)
            .disable(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES)
            .disable(MapperFeature.DEFAULT_VIEW_INCLUSION)
            .disable(DeserializationFeature.ACCEPT_FLOAT_AS_INT)
            .disable(MapperFeature.ALLOW_COERCION_OF_SCALARS)
            .build();

    private JsonUtils() {
    }

    public static String toJson(Object value) throws JsonProcessingException {
        return MAPPER.writeValueAsString(value);
    }

    public static <T> T fromJson(String json, Class<T> type) throws JsonProcessingException {
        return MAPPER.readValue(json, type);
    }

    public static JsonNode readTree(String json) throws JsonProcessingException {
        return MAPPER.readTree(json);
    }

    /** Independent copy for serializer-specific settings; changing it does not affect shared JSON operations. */
    public static ObjectMapper copyMapper() {
        return MAPPER.copy();
    }
}
