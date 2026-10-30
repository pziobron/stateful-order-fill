package org.example.order.lifecycle.db;

import org.apache.kafka.clients.admin.AdminClient;
import org.apache.kafka.clients.admin.NewTopic;
import org.apache.kafka.clients.consumer.ConsumerConfig;
import org.apache.kafka.clients.consumer.KafkaConsumer;
import org.apache.kafka.common.serialization.StringDeserializer;
import org.example.order.fix.model.VerificationRecord;
import org.example.order.lifecycle.db.kafka.ExecutionReportListener;
import org.example.order.lifecycle.db.service.InputPosition;
import org.example.order.lifecycle.db.service.RelationalLifecycleProcessor;
import org.example.order.lifecycle.db.service.OutboxPublisher;
import org.example.order.lifecycle.util.JsonUtils;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.kafka.config.KafkaListenerEndpointRegistry;
import org.springframework.kafka.config.ConcurrentKafkaListenerContainerFactory;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.kafka.listener.MessageListenerContainer;
import org.springframework.kafka.listener.MessageListener;
import org.springframework.kafka.listener.ContainerProperties;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.kafka.KafkaContainer;
import org.testcontainers.postgresql.PostgreSQLContainer;

import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.*;
import static org.awaitility.Awaitility.await;

@Testcontainers
@SpringBootTest(properties = {"app.outbox-enabled=false", "spring.kafka.listener.poll-timeout=100"})
class KafkaLifecycleIntegrationTest {
    private static final String INPUT = "db-centric-test-input";
    private static final String OUTPUT = "db-centric-test-output";
    private static final String GROUP = "db-centric-test";
    @Container
    static final PostgreSQLContainer POSTGRES = new PostgreSQLContainer("postgres:18.6");
    @Container
    static final KafkaContainer KAFKA = new KafkaContainer("apache/kafka:4.1.2");

    @DynamicPropertySource
    static void properties(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", POSTGRES::getJdbcUrl);
        registry.add("spring.datasource.username", POSTGRES::getUsername);
        registry.add("spring.datasource.password", POSTGRES::getPassword);
        registry.add("spring.kafka.bootstrap-servers", KAFKA::getBootstrapServers);
        registry.add("spring.kafka.consumer.group-id", () -> GROUP);
        registry.add("app.executions-topic", () -> INPUT);
        registry.add("app.verification-topic", () -> OUTPUT);
        registry.add("app.source-id", () -> "integration-source");
    }

    @TestConfiguration
    static class Topics {
        @Bean NewTopic input() { return new NewTopic(INPUT, 2, (short) 1); }
        @Bean NewTopic output() { return new NewTopic(OUTPUT, 2, (short) 1); }
    }

    @Autowired KafkaTemplate<String, String> producer;
    @Autowired JdbcTemplate jdbc;
    @Autowired OutboxPublisher publisher;
    @Autowired RelationalLifecycleProcessor processor;
    @Autowired KafkaListenerEndpointRegistry listeners;
    @Autowired ConcurrentKafkaListenerContainerFactory<String, String> containerFactory;
    @Autowired ExecutionReportListener reportListener;

