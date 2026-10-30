package org.example.order.lifecycle.db.kafka;

import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.example.order.lifecycle.db.service.InputPosition;
import org.example.order.lifecycle.db.service.RelationalLifecycleProcessor;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.stereotype.Component;

@Component
public class ExecutionReportListener {
    private final RelationalLifecycleProcessor processor;
    private final String sourceId;

    public ExecutionReportListener(RelationalLifecycleProcessor processor, @Value("${app.source-id}") String sourceId) {
        if (sourceId.isBlank()) throw new IllegalArgumentException("source-id must not be blank");
        this.processor = processor;
        this.sourceId = sourceId;
    }

    @SuppressWarnings("unused") // Invoked by the Spring Kafka listener container.
    @KafkaListener(topics = "${app.executions-topic}")
    public void receive(ConsumerRecord<String, String> record) {
        // Each invocation commits its own database transaction. BATCH acknowledgement
        // lets the container commit Kafka offsets after processing the poll's records;
        // processed_input makes re-delivery before that offset commit a no-op.
        processor.process(new InputPosition(sourceId, record.topic(), record.partition(), record.offset()),
                record.value());
    }
}
