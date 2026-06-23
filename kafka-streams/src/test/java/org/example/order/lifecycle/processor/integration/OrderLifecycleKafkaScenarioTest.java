package org.example.order.lifecycle.processor.integration;

import lombok.extern.slf4j.Slf4j;
import org.apache.kafka.clients.consumer.ConsumerConfig;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.apache.kafka.clients.consumer.ConsumerRecords;
import org.apache.kafka.clients.consumer.KafkaConsumer;
import org.apache.kafka.clients.producer.KafkaProducer;
import org.apache.kafka.clients.producer.ProducerConfig;
import org.apache.kafka.clients.producer.ProducerRecord;
import org.apache.kafka.common.serialization.StringDeserializer;
import org.apache.kafka.common.serialization.StringSerializer;
import org.example.order.fix.model.ExecutionReport;
import org.example.order.fix.model.VerificationRecord;
import org.example.order.lifecycle.util.JsonUtils;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.config.YamlPropertiesFactoryBean;
import org.springframework.core.io.ClassPathResource;
import org.springframework.kafka.support.serializer.JsonDeserializer;
import org.springframework.kafka.support.serializer.JsonSerializer;

import java.io.IOException;
import java.time.Duration;
import java.time.Instant;
import java.util.*;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;
import java.util.stream.Collectors;

import static org.awaitility.Awaitility.await;
import static org.example.order.lifecycle.processor.utils.TestUtils.generateExecutionReportMessage;
import static org.junit.jupiter.api.Assertions.*;


/**
 * End-to-end Kafka integration test for the Order Lifecycle processor.
 *
 * <p>This test publishes {@link ExecutionReport} messages to Kafka and verifies
 * the resulting records produced to the verification topic.
 *
 * <p>The test is intentionally runtime-agnostic and can be used to validate
 * either the Kafka Streams implementation or the Apache Flink implementation,
 * depending on which processor is currently running.
 *
 * <p>Verified aspects:
 * <ul>
 *     <li>successful message ingestion from Kafka</li>
 *     <li>correct hierarchy key aggregation (parent/child orders)</li>
 *     <li>verification record production</li>
 *     <li>end-to-end processing behaviour</li>
 * </ul>
 *
 * <p>Detailed state transition validation is covered separately by unit tests
 * and processor-specific tests.
 */
