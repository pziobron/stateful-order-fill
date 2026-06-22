package org.example.order.lifecycle.util;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.json.JsonMapper;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import lombok.Getter;
import lombok.extern.slf4j.Slf4j;

import static com.fasterxml.jackson.core.json.JsonWriteFeature.WRITE_NUMBERS_AS_STRINGS;
import static com.fasterxml.jackson.databind.DeserializationFeature.UNWRAP_ROOT_VALUE;
import static com.fasterxml.jackson.databind.SerializationFeature.WRAP_ROOT_VALUE;
import static com.fasterxml.jackson.databind.SerializationFeature.WRITE_DATES_AS_TIMESTAMPS;

/**
 * Utility class providing JSON serialization and deserialization functionality.
 * <p>
 * This class uses Jackson's {@link ObjectMapper} for JSON processing and provides
 * convenience methods for common JSON operations with proper error handling.
 * </p>
 */
@Slf4j
public class JsonUtils {

    @Getter
    private static final ObjectMapper objectMapper = JsonMapper.builder()
            .addModule(new JavaTimeModule())
            .disable(WRITE_DATES_AS_TIMESTAMPS)
            .enable(WRITE_NUMBERS_AS_STRINGS.mappedFeature())
            .enable(WRAP_ROOT_VALUE)
            .enable(UNWRAP_ROOT_VALUE)
            .build();

    private JsonUtils() {
    }

    /**
     * Converts an object to a pretty-printed JSON string.
     *
     * @param object the object to be converted to JSON
     * @return a pretty-printed JSON string representation of the object,
     * or the result of the object's {@code toString()} method if conversion fails
     */
    public static String toPrettyJson(Object object) {
        try {
            return objectMapper.writeValueAsString(object);
        } catch (Exception e) {
            log.error("Failed to convert object to JSON", e);
            return object.toString();
        }
    }

    /**
     * Deserializes a JSON string into an object of the specified type.
     *
     * @param <T>  the type of the object to deserialize to
     * @param json the JSON string to deserialize
     * @param type the class of the object to deserialize to
     * @return the deserialized object, or null if deserialization fails
     * @throws IllegalArgumentException if json is null or empty
     */
    public static <T> T readJsonToObject(String json, Class<T> type) {
        if (json == null || json.trim().isEmpty()) {
            throw new IllegalArgumentException("JSON string cannot be null or empty");
        }
        try {
            return objectMapper.readValue(json, type);
        } catch (Exception e) {
            log.error("Failed to deserialize JSON to type: " + type.getSimpleName(), e);
            return null;
        }
    }

}
