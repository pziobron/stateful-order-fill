package org.example.order.lifecycle.flink.function;

import org.apache.flink.api.common.functions.OpenContext;
import org.apache.flink.api.common.state.ValueState;
import org.apache.flink.api.common.state.ValueStateDescriptor;
import org.apache.flink.api.common.typeinfo.Types;
import org.apache.flink.streaming.api.functions.KeyedProcessFunction;
import org.apache.flink.util.Collector;
import org.example.order.fix.model.ExecutionReport;
import org.example.order.lifecycle.flink.job.FillOrderFlinkJob;
import org.example.order.lifecycle.flink.model.ProcessedOrderState;
import org.example.order.lifecycle.model.OrderState;
import org.example.order.lifecycle.service.FillOrderService;
import org.example.order.lifecycle.service.OrderStateUpdater;

/**
 * Maintains the lifecycle state of an order hierarchy in Flink managed keyed
 * state.
 *
 * <p>Every hierarchy is keyed by its logical parent-order identifier, ensuring
 * that all parent, child, and fill reports for that hierarchy are processed by
 * the same Flink subtask.</p>
 *
 * <p>The updated {@link OrderState} is persisted after every input record.
 * A {@link ProcessedOrderState} is emitted only once: when the hierarchy first
 * reaches its fully-filled state. This output is used as the final completion
 * signal by the verification sink and integration benchmark.</p>
 *
 * <p>A separate managed-state flag prevents duplicate completion records when
 * duplicate or replayed input events are received after the hierarchy has
 * already completed.</p>
 */
public class OrderStateProcessFunction
        extends KeyedProcessFunction<
        String,
        ExecutionReport,
        ProcessedOrderState
        > {

    private static final String COMPLETION_EMITTED_STATE =
            "completion-emitted";

    private transient ValueState<OrderState> orderState;

    private transient ValueState<Boolean> completionEmittedState;

    private transient FillOrderService fillOrderService;

    private transient String processorId;

    /**
     * Initializes the keyed lifecycle state, completion-emission flag, shared
     * domain service, and processor identity.
     *
     * @param openContext context supplied during operator initialization
     */
    @Override
    public void open(OpenContext openContext) {
        orderState = getRuntimeContext().getState(
                new ValueStateDescriptor<>(
                        FillOrderFlinkJob.ORDER_STATE_STORE,
                        Types.GENERIC(OrderState.class)
                )
        );

        completionEmittedState = getRuntimeContext().getState(
                new ValueStateDescriptor<>(
                        COMPLETION_EMITTED_STATE,
                        Types.BOOLEAN
                )
        );

        fillOrderService = new FillOrderService(
                new OrderStateUpdater()
        );

        String podId = System.getenv()
                .getOrDefault("POD_ID", "unknown");

        int subtaskIndex = getRuntimeContext()
                .getTaskInfo()
                .getIndexOfThisSubtask();

        processorId = "%s-subtask-%d"
                .formatted(podId, subtaskIndex);
    }

    /**
     * Applies one execution report to the current hierarchy state.
     *
     * <p>The updated state is always persisted. A verification output is
     * produced only when the hierarchy is fully filled and no completion output
     * has previously been emitted for the current hierarchy key.</p>
     *
     * @param report incoming execution report
     * @param context keyed processing context
     * @param out collector receiving the final completed hierarchy
     * @throws Exception if managed-state access or domain processing fails
     */
    @Override
    public void processElement(
            ExecutionReport report,
            Context context,
            Collector<ProcessedOrderState> out
    ) throws Exception {

        String hierarchyKey = context.getCurrentKey();

        OrderState currentState = orderState.value();

        if (currentState == null) {
            currentState = new OrderState();
            currentState.setOrderId(hierarchyKey);
        }

        OrderState updatedState =
                fillOrderService.processExecutionReport(
                        hierarchyKey,
                        report,
                        currentState
                );

        orderState.update(updatedState);

        boolean completionAlreadyEmitted =
                Boolean.TRUE.equals(
                        completionEmittedState.value()
                );

        if (updatedState.isFullyFilled()
                && updatedState.getCompletedAt() != null
                && !completionAlreadyEmitted) {

            out.collect(
                    new ProcessedOrderState(
                            updatedState,
                            processorId
                    )
            );

            completionEmittedState.update(true);
        }
    }
}