package org.example.order.lifecycle.db.service;

import com.fasterxml.jackson.core.JsonProcessingException;
import org.example.order.fix.model.VerificationRecord;
import org.example.order.lifecycle.util.JsonUtils;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

/** The hierarchy row acts as an outbox; uncertain sends leave publication pending. */
@Component
public class CompletionPublisher {
    private static final Logger log = LoggerFactory.getLogger(CompletionPublisher.class);
    private final JdbcTemplate jdbc;
    private final TransactionTemplate transaction;
    private final CompletionSender sender;
    private final String topic;
    private final boolean enabled;

    public CompletionPublisher(JdbcTemplate jdbc, PlatformTransactionManager manager, CompletionSender sender,
                               @Value("${app.verification-topic}") String topic,
                               @Value("${app.publication-enabled:true}") boolean enabled) {
        this.jdbc = jdbc;
        this.transaction = new TransactionTemplate(manager);
        transaction.setTimeout(20);
        this.sender = sender;
        this.topic = topic;
        this.enabled = enabled;
    }

    @Scheduled(fixedDelayString = "${app.publication-delay-ms:100}")
    public void publishPending() {
        if (!enabled) return;
        try {
            // Bound each pass; each send has its own transaction.
            for (int i = 0; i < 100; i++) {
                if (!publishOne()) break;
            }
        } catch (RuntimeException e) {
            log.warn("Completion publication failed; pending hierarchy will be retried", e);
        }
    }

    public boolean publishOne() {
        String publishedKey = transaction.execute(ignored -> {
            var rows = jdbc.query("""
                    SELECT hierarchy_key, completed_by, first_processed_at, completed_at
                    FROM hierarchies
                    WHERE completed_at IS NOT NULL AND published_at IS NULL
                    ORDER BY completed_at, hierarchy_key
                    LIMIT 1 FOR UPDATE SKIP LOCKED
                    """, (rs, row) -> new VerificationRecord(rs.getString(1), rs.getString(2),
                    rs.getTimestamp(3).toInstant(), rs.getTimestamp(4).toInstant()));
            if (rows.isEmpty()) return null;
            VerificationRecord record = rows.get(0);
            sender.send(topic, record.hierarchyKey(), serialize(record));
            jdbc.update("UPDATE hierarchies SET published_at=clock_timestamp() WHERE hierarchy_key=?",
                    record.hierarchyKey());
            return record.hierarchyKey();
        });
        if (publishedKey == null) return false;
        log.info("Verification published; publication state committed: hierarchy={}, topic={}", publishedKey, topic);
        return true;
    }

    private static String serialize(VerificationRecord record) {
        try {
            return JsonUtils.getObjectMapper().writeValueAsString(record);
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("Cannot serialize completion", e);
        }
    }
}
