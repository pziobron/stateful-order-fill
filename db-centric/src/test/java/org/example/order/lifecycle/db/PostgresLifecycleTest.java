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
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import org.junit.jupiter.params.provider.MethodSource;
import org.junit.jupiter.params.provider.Arguments;
import java.util.stream.Stream;
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

import static org.junit.jupiter.api.Assertions.*;
import static java.util.Objects.requireNonNull;
import static org.example.order.lifecycle.testing.LifecycleScenarios.*;

@Testcontainers
class PostgresLifecycleTest {
    @Container
    static final PostgreSQLContainer POSTGRES = new PostgreSQLContainer("postgres:18.6");
    static JdbcTemplate jdbc;
    static DataSourceTransactionManager manager;
    enum Storage { RELATIONAL, JSONB }
    Storage storage;
    LifecycleProcessor processor;
    CompletionStore completions;
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

    }

    void clear() {
        jdbc.execute("TRUNCATE lifecycle_documents, fills, orders, hierarchies CASCADE");
    }

    @ParameterizedTest
    @MethodSource("invalidPayloads")
    void unparseableReportDoesNotCreateDatabaseState(Storage storage, String payload) {
        select(storage);
        assertThrows(IllegalArgumentException.class, () -> processor.process(payload));
        assertEquals(0, count("hierarchies"));
        assertEquals(0, completedCount());
    }

    @ParameterizedTest(name = "{0}")
    @EnumSource(Storage.class)
    void agreesWithJavaForEveryOrderFillPermutation(Storage storage) throws Exception {
        select(storage);
        for (var scenario : orderFillScenarios("root")) {
            clear();
            compare(scenario.events());
            assertEquals(scenario.filledQuantity(), rootQuantity());
            if (storage == Storage.JSONB) assertEquals(scenario.status(), document("root").getStatus());
            else assertEquals(scenario.status().name(), jdbc.queryForObject(
                    "SELECT status FROM order_lifecycle WHERE order_id='root'", String.class));
        }
    }

    @ParameterizedTest(name = "{0}")
    @EnumSource(Storage.class)
    void agreesWithJavaForParentAndChildFillsAndLateDefinitions(Storage storage) throws Exception {
        select(storage);
        for (var events : permutations(List.of(order("root", null, 100),
                order("child", "root", 100), fill("child", "root", 100, TIME.plusNanos(1))))) {
            clear();
            compare(events);
        }
    }

    @ParameterizedTest(name = "{0}")
    @EnumSource(Storage.class)
    void unsupportedTypesDoNotCreateDatabaseState(Storage storage) {
        select(storage);
        for (char type : new char[]{'C', 'X'}) {
            assertThrows(IllegalArgumentException.class,
                    () -> processor.process(json(report("root", null, type, "unsupported", TIME))));
            assertEquals(0, count("hierarchies"));
            assertEquals(0, count("orders"));
            assertEquals(0, completedCount());
        }
    }

    @ParameterizedTest(name = "{0}")
    @EnumSource(Storage.class)
    void deliveryReplayIsIdempotentAndFillConflictRollsBack(Storage storage) throws Exception {
        select(storage);
        ExecutionReport order = order("root", null, 100);
        process(order);
        ExecutionReport fill = fill("root", null, 100, TIME);
        String payload = json(fill);
        processor.process(payload);
        var original = jdbc.queryForMap("SELECT completed_at, completed_by FROM " + table());
        processor.process(payload); // DB commit happened, Kafka commit was lost.
        process(fill); // Same business fill at a new Kafka offset.
        process(order); // Replayed immutable definition after completion.
        assertEquals(1, count("fills"));
        assertEquals(1, completedCount());
        assertEquals(original, jdbc.queryForMap("SELECT completed_at, completed_by FROM " + table()));
        var conflict = fill("root", null, 99, TIME);
        assertThrows(IllegalArgumentException.class, () -> process(conflict));
        assertEquals(100L, rootQuantity());
        assertEquals(original, jdbc.queryForMap("SELECT completed_at, completed_by FROM " + table()));
    }

    @ParameterizedTest(name = "{0}")
    @EnumSource(Storage.class)
    void rollsBackFillWhenRecordingCompletionFails(Storage storage) throws Exception {
        select(storage);
        process(order("root", null, 100));
        jdbc.execute("ALTER TABLE " + table() + " ADD CONSTRAINT injected_failure CHECK (completed_at IS NULL)");
        ExecutionReport fill = fill("root", null, 100, TIME);
        try {
            assertThrows(RuntimeException.class, () -> processor.process(json(fill)));
            assertEquals(0, count("fills"));
            assertNull(jdbc.queryForObject("SELECT completed_at FROM " + table(), java.sql.Timestamp.class));
        } finally {
            jdbc.execute("ALTER TABLE " + table() + " DROP CONSTRAINT injected_failure");
        }
        processor.process(json(fill));
        assertEquals(100L, rootQuantity());
        assertEquals(1, completedCount());
    }

    @ParameterizedTest(name = "{0}")
    @EnumSource(Storage.class)
    void concurrentConsumersDoNotLoseFillsOrCreateMultipleCompletions(Storage storage) throws Exception {
        select(storage);
        process(order("root", null, 12));
        var pool = Executors.newFixedThreadPool(6);
        try {
            List<Callable<Void>> tasks = new ArrayList<>();
            for (int i = 0; i < 12; i++) {
                int id = i;
                tasks.add(() -> {
                    // Independent application service/connection, shared PostgreSQL state.
                    var instance = newProcessor("processor-" + id);
                    instance.process(json(fill("child-" + id, "root", 1, TIME)));
                    return null;
                });
            }
            for (Future<Void> future : pool.invokeAll(tasks, 30, TimeUnit.SECONDS)) future.get();
        } finally {
            pool.shutdownNow();
        }
        assertEquals(12L, rootQuantity());
        assertEquals(12, count("fills"));
        assertEquals(1, completedCount());
    }

    @ParameterizedTest(name = "{0}")
    @EnumSource(Storage.class)
    void concurrentFirstEventsCreateHierarchyAndPlaceholdersWithoutLosingFacts(Storage storage) throws Exception {
        select(storage);
        var pool = Executors.newFixedThreadPool(4);
        try {
            List<Callable<Void>> tasks = new ArrayList<>();
            for (int i = 0; i < 8; i++) {
                int id = i;
                tasks.add(() -> {
                    processor.process(json(fill("child-" + id, "root", 1, TIME)));
                    return null;
                });
            }
            for (Future<Void> future : pool.invokeAll(tasks, 30, TimeUnit.SECONDS)) future.get();
        } finally {
            pool.shutdownNow();
        }
        assertEquals(8, count("fills"));
        assertEquals(9, count("orders"));
        assertEquals(0, completedCount());
        process(order("root", null, 8));
        assertEquals(8L, rootQuantity());
        assertEquals(1, completedCount());
        // Completion must not prevent subsequent facts being stored.
        process(fill("root", null, 1, TIME));
        assertEquals(9L, rootQuantity());
        assertEquals(9, count("fills"));
        assertEquals(1, completedCount());
    }

    @ParameterizedTest(name = "{0}")
    @EnumSource(Storage.class)
    void failedOrUncertainPublicationRetriesTheSamePayload(Storage storage) throws Exception {
        select(storage);
        process(order("root", null, 100));
        process(fill("root", null, 100, TIME));
        List<String> delivered = new ArrayList<>();
        var failing = new CompletionPublisher(completions, manager, (topic, key, payload) -> {
            delivered.add(payload); // Kafka accepted it, but acknowledgement was lost.
            throw new IllegalStateException("Simulated uncertain send");
        }, "verification", false);
        assertThrows(IllegalStateException.class, failing::publishOne);
        assertNull(jdbc.queryForObject("SELECT published_at FROM " + table(), java.sql.Timestamp.class));
        // Later facts from another instance cannot change the completion payload.
        var extraFill = fill("root", null, 25, TIME.plusSeconds(1));
        extraFill.setExecId("extra-fill");
        newProcessor("processor-b").process(json(extraFill));
        assertEquals(125L, rootQuantity());
        var succeeding = new CompletionPublisher(completions, manager,
                (topic, key, payload) -> delivered.add(payload), "verification", false);
        assertTrue(succeeding.publishOne());
        assertFalse(succeeding.publishOne());
        assertNotNull(jdbc.queryForObject("SELECT published_at FROM " + table(), java.sql.Timestamp.class));
        process(order("root", null, 100));
        process(extraFill);
        assertFalse(succeeding.publishOne());
        assertEquals(2, delivered.size());
        assertEquals(delivered.get(0), delivered.get(1));
        VerificationRecord record = JsonUtils.getObjectMapper().readValue(delivered.get(0), VerificationRecord.class);
        assertEquals("processor-a", record.podId());
        assertEquals("root", record.hierarchyKey());
    }

    @ParameterizedTest(name = "{0}")
    @EnumSource(Storage.class)
    void publishersSkipLockedRows(Storage storage) throws Exception {
        select(storage);
        for (String root : List.of("a", "b")) {
            process(order(root, null, 1));
            process(fill(root, null, 1, TIME));
        }
        var executor = Executors.newSingleThreadExecutor();
        try {
            new TransactionTemplate(manager).executeWithoutResult(ignored -> {
                String lockedKey = jdbc.queryForObject("SELECT hierarchy_key FROM " + table()
                        + " WHERE completed_at IS NOT NULL ORDER BY completed_at, hierarchy_key LIMIT 1 FOR UPDATE", String.class);
                Future<Boolean> future = executor.submit(() -> new CompletionPublisher(completions, manager,
                        (topic, key, payload) -> assertNotEquals(lockedKey, key), "verification", false).publishOne());
                try { assertTrue(future.get(5, TimeUnit.SECONDS)); }
                catch (Exception e) { throw new IllegalStateException(e); }
            });
        } finally {
            executor.shutdownNow();
        }
        assertEquals(1, jdbc.queryForObject("SELECT COUNT(*) FROM " + table() + " WHERE published_at IS NOT NULL", Integer.class));
    }

    @ParameterizedTest(name = "{0}")
    @EnumSource(Storage.class)
    void retainsPartialStateInOnlyTheSelectedStorage(Storage storage) throws Exception {
        select(storage);
        process(order("root", null, 100));
        process(fill("child", "root", 40, TIME));
        assertEquals(40L, rootQuantity());
        assertEquals(0, completedCount());
        if (storage == Storage.JSONB) {
            assertEquals(0, jdbc.queryForObject("SELECT count(*) FROM hierarchies", Integer.class));
            assertEquals(0, jdbc.queryForObject("SELECT count(*) FROM orders", Integer.class));
            assertEquals(0, jdbc.queryForObject("SELECT count(*) FROM fills", Integer.class));
        } else {
            assertEquals(0, jdbc.queryForObject("SELECT count(*) FROM lifecycle_documents", Integer.class));
        }
    }

    void compare(List<ExecutionReport> events) throws Exception {
        OrderState reference = new OrderState();
        FillOrderService service = new FillOrderService(new OrderStateUpdater());
        for (ExecutionReport event : events) {
            process(event);
            service.processExecutionReport("root", event, reference);
            assertNode("root", reference);
            for (var entry : reference.getChildOrders().entrySet()) assertNode(entry.getKey(), entry.getValue());
            assertEquals(reference.getCompletedAt() == null ? 0 : 1, completedCount());
        }
    }

    void assertNode(String key, OrderNode reference) throws Exception {
        if (storage == Storage.JSONB) {
            OrderState root = document("root");
            OrderNode actual = key.equals("root") ? root : root.getChildOrders().get(key);
            assertNotNull(actual);
            assertEquals(reference.getFilledQuantity(), actual.getFilledQuantity());
            assertEquals(reference.getStatus(), actual.getStatus());
            assertEquals(reference.getFills(), actual.getFills());
        } else {
            var row = jdbc.queryForMap("SELECT filled_quantity, status FROM order_lifecycle WHERE order_id=?", key);
            assertEquals(reference.getFilledQuantity(), ((Number) row.get("filled_quantity")).longValue());
            assertEquals(reference.getStatus().name(), row.get("status"));
        }
    }

    long rootQuantity() throws Exception {
        if (storage == Storage.JSONB) return document("root").getFilledQuantity();
        return requireNonNull(jdbc.queryForObject(
                "SELECT filled_quantity FROM order_lifecycle WHERE order_id='root'", Long.class));
    }

    int completedCount() {
        return requireNonNull(jdbc.queryForObject(
                "SELECT count(*) FROM " + table() + " WHERE completed_at IS NOT NULL", Integer.class));
    }

    int count(String table) {
        if (storage == Storage.JSONB) {
            if (table.equals("hierarchies")) table = "lifecycle_documents";
            else if (table.equals("orders") || table.equals("fills")) {
                String fact = table;
                return jdbc.query("SELECT state::text FROM lifecycle_documents", (rs, row) -> {
                    try {
                        OrderState root = JsonUtils.getObjectMapper().readValue(rs.getString(1), OrderState.class);
                        return fact.equals("orders") ? 1 + root.getChildOrders().size() : root.getAllFills().size();
                    } catch (Exception e) { throw new IllegalStateException(e); }
                }).stream().mapToInt(Integer::intValue).sum();
            }
        }
        return requireNonNull(jdbc.queryForObject("SELECT COUNT(*) FROM " + table, Integer.class));
    }

    String table() { return storage == Storage.JSONB ? "lifecycle_documents" : "hierarchies"; }

    OrderState document(String key) throws Exception {
        return JsonUtils.getObjectMapper().readValue(jdbc.queryForObject(
                "SELECT state::text FROM lifecycle_documents WHERE hierarchy_key=?", String.class, key), OrderState.class);
    }

    void select(Storage storage) {
        this.storage = storage;
        processor = newProcessor("processor-a");
        completions = storage == Storage.JSONB ? new JsonbCompletionStore(jdbc) : new RelationalCompletionStore(jdbc);
    }

    LifecycleProcessor newProcessor(String id) {
        return storage == Storage.JSONB ? new JsonbLifecycleProcessor(jdbc, manager, id)
                : new RelationalLifecycleProcessor(jdbc, manager, id);
    }

    static Stream<Arguments> invalidPayloads() {
        return Stream.of(Storage.values()).flatMap(storage -> Stream.of("null", "malformed-json")
                .map(payload -> Arguments.of(storage, payload)));
    }

    void process(ExecutionReport report) throws Exception { processor.process(json(report)); }
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
