package org.example.order.lifecycle.db.kafka;

import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.example.order.lifecycle.db.service.LifecycleProcessor;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.stereotype.Component;

@Component
public class ExecutionReportListener {
    private final LifecycleProcessor processor;

    public ExecutionReportListener(LifecycleProcessor processor) {
        this.processor = processor;
    }

    @SuppressWarnings("unused") // Invoked by the Spring Kafka listener container.
    @KafkaListener(topics = "${app.executions-topic}")
    public void receive(ConsumerRecord<String, String> record) {
        // Each invocation commits its own database transaction. BATCH acknowledgement
        // lets the container commit Kafka offsets after processing the poll's records;
        // idempotent order/fill writes make redelivery safe.
        processor.process(record.value());
    }
}
