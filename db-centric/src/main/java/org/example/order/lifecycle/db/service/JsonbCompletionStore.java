package org.example.order.lifecycle.db.service;

import org.example.order.fix.model.VerificationRecord;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

import java.time.Instant;
import java.util.Optional;

@Component
@ConditionalOnProperty(name = "app.state-storage", havingValue = "jsonb")
public class JsonbCompletionStore implements CompletionStore {
    private final JdbcTemplate jdbc;

    public JsonbCompletionStore(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    @Override
    public Optional<VerificationRecord> lockNextCompletion() {
        // JsonUtils wraps OrderState under its class name. Read the immutable
        // document timestamps to preserve Instant nanoseconds in the output.
        return jdbc.query("""
                SELECT hierarchy_key, completed_by,
                       state #>> '{OrderState,firstProcessedAt}',
                       state #>> '{OrderState,completedAt}'
                FROM lifecycle_documents
                WHERE completed_at IS NOT NULL AND published_at IS NULL
                ORDER BY completed_at, hierarchy_key
                LIMIT 1 FOR UPDATE SKIP LOCKED
                """, (rs, row) -> new VerificationRecord(rs.getString(1), rs.getString(2),
                Instant.parse(rs.getString(3)), Instant.parse(rs.getString(4)))).stream().findFirst();
    }

    @Override
    public void markPublished(String hierarchyKey) {
        jdbc.update("UPDATE lifecycle_documents SET published_at=clock_timestamp() WHERE hierarchy_key=?", hierarchyKey);
    }
}
