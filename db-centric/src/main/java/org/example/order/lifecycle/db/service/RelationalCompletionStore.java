package org.example.order.lifecycle.db.service;

import org.example.order.fix.model.VerificationRecord;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

import java.util.Optional;

@Component
@ConditionalOnProperty(name = "app.state-storage", havingValue = "relational", matchIfMissing = true)
public class RelationalCompletionStore implements CompletionStore {
    private final JdbcTemplate jdbc;

    public RelationalCompletionStore(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    @Override
    public Optional<VerificationRecord> lockNextCompletion() {
        return jdbc.query("""
                SELECT hierarchy_key, completed_by, first_processed_at, completed_at
                FROM hierarchies
                WHERE completed_at IS NOT NULL AND published_at IS NULL
                ORDER BY completed_at, hierarchy_key
                LIMIT 1 FOR UPDATE SKIP LOCKED
                """, (rs, row) -> new VerificationRecord(rs.getString(1), rs.getString(2),
                rs.getTimestamp(3).toInstant(), rs.getTimestamp(4).toInstant())).stream().findFirst();
    }

    @Override
    public void markPublished(String hierarchyKey) {
        jdbc.update("UPDATE hierarchies SET published_at=clock_timestamp() WHERE hierarchy_key=?", hierarchyKey);
    }
}
