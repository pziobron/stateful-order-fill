package org.example.order.lifecycle.processor.util;

import org.apache.kafka.streams.processor.TimestampExtractor;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.example.order.fix.model.ExecutionReport;

/**
 * Timestamp extractor that uses the business transaction time (TxnTime) from ExecutionReport
 * for Kafka Streams windowing operations.
 * This ensures that windowing is based on actual business timestamps rather than
 * record processing times, which is critical for accurate financial analytics.
 */
public class BusinessTimestampExtractor implements TimestampExtractor {

    @Override
    public long extract(ConsumerRecord<Object, Object> record, long previousTimestamp) {
        if (record.value() instanceof ExecutionReport report) {
            return report.getTxnTime()
                    .atZone(java.time.ZoneId.systemDefault())
                    .toInstant()
                    .toEpochMilli();
        }
        
        // Fallback to record timestamp if not an ExecutionReport
        return record.timestamp();
    }
}
