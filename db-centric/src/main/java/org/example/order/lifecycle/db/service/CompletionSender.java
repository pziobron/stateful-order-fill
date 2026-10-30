package org.example.order.lifecycle.db.service;

@FunctionalInterface
public interface CompletionSender {
    void send(String topic, String hierarchyKey, String payload);
}
