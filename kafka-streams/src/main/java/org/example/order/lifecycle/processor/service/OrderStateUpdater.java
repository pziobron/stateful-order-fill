package org.example.order.lifecycle.processor.service;

import org.example.order.fix.model.ExecutionReport;
import org.example.order.lifecycle.model.OrderNode;
import org.springframework.stereotype.Component;

/**
 * Service component responsible for updating the state of an order based on execution reports.
 * <p>
 * This component transforms data from {@link ExecutionReport} objects into corresponding updates
 * for {@link OrderNode} objects. It handles the mapping of trade execution details to the
 * appropriate fields in the order state.
 * </p>
 * <p>
 * The updater is designed to be used within the order processing pipeline to maintain the
 * current state of orders as execution reports are received.
 * </p>
 */
@Component
public class OrderStateUpdater {

    /**
     * Updates the provided {@link OrderNode} with data from the given {@link ExecutionReport}.
     * <p>
     * This method maps the following fields from the execution report to the order state:
     * <ul>
     *   <li>Order ID</li>
     *   <li>Message ID (from execution ID)</li>
     *   <li>Expected quantity</li>
     *   <li>Trade date</li>
     *   <li>Transaction time</li>
     *   <li>Currency</li>
     * </ul>
     *
     * @param executionReport the execution report containing trade execution details (must not be null)
     * @param orderNode      the order node to be updated (must not be null)
     */
    public void update(ExecutionReport executionReport, OrderNode orderNode) {
        orderNode.setOrderId(executionReport.getOrderId());
        orderNode.setMsgId(executionReport.getExecId());
        orderNode.setExpectedQuantity(executionReport.getOrderQuantity());
        orderNode.setTradeDate(executionReport.getTradeDate());
        orderNode.setTransactionTime(executionReport.getTxnTime());
        orderNode.setCurrency(executionReport.getCurrency());
    }

}
