package org.example.order.lifecycle.db.kafka;

import org.example.order.lifecycle.db.service.CompletionSender;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.stereotype.Component;

import java.util.concurrent.TimeUnit;

@Component
public class KafkaCompletionSender implements CompletionSender {
    private final KafkaTemplate<String, String> template;

    public KafkaCompletionSender(KafkaTemplate<String, String> template) {
        this.template = template;
    }

    @Override
    public void send(String topic, String key, String payload) {
        try {
            template.send(topic, key, payload).get(12, TimeUnit.SECONDS);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("Interrupted while publishing completion", e);
        } catch (Exception e) {
            throw new IllegalStateException("Completion send failed or timed out", e);
        }
    }
}
