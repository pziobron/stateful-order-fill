package org.example.order.lifecycle.processor.util;

import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.apache.kafka.streams.processor.TimestampExtractor;
import org.example.order.fix.model.ExecutionReport;
import org.example.order.lifecycle.util.EventTimeUtils;

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
            return EventTimeUtils.extractEventTimestamp(report);
        }
        
        // Fallback to record timestamp if not an ExecutionReport
        return record.timestamp();
    }
}
