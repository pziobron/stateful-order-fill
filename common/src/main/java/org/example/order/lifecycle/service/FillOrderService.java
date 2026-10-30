package org.example.order.lifecycle.service;

import lombok.extern.slf4j.Slf4j;
import org.example.order.fix.model.ExecutionReport;
import org.example.order.lifecycle.model.Fill;
import org.example.order.lifecycle.model.OrderNode;
import org.example.order.lifecycle.model.OrderState;
import org.example.order.lifecycle.model.OrderStatus;

import java.time.Instant;
import java.time.ZonedDateTime;

import static org.example.order.lifecycle.util.ExecutionReportUtils.*;
import static org.example.order.lifecycle.util.JsonUtils.toPrettyJson;

/**
 * Service responsible for processing execution reports and managing the order state lifecycle.
 * <p>
 * This service handles both order and fill execution reports, updating the order state
 * accordingly. It ensures that the order status is properly maintained and updated
 * when the order becomes fully filled.
 *
 * <p>The service is designed to be resilient to out-of-order message processing,
 * handling cases where fill reports might be received before the corresponding order message.
 *
 * @see OrderState
 * @see ExecutionReport
 * @see Fill
 */
@Slf4j
public class FillOrderService {

    private final OrderStateUpdater orderStateUpdater;

    /**
     * The constructor
     * @param orderStateUpdater the order state updater
     */
    public FillOrderService(OrderStateUpdater orderStateUpdater) {
        this.orderStateUpdater = orderStateUpdater;
    }

    /**
     * Processes an execution report and updates the order state based on the report type.
     * <p>
     * This method handles two types of execution reports:
     * <ul>
     *   <li>Order reports: Updates the order details in the order state</li>
     *   <li>Fill reports: Records the fill information and updates the order's fill status</li>
     * </ul>
     * The method ensures the order status is updated to {@link OrderStatus#FILLED} when
     * the order becomes fully filled.
     *
     * @param orderId         the unique identifier of the order being processed
     * @param executionReport the execution report containing order or fill information
     * @param orderState      the current state of the order, initialized by the caller
     * @return the updated {@link OrderState} after processing the execution report
     */
    public OrderState processExecutionReport(
            String orderId, ExecutionReport executionReport, OrderState orderState) {
        if (!isOrder(executionReport) && !isFill(executionReport)) {
            throw new IllegalArgumentException("Unsupported report type: " + executionReport.getType());
        }
        log.info("Processing execution report for orderId: {}", orderId);

        Instant now = Instant.now();

        if (orderState.getFirstProcessedAt() == null) {
            orderState.setFirstProcessedAt(now);
        }

        if (orderState.getOrderId() == null) {
            orderState.setOrderId(orderId); // Ensure orderId  (kafka key) is set
        }

        OrderNode targetOrderNode = isChild(executionReport) ?
                orderState.getChildOrders().computeIfAbsent(executionReport.getOrderId(), ignored -> new OrderNode()):
                orderState;

        boolean wasFullyFilled = orderState.isFullyFilled();

        if (isOrder(executionReport)) {
            orderStateUpdater.update(executionReport, targetOrderNode);
        } else if (isFill(executionReport)) {
            targetOrderNode.addFill(new Fill(
                    executionReport.getExecId(), executionReport.getTxnTime(),
                    executionReport.getLastQty(), executionReport.getLastPx()));
        }

        targetOrderNode.refreshStatus();
        if (targetOrderNode != orderState) {
            orderState.refreshStatus();
        }

        orderState.setLastActionTimestamp(ZonedDateTime.now());

        if (!wasFullyFilled
                && orderState.isFullyFilled()
                && orderState.getCompletedAt() == null) {
            orderState.setCompletedAt(Instant.now());
        }

        if (log.isInfoEnabled()) {
            log.info("Updating Lifecycle: {}", toPrettyJson(orderState));
        }

        return orderState;
    }

}
