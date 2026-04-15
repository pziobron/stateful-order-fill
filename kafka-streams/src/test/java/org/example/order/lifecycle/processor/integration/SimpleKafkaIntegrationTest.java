package org.example.order.lifecycle.processor.integration;

import lombok.extern.slf4j.Slf4j;
import org.apache.kafka.clients.producer.KafkaProducer;
import org.apache.kafka.clients.producer.ProducerConfig;
import org.apache.kafka.clients.producer.ProducerRecord;
import org.apache.kafka.common.serialization.StringSerializer;
import org.example.order.fix.model.ExecutionReport;
import org.example.order.lifecycle.processor.util.JsonUtils;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.config.YamlPropertiesFactoryBean;
import org.springframework.core.io.ClassPathResource;
import org.springframework.kafka.support.serializer.JsonSerializer;

import java.io.IOException;
import java.util.*;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import static org.awaitility.Awaitility.await;
import static org.example.order.lifecycle.processor.utils.TestUtils.generateExecutionReportMessage;
import static org.junit.jupiter.api.Assertions.*;

/**
 * Simple Kafka producer test using real Kafka.
 * This test sends messages to the actual Kafka running in docker-compose.
 * <br/>
 * NOTE:
 * This test only verifies successful message production.
 * State verification is covered by Kafka Streams TopologyTestDriver tests.
 */
@Slf4j
public class SimpleKafkaIntegrationTest {

    private KafkaProducer<String, ExecutionReport> producer;
    private String topic;

    /**
     * Sets up the test environment by initializing the Kafka producer.
     * Loads configuration from application-local.yaml and configures the producer
     * with appropriate serializers for sending ExecutionReport messages.
     */
    @BeforeEach
    void setUp() {
        // Load properties from YAML file
        Properties appProps = loadApplicationProperties();
        String bootstrapServers = appProps.getProperty("spring.kafka.bootstrap-servers");
        this.topic = appProps.getProperty("kafka.executions.topic");
        
        log.info("Loaded configuration - Bootstrap servers: {}, Topic: {}", bootstrapServers, topic);

        // Create and configure the JsonSerializer with custom ObjectMapper
        JsonSerializer<ExecutionReport> valueSerializer = new JsonSerializer<>(JsonUtils.getObjectMapper());
        valueSerializer.setAddTypeInfo(false); // Optional: disable type info if not needed

        // Configure producer properties
        Properties producerProps = new Properties();
        producerProps.put(ProducerConfig.BOOTSTRAP_SERVERS_CONFIG, bootstrapServers);
        
        // Create producer using the configured serializer
        producer = new KafkaProducer<>(
            producerProps,
            new StringSerializer(),
            valueSerializer
        );
    }

    /**
     * Cleans up test resources by closing the Kafka producer.
     * Ensures proper resource cleanup after each test.
     */
    @AfterEach
    void tearDown() {
        if (producer != null) {
            producer.close();
        }
    }

    /**
     * Tests a simple order fill scenario by sending an order and two fill messages to Kafka.
     * This test verifies that:
     * <ul>
     *   <li>Messages can be successfully sent to the executions topic</li>
     *   <li>The producer handles multiple concurrent messages correctly</li>
     *   <li>All messages are sent within the expected timeout period</li>
     * </ul>
     * 
     * The test uses real Kafka running in docker-compose and loads test data
     * from JSON templates located in the test resources.
     * 
     * @throws IOException if there's an error reading test data files
     */
    @Test
    void testSimpleOrderFill() throws IOException {
        String prefix = random();
        String inputDir = "jsonData/fillOrder/fullyFilled/2_fills/";

        ExecutionReport order = generateExecutionReportMessage(prefix, inputDir + "order.json");
        ExecutionReport fill1 = generateExecutionReportMessage(prefix, inputDir + "fill01.json");
        ExecutionReport fill2 = generateExecutionReportMessage(prefix, inputDir + "fill02.json");

        // Validate test data before sending
        assertNotNull(order.getOrderId(), "Order ID should not be null");
        assertNotNull(fill1.getOrderId(), "Fill1 ID should not be null");
        assertNotNull(fill2.getOrderId(), "Fill2 ID should not be null");

        List<Future<?>> futures = new ArrayList<>();

        try {
            log.info("Sending order {} to topic {}", order.getOrderId(), topic);
            futures.add(producer.send(new ProducerRecord<>(topic, order.getOrderId(), order)));
            
            log.info("Sending fill {} for order {}", fill1.getOrderId(), fill1.getOrderId());
            futures.add(producer.send(new ProducerRecord<>(topic, fill1.getOrderId(), fill1)));
            
            log.info("Sending fill {} for order {}", fill2.getOrderId(), fill2.getOrderId());
            futures.add(producer.send(new ProducerRecord<>(topic, fill2.getOrderId(), fill2)));
        } catch (Exception e) {
            log.error("Failed to send messages", e);
            fail("Message sending failed: " + e.getMessage());
        }

        await().atMost(10, TimeUnit.SECONDS).untilAsserted(() -> {
            assertTrue(allFuturesDone(futures), "All messages should be sent successfully");
            log.info("All {} messages form simple fill scenario sent successfully", futures.size());
        });
    }