    @Test
    void commitsAfterDatabaseSuccessAndLeavesFailedRecordUncommitted() throws Exception {
        // These limits are initialized once per pooled connection, not per input.
        assertEquals("10s", jdbc.queryForObject("SHOW lock_timeout", String.class));
        assertEquals("15s", jdbc.queryForObject("SHOW statement_timeout", String.class));
        assertEquals("UTC", jdbc.queryForObject("SHOW TimeZone", String.class));
        var time = PostgresLifecycleTest.TIME;
        var order = PostgresLifecycleTest.order("root", null, 100);
        var fill = PostgresLifecycleTest.fill("root", null, 100, time);
        String orderJson = PostgresLifecycleTest.json(order);
        producer.send(INPUT, 0, "root", orderJson).get(15, TimeUnit.SECONDS);
        producer.send(INPUT, 0, "root", PostgresLifecycleTest.json(fill)).get(15, TimeUnit.SECONDS);
        await().atMost(Duration.ofSeconds(30)).untilAsserted(() ->
                assertEquals(1, jdbc.queryForObject("SELECT COUNT(*) FROM outbox", Integer.class)));
        try (var admin = AdminClient.create(Map.of("bootstrap.servers", KAFKA.getBootstrapServers()))) {
            var partition = new org.apache.kafka.common.TopicPartition(INPUT, 0);
            await().atMost(Duration.ofSeconds(15)).untilAsserted(() -> {
                var offsets = admin.listConsumerGroupOffsets(GROUP).partitionsToOffsetAndMetadata()
                        .get(5, TimeUnit.SECONDS);
                assertNotNull(offsets.get(partition));
                assertEquals(2L, offsets.get(partition).offset());
            });
            // Re-delivery after the DB commit must not create another result.
            processor.process(new InputPosition("integration-source", INPUT, 0, 0), orderJson);
            assertTrue(publisher.publishOne());
            assertFalse(publisher.publishOne());
            try (var consumer = new KafkaConsumer<>(Map.of(
                    ConsumerConfig.BOOTSTRAP_SERVERS_CONFIG, KAFKA.getBootstrapServers(),
                    ConsumerConfig.GROUP_ID_CONFIG, "verification-" + UUID.randomUUID(),
                    ConsumerConfig.AUTO_OFFSET_RESET_CONFIG, "earliest",
                    ConsumerConfig.ENABLE_AUTO_COMMIT_CONFIG, false),
                    new StringDeserializer(), new StringDeserializer())) {
                consumer.subscribe(List.of(OUTPUT));
                await().atMost(Duration.ofSeconds(20)).untilAsserted(() -> {
                    var records = consumer.poll(Duration.ofMillis(200));
                    assertEquals(1, records.count());
                    var output = records.iterator().next();
                    assertEquals("root", output.key());
                    var record = JsonUtils.getObjectMapper().readValue(output.value(), VerificationRecord.class);
                    assertEquals("root", record.hierarchyKey());
                    assertNotNull(record.firstProcessedAt());
                    assertNotNull(record.completedAt());
                });
            }
            producer.send(INPUT, 0, "root", "malformed-json").get(15, TimeUnit.SECONDS);
            await().atMost(Duration.ofSeconds(20)).until(() -> listeners.getListenerContainers().stream()
                    .noneMatch(MessageListenerContainer::isRunning));
            assertEquals(2L, admin.listConsumerGroupOffsets(GROUP).partitionsToOffsetAndMetadata()
                    .get(5, TimeUnit.SECONDS).get(partition).offset());
            assertEquals(2, jdbc.queryForObject("SELECT COUNT(*) FROM processed_input", Integer.class));
        }
    }

