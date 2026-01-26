package org.example.order.lifecycle.model;

/**
 * Represents the various states that an order can be in during its lifecycle.
 * This enum is used to track the current status of an order in the order management system.
 *
 * <p>The order lifecycle typically progresses from NEW to FILLED, with additional
 * statuses potentially added in the future to represent other states such as
 * PARTIALLY_FILLED, CANCELLED, or REJECTED if needed.</p>
 *
 * @see OrderState
 */
public enum OrderStatus {
    /**
     * The order has been created but not yet filled.
     * This is the initial state of all new orders.
     */
    NEW,

    /**
     * The order has been completely filled.
     * No further executions are expected for this order.
     *
     * @see OrderState#isFullyFilled()
     */
    FILLED
}
