package org.example.order.lifecycle.flink.serialization;

import org.apache.flink.connector.kafka.sink.KafkaRecordSerializationSchema;
import org.apache.kafka.clients.producer.ProducerRecord;
import org.example.order.fix.model.VerificationRecord;
import org.example.order.lifecycle.flink.model.ProcessedOrderState;
import org.example.order.lifecycle.model.OrderState;
import org.example.order.lifecycle.util.JsonUtils;

import java.nio.charset.StandardCharsets;

/**
 * Kafka serialization schema used for publishing verification records.
 *
 * <p>For every processed order-state update, a corresponding
 * {@link VerificationRecord} is created and sent to the verification Kafka
 * topic.</p>
 *
 * <p>Each verification record contains:</p>
 *
 * <ul>
 *     <li>the logical order hierarchy identifier</li>
 *     <li>the TaskManager pod and Flink subtask that processed the event</li>
 *     <li>the verification timestamp</li>
 * </ul>
 *
 * <p>The runtime processor identifier is produced by the stateful processing
 * operator and transported together with the updated {@link OrderState}.
 * This ensures that the verification record identifies the subtask that
 * actually updated managed state, rather than the job submitter or a
 * potentially separate sink subtask.</p>
 *
 * <p>The verification topic is used for integration testing, partition
 * distribution analysis, and validation that all events belonging to the
 * same order hierarchy are consistently processed by the same Flink
 * subtask.</p>
 */
public class VerificationRecordSerializationSchema
        implements KafkaRecordSerializationSchema<ProcessedOrderState> {

    private final String topic;

    /**
     * Creates a serializer publishing verification records to the specified
     * Kafka topic.
     *
     * @param topic verification topic name
     */
    public VerificationRecordSerializationSchema(String topic) {
        this.topic = topic;
    }

    /**
     * Converts an updated order state and its runtime processor metadata into
     * a Kafka producer record containing a serialized verification record.
     *
     * @param processedState updated order state with processor metadata
     * @param context Kafka sink serialization context
     * @param timestamp event timestamp supplied by Flink
     * @return Kafka producer record ready to be sent
     */
    @Override
    public ProducerRecord<byte[], byte[]> serialize(
            ProcessedOrderState processedState,
            KafkaSinkContext context,
            Long timestamp
    ) {
        try {
            OrderState state = processedState.orderState();

            String key = state.getOrderId();

            VerificationRecord record = new VerificationRecord(
                    key,
                    processedState.processorId(),
                    state.getFirstProcessedAt(),
                    state.getCompletedAt()
            );

            return new ProducerRecord<>(
                    topic,
                    key.getBytes(StandardCharsets.UTF_8),
                    JsonUtils.getObjectMapper()
                            .writeValueAsBytes(record)
            );
        } catch (Exception e) {
            throw new RuntimeException(
                    "Failed to serialize verification record",
                    e
            );
        }
    }
}
