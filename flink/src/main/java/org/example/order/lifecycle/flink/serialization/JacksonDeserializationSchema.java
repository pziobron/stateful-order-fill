package org.example.order.lifecycle.flink.serialization;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.apache.flink.api.common.serialization.DeserializationSchema;
import org.apache.flink.api.common.typeinfo.TypeInformation;
import org.example.order.lifecycle.util.JsonUtils;
import org.apache.flink.api.java.typeutils.GenericTypeInfo;

import java.io.IOException;

/**
 * Generic Flink deserialization schema based on Jackson.
 * <p>
 * Converts incoming Kafka message payloads into strongly typed Java objects
 * using the shared application {@link ObjectMapper} configuration provided
 * by {@link JsonUtils}.
 * <p>
 * The ObjectMapper instance is initialized lazily to avoid unnecessary
 * serialization overhead during Flink operator serialization.
 *
 * @param <T> target type produced by this deserialization schema
 */
public class JacksonDeserializationSchema<T> implements DeserializationSchema<T> {

    private final Class<T> clazz;

    private transient ObjectMapper objectMapper;

    /**
     * Creates a new deserialization schema for the specified type.
     *
     * @param clazz target class used during JSON deserialization
     */
    public JacksonDeserializationSchema(Class<T> clazz) {
        this.clazz = clazz;
    }

    /**
     * Deserializes a Kafka message payload into a Java object.
     *
     * @param message serialized JSON payload
     * @return deserialized object instance
     * @throws IOException if the payload cannot be deserialized
     */
    @Override
    public T deserialize(byte[] message) throws IOException {
        if (objectMapper == null) {
            objectMapper = JsonUtils.getObjectMapper();
        }

        return objectMapper.readValue(message, clazz);
    }

    @Override
    public boolean isEndOfStream(T nextElement) {
        return false;
    }

    /**
     * Returns Flink type information for the produced type.
     *
     * @return produced type information
     */
    @Override
    public TypeInformation<T> getProducedType() {
        return new GenericTypeInfo<>(clazz);
    }
}