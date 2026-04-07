package org.example.order.lifecycle.processor.config;

import org.apache.kafka.common.serialization.Serde;
import org.apache.kafka.streams.StreamsBuilder;
import org.apache.kafka.streams.StreamsConfig;
import org.example.order.fix.model.ExecutionReport;
import org.example.order.lifecycle.model.OrderState;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.util.Properties;

import static org.example.order.lifecycle.processor.util.JsonUtils.createJsonSerde;

/**
 * Configuration class for Kafka Streams in the order lifecycle processor.
 * <p>
 * This class sets up the necessary configuration for the Kafka Streams application,
 * including serialization/deserialization for domain objects and basic stream properties.
 * It's responsible for creating and configuring the Kafka Streams execution environment.
 *
 * <p>The configuration is driven by Spring's property system, with values typically
 * provided through application properties or YAML files.</p>
 *
 * @see org.springframework.context.annotation.Configuration
 */
@Configuration
@SuppressWarnings("unused")
public class KafkaStreamsConfig {
    /**
     * Comma-separated list of host:port pairs to use for establishing the initial connection to the Kafka cluster.
     * Injected from Spring's environment properties (spring.kafka.bootstrap-servers).
     */
    @Value("${spring.kafka.bootstrap-servers}")
    private String bootstrapServers;

    /**
     * The application ID for the Kafka Streams application.
     * Used as the client-id prefix and the group-id for consumer coordination.
     * Injected from Spring's environment properties (spring.application.name).
     */
    @Value("${spring.application.name}")
    private String applicationName;

    /**
     * Creates and configures the base properties for the Kafka Streams application.
     * <p>
     * This method sets up the essential configuration required by Kafka Streams,
     * including the application ID and bootstrap servers. The application ID is used
     * for consumer group management and state store namespacing.
     *
     * @return A Properties object containing the Kafka Streams configuration
     * @see org.apache.kafka.streams.StreamsConfig
     */
    @Bean
    @SuppressWarnings("unused")
    public Properties kafkaStreamsProperties() {
        Properties props = new Properties();

        //Basic Kafka Stream configuration
        props.put(StreamsConfig.APPLICATION_ID_CONFIG, applicationName);
        props.put(StreamsConfig.BOOTSTRAP_SERVERS_CONFIG, bootstrapServers);

        return props;
    }

    /**
     * Creates a JSON Serde for serializing and deserializing ExecutionReport objects.
     *
     * @return Configured Serde for ExecutionReport objects
     * @see org.example.order.fix.model.ExecutionReport
     */
    @Bean
    @SuppressWarnings("unused")
    public Serde<ExecutionReport> executionReportSerde() {
        return createJsonSerde(ExecutionReport.class);
    }

    /**
     * Creates a JSON Serde for serializing and deserializing OrderState objects.
     *
     * @return Configured Serde for OrderState objects
     * @see org.example.order.lifecycle.model.OrderState
     */
    @Bean
    @SuppressWarnings("unused")
    public Serde<OrderState> orderStateSerde() {
        return createJsonSerde(OrderState.class);
    }

    /**
     * Creates and provides a StreamsBuilder instance for building Kafka Streams topologies.
     * This bean is required by Spring Kafka Streams to build the processing topology.
     *
     * @return A new StreamsBuilder instance
     * @see org.apache.kafka.streams.StreamsBuilder
     */
    @Bean
    @SuppressWarnings("unused")
    public StreamsBuilder streamsBuilder() {
        return new StreamsBuilder();
    }
}
