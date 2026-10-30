package org.example.order.lifecycle.db;

import org.example.order.fix.model.ExecutionReport;
import org.example.order.fix.model.VerificationRecord;
import org.example.order.lifecycle.db.service.*;
import org.example.order.lifecycle.model.OrderNode;
import org.example.order.lifecycle.model.OrderState;
import org.example.order.lifecycle.service.FillOrderService;
import org.example.order.lifecycle.service.OrderStateUpdater;
import org.example.order.lifecycle.util.JsonUtils;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.postgresql.PostgreSQLContainer;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicLong;

import static org.junit.jupiter.api.Assertions.*;
import static java.util.Objects.requireNonNull;
import static org.example.order.lifecycle.testing.LifecycleScenarios.*;

@Testcontainers
class PostgresLifecycleTest {
    @Container
    static final PostgreSQLContainer POSTGRES = new PostgreSQLContainer("postgres:18.6");
    static JdbcTemplate jdbc;
    static DataSourceTransactionManager manager;
    RelationalLifecycleProcessor processor;
    final AtomicLong offsets = new AtomicLong();
    static final LocalDateTime TIME = LocalDateTime.of(2026, 1, 1, 10, 0);

    @BeforeAll
    static void migrate() {
        var source = new DriverManagerDataSource();
        source.setUrl(POSTGRES.getJdbcUrl());
        source.setUsername(POSTGRES.getUsername());
        source.setPassword(POSTGRES.getPassword());
        jdbc = new JdbcTemplate(source);
        manager = new DataSourceTransactionManager(source);
        Flyway.configure().dataSource(source).load().migrate();
    }

    @BeforeEach
    void reset() {
        clear();
        processor = new RelationalLifecycleProcessor(jdbc, manager, "processor-a", "verification");
    }

    void clear() {
        jdbc.execute("TRUNCATE outbox, processed_input, fills, orders, hierarchies CASCADE");
    }

    @ParameterizedTest
    @ValueSource(strings = {"null", "malformed-json"})
    void unparseableReportDoesNotCreateDatabaseState(String payload) {
        assertThrows(IllegalArgumentException.class, () -> processor.process(position(), payload));
        assertEquals(0, count("processed_input"));
        assertEquals(0, count("hierarchies"));
        assertEquals(0, count("outbox"));
    }

    @Test
    void agreesWithJavaForEveryOrderFillPermutation() throws Exception {
        for (var scenario : orderFillScenarios("root")) {
            clear();
            compare(scenario.events());
            assertEquals(scenario.filledQuantity(), rootQuantity());
            assertEquals(scenario.status().name(), jdbc.queryForObject(
                    "SELECT status FROM order_lifecycle WHERE order_id='root'", String.class));
        }
    }

    @Test
    void agreesWithJavaForParentAndChildFillsAndLateDefinitions() throws Exception {
        for (var events : permutations(List.of(order("root", null, 100),
                order("child", "root", 100), fill("child", "root", 100, TIME.plusNanos(1))))) {
            clear();
            compare(events);
        }
    }

    @Test
    void unsupportedTypesDoNotCreateDatabaseState() {
        for (char type : new char[]{'C', 'X'}) {
            assertThrows(IllegalArgumentException.class,
                    () -> processor.process(position(), json(report("root", null, type, "unsupported", TIME))));
            assertEquals(0, count("processed_input"));
            assertEquals(0, count("hierarchies"));
            assertEquals(0, count("orders"));
            assertEquals(0, count("outbox"));
        }
    }

    @Test
    void deliveryReplayIsIdempotentAndFillConflictRollsBack() throws Exception {
        ExecutionReport order = order("root", null, 100);
        process(order);
        ExecutionReport fill = fill("root", null, 100, TIME);
        String payload = json(fill);
        var position = position();
        processor.process(position, payload);
        processor.process(position, payload); // DB commit happened, Kafka commit was lost.
        process(fill); // Same business fill at a new Kafka offset.
        assertEquals(1, count("fills"));
        assertEquals(1, count("outbox"));
        assertEquals(3, count("processed_input"));
        String original = jdbc.queryForObject("SELECT payload FROM outbox", String.class);
        var conflict = fill("root", null, 99, TIME);
        assertThrows(IllegalArgumentException.class, () -> process(conflict));
        assertEquals(3, count("processed_input"));
        assertEquals(100L, rootQuantity());
        assertEquals(original, jdbc.queryForObject("SELECT payload FROM outbox", String.class));
        // Already committed positions are skipped without comparing payload bytes.
        // Replacing content at one Kafka position is outside the source contract.
        processor.process(position, json(conflict));
        assertEquals(3, count("processed_input"));
        assertEquals(100L, rootQuantity());
    }