    @Test
    void commitsEachFactBeforePollOffsetsAndReplaysUnacknowledgedDatabaseCommit() throws Exception {
        String topic = "db-centric-batch-input-" + UUID.randomUUID();
        String group = "db-centric-batch-replay-" + UUID.randomUUID();
        String root = "batch-root";
        var partition = new org.apache.kafka.common.TopicPartition(topic, 0);
        var reachedThirdCommit = new CountDownLatch(1);
        var allowFailure = new CountDownLatch(1);
        var failOnce = new AtomicBoolean(true);
        Map<Long, AtomicInteger> deliveries = new ConcurrentHashMap<>();
        var container = containerFactory.createContainer(topic);
        container.setBeanName("batch-replay-test");
        container.getContainerProperties().setGroupId(group);
        assertEquals(ContainerProperties.AckMode.BATCH, container.getContainerProperties().getAckMode());
        assertTrue(container.getContainerProperties().isSyncCommits());
        container.setupMessageListener((MessageListener<String, String>) record -> {
            deliveries.computeIfAbsent(record.offset(), ignored -> new AtomicInteger()).incrementAndGet();
            reportListener.receive(record);
            if (record.offset() == 2 && failOnce.compareAndSet(true, false)) {
                // DB commit succeeded, but this record has not returned to the container for ACK.
                reachedThirdCommit.countDown();
                try {
                    if (!allowFailure.await(20, TimeUnit.SECONDS)) {
                        throw new IllegalStateException("Test did not release the simulated failure");
                    }
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    throw new IllegalStateException(e);
                }
                throw new IllegalStateException("Simulated failure after DB commit before Kafka ACK");
            }
        });
        try (var admin = AdminClient.create(Map.of("bootstrap.servers", KAFKA.getBootstrapServers()))) {
            admin.createTopics(List.of(new NewTopic(topic, 1, (short) 1))).all().get(15, TimeUnit.SECONDS);
            var extraFill = PostgresLifecycleTest.fill(root, null, 25, PostgresLifecycleTest.TIME.plusSeconds(1));
            extraFill.setExecId("extra-fill");
            // Preload one partition before starting the consumer so all three records fit one poll.
            for (var report : List.of(PostgresLifecycleTest.order(root, null, 100),
                    PostgresLifecycleTest.fill(root, null, 100, PostgresLifecycleTest.TIME), extraFill)) {
                producer.send(topic, 0, root, PostgresLifecycleTest.json(report)).get(15, TimeUnit.SECONDS);
            }
            container.start();
            assertTrue(reachedThirdCommit.await(30, TimeUnit.SECONDS), "Third DB commit was not reached");
            // Read using independent JDBC connections while the third listener invocation is blocked.
            assertEquals(3, jdbc.queryForObject("SELECT count(*) FROM processed_input WHERE topic=?", Integer.class, topic));
            assertEquals(2, jdbc.queryForObject("SELECT count(*) FROM fills WHERE hierarchy_key=?", Integer.class, root));
            assertEquals(125L, jdbc.queryForObject("SELECT filled_quantity FROM order_lifecycle WHERE hierarchy_key=? AND order_id=?", Long.class, root, root));
            String completion = jdbc.queryForObject("SELECT payload FROM outbox WHERE hierarchy_key=?", String.class, root);
            var lastProcessed = jdbc.queryForObject("SELECT last_processed_at FROM hierarchies WHERE hierarchy_key=?", java.sql.Timestamp.class, root);
            var beforeFailure = admin.listConsumerGroupOffsets(group).partitionsToOffsetAndMetadata().get(5, TimeUnit.SECONDS);
            assertTrue(beforeFailure.get(partition) == null || beforeFailure.get(partition).offset() == 0,
                    "Offsets must not advance per record while the poll is still being processed");

            allowFailure.countDown();
            await().atMost(Duration.ofSeconds(20)).until(() -> !container.isRunning());
            // Spring may commit the successfully acknowledged prefix before stopping on the error.
            await().atMost(Duration.ofSeconds(15)).untilAsserted(() -> {
                var offsets = admin.listConsumerGroupOffsets(group).partitionsToOffsetAndMetadata().get(5, TimeUnit.SECONDS);
                assertNotNull(offsets.get(partition));
                assertEquals(2L, offsets.get(partition).offset(), "The failed invocation must remain unacknowledged");
                assertTrue(admin.describeConsumerGroups(List.of(group)).all().get(5, TimeUnit.SECONDS)
                        .get(group).members().isEmpty(), "Consumer must finish stopping before restart");
            });
            container.start();
            await().atMost(Duration.ofSeconds(30)).untilAsserted(() -> {
                var offsets = admin.listConsumerGroupOffsets(group).partitionsToOffsetAndMetadata().get(5, TimeUnit.SECONDS);
                assertEquals(3L, offsets.get(partition).offset());
                assertEquals(2, deliveries.get(2L).get(), "DB-committed, unacknowledged input must be replayed");
            });
            assertEquals(1, deliveries.get(0L).get());
            assertEquals(1, deliveries.get(1L).get());
            assertEquals(3, jdbc.queryForObject("SELECT count(*) FROM processed_input WHERE topic=?", Integer.class, topic));
            assertEquals(2, jdbc.queryForObject("SELECT count(*) FROM fills WHERE hierarchy_key=?", Integer.class, root));
            assertEquals(125L, jdbc.queryForObject("SELECT filled_quantity FROM order_lifecycle WHERE hierarchy_key=? AND order_id=?", Long.class, root, root));
            assertEquals(1, jdbc.queryForObject("SELECT count(*) FROM outbox WHERE hierarchy_key=?", Integer.class, root));
            assertEquals(completion, jdbc.queryForObject("SELECT payload FROM outbox WHERE hierarchy_key=?", String.class, root));
            assertEquals(lastProcessed, jdbc.queryForObject("SELECT last_processed_at FROM hierarchies WHERE hierarchy_key=?", java.sql.Timestamp.class, root),
                    "Replay must skip the already committed fact rather than mutate the hierarchy again");
        } finally {
            allowFailure.countDown();
            container.stop();
            jdbc.update("DELETE FROM outbox WHERE hierarchy_key=?", root);
            jdbc.update("DELETE FROM fills WHERE hierarchy_key=?", root);
            jdbc.update("DELETE FROM orders WHERE hierarchy_key=?", root);
            jdbc.update("DELETE FROM hierarchies WHERE hierarchy_key=?", root);
            jdbc.update("DELETE FROM processed_input WHERE topic=?", topic);
        }
    }

}
