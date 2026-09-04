package org.voxrox.mailbackend.feature.account;

import java.util.Map;

import org.jspecify.annotations.Nullable;

import tools.jackson.core.JacksonException;
import tools.jackson.core.type.TypeReference;
import tools.jackson.databind.ObjectMapper;

/**
 * Jackson 3 ({@code tools.jackson}), like the rest of the application. Jackson
 * 2 is not shipped: {@code backend/pom.xml} excludes
 * {@code com.fasterxml.jackson.core} from the fat jar, because it only entered
 * the tree through springdoc, which is excluded too. The compile classpath
 * still has it, so a Jackson 2 import here compiles and every test passes — and
 * then the packaged sidecar dies on the first account it maps. See
 * {@code ArchitectureTest.productionCodeDoesNotUseJackson2}.
 */
public final class AccountLastErrorJson {

    private static final ObjectMapper MAPPER = new ObjectMapper();
    private static final TypeReference<Map<String, String>> STRING_MAP = new TypeReference<>() {
    };

    private AccountLastErrorJson() {
    }

    public static @Nullable String write(@Nullable Map<String, String> args) {
        if (args == null || args.isEmpty()) {
            return null;
        }
        try {
            return MAPPER.writeValueAsString(args);
        } catch (JacksonException e) {
            throw new IllegalArgumentException("Failed to serialize last_error_args", e);
        }
    }

    public static Map<String, String> read(@Nullable String json) {
        if (json == null || json.isBlank()) {
            return Map.of();
        }
        try {
            return MAPPER.readValue(json, STRING_MAP);
        } catch (JacksonException e) {
            return Map.of();
        }
    }
}
