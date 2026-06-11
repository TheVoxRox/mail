package org.voxrox.mailbackend.feature.account;

import java.util.Map;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;

public final class AccountLastErrorJson {

    private static final ObjectMapper MAPPER = new ObjectMapper();
    private static final TypeReference<Map<String, String>> STRING_MAP = new TypeReference<>() {
    };

    private AccountLastErrorJson() {
    }

    public static String write(Map<String, String> args) {
        if (args == null || args.isEmpty()) {
            return null;
        }
        try {
            return MAPPER.writeValueAsString(args);
        } catch (JsonProcessingException e) {
            throw new IllegalArgumentException("Failed to serialize last_error_args", e);
        }
    }

    public static Map<String, String> read(String json) {
        if (json == null || json.isBlank()) {
            return Map.of();
        }
        try {
            return MAPPER.readValue(json, STRING_MAP);
        } catch (JsonProcessingException e) {
            return Map.of();
        }
    }
}
