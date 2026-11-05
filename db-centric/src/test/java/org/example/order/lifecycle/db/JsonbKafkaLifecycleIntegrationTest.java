package org.example.order.lifecycle.db;

import org.apache.kafka.clients.admin.AdminClient;
import org.apache.kafka.clients.admin.NewTopic;
import org.apache.kafka.clients.consumer.ConsumerConfig;
import org.apache.kafka.clients.consumer.KafkaConsumer;
import org.apache.kafka.common.TopicPartition;
import org.apache.kafka.common.serialization.StringDeserializer;
import org.example.order.fix.model.VerificationRecord;
import org.example.order.lifecycle.db.service.CompletionPublisher;
import org.example.order.lifecycle.model.OrderState;
import org.example.order.lifecycle.util.JsonUtils;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.kafka.config.KafkaListenerEndpointRegistry;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.kafka.listener.MessageListenerContainer;
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

import static org.awaitility.Awaitility.await;
import static org.junit.jupiter.api.Assertions.*;

@Testcontainers
@SpringBootTest(properties = {"app.state-storage=jsonb", "app.publication-enabled=false",
        "app.processor-id=jsonb-integration", "spring.kafka.listener.poll-timeout=100"})
class JsonbKafkaLifecycleIntegrationTest {
    private static final String INPUT = "jsonb-test-input";
    private static final String OUTPUT = "jsonb-test-output";
    private static final String GROUP = "jsonb-test";
    @Container static final PostgreSQLContainer POSTGRES = new PostgreSQLContainer("postgres:18.6");
    @Container static final KafkaContainer KAFKA = new KafkaContainer("apache/kafka:4.1.2");

    @DynamicPropertySource
    static void properties(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", POSTGRES::getJdbcUrl);
        registry.add("spring.datasource.username", POSTGRES::getUsername);
        registry.add("spring.datasource.password", POSTGRES::getPassword);
        registry.add("spring.kafka.bootstrap-servers", KAFKA::getBootstrapServers);
        registry.add("spring.kafka.consumer.group-id", () -> GROUP);
        registry.add("app.executions-topic", () -> INPUT);
        registry.add("app.verification-topic", () -> OUTPUT);
    }

    @TestConfiguration
    static class Topics {
        @Bean NewTopic input() { return new NewTopic(INPUT, 1, (short) 1); }
        @Bean NewTopic output() { return new NewTopic(OUTPUT, 1, (short) 1); }
    }

    @Autowired KafkaTemplate<String, String> producer;
    @Autowired JdbcTemplate jdbc;
    @Autowired CompletionPublisher publisher;
    @Autowired KafkaListenerEndpointRegistry listeners;

    @Test
    void processesLateDefinitionsPublishesExactDocumentTimestampsAndStopsOnConflictingFill() throws Exception {
        var fill = PostgresLifecycleTest.fill("child", "root", 50, PostgresLifecycleTest.TIME.plusNanos(123));
        for (var report : List.of(fill, PostgresLifecycleTest.order("child", "root", 50),
                PostgresLifecycleTest.order("root", null, 50), fill)) {
            producer.send(INPUT, 0, report.getOrderId(), PostgresLifecycleTest.json(report)).get(15, TimeUnit.SECONDS);
        }
        var partition = new TopicPartition(INPUT, 0);
        try (var admin = AdminClient.create(Map.of("bootstrap.servers", KAFKA.getBootstrapServers()))) {
            await().atMost(Duration.ofSeconds(30)).untilAsserted(() -> assertEquals(4L,
                    admin.listConsumerGroupOffsets(GROUP).partitionsToOffsetAndMetadata()
                            .get(5, TimeUnit.SECONDS).get(partition).offset()));
            String document = jdbc.queryForObject("SELECT state::text FROM lifecycle_documents", String.class);
            OrderState state = JsonUtils.getObjectMapper().readValue(document, OrderState.class);
            assertEquals(50, state.getFilledQuantity());
            assertEquals(1, state.getChildOrders().get("child").getFills().size());
            assertEquals(0, jdbc.queryForObject("SELECT count(*) FROM hierarchies", Integer.class));
            assertEquals(0, jdbc.queryForObject("SELECT count(*) FROM orders", Integer.class));
            assertEquals(0, jdbc.queryForObject("SELECT count(*) FROM fills", Integer.class));
            assertTrue(publisher.publishOne());
            assertFalse(publisher.publishOne());
            try (var consumer = new KafkaConsumer<>(Map.of(
                    ConsumerConfig.BOOTSTRAP_SERVERS_CONFIG, KAFKA.getBootstrapServers(),
                    ConsumerConfig.GROUP_ID_CONFIG, "jsonb-verifier-" + UUID.randomUUID(),
                    ConsumerConfig.AUTO_OFFSET_RESET_CONFIG, "earliest",
                    ConsumerConfig.ENABLE_AUTO_COMMIT_CONFIG, false),
                    new StringDeserializer(), new StringDeserializer())) {
                consumer.subscribe(List.of(OUTPUT));
                await().atMost(Duration.ofSeconds(20)).untilAsserted(() -> {
                    var records = consumer.poll(Duration.ofMillis(200));
                    assertEquals(1, records.count());
                    var output = records.iterator().next();
                    VerificationRecord record = JsonUtils.getObjectMapper().readValue(output.value(), VerificationRecord.class);
                    assertEquals("root", output.key());
                    assertEquals("jsonb-integration", record.podId());
                    assertEquals(state.getFirstProcessedAt(), record.firstProcessedAt());
                    assertEquals(state.getCompletedAt(), record.completedAt());
                });
            }
            var conflict = PostgresLifecycleTest.fill("child", "root", 51, fill.getTxnTime());
            producer.send(INPUT, 0, "child", PostgresLifecycleTest.json(conflict)).get(15, TimeUnit.SECONDS);
            await().atMost(Duration.ofSeconds(20)).until(() -> listeners.getListenerContainers().stream()
                    .noneMatch(MessageListenerContainer::isRunning));
            assertEquals(4L, admin.listConsumerGroupOffsets(GROUP).partitionsToOffsetAndMetadata()
                    .get(5, TimeUnit.SECONDS).get(partition).offset());
            assertEquals(document, jdbc.queryForObject("SELECT state::text FROM lifecycle_documents", String.class));
            assertNotNull(jdbc.queryForObject("SELECT published_at FROM lifecycle_documents", java.sql.Timestamp.class));
        }
    }
}
