package org.example.order.lifecycle.db.service;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import java.util.UUID;

/** Holds one row lock while sending. A failed/uncertain send leaves the row pending. */
@Component
public class OutboxPublisher {
    private static final Logger log = LoggerFactory.getLogger(OutboxPublisher.class);
    private final JdbcTemplate jdbc;
    private final TransactionTemplate transaction;
    private final CompletionSender sender;
    private final boolean enabled;

    public OutboxPublisher(JdbcTemplate jdbc, PlatformTransactionManager manager, CompletionSender sender,
                           @Value("${app.outbox-enabled:true}") boolean enabled) {
        this.jdbc = jdbc;
        this.transaction = new TransactionTemplate(manager);
        transaction.setTimeout(20);
        this.sender = sender;
        this.enabled = enabled;
    }

    @Scheduled(fixedDelayString = "${app.outbox-delay-ms:100}")
    public void publishPending() {
        if (!enabled) return;
        try {
            // Bound each scheduler run; each send has its own short transaction.
            for (int i = 0; i < 100; i++) {
                if (!publishOne()) break;
            }
        } catch (RuntimeException e) {
            log.warn("Outbox publication failed; pending record will be retried", e);
        }
    }

    public boolean publishOne() {
        Pending published = transaction.execute(ignored -> {
            var rows = jdbc.query("""
                    SELECT event_id, hierarchy_key, topic, payload FROM outbox
                    WHERE published_at IS NULL ORDER BY created_at, event_id
                    LIMIT 1 FOR UPDATE SKIP LOCKED
                    """, (rs, row) -> new Pending(rs.getObject(1, UUID.class), rs.getString(2),
                    rs.getString(3), rs.getString(4)));
            if (rows.isEmpty()) return null;
            Pending row = rows.get(0);
            sender.send(row.topic(), row.key(), row.payload());
            jdbc.update("UPDATE outbox SET published_at=clock_timestamp() WHERE event_id=?", row.id());
            return row;
        });
        if (published == null) return false;
        log.info("Verification published; outbox update committed: hierarchy={}, eventId={}, topic={}",
                published.key(), published.id(), published.topic());
        return true;
    }

    private record Pending(UUID id, String key, String topic, String payload) {}
}
