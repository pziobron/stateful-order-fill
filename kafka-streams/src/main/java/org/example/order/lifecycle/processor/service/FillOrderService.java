package org.example.order.lifecycle.processor.service;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.example.order.fix.model.ExecutionReport;
import org.example.order.lifecycle.model.Fill;
import org.example.order.lifecycle.model.OrderNode;
import org.example.order.lifecycle.model.OrderState;
import org.example.order.lifecycle.model.OrderStatus;
import org.springframework.stereotype.Service;

import java.time.ZonedDateTime;

import static org.example.order.lifecycle.processor.util.ExecutionReportUtils.isFill;
import static org.example.order.lifecycle.processor.util.ExecutionReportUtils.isOrder;
import static org.example.order.lifecycle.processor.util.ExecutionReportUtils.isChild;
import static org.example.order.lifecycle.processor.util.JsonUtils.toPrettyJson;

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
@Service
@RequiredArgsConstructor
public class FillOrderService {

    private final OrderStateUpdater orderStateUpdater;

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
     * @param orderState      the current state of the order, or null if this is the first report for the order
     * @return the updated {@link OrderState} after processing the execution report
     */
    public OrderState processExecutionReport(
            String orderId, ExecutionReport executionReport, OrderState orderState) {
        log.info("Processing execution report for orderId: {}", orderId);

        if (orderState.getOrderId() == null) {
            orderState.setOrderId(orderId); // Ensure orderId  (kafka key) is set
        }

        OrderNode targetOrderNode = isChild(executionReport) ?
                orderState.getChildOrders().computeIfAbsent(executionReport.getOrderId(), _ -> new OrderNode()):
                orderState;


        if (isOrder(executionReport)) {
            orderStateUpdater.update(executionReport, targetOrderNode);
            updateOrderStatusIfFullyFilled(orderState, targetOrderNode); //In case the order arrives after the fill -> may happen
        } else if (isFill(executionReport)) {
            Fill fill = new Fill(
                    executionReport.getExecId(),
                    executionReport.getTxnTime(),
                    executionReport.getLastQty(),
                    executionReport.getLastPx());
            targetOrderNode.addFill(fill);
            updateOrderStatusIfFullyFilled(orderState, targetOrderNode);
        }
        orderState.setLastActionTimestamp(ZonedDateTime.now());
        log.info("Updating Lifecycle: {}", toPrettyJson(orderState));
        return orderState;
    }

    /**
     * Updates the order status to {@link OrderStatus#FILLED} if the order's filled quantity
     * meets or exceeds the order quantity.
     * <p>
     * This method checks if the order is fully filled by comparing the total filled quantity
     * against the order quantity. If the order is fully filled, the status is updated and
     * a log message is generated.
     *
     * @param orderState the order state to check and potentially update
     * @param orderNode the order node to check and potentially update
     */
    private void updateOrderStatusIfFullyFilled(OrderState orderState, OrderNode orderNode) {
        if (orderNode.isFullyFilled()) {
            orderNode.setStatus(OrderStatus.FILLED);
            log.info("The order: {} is fully filled !", orderNode.getOrderId());
        }
        if (orderState.isFullyFilled()) {
            orderState.setStatus(OrderStatus.FILLED);
            log.info("The order: {} is fully filled !", orderState.getOrderId());
        }
    }

}
