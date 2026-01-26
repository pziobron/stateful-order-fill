package org.example.order.lifecycle.model;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonInclude;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.io.Serializable;
import java.time.LocalDateTime;
import java.time.ZonedDateTime;
import java.util.*;

/**
 * Represents the complete state of an order in the order lifecycle management system.
 * This class tracks all relevant information about an order including its status,
 * fills, and execution details. It is designed to be serializable for distributed processing
 * and includes JSON annotations for easy conversion to/from JSON format.
 *
 * <p>The class maintains the following key information:
 * <ul>
 *   <li>Order identification and metadata (ID, trade date, currency)</li>
 *   <li>Current order status and execution state</li>
 *   <li>Collection of fills and filled quantities</li>
 *   <li>Timestamps for tracking order activity</li>
 * </ul>
 *
 * @see OrderStatus
 * @see Fill
 */
@Data
@NoArgsConstructor
@JsonInclude(JsonInclude.Include.NON_EMPTY)
@JsonIgnoreProperties(ignoreUnknown = true)
public class OrderState implements Serializable {
    /**
     * Unique identifier for the order.
     */
    private String orderId;

    /**
     * Unique identifier for the message associated with this order.
     * This field tracks the original message ID that created or modified the order.
     */
    private String msgId;

    /**
     * The trade date of the order.
     */
    private Date tradeDate;

    /**
     * The timestamp when the order was last updated.
     */
    private LocalDateTime transactionTime;

    /**
     * The currency of the order.
     */
    private String currency;

    /**
     * Current status of the order.
     */
    private OrderStatus status = OrderStatus.NOT_FILLED;

    /**
     * The total quantity expected to be filled for this order.
     */
    private long expectedQuantity;

    /**
     * List of all fills associated with this order.
     */
    private List<Fill> fills = new ArrayList<>();

    /**
     * Map of fill IDs to their respective quantities for quick lookup.
     */
    private Map<String, Long> filledQuantityMap = new HashMap<>();

    /**
     * Timestamp of the last action performed on this order.
     */
    private ZonedDateTime lastActionTimestamp;

    /**
     * Calculates the total filled quantity by summing up all fill quantities.
     *
     * @return the total quantity that has been filled for this order
     */
    public long getFilledQuantity() {
        return filledQuantityMap.values().stream().mapToLong(Long::longValue).sum();
    }

    /**
     * Adds a new fill to this order and updates the filled quantity.
     *
     * @param fill the fill to be added to this order
     * @throws IllegalArgumentException if the fill is null
     */
    public void addFill(Fill fill) {
        fills.add(fill);
        this.filledQuantityMap.put(fill.getFillId(), fill.getQuantity());
    }

    /**
     * Checks if the order has been fully filled.
     *
     * @return true if the order has an expected quantity greater than zero and
     * the filled quantity meets or exceeds the expected quantity
     */
    public boolean isFullyFilled() {
        return expectedQuantity > 0 && getFilledQuantity() >= expectedQuantity;
    }

}
