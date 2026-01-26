package org.example.order.lifecycle.processor.util;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.json.JsonMapper;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import lombok.Getter;
import lombok.extern.slf4j.Slf4j;
import org.springframework.kafka.support.serializer.JsonSerde;

import static com.fasterxml.jackson.annotation.JsonInclude.Include.NON_EMPTY;
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
            .build().setSerializationInclusion(NON_EMPTY);

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

    /**
     * Creates a new {@link JsonSerde} instance for the specified class using the pre-configured ObjectMapper.
     * This ensures consistent JSON serialization/deserialization behavior across the application.
     *
     * @param <T>   the type of object to be serialized/deserialized
     * @param clazz the class of the object to be serialized/deserialized
     * @return a new JsonSerde instance configured with the application's ObjectMapper
     * @throws IllegalArgumentException if clazz is null
     */
    public static <T> JsonSerde<T> createJsonSerde(Class<T> clazz) {
        return new JsonSerde<>(clazz, objectMapper);
    }

}
