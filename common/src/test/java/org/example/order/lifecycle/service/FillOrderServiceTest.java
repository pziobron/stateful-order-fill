package org.example.order.lifecycle.service;

import org.example.order.fix.model.ExecutionReport;
import org.example.order.lifecycle.model.OrderState;
import org.example.order.lifecycle.model.OrderStatus;
import org.example.order.lifecycle.util.JsonUtils;
import org.junit.jupiter.api.Test;

import java.time.LocalDateTime;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;
import static org.example.order.lifecycle.testing.LifecycleScenarios.*;

class FillOrderServiceTest {
    private final FillOrderService service = new FillOrderService(new OrderStateUpdater());
    private final LocalDateTime eventTime = LocalDateTime.of(2026, 1, 1, 10, 0);

    @Test
    void reconcilesEveryOrderFillPermutation() {
        for (var scenario : orderFillScenarios("parent")) {
            OrderState state = process(scenario.events());
            assertEquals(scenario.filledQuantity(), state.getFilledQuantity());
            assertEquals(scenario.status(), state.getStatus());
            assertEquals(1, state.getFills().size());
            assertEquals(1, state.getFilledQuantityMap().size());
        }
    }

    @Test
    void deduplicatesIdenticalFillsAndRejectsConflicts() {
        ExecutionReport fill = report('F', "fill", eventTime, 40);
        OrderState state = process(List.of(fill, fill));
        assertEquals(40, state.getFilledQuantity());
        assertEquals(1, state.getFills().size());
        ExecutionReport conflict = report('F', "fill", eventTime, 50);
        assertThrows(IllegalArgumentException.class,
                () -> service.processExecutionReport("parent", conflict, state));
        assertEquals(40, state.getFilledQuantity());
    }

    @Test
    void childFillsAndLateDefinitionsAgreeInEveryDeliveryOrder() throws Exception {
        ExecutionReport parent = report('O', "parent-order", eventTime, 100);
        ExecutionReport child = report('O', "child-order", eventTime, 100);
        ExecutionReport fill = report('F', "fill", eventTime.plusMinutes(1), 100);
        for (ExecutionReport event : List.of(child, fill)) {
            event.setOrderId("child");
            event.setParentId("parent");
        }
        for (var events : permutations(List.of(parent, child, fill))) {
            OrderState state = process(events);
            assertEquals(100, state.getFilledQuantity());
            assertEquals(OrderStatus.FILLED, state.getStatus());
            assertEquals(OrderStatus.FILLED, state.getChildOrders().get("child").getStatus());
            OrderState restored = JsonUtils.getObjectMapper().readValue(
                    JsonUtils.getObjectMapper().writeValueAsString(state), OrderState.class);
            assertEquals(100, restored.getFilledQuantity());
            assertEquals(1, restored.getChildOrders().get("child").getFills().size());
        }
    }

    @Test
    void rejectsUnsupportedTypesWithoutChangingState() {
        for (char type : new char[]{'C', 'X'}) {
            OrderState state = new OrderState();
            OrderState before = new OrderState();
            assertThrows(IllegalArgumentException.class,
                    () -> service.processExecutionReport("parent", report(type, "unsupported", eventTime, 0), state));
            assertEquals(before, state);
        }
    }

    @Test
    void completionTimestampRemainsFirstObservationAfterAdditionalFill() {
        OrderState state = process(List.of(report('O', "order", eventTime, 100),
                report('F', "fill", eventTime, 100)));
        var firstCompletion = state.getCompletedAt();
        assertNotNull(firstCompletion);
        service.processExecutionReport("parent", report('F', "extra", eventTime, 25), state);
        assertEquals(125, state.getFilledQuantity());
        assertEquals(firstCompletion, state.getCompletedAt());
    }

    private OrderState process(List<ExecutionReport> events) {
        OrderState state = new OrderState();
        events.forEach(event -> service.processExecutionReport("parent", event, state));
        return state;
    }

    private ExecutionReport report(char type, String id, LocalDateTime time, long quantity) {
        ExecutionReport report = new ExecutionReport();
        report.setOrderId("parent");
        report.setExecId(id);
        report.setType(type);
        report.setTxnTime(time);
        report.setOrderQuantity(quantity);
        report.setLastQty(quantity);
        report.setLastPx(10);
        return report;
    }

}
