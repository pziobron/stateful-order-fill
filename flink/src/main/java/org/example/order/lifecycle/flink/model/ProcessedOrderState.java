package org.example.order.lifecycle.flink.model;

import org.example.order.lifecycle.model.OrderState;

/**
 * Represents an updated order lifecycle state together with the identity
 * of the Flink runtime subtask that processed the corresponding event.
 *
 * <p>The processor identifier is runtime metadata used exclusively for
 * verification, partition-distribution analysis, and performance experiments.
 * It is intentionally kept outside the shared {@link OrderState} domain model.</p>
 *
 * @param orderState updated order lifecycle state
 * @param processorId identifier of the TaskManager pod and Flink subtask
 */
public record ProcessedOrderState(
        OrderState orderState,
        String processorId
) {
}