    @Test
    void rollsBackStateAndInputIdentityWhenOutboxInsertFails() throws Exception {
        process(order("root", null, 100));
        jdbc.execute("ALTER TABLE outbox ADD CONSTRAINT injected_failure CHECK (false)");
        ExecutionReport fill = fill("root", null, 100, TIME);
        var position = position();
        try {
            assertThrows(RuntimeException.class, () -> processor.process(position, json(fill)));
            assertEquals(0, count("fills"));
            assertEquals(1, count("processed_input"));
            assertNull(jdbc.queryForObject("SELECT completed_at FROM hierarchies", java.sql.Timestamp.class));
        } finally {
            jdbc.execute("ALTER TABLE outbox DROP CONSTRAINT injected_failure");
        }
        processor.process(position, json(fill));
        assertEquals(100L, rootQuantity());
        assertEquals(1, count("outbox"));
    }

    @Test
    void concurrentConsumersDoNotLoseFillsOrCreateMultipleCompletions() throws Exception {
        process(order("root", null, 12));
        var pool = Executors.newFixedThreadPool(6);
        try {
            List<Callable<Void>> tasks = new ArrayList<>();
            for (int i = 0; i < 12; i++) {
                int id = i;
                tasks.add(() -> {
                    // Independent application service/connection, shared PostgreSQL state.
                    var instance = new RelationalLifecycleProcessor(jdbc, manager, "processor-" + id, "verification");
                    instance.process(position(), json(fill("child-" + id, "root", 1, TIME)));
                    return null;
                });
            }
            for (Future<Void> future : pool.invokeAll(tasks, 30, TimeUnit.SECONDS)) future.get();
        } finally {
            pool.shutdownNow();
        }
        assertEquals(12L, rootQuantity());
        assertEquals(12, count("fills"));
        assertEquals(1, count("outbox"));
    }

    @Test
    void concurrentFirstEventsCreateHierarchyAndPlaceholdersWithoutLosingFacts() throws Exception {
        var pool = Executors.newFixedThreadPool(4);
        try {
            List<Callable<Void>> tasks = new ArrayList<>();
            for (int i = 0; i < 8; i++) {
                int id = i;
                tasks.add(() -> {
                    processor.process(position(), json(fill("child-" + id, "root", 1, TIME)));
                    return null;
                });
            }
            for (Future<Void> future : pool.invokeAll(tasks, 30, TimeUnit.SECONDS)) future.get();
        } finally {
            pool.shutdownNow();
        }
        assertEquals(8, count("fills"));
        assertEquals(9, count("orders"));
        assertEquals(0, count("outbox"));
        process(order("root", null, 8));
        assertEquals(8L, rootQuantity());
        assertEquals(1, count("outbox"));
        // Completion must not prevent subsequent facts being stored.
        process(fill("root", null, 1, TIME));
        assertEquals(9L, rootQuantity());
        assertEquals(9, count("fills"));
        assertEquals(1, count("outbox"));
    }

    @Test
    void sourceIncarnationScopesInputReplayIdentity() throws Exception {
        var first = new InputPosition("source-v1", "executions", 0, 0);
        processor.process(first, json(order("root", null, 100)));
        processor.process(first, json(order("root", null, 100)));
        processor.process(new InputPosition("source-v2", "executions", 0, 0),
                json(order("another-root", null, 50)));
        assertEquals(2, count("processed_input"));
        assertEquals(2, count("orders"));
    }

