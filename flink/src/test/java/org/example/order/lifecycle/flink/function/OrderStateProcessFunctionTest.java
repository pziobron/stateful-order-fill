package org.example.order.lifecycle.flink.function;

import org.apache.flink.api.common.state.ValueStateDescriptor;
import org.apache.flink.api.common.typeinfo.Types;
import org.apache.flink.runtime.checkpoint.OperatorSubtaskState;
import org.apache.flink.runtime.state.VoidNamespace;
import org.apache.flink.runtime.state.VoidNamespaceSerializer;
import org.apache.flink.streaming.api.operators.KeyedProcessOperator;
import org.apache.flink.streaming.util.KeyedOneInputStreamOperatorTestHarness;
import org.example.order.fix.model.ExecutionReport;
import org.example.order.lifecycle.flink.job.FillOrderFlinkJob;
import org.example.order.lifecycle.flink.model.ProcessedOrderState;
import org.example.order.lifecycle.model.OrderState;
import org.example.order.lifecycle.model.OrderStatus;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.time.LocalDateTime;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class OrderStateProcessFunctionTest {
    private static final LocalDateTime START = LocalDateTime.of(2026, 1, 1, 10, 0);

    @Test
    void emitsOnlyOnFirstCompletionWhileLaterReportsStillUpdateState() throws Exception {
        try (var harness = newHarness()) {
            harness.open();
            harness.processElement(order("parent", null), 0);
            harness.processElement(fill("parent", null, "fill-1", 40), 1);
            assertTrue(harness.extractOutputValues().isEmpty());

            ExecutionReport finalFill = fill("parent", null, "fill-2", 60);
            harness.processElement(finalFill, 2);
            List<ProcessedOrderState> output = harness.extractOutputValues();
            assertEquals(1, output.size());
            ProcessedOrderState completion = output.get(0);
            assertEquals("parent", completion.orderState().getOrderId());
            assertEquals(100, completion.orderState().getFilledQuantity());
            assertNotNull(completion.orderState().getFirstProcessedAt());
            assertNotNull(completion.orderState().getCompletedAt());
            assertEquals(System.getenv().getOrDefault("POD_ID", "unknown") + "-subtask-0",
                    completion.processorId());

            harness.getOutput().clear();
            harness.processElement(finalFill, 3);
            harness.processElement(fill("parent", null, "extra", 25), 4);
            assertTrue(harness.extractOutputValues().isEmpty());
            OrderState state = state(harness, "parent");
            assertEquals(OrderStatus.FILLED, state.getStatus());
            assertEquals(125, state.getFilledQuantity());
            assertEquals(3, state.getFills().size());
        }
    }

    @Test
    void isolatesHierarchyStateAndCompletionFlags() throws Exception {
        try (var harness = newHarness()) {
            harness.open();
            harness.processElement(order("parent-a", null), 0);
            harness.processElement(order("parent-b", null), 1);
            harness.processElement(order("child-a", "parent-a"), 2);
            harness.processElement(order("child-b", "parent-b"), 3);
            harness.processElement(fill("child-a", "parent-a", "fill", 100), 4);
            assertEquals(1, harness.extractOutputValues().size());
            assertEquals("parent-a", harness.extractOutputValues().get(0).orderState().getOrderId());
            assertEquals(0, state(harness, "parent-b").getFilledQuantity());
            assertNull(state(harness, "parent-b").getCompletedAt());

            harness.getOutput().clear();
            harness.processElement(fill("child-b", "parent-b", "fill", 100), 5);
            assertEquals(1, harness.extractOutputValues().size());
            assertEquals("parent-b", harness.extractOutputValues().get(0).orderState().getOrderId());
            harness.getOutput().clear();
            harness.processElement(fill("child-a", "parent-a", "fill", 100), 6);
            harness.processElement(fill("child-b", "parent-b", "fill", 100), 7);
            assertTrue(harness.extractOutputValues().isEmpty());
        }
    }

    @Test
    void restoresPartialHierarchyAndContinuesWithRemainingFill() throws Exception {
        OperatorSubtaskState snapshot;
        Instant firstProcessedAt;
        try (var harness = newHarness()) {
            harness.open();
            harness.processElement(order("parent", null), 0);
            harness.processElement(order("child", "parent"), 1);
            harness.processElement(fill("child", "parent", "fill-1", 40), 2);
            assertTrue(harness.extractOutputValues().isEmpty());
            firstProcessedAt = state(harness, "parent").getFirstProcessedAt();
            snapshot = harness.snapshot(1, 10);
        }

        try (var restored = newHarness()) {
            restored.initializeState(snapshot);
            restored.open();
            assertEquals(40, state(restored, "parent").getFilledQuantity());
            restored.processElement(fill("child", "parent", "fill-2", 60), 3);
            assertEquals(1, restored.extractOutputValues().size());
            OrderState completed = restored.extractOutputValues().get(0).orderState();
            assertEquals(100, completed.getFilledQuantity());
            assertEquals(firstProcessedAt, completed.getFirstProcessedAt());
            assertEquals(2, completed.getChildOrders().get("child").getFills().size());
            assertNotNull(completed.getCompletedAt());
        }
    }

    @Test
    void restoresCompletionFlagAndSuppressesReplayedCompletion() throws Exception {
        OperatorSubtaskState snapshot;
        Instant completedAt;
        try (var harness = newHarness()) {
            harness.open();
            harness.processElement(order("parent", null), 0);
            harness.processElement(fill("parent", null, "fill", 100), 1);
            assertEquals(1, harness.extractOutputValues().size());
            completedAt = state(harness, "parent").getCompletedAt();
            snapshot = harness.snapshot(1, 10);
        }

        try (var restored = newHarness()) {
            restored.initializeState(snapshot);
            restored.open();
            assertEquals(100, state(restored, "parent").getFilledQuantity());
            restored.processElement(fill("parent", null, "fill", 100), 2);
            restored.processElement(fill("parent", null, "extra", 25), 3);
            assertTrue(restored.extractOutputValues().isEmpty());
            assertEquals(completedAt, state(restored, "parent").getCompletedAt());
            assertEquals(125, state(restored, "parent").getFilledQuantity());

            restored.processElement(order("another", null), 4);
            restored.processElement(fill("another", null, "fill", 100), 5);
            assertEquals(1, restored.extractOutputValues().size());
            assertEquals("another", restored.extractOutputValues().get(0).orderState().getOrderId());
        }
    }

    @Test
    void rejectsUnsupportedReportWithoutChangingStoredState() throws Exception {
        try (var harness = newHarness()) {
            harness.open();
            harness.processElement(order("parent", null), 0);
            var before = org.example.order.lifecycle.util.JsonUtils.toPrettyJson(state(harness, "parent"));
            assertThrows(IllegalArgumentException.class,
                    () -> harness.processElement(report("parent", null, 'C', "unsupported"), 1));
            assertEquals(before, org.example.order.lifecycle.util.JsonUtils.toPrettyJson(state(harness, "parent")));
            assertTrue(harness.extractOutputValues().isEmpty());
        }
    }

    private static KeyedOneInputStreamOperatorTestHarness<String, ExecutionReport, ProcessedOrderState>
            newHarness() throws Exception {
        var harness = new KeyedOneInputStreamOperatorTestHarness<>(
                new KeyedProcessOperator<>(new OrderStateProcessFunction()),
                report -> report.getParentId() == null ? report.getOrderId() : report.getParentId(),
                Types.STRING);
        // Match the explicit generic output type in FillOrderFlinkJob, avoiding
        // automatic POJO inference for the recursive hierarchy model.
        harness.setup(Types.GENERIC(ProcessedOrderState.class)
                .createSerializer(harness.getExecutionConfig().getSerializerConfig()));
        return harness;
    }

    private static OrderState state(
            KeyedOneInputStreamOperatorTestHarness<String, ExecutionReport, ProcessedOrderState> harness,
            String key) throws Exception {
        var backend = harness.getOperator().getKeyedStateBackend();
        backend.setCurrentKey(key);
        return backend.getPartitionedState(
                VoidNamespace.INSTANCE, VoidNamespaceSerializer.INSTANCE,
                new ValueStateDescriptor<>(FillOrderFlinkJob.ORDER_STATE_STORE, Types.GENERIC(OrderState.class)))
                .value();
    }

    private static ExecutionReport order(String orderId, String parentId) {
        ExecutionReport report = report(orderId, parentId, 'O', orderId + "-definition");
        report.setOrderQuantity(100);
        return report;
    }

    private static ExecutionReport fill(String orderId, String parentId, String fillId, long quantity) {
        ExecutionReport report = report(orderId, parentId, 'F', fillId);
        report.setTxnTime(START.plusMinutes(1));
        report.setLastQty(quantity);
        report.setLastPx(10);
        return report;
    }

    private static ExecutionReport report(String orderId, String parentId, char type, String execId) {
        ExecutionReport report = new ExecutionReport();
        report.setOrderId(orderId);
        report.setParentId(parentId);
        report.setExecId(execId);
        report.setType(type);
        report.setTxnTime(START);
        return report;
    }
}
