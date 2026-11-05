package org.example.order.lifecycle.db.service;

import com.fasterxml.jackson.core.JsonProcessingException;
import org.example.order.fix.model.ExecutionReport;
import org.example.order.lifecycle.model.OrderState;
import org.example.order.lifecycle.service.FillOrderService;
import org.example.order.lifecycle.service.OrderStateUpdater;
import org.example.order.lifecycle.util.JsonUtils;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionTemplate;

import java.sql.Timestamp;
import java.util.Objects;

/** Reads, aggregates and persists one whole hierarchy document per event. */
@Service
@ConditionalOnProperty(name = "app.state-storage", havingValue = "jsonb")
public class JsonbLifecycleProcessor implements LifecycleProcessor {
    private static final String EMPTY_STATE = serialize(new OrderState());
    private static final Logger log = LoggerFactory.getLogger(JsonbLifecycleProcessor.class);
    private final JdbcTemplate jdbc;
    private final TransactionTemplate transaction;
    private final String processorId;
    private final FillOrderService fillOrderService = new FillOrderService(new OrderStateUpdater());

    public JsonbLifecycleProcessor(JdbcTemplate jdbc, PlatformTransactionManager manager,
                                  @Value("${app.processor-id}") String processorId) {
        this.jdbc = jdbc;
        this.processorId = processorId;
        transaction = new TransactionTemplate(manager);
        transaction.setIsolationLevel(TransactionDefinition.ISOLATION_READ_COMMITTED);
        transaction.setTimeout(20);
    }

    @Override
    public void process(String payload) {
        ExecutionReport report = ExecutionReportParser.parse(payload);
        String key = report.getParentId() == null ? report.getOrderId() : report.getParentId();
        Boolean completed = transaction.execute(ignored -> {
            // A concurrent first event can create the row. ON CONFLICT waits for
            // that transaction; the following SELECT reads and locks its latest state.
            jdbc.update("""
                    INSERT INTO lifecycle_documents (hierarchy_key, state)
                    VALUES (?, CAST(? AS jsonb)) ON CONFLICT DO NOTHING
                    """, key, EMPTY_STATE);
            OrderState state = jdbc.queryForObject("""
                    SELECT state::text FROM lifecycle_documents WHERE hierarchy_key=? FOR UPDATE
                    """, (rs, row) -> deserialize(rs.getString(1)), key);
            Objects.requireNonNull(state, "Missing lifecycle document");
            boolean alreadyCompleted = state.getCompletedAt() != null;
            fillOrderService.processExecutionReport(key, report, state);
            boolean firstCompletion = !alreadyCompleted && state.getCompletedAt() != null;
            jdbc.update("""
                    UPDATE lifecycle_documents SET state=CAST(? AS jsonb),
                        completed_by=CASE WHEN completed_at IS NULL AND ? THEN ? ELSE completed_by END,
                        completed_at=COALESCE(completed_at, ?)
                    WHERE hierarchy_key=?
                    """, serialize(state), firstCompletion, processorId,
                    state.getCompletedAt() == null ? null : Timestamp.from(state.getCompletedAt()), key);
            return firstCompletion;
        });
        log.info("Execution report committed: storage=jsonb, hierarchy={}, orderId={}, execId={}, type={}",
                key, report.getOrderId(), report.getExecId(), report.getType());
        if (Boolean.TRUE.equals(completed)) {
            log.info("Hierarchy completed; verification pending: storage=jsonb, hierarchy={}, processorId={}",
                    key, processorId);
        }
    }

    private static String serialize(OrderState state) {
        try {
            return JsonUtils.getObjectMapper().writeValueAsString(state);
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("Cannot serialize lifecycle document", e);
        }
    }

    private static OrderState deserialize(String json) {
        try {
            return JsonUtils.getObjectMapper().readValue(json, OrderState.class);
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("Cannot deserialize lifecycle document", e);
        }
    }
}
