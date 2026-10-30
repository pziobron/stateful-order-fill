package org.example.order.lifecycle.testing;

import org.example.order.fix.model.ExecutionReport;
import org.example.order.lifecycle.model.OrderStatus;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;

/** Engine-independent contract examples consumed by Java and PostgreSQL tests. */
public final class LifecycleScenarios {
    private LifecycleScenarios() {}

    public record Scenario(List<ExecutionReport> events, long filledQuantity, OrderStatus status) {}

    public static List<Scenario> orderFillScenarios(String orderId) {
        LocalDateTime time = LocalDateTime.of(2026, 1, 1, 10, 0);
        List<Scenario> scenarios = new ArrayList<>();
        for (long quantity : new long[]{40, 100}) {
            var order = report(orderId, 'O', "order", time);
            order.setOrderQuantity(100);
            var fill = report(orderId, 'F', "fill", time.plusNanos(1));
            fill.setLastQty(quantity);
            fill.setLastPx(10);
            for (var sequence : permutations(List.of(order, fill, fill))) {
                scenarios.add(new Scenario(sequence, quantity,
                        quantity == 100 ? OrderStatus.FILLED : OrderStatus.NOT_FILLED));
            }
        }
        return scenarios;
    }

    public static <T> List<List<T>> permutations(List<T> values) {
        if (values.isEmpty()) return List.of(List.of());
        List<List<T>> result = new ArrayList<>();
        for (int i = 0; i < values.size(); i++) {
            List<T> remaining = new ArrayList<>(values);
            T first = remaining.remove(i);
            for (List<T> tail : permutations(remaining)) {
                List<T> sequence = new ArrayList<>();
                sequence.add(first);
                sequence.addAll(tail);
                result.add(sequence);
            }
        }
        return result;
    }

    private static ExecutionReport report(String id, char type, String execId, LocalDateTime time) {
        var report = new ExecutionReport();
        report.setOrderId(id);
        report.setType(type);
        report.setExecId(execId);
        report.setTxnTime(time);
        return report;
    }
}