    @Test
    void testParentOrderFill() throws IOException {
        String prefix = random();
        String inputDir = "jsonData/fillOrder/fullyFilled/parent_order/";

        ExecutionReport parentOrder = generateExecutionReportMessage(prefix, inputDir + "parent_order.json");
        ExecutionReport childOrder1 = generateExecutionReportMessage(prefix, inputDir + "child_order1.json");
        ExecutionReport childOrder2 = generateExecutionReportMessage(prefix, inputDir + "child_order2.json");
        ExecutionReport fill1ChildOrder1 = generateExecutionReportMessage(prefix, inputDir + "fill01_child_order1.json");
        ExecutionReport fill2ChildOrder2 = generateExecutionReportMessage(prefix, inputDir + "fill02_child_order2.json");


        // Validate test data before sending
        assertNotNull(parentOrder.getOrderId(), "Order ID should not be null");
        assertNotNull(childOrder1.getOrderId(), "Child Order 1 ID should not be null");
        assertNotNull(childOrder2.getOrderId(), "Child Order 2 ID should not be null");
        assertNotNull(fill1ChildOrder1.getOrderId(), "Child Order 1 Fill ID should not be null");
        assertNotNull(fill2ChildOrder2.getOrderId(), "Child Order 2 Fill ID should not be null");

        List<Future<?>> futures = new ArrayList<>();

        try {
            log.info("Sending parent order {} to topic {}", parentOrder.getOrderId(), topic);
            futures.add(producer.send(new ProducerRecord<>(topic, parentOrder.getOrderId(), parentOrder)));

            log.info("Sending child order 1 {} to topic {}", childOrder1.getOrderId(), childOrder1.getOrderId());
            futures.add(producer.send(new ProducerRecord<>(topic, childOrder1.getOrderId(), childOrder1)));

            log.info("Sending child order 2 {} to topic {}", childOrder2.getOrderId(), childOrder2.getOrderId());
            futures.add(producer.send(new ProducerRecord<>(topic, childOrder2.getOrderId(), childOrder2)));

            log.info("Sending fill {} for child order 2 {}", fill2ChildOrder2.getOrderId(), fill2ChildOrder2.getOrderId());
            futures.add(producer.send(new ProducerRecord<>(topic, fill2ChildOrder2.getOrderId(), fill2ChildOrder2)));

            log.info("Sending fill {} for child order 1 {}", fill1ChildOrder1.getOrderId(), fill1ChildOrder1.getOrderId());
            futures.add(producer.send(new ProducerRecord<>(topic, fill1ChildOrder1.getOrderId(), fill1ChildOrder1)));

            log.info("Sending fill {} for child order 2 {}", fill2ChildOrder2.getOrderId(), fill2ChildOrder2.getOrderId());
            futures.add(producer.send(new ProducerRecord<>(topic, fill2ChildOrder2.getOrderId(), fill2ChildOrder2)));
        } catch (Exception e) {
            log.error("Failed to send messages", e);
            fail("Message sending failed: " + e.getMessage());
        }

        await().atMost(10, TimeUnit.SECONDS).untilAsserted(() -> {
            assertTrue(allFuturesDone(futures), "All messages should be sent successfully");
            log.info("All {} messages for parent order scenario sent successfully", futures.size());
        });
    }

    /**
     * Checks if all Future objects in the collection have completed execution.
     * 
     * @param futures Collection of Future objects to check
     * @return true if all futures are done, false otherwise
     */
    private boolean allFuturesDone(Collection<Future<?>> futures) {
        return futures.stream().allMatch(Future::isDone);
    }

    /**
     * Loads application properties from the application-local.yaml file.
     * Provides fallback default values if the configuration file cannot be loaded.
     * 
     * @return Properties object containing Kafka configuration
     */
    private Properties loadApplicationProperties() {
        try {
            YamlPropertiesFactoryBean factory = new YamlPropertiesFactoryBean();
            factory.setResources(new ClassPathResource("application-local.yaml"));
            return factory.getObject();
        } catch (Exception e) {
            log.error("Failed to load application-local.yaml", e);
            // Fallback to defaults
            Properties props = new Properties();
            props.setProperty("spring.kafka.bootstrap-servers", "localhost:9092");
            props.setProperty("kafka.executions.topic", "org.example.order.executions");
            return props;
        }
    }

    /**
     * Generates a random 4-character string for use in test data.
     * This helps ensure test isolation by providing unique identifiers.
     * 
     * @return A random 4-character string
     */
    private String random() {
        return UUID.randomUUID().toString().substring(0, 4);
    }
}