@Slf4j
public class OrderLifecycleKafkaScenarioTest {
    private static final int PARENT_ORDER_RECORDS_PER_HIERARCHY = 5;

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
        consumerProps.put(ConsumerConfig.AUTO_OFFSET_RESET_CONFIG, "latest");
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
        positionVerificationConsumerAtEnd();
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
     * Executes the parent-order hierarchy scenario and waits until all expected
     * verification records for the current test run have been observed.
     *
     * <p>Each iteration publishes five input execution reports belonging to one
     * logical hierarchy. The processor emits one final verification record when
     * that hierarchy first reaches the fully filled state.</p>
     *
     * <p>The recorded timings represent:</p>
     *
     * <ul>
     *     <li>{@code inputSubmissionMs} — test-data generation, Kafka sends,
     *     and producer flush</li>
     *     <li>{@code drainAfterSubmissionMs} — time required for the processor,
     *     verification sink, and verification consumer to finish the scenario
     *     after all input messages have been submitted</li>
     *     <li>{@code endToEndMs} — complete scenario duration</li>
     *     <li>{@code processingWindowMs}— interval between the earliest firstProcessedAt
     *     timestamp and the latest completedAt timestamp among all completed hierarchies</li>
     * </ul>
     *
     * @throws IOException when test data cannot be loaded
     */
    @Test
    void testParentOrderFill() throws IOException {
        log.info(
                "Running testParentOrderFill {} iteration(s)",
                testIterations
        );

        long startedAtNanos = System.nanoTime();

        String inputDir =
                "jsonData/fillOrder/fullyFilled/parent_order/";

        int expectedInputMessages =
                testIterations
                        * PARENT_ORDER_RECORDS_PER_HIERARCHY;

        AtomicReference<Exception> producerFailure =
                new AtomicReference<>();

        Set<String> expectedHierarchyKeys =
                new HashSet<>(testIterations);

        for (int i = 0; i < testIterations; i++) {
            String prefix = uniquePrefix(i);

            ExecutionReport parentOrder =
                    generateExecutionReportMessage(
                            prefix,
                            inputDir + "parent_order.json"
                    );

            ExecutionReport childOrder1 =
                    generateExecutionReportMessage(
                            prefix,
                            inputDir + "child_order1.json"
                    );

            ExecutionReport childOrder2 =
                    generateExecutionReportMessage(
                            prefix,
                            inputDir + "child_order2.json"
                    );

            ExecutionReport fill1ChildOrder1 =
                    generateExecutionReportMessage(
                            prefix,
                            inputDir + "fill01_child_order1.json"
                    );

            ExecutionReport fill2ChildOrder2 =
                    generateExecutionReportMessage(
                            prefix,
                            inputDir + "fill02_child_order2.json"
                    );

            assertNotNull(
                    parentOrder.getOrderId(),
                    "Parent order ID should not be null"
            );
            assertNotNull(
                    childOrder1.getOrderId(),
                    "Child order 1 ID should not be null"
            );
            assertNotNull(
                    childOrder2.getOrderId(),
                    "Child order 2 ID should not be null"
            );
            assertNotNull(
                    fill1ChildOrder1.getOrderId(),
                    "Child order 1 fill ID should not be null"
            );
            assertNotNull(
                    fill2ChildOrder2.getOrderId(),
                    "Child order 2 fill ID should not be null"
            );

            expectedHierarchyKeys.add(parentOrder.getOrderId());

            send(
                    parentOrder.getOrderId(),
                    parentOrder,
                    producerFailure
            );

            send(
                    childOrder1.getOrderId(),
                    childOrder1,
                    producerFailure
            );

            send(
                    childOrder2.getOrderId(),
                    childOrder2,
                    producerFailure
            );

            send(
                    fill1ChildOrder1.getOrderId(),
                    fill1ChildOrder1,
                    producerFailure
            );

            send(
                    fill2ChildOrder2.getOrderId(),
                    fill2ChildOrder2,
                    producerFailure
            );
        }

        producer.flush();

        Exception sendFailure = producerFailure.get();

        if (sendFailure != null) {
            throw new AssertionError(
                    "At least one input message could not be published",
                    sendFailure
            );
        }

        long publishingCompletedAtNanos = System.nanoTime();

        int verificationTimeoutSeconds =
                testIterations >= 100_000 ? 300 : 60;

        VerificationResult verificationResult =
                verifyPartitioning(
                        expectedHierarchyKeys,
                        verificationTimeoutSeconds
                );

        long completedAtNanos = System.nanoTime();

        long inputSubmissionMs =
                TimeUnit.NANOSECONDS.toMillis(
                        publishingCompletedAtNanos - startedAtNanos
                );

        long drainAfterSubmissionMs =
                TimeUnit.NANOSECONDS.toMillis(
                        completedAtNanos - publishingCompletedAtNanos
                );

        long endToEndMs =
                TimeUnit.NANOSECONDS.toMillis(
                        completedAtNanos - startedAtNanos
                );

        double endToEndMsgPerSec =
                calculateThroughput(
                        expectedInputMessages,
                        endToEndMs
                );

        double processingInputMsgPerSec =
                calculateThroughput(
                        expectedInputMessages,
                        verificationResult.processingWindowMs()
                );

        double completedHierarchiesPerSec =
                calculateThroughput(
                        verificationResult.verificationRecords(),
                        verificationResult.processingWindowMs()
                );

        log.info(
                "SCENARIO_RESULT scenario=parent-order-fill " +
                        "messages={} verificationRecords={} " +
                        "hierarchyKeys={} processors={} " +
                        "inputSubmissionMs={} drainAfterSubmissionMs={} " +
                        "endToEndMs={} processingWindowMs={} " +
                        "endToEndMsgPerSec={} processingInputMsgPerSec={} " +
                        "completedHierarchiesPerSec={} " +
                        "processorDistribution={}",
                expectedInputMessages,
                verificationResult.verificationRecords(),
                verificationResult.hierarchyKeys(),
                verificationResult.processorIds().size(),
                inputSubmissionMs,
                drainAfterSubmissionMs,
                endToEndMs,
                verificationResult.processingWindowMs(),
                String.format(Locale.ROOT, "%.2f", endToEndMsgPerSec),
                String.format(Locale.ROOT, "%.2f", processingInputMsgPerSec),
                String.format(Locale.ROOT, "%.2f", completedHierarchiesPerSec),
                verificationResult.hierarchyCountByProcessor()
        );
    }

    private record VerificationResult(
            int hierarchyKeys,
            Set<String> processorIds,
            Map<String, Long> hierarchyCountByProcessor,
            long processingWindowMs,
            int verificationRecords
    ) {}

    private void send(
            String key,
            ExecutionReport report,
            AtomicReference<Exception> producerFailure
    ) {
        producer.send(
                new ProducerRecord<>(topic, key, report),
                (ignored, exception) -> {
                    if (exception != null) {
                        producerFailure.compareAndSet(
                                null,
                                exception
                        );
                    }
                }
        );
    }

