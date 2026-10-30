package org.example.order.lifecycle.processor.stream;

import org.apache.kafka.streams.processor.api.ContextualProcessor;
import org.apache.kafka.streams.processor.api.ProcessorContext;
import org.apache.kafka.streams.processor.api.Record;
import org.apache.kafka.streams.state.KeyValueStore;
import org.example.order.fix.model.VerificationRecord;
import org.example.order.lifecycle.model.OrderState;

/**
 * Forwards the first observed completion per hierarchy using durable emission state.
 * This does not make at-least-once delivery exactly-once or retract corrected results.
 */
public class CompletionVerificationProcessor
        extends ContextualProcessor<String, OrderState, String, VerificationRecord> {

    public static final String COMPLETION_EMITTED_STORE = "completion-emitted-store";

    private final String processorId;
    private KeyValueStore<String, Boolean> completionEmitted;

    public CompletionVerificationProcessor(String processorId) {
        this.processorId = processorId;
    }

    @Override
    public void init(ProcessorContext<String, VerificationRecord> context) {
        super.init(context);
        completionEmitted = context.getStateStore(COMPLETION_EMITTED_STORE);
    }

    @Override
    public void process(Record<String, OrderState> record) {
        OrderState state = record.value();
        if (record.key() == null || state == null
                || !state.isFullyFilled() || state.getCompletedAt() == null
                || Boolean.TRUE.equals(completionEmitted.get(record.key()))) {
            return;
        }

        context().forward(record.withValue(new VerificationRecord(
                record.key(), processorId, state.getFirstProcessedAt(), state.getCompletedAt())));
        completionEmitted.put(record.key(), true);
    }
}
