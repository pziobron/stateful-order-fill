package org.example.order.lifecycle.db.service;

import com.fasterxml.jackson.core.JsonProcessingException;
import org.example.order.fix.model.VerificationRecord;
import org.example.order.lifecycle.util.JsonUtils;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

/** The hierarchy row acts as an outbox; uncertain sends leave publication pending. */
@Component
public class CompletionPublisher {
    private static final Logger log = LoggerFactory.getLogger(CompletionPublisher.class);
    private final CompletionStore store;
    private final TransactionTemplate transaction;
    private final CompletionSender sender;
    private final String topic;
    private final boolean enabled;

    public CompletionPublisher(CompletionStore store, PlatformTransactionManager manager, CompletionSender sender,
                               @Value("${app.verification-topic}") String topic,
                               @Value("${app.publication-enabled:true}") boolean enabled) {
        this.store = store;
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
            var pending = store.lockNextCompletion();
            if (pending.isEmpty()) return null;
            VerificationRecord record = pending.get();
            sender.send(topic, record.hierarchyKey(), serialize(record));
            store.markPublished(record.hierarchyKey());
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
