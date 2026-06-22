package org.example.order.lifecycle.processor.integration;

import lombok.extern.slf4j.Slf4j;
import org.apache.kafka.clients.consumer.ConsumerConfig;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.apache.kafka.clients.consumer.KafkaConsumer;
import org.apache.kafka.clients.producer.KafkaProducer;
import org.apache.kafka.clients.producer.ProducerConfig;
import org.apache.kafka.clients.producer.ProducerRecord;
import org.apache.kafka.common.serialization.StringDeserializer;
import org.apache.kafka.common.serialization.StringSerializer;
import org.example.order.fix.model.ExecutionReport;
import org.example.order.fix.model.VerificationRecord;
import org.example.order.lifecycle.processor.util.JsonUtils;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.config.YamlPropertiesFactoryBean;
import org.springframework.core.io.ClassPathResource;
import org.springframework.kafka.support.serializer.JsonDeserializer;
import org.springframework.kafka.support.serializer.JsonSerializer;

import java.io.IOException;
import java.time.Instant;
import java.util.*;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.stream.Collectors;

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
    private KafkaConsumer<String, VerificationRecord> verificationConsumer;
    private String topic;

    private int testIterations = 1;

    /**
     * Sets up the test environment by initializing the Kafka producer.
     * Loads configuration from application-local.yaml and configures the producer
     * with appropriate serializers for sending ExecutionReport messages.
     */
    @BeforeEach
    void setUp() {
        // Read test iterations from system property (set by gradle -DtestIterations) or env var
        String iterationsProp = System.getProperty("testIterations");
        if (iterationsProp == null) {
            iterationsProp = System.getenv("TEST_ITERATIONS");
        }
        if (iterationsProp != null) {
            try {
                this.testIterations = Integer.parseInt(iterationsProp);
            } catch (NumberFormatException e) {
                log.warn("Invalid testIterations value '{}', using default 1", iterationsProp);
            }
        }
        // Load properties from YAML file
        Properties appProps = loadApplicationProperties();

        String bootstrapServers = System.getProperty(
                "kafka.bootstrapServers",
                appProps.getProperty("spring.kafka.bootstrap-servers")
        );

        this.topic = appProps.getProperty("kafka.executions.topic");
        String verificationTopic = appProps.getProperty("kafka.verification.topic", "org.example.order.verification");

        log.info("Loaded configuration - Bootstrap servers: {}, Topic: {}, Verification Topic: {}",
                bootstrapServers, topic, verificationTopic);

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

        // Create verification topic consumer
        Properties consumerProps = new Properties();
        consumerProps.put(ConsumerConfig.BOOTSTRAP_SERVERS_CONFIG, bootstrapServers);
        consumerProps.put(ConsumerConfig.GROUP_ID_CONFIG, "integration-test-" + UUID.randomUUID());
        consumerProps.put(ConsumerConfig.AUTO_OFFSET_RESET_CONFIG, "earliest");
        consumerProps.put(ConsumerConfig.MAX_POLL_RECORDS_CONFIG, "10000");
        JsonDeserializer<VerificationRecord> verificationDeserializer =
                new JsonDeserializer<>(VerificationRecord.class, JsonUtils.getObjectMapper());

        verificationDeserializer.setRemoveTypeHeaders(false);
        verificationDeserializer.addTrustedPackages("*");
        verificationDeserializer.setUseTypeMapperForKey(false);

        verificationConsumer = new KafkaConsumer<>(
                consumerProps,
                new StringDeserializer(),
                verificationDeserializer
        );
        verificationConsumer.subscribe(Collections.singletonList(verificationTopic));
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
        if (verificationConsumer != null) {
            verificationConsumer.close();
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
        log.info("Running testSimpleOrderFill as smoke test");

        String prefix = uniquePrefix();
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
            futures.add(producer.send(new ProducerRecord<>(topic, order.getOrderId(), order)));
            futures.add(producer.send(new ProducerRecord<>(topic, fill1.getOrderId(), fill1)));
            futures.add(producer.send(new ProducerRecord<>(topic, fill2.getOrderId(), fill2)));
        } catch (Exception e) {
            log.error("Failed to send messages", e);
            fail("Message sending failed: " + e.getMessage());
        }

        await().atMost(10, TimeUnit.SECONDS).untilAsserted(() -> {
            assertTrue(allFuturesDone(futures), "All messages should be sent successfully");
            log.info("All {} messages for simple fill scenario sent successfully", futures.size());
        });

        log.info(
                "SMOKE_TEST_RESULT scenario=simple-order-fill messages={}",
                futures.size()
        );
    }

    /**
     * Tests the parent order fill scenario.
     *
     * @throws IOException when reading test data
     */
    @Test
    void testParentOrderFill() throws IOException {
        log.info("Running testParentOrderFill {} iteration(s)", testIterations);

        long startedAtNanos = System.nanoTime();

        String inputDir = "jsonData/fillOrder/fullyFilled/parent_order/";

        List<Future<?>> futures = new ArrayList<>();
        Set<String> expectedHierarchyKeys = new HashSet<>();

        for (int i = 0; i < testIterations; i++) {
            String prefix = uniquePrefix(i);

            ExecutionReport parentOrder =
                    generateExecutionReportMessage(prefix, inputDir + "parent_order.json");
            ExecutionReport childOrder1 =
                    generateExecutionReportMessage(prefix, inputDir + "child_order1.json");
            ExecutionReport childOrder2 =
                    generateExecutionReportMessage(prefix, inputDir + "child_order2.json");
            ExecutionReport fill1ChildOrder1 =
                    generateExecutionReportMessage(prefix, inputDir + "fill01_child_order1.json");
            ExecutionReport fill2ChildOrder2 =
                    generateExecutionReportMessage(prefix, inputDir + "fill02_child_order2.json");

            assertNotNull(parentOrder.getOrderId(), "Parent order ID should not be null");
            assertNotNull(childOrder1.getOrderId(), "Child Order 1 ID should not be null");
            assertNotNull(childOrder2.getOrderId(), "Child Order 2 ID should not be null");
            assertNotNull(fill1ChildOrder1.getOrderId(), "Child Order 1 Fill ID should not be null");
            assertNotNull(fill2ChildOrder2.getOrderId(), "Child Order 2 Fill ID should not be null");

            expectedHierarchyKeys.add(parentOrder.getOrderId());

            futures.add(producer.send(new ProducerRecord<>(topic, parentOrder.getOrderId(), parentOrder)));
            futures.add(producer.send(new ProducerRecord<>(topic, childOrder1.getOrderId(), childOrder1)));
            futures.add(producer.send(new ProducerRecord<>(topic, childOrder2.getOrderId(), childOrder2)));
            futures.add(producer.send(new ProducerRecord<>(topic, fill1ChildOrder1.getOrderId(), fill1ChildOrder1)));
            futures.add(producer.send(new ProducerRecord<>(topic, fill2ChildOrder2.getOrderId(), fill2ChildOrder2)));
        }

        producer.flush();

        await().atMost(30, TimeUnit.SECONDS).untilAsserted(() ->
                assertTrue(allFuturesDone(futures), "All messages should be sent successfully")
        );

        int verificationTimeoutSeconds = testIterations >= 100_000 ? 300 : 60;

        VerificationResult verificationResult =
                verifyPartitioning(expectedHierarchyKeys, verificationTimeoutSeconds);

        long endToEndVerificationMs = TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - startedAtNanos);
        double endToEndVerificationSeconds = endToEndVerificationMs / 1000.0;
        double messagesPerSecond = futures.size() / endToEndVerificationSeconds;
        double processingMsgPerSec = verificationResult.processingWindowMs() > 0
                ? futures.size() / (verificationResult.processingWindowMs() / 1000.0)
                : 0.0;

        log.info(
                "SCENARIO_RESULT scenario=parent-order-fill messages={} hierarchyKeys={} pods={} endToEndMs={} observedProcessingWindowMs={} endToEndMsgPerSec={} processingMsgPerSec={} podDistribution={}",
                futures.size(),
                verificationResult.hierarchyKeys(),
                verificationResult.podIds().size(),
                endToEndVerificationMs,
                verificationResult.processingWindowMs(),
                String.format("%.2f", messagesPerSecond),
                String.format("%.2f", processingMsgPerSec),
                verificationResult.hierarchyCountByPod()
        );
    }

    private record VerificationResult(
            int hierarchyKeys,
            Set<String> podIds,
            Map<String, Long> hierarchyCountByPod,
            long processingWindowMs
    ) {}

    /**
     * Verifies that during stable task assignment all events belonging to the same
     * hierarchy key are processed by a single pod.
     * <p>
     * The method consumes verification records emitted by the Kafka Streams topology
     * and checks that each hierarchy key is associated with exactly one pod ID.
     * This validates the key-based partitioning guarantee provided by Kafka Streams:
     * all events for the same hierarchy should be routed to the same stream task
     * and therefore processed sequentially by a single pod.
     *
     * @param expectedHierarchyKeys hierarchy keys expected to be processed during this test run
     * @param timeoutSeconds timeout in seconds for the verification process
     * @return verification summary containing the number of observed hierarchy keys
     *         and the set of participating pod IDs
     */
    private VerificationResult verifyPartitioning(Set<String> expectedHierarchyKeys,
                                                  int timeoutSeconds) {
        Map<String, Set<String>> keyToPodIds = new HashMap<>();
        Map<String, Instant> processedAtByHierarchyKey = new HashMap<>();

        await()
                .atMost(timeoutSeconds, TimeUnit.SECONDS)
                .pollInterval(java.time.Duration.ofMillis(500))
                .untilAsserted(() -> {
                    var records = verificationConsumer.poll(java.time.Duration.ofMillis(500));

                    for (ConsumerRecord<String, VerificationRecord> record : records) {
                        String key = record.key();
                        VerificationRecord value = record.value();

                        if (key == null || value == null) {
                            continue;
                        }

                        if (!expectedHierarchyKeys.contains(key)) {
                            continue;
                        }

                        keyToPodIds
                                .computeIfAbsent(key, ignored -> new HashSet<>())
                                .add(value.podId());

                        try {
                            processedAtByHierarchyKey.putIfAbsent(
                                    key,
                                    Instant.parse(value.processedAt())
                            );
                        } catch (Exception e) {
                            log.warn("Invalid processedAt timestamp: {}", value.processedAt());
                        }
                    }

                    assertEquals(
                            expectedHierarchyKeys.size(),
                            keyToPodIds.size(),
                            "Not all expected hierarchy keys were observed yet. Expected="
                                    + expectedHierarchyKeys.size()
                                    + ", observed="
                                    + keyToPodIds.size()
                    );

                    for (Map.Entry<String, Set<String>> entry : keyToPodIds.entrySet()) {
                        assertEquals(
                                1,
                                entry.getValue().size(),
                                "Hierarchy key " + entry.getKey()
                                        + " was processed by multiple pods: "
                                        + entry.getValue()
                        );
                    }
                });

        Set<String> allPods = keyToPodIds.values()
                .stream()
                .flatMap(Set::stream)
                .collect(Collectors.toSet());

        Map<String, Long> hierarchyCountByPod = keyToPodIds.values()
                .stream()
                .map(podIds -> podIds.iterator().next())
                .collect(Collectors.groupingBy(
                        podId -> podId,
                        TreeMap::new,
                        Collectors.counting()
                ));

        long processingWindowMs = 0;

        Collection<Instant> processedAtTimestamps = processedAtByHierarchyKey.values();

        if (!processedAtTimestamps.isEmpty()) {
            Instant firstProcessedAt = processedAtTimestamps.stream()
                    .min(Instant::compareTo)
                    .orElseThrow();

            Instant lastProcessedAt = processedAtTimestamps.stream()
                    .max(Instant::compareTo)
                    .orElseThrow();

            processingWindowMs = java.time.Duration.between(firstProcessedAt, lastProcessedAt).toMillis();
        }

        return new VerificationResult(
                keyToPodIds.size(),
                allPods,
                hierarchyCountByPod,
                processingWindowMs
        );
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
            props.setProperty("kafka.verification.topic", "org.example.order.verification");
            return props;
        }
    }

    /**
     * Generates a random 8-character string for use in test data.
     * This helps ensure test isolation by providing unique identifiers.
     *
     * @param iteration The current iteration of the test
     *
     * @return A random 8-character string
     */
    private String uniquePrefix(int iteration) {
        return "run-" + iteration + "-" + UUID.randomUUID().toString().substring(0, 8);
    }

    private String uniquePrefix() {
        return uniquePrefix(0);
    }
}