    /**
     * Consumes verification records belonging to the current test run and verifies
     * that every expected hierarchy produced the expected number of records.
     *
     * <p>Records from previous test runs are ignored by comparing their hierarchy
     * key with {@code expectedHierarchyKeys}. The verification consumer is also
     * positioned at the end of the topic during setup, preventing repeated scans
     * of historical records.</p>
     *
     * <p>The per-hierarchy counter is capped at the expected number of records.
     * This prevents additional at-least-once duplicates from inflating throughput
     * calculations or extending the observed processing window.</p>
     *
     * @param expectedHierarchyKeys hierarchy keys generated by the current test
     * @param timeoutSeconds maximum verification time
     * @return summary of observed records and processor ownership
     */
    private VerificationResult verifyPartitioning(
            Set<String> expectedHierarchyKeys,
            int timeoutSeconds
    ) {
        Map<String, String> processorByHierarchy = new HashMap<>();
        ProcessingWindow processingWindow = new ProcessingWindow();
        AtomicLong acceptedRecords = new AtomicLong();
        AtomicLong lastProgressLogNanos = new AtomicLong(System.nanoTime());

        await()
                .atMost(Duration.ofSeconds(timeoutSeconds))
                .pollInterval(Duration.ofMillis(100))
                .until(() -> {
                    ConsumerRecords<String, VerificationRecord> records =
                            verificationConsumer.poll(Duration.ofMillis(250));

                    for (ConsumerRecord<String, VerificationRecord> record : records) {
                        String hierarchyKey = record.key();
                        VerificationRecord verification = record.value();

                        if (hierarchyKey == null || verification == null) {
                            continue;
                        }

                        if (!expectedHierarchyKeys.contains(hierarchyKey)) {
                            continue;
                        }

                        String processorId = verification.podId();

                        if (processorId == null || processorId.isBlank()) {
                            fail(
                                    "Verification record for hierarchy "
                                            + hierarchyKey
                                            + " has no processor id"
                            );
                        }

                        String previousProcessor =
                                processorByHierarchy.putIfAbsent(
                                        hierarchyKey,
                                        processorId
                                );

                        if (previousProcessor != null
                                && !previousProcessor.equals(processorId)) {
                            fail(
                                    "Hierarchy "
                                            + hierarchyKey
                                            + " was processed by multiple processors: "
                                            + previousProcessor
                                            + ", "
                                            + processorId
                            );
                        }

                        // Accept only the first final verification record for each hierarchy.
                        // Later records from the same processor are treated as at-least-once
                        // duplicates and do not affect counts or timing.
                        if (previousProcessor == null) {
                            acceptedRecords.incrementAndGet();

                            processingWindow.accept(
                                    verification.firstProcessedAt(),
                                    verification.completedAt()
                            );
                        }
                    }

                    long now = System.nanoTime();

                    if (now - lastProgressLogNanos.get()
                            >= TimeUnit.SECONDS.toNanos(10)
                            && lastProgressLogNanos.compareAndSet(
                            lastProgressLogNanos.get(),
                            now
                    )) {

                        log.info(
                                "VERIFICATION_PROGRESS completed={} expected={} processors={}",
                                processorByHierarchy.size(),
                                expectedHierarchyKeys.size(),
                                processorByHierarchy.values()
                                        .stream()
                                        .distinct()
                                        .count()
                        );
                    }

                    return processorByHierarchy.size()
                            == expectedHierarchyKeys.size();
                });

        assertEquals(
                expectedHierarchyKeys,
                processorByHierarchy.keySet(),
                "Not all expected hierarchies were processed"
        );

        Set<String> processorIds =
                new HashSet<>(processorByHierarchy.values());

        Map<String, Long> hierarchyCountByProcessor =
                processorByHierarchy.values()
                        .stream()
                        .collect(Collectors.groupingBy(
                                processorId -> processorId,
                                TreeMap::new,
                                Collectors.counting()
                        ));

        return new VerificationResult(
                processorByHierarchy.size(),
                processorIds,
                hierarchyCountByProcessor,
                processingWindow.durationMs(),
                Math.toIntExact(acceptedRecords.get())
        );
    }

    private double calculateThroughput(
            int messages,
            long durationMs
    ) {
        if (durationMs <= 0) {
            return 0.0;
        }

        return messages / (durationMs / 1000.0);
    }

    private void positionVerificationConsumerAtEnd() {
        long deadlineNanos =
                System.nanoTime() + TimeUnit.SECONDS.toNanos(10);

        while (verificationConsumer.assignment().isEmpty()
                && System.nanoTime() < deadlineNanos) {

            verificationConsumer.poll(
                    java.time.Duration.ofMillis(100)
            );
        }

        Set<org.apache.kafka.common.TopicPartition> assignments =
                verificationConsumer.assignment();

        if (assignments.isEmpty()) {
            throw new IllegalStateException(
                    "Verification consumer did not receive partition assignment"
            );
        }

        verificationConsumer.seekToEnd(assignments);

        // Force KafkaConsumer to resolve the actual end offsets immediately.
        for (org.apache.kafka.common.TopicPartition partition : assignments) {
            verificationConsumer.position(partition);
        }

        log.info(
                "Verification consumer positioned at the end of partitions: {}",
                assignments
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

    private static final class ProcessingWindow {
        private Instant earliestStart;
        private Instant latestCompletion;

        void accept(
                Instant firstProcessedAt,
                Instant completedAt
        ) {
            if (firstProcessedAt == null || completedAt == null) {
                fail("Verification timestamps must not be null");
            }

            if (earliestStart == null
                    || firstProcessedAt.isBefore(earliestStart)) {
                earliestStart = firstProcessedAt;
            }

            if (latestCompletion == null
                    || completedAt.isAfter(latestCompletion)) {
                latestCompletion = completedAt;
            }
        }

        long durationMs() {
            if (earliestStart == null || latestCompletion == null) {
                return 0;
            }

            return Duration
                    .between(
                            earliestStart,
                            latestCompletion
                    )
                    .toMillis();
        }
    }
}
