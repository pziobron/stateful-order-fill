package org.example.order.lifecycle.db.service;

import org.example.order.fix.model.ExecutionReport;
import org.example.order.lifecycle.model.Fill;
import org.example.order.lifecycle.util.JsonUtils;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionTemplate;

import java.sql.Timestamp;
import java.time.Instant;
import java.util.Objects;

/** One transaction owns the hierarchy mutation and durable pending completion. */
@Service
public class RelationalLifecycleProcessor {
    private static final Logger log = LoggerFactory.getLogger(RelationalLifecycleProcessor.class);
    private final JdbcTemplate jdbc;
    private final TransactionTemplate transaction;
    private final String processorId;

    public RelationalLifecycleProcessor(JdbcTemplate jdbc, PlatformTransactionManager manager,
                                       @Value("${app.processor-id}") String processorId) {
        this.jdbc = jdbc;
        this.processorId = processorId;
        transaction = new TransactionTemplate(manager);
        transaction.setIsolationLevel(TransactionDefinition.ISOLATION_READ_COMMITTED);
        transaction.setTimeout(20);
    }

    public void process(String payload) {
        // This POC assumes reports satisfy the shared input contract.
        // Input-field validation is outside the benchmark scope.
        ExecutionReport report = parseReport(payload);
        if (report.getType() != 'O' && report.getType() != 'F') {
            throw new IllegalArgumentException("Unsupported report type: " + report.getType());
        }
        String key = report.getParentId() == null ? report.getOrderId() : report.getParentId();

        Boolean completed = transaction.execute(ignored -> {
            boolean alreadyCompleted = lockHierarchy(key);
            applyReport(key, report);
            return !alreadyCompleted && recordFirstCompletion(key);
        });
        log.info("Execution report committed: hierarchy={}, orderId={}, execId={}, type={}",
                key, report.getOrderId(), report.getExecId(), report.getType());
        if (Boolean.TRUE.equals(completed)) {
            log.info("Hierarchy completed; verification pending: hierarchy={}, processorId={}",
                    key, processorId);
        }
    }

    private ExecutionReport parseReport(String payload) {
        try {
            return Objects.requireNonNull(
                    JsonUtils.getObjectMapper().readValue(payload, ExecutionReport.class),
                    "Execution report must not be null");
        } catch (Exception e) {
            throw new IllegalArgumentException("Invalid execution report", e);
        }
    }

    /** The upsert holds the hierarchy row lock until commit and updates its processing time. */
    private boolean lockHierarchy(String key) {
        Instant started = Instant.now();
        return Boolean.TRUE.equals(jdbc.queryForObject("""
                INSERT INTO hierarchies (hierarchy_key, first_processed_at, last_processed_at)
                VALUES (?, ?, ?)
                ON CONFLICT (hierarchy_key) DO UPDATE
                SET last_processed_at=GREATEST(hierarchies.last_processed_at, EXCLUDED.last_processed_at)
                RETURNING completed_at IS NOT NULL
                """, Boolean.class, key, Timestamp.from(started), Timestamp.from(started)));
    }

    private void applyReport(String key, ExecutionReport report) {
        switch (report.getType()) {
            case 'O' -> {
                // A child definition's foreign key needs the root, even if its definition is late.
                if (!key.equals(report.getOrderId())) ensureOrders(key, key, null);
                updateOrderDefinition(key, report);
            }
            case 'F' -> {
                ensureOrders(key, report.getOrderId(), report.getParentId());
                addFill(key, report);
            }
            default -> throw new IllegalArgumentException("Unsupported report type");
        }
    }

    private void ensureOrders(String key, String orderId, String parentId) {
        if (key.equals(orderId)) {
            jdbc.update("""
                    INSERT INTO orders (hierarchy_key, order_id, parent_order_id) VALUES (?, ?, NULL)
                    ON CONFLICT DO NOTHING
                    """, key, key);
        } else {
            // Both placeholder rows are visible to the self-referencing FK at statement end.
            jdbc.update("""
                    INSERT INTO orders (hierarchy_key, order_id, parent_order_id)
                    VALUES (?, ?, NULL), (?, ?, ?) ON CONFLICT DO NOTHING
                    """, key, key, key, orderId, parentId);
        }
    }

    private void updateOrderDefinition(String key, ExecutionReport report) {
        jdbc.update("""
                INSERT INTO orders (hierarchy_key, order_id, parent_order_id, definition_received,
                    expected_quantity, exec_id, trade_date, transaction_time, transaction_nanos, currency)
                VALUES (?, ?, ?, true, ?, ?, ?, ?, ?, ?)
                ON CONFLICT (hierarchy_key, order_id) DO UPDATE SET
                    definition_received=true, expected_quantity=EXCLUDED.expected_quantity,
                    exec_id=EXCLUDED.exec_id, trade_date=EXCLUDED.trade_date,
                    transaction_time=EXCLUDED.transaction_time, transaction_nanos=EXCLUDED.transaction_nanos,
                    currency=EXCLUDED.currency
                """, key, report.getOrderId(), report.getParentId(), report.getOrderQuantity(), report.getExecId(),
                report.getTradeDate() == null ? null : new Timestamp(report.getTradeDate().getTime()),
                report.getTxnTime().withNano(0), report.getTxnTime().getNano(), report.getCurrency());
    }

    private void addFill(String key, ExecutionReport report) {
        int inserted = jdbc.update("""
                INSERT INTO fills VALUES (?, ?, ?, ?, ?, ?, ?) ON CONFLICT DO NOTHING
                """, key, report.getOrderId(), report.getExecId(), report.getTxnTime().withNano(0),
                report.getTxnTime().getNano(), report.getLastQty(), report.getLastPx());
        if (inserted == 1) return;

        Fill stored = jdbc.queryForObject("""
                SELECT transaction_time, transaction_nanos, quantity, price FROM fills
                WHERE hierarchy_key=? AND order_id=? AND fill_id=?
                """, (rs, row) -> new Fill(report.getExecId(),
                rs.getTimestamp(1).toLocalDateTime().withNano(rs.getInt(2)), rs.getLong(3), rs.getDouble(4)),
                key, report.getOrderId(), report.getExecId());
        Fill incoming = new Fill(report.getExecId(), report.getTxnTime(), report.getLastQty(), report.getLastPx());
        if (!Objects.equals(stored, incoming)) {
            throw new IllegalArgumentException("Conflicting fill: " + report.getExecId());
        }
    }

    private boolean recordFirstCompletion(String key) {
        return jdbc.update("""
                UPDATE hierarchies SET completed_at=?, completed_by=?
                WHERE hierarchy_key=? AND completed_at IS NULL
                  AND EXISTS (SELECT 1 FROM order_lifecycle
                              WHERE hierarchy_key=? AND order_id=? AND status='FILLED')
                """, Timestamp.from(Instant.now()), processorId, key, key, key) == 1;
    }
}