    @Test
    void failedOrUncertainPublicationRetriesTheSamePayload() throws Exception {
        process(order("root", null, 100));
        process(fill("root", null, 100, TIME));
        List<String> delivered = new ArrayList<>();
        var failing = new OutboxPublisher(jdbc, manager, (topic, key, payload) -> {
            delivered.add(payload); // Kafka accepted it, but acknowledgement was lost.
            throw new IllegalStateException("Simulated uncertain send");
        }, false);
        assertThrows(IllegalStateException.class, failing::publishOne);
        assertNull(jdbc.queryForObject("SELECT published_at FROM outbox", java.sql.Timestamp.class));
        var succeeding = new OutboxPublisher(jdbc, manager,
                (topic, key, payload) -> delivered.add(payload), false);
        assertTrue(succeeding.publishOne());
        assertFalse(succeeding.publishOne());
        assertEquals(2, delivered.size());
        assertEquals(delivered.get(0), delivered.get(1));
        VerificationRecord record = JsonUtils.getObjectMapper().readValue(delivered.get(0), VerificationRecord.class);
        assertEquals("processor-a", record.podId());
        assertEquals("root", record.hierarchyKey());
    }

    @Test
    void publishersSkipLockedRows() throws Exception {
        for (String root : List.of("a", "b")) {
            process(order(root, null, 1));
            process(fill(root, null, 1, TIME));
        }
        var executor = Executors.newSingleThreadExecutor();
        try {
            new TransactionTemplate(manager).executeWithoutResult(ignored -> {
                String lockedKey = jdbc.queryForObject("""
                        SELECT hierarchy_key FROM outbox ORDER BY created_at, event_id LIMIT 1 FOR UPDATE
                        """, String.class);
                Future<Boolean> future = executor.submit(() -> new OutboxPublisher(jdbc, manager,
                        (topic, key, payload) -> assertNotEquals(lockedKey, key), false).publishOne());
                try { assertTrue(future.get(5, TimeUnit.SECONDS)); }
                catch (Exception e) { throw new IllegalStateException(e); }
            });
        } finally {
            executor.shutdownNow();
        }
        assertEquals(1, jdbc.queryForObject("SELECT COUNT(*) FROM outbox WHERE published_at IS NOT NULL", Integer.class));
    }

    void compare(List<ExecutionReport> events) throws Exception {
        OrderState reference = new OrderState();
        FillOrderService service = new FillOrderService(new OrderStateUpdater());
        for (ExecutionReport event : events) {
            process(event);
            service.processExecutionReport("root", event, reference);
            assertNode("root", reference);
            for (var entry : reference.getChildOrders().entrySet()) assertNode(entry.getKey(), entry.getValue());
            assertEquals(reference.getCompletedAt() == null ? 0 : 1, count("outbox"));
        }
    }

    void assertNode(String key, OrderNode reference) {
        var row = jdbc.queryForMap("SELECT filled_quantity, status FROM order_lifecycle WHERE order_id=?", key);
        assertEquals(reference.getFilledQuantity(), ((Number) row.get("filled_quantity")).longValue());
        assertEquals(reference.getStatus().name(), row.get("status"));
    }

    long rootQuantity() {
        return requireNonNull(jdbc.queryForObject(
                "SELECT filled_quantity FROM order_lifecycle WHERE order_id='root'", Long.class));
    }

    int count(String table) {
        return requireNonNull(jdbc.queryForObject("SELECT COUNT(*) FROM " + table, Integer.class));
    }
    InputPosition position() { return new InputPosition("test-source", "executions", 0, offsets.getAndIncrement()); }
    void process(ExecutionReport report) throws Exception { processor.process(position(), json(report)); }
    static String json(ExecutionReport report) throws Exception { return JsonUtils.getObjectMapper().writeValueAsString(report); }

    static ExecutionReport order(String id, String parent, long quantity) {
        var report = report(id, parent, 'O', id + "-definition", TIME.minusMinutes(1));
        report.setOrderQuantity(quantity);
        return report;
    }
    static ExecutionReport fill(String id, String parent, long quantity, LocalDateTime time) {
        var report = report(id, parent, 'F', "fill", time);
        report.setLastQty(quantity);
        report.setLastPx(10);
        return report;
    }
    static ExecutionReport report(String id, String parent, char type, String execId, LocalDateTime time) {
        var report = new ExecutionReport();
        report.setOrderId(id); report.setParentId(parent); report.setType(type);
        report.setExecId(execId); report.setTxnTime(time);
        return report;
    }
}
