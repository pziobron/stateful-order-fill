package org.example.order.lifecycle.processor.stream;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.apache.kafka.common.serialization.Serde;
import org.apache.kafka.common.serialization.Serdes;
import org.apache.kafka.common.utils.Bytes;
import org.apache.kafka.streams.KafkaStreams;
import org.apache.kafka.streams.StreamsBuilder;
import org.apache.kafka.streams.kstream.*;
import org.apache.kafka.streams.state.KeyValueStore;
import org.example.order.fix.model.ExecutionReport;
import org.example.order.lifecycle.model.OrderState;
import org.example.order.lifecycle.processor.service.FillOrderService;
import org.springframework.beans.factory.DisposableBean;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import javax.annotation.PostConstruct;
import java.util.Properties;

/**
 * Kafka Streams implementation for processing order fills and maintaining order state.
 * <p>
 * This service consumes execution reports from a Kafka topic, processes them to update
 * the state of orders, and maintains the state in a state store.
 * <p>
 * The stream processing pipeline:
 * <ol>
 *   <li>Consumes execution reports from the configured topic</li>
 *   <li>Aggregates execution reports to maintain order state</li>
 *   <li>Stores the latest order state in a persistent state store</li>
 * </ol>
 *
 * @see org.example.order.lifecycle.processor.service.FillOrderService
 * @see org.example.order.fix.model.ExecutionReport
 * @see org.example.order.lifecycle.model.OrderState
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class FillOrderStream implements DisposableBean {

    public static final String ORDER_STATE_STORE = "order-state-store";

    /**
     * Builder for constructing the Kafka Streams topology.
     */
    private final StreamsBuilder streamsBuilder;

    /**
     * Serde for serializing/deserializing ExecutionReport objects.
     */
    private final Serde<ExecutionReport> executionReportSerde;

    /**
     * Serde for serializing/deserializing OrderState objects.
     */
    private final Serde<OrderState> orderStateSerde;

    /**
     * Configuration properties for the Kafka Streams application.
     */
    private final Properties kafkaStreamsProperties;

    /**
     * Service for processing execution reports and updating order state.
     */
    private final FillOrderService fillOrderService;
    /**
     * Name of the topic from which execution reports are consumed.
     * Configured via Spring's property injection.
     */
    @Value("${kafka.executions.topic}")
    protected String executionReportsTopic;
    /**
     * The Kafka Streams instance.
     */
    private KafkaStreams streams;

    /**
     * Initializes and starts the Kafka Streams application.
     * <p>
     * This method is automatically called after the bean is constructed and
     * all dependencies are injected. It sets up the stream processing topology
     * and starts the Kafka Streams application.
     *
     * @throws RuntimeException if there is an error starting the streams application
     */
    @PostConstruct
    public void startKafkaStream() {
        try {
            // Create a stream from the execution reports topic
            log.info("Creating stream from topic: {}", executionReportsTopic);
            KStream<String, ExecutionReport> executionReportStream =
                    streamsBuilder.stream(executionReportsTopic,
                                    Consumed.with(Serdes.String(), executionReportSerde));

            // Group by order ID and aggregate order states
            KTable<String, OrderState> orderStates = executionReportStream.groupByKey(Grouped.with(Serdes.String(), executionReportSerde))
                    .aggregate(
                            OrderState::new, //initialize the state
                            fillOrderService::processExecutionReport,
                            Materialized.<String, OrderState, KeyValueStore<Bytes, byte[]>>as(ORDER_STATE_STORE)
                                    .withKeySerde(Serdes.String())
                                    .withValueSerde(orderStateSerde));

            orderStates.toStream().print(Printed.<String, OrderState>toSysOut().withLabel("LIFECYCLES"));

            var topology = streamsBuilder.build();
            streams = new KafkaStreams(topology, kafkaStreamsProperties);

            // Add shutdown hook
            Runtime.getRuntime().addShutdownHook(new Thread(() -> {
                log.info("Shutting down Kafka Streams...");
                streams.close(java.time.Duration.ofSeconds(30));
            }));

            log.info("Starting Kafka Streams...");

            streams.start();

        } catch (Exception e) {
            log.error("Error in Kafka Streams application", e);
            throw new RuntimeException("Failed to start Kafka Streams", e);
        }
    }

    /**
     * Cleans up resources when the application shuts down.
     * <p>
     * This method is called when the Spring application context is being closed.
     * It ensures that the Kafka Streams application is properly shut down.
     */
    @Override
    public void destroy() {
        if (streams != null) {
            log.info("Closing Kafka Streams...");
            streams.close(java.time.Duration.ofSeconds(30));
        }
    }

}
