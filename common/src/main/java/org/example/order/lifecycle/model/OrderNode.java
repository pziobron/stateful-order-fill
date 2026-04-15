package org.example.order.lifecycle.model;

import com.fasterxml.jackson.annotation.JsonIgnore;
import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonInclude;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.io.Serializable;
import java.time.LocalDateTime;
import java.util.*;

/**
 * Represents a node in the order hierarchy, containing order information and execution details.
 * <p>
 * This class serves as the base model for orders in the trading system, supporting both
 * parent and child order relationships. It maintains the complete lifecycle information
 * including fills, quantities, and current status.
 * </p>
 * <p>
 * OrderNode supports hierarchical order structures where large orders can be split
 * into smaller child orders. The class provides methods to aggregate fills and quantities
 * across the entire order hierarchy.
 * </p>
 *
 * @see Fill
 * @see OrderStatus
 * @see OrderState
 */
@Data
@NoArgsConstructor
@JsonInclude(JsonInclude.Include.NON_EMPTY)
@JsonIgnoreProperties(ignoreUnknown = true)
public class OrderNode implements Serializable {
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
     * Unique identifier for the parent order.
     */
    private String parentOrderId;

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
     * Map of child orders associated with this order.
     * The key is the child order ID and the value is the OrderNode representing the child order.
     */
    private Map<String, OrderNode> childOrders = new HashMap<>();

    /**
     * Calculates the total filled quantity by summing up all fill quantities.
     *
     * @return the total quantity that has been filled for this order
     */
    public long getFilledQuantity() {
        long filledQuantityForOrder =  filledQuantityMap.values().stream().mapToLong(Long::longValue).sum();
        long filledQuantityForChildOrders = childOrders.values().stream().mapToLong(OrderNode::getFilledQuantity).sum();
        return (filledQuantityForOrder + filledQuantityForChildOrders);
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

    /**
     * Retrieves all fills from child orders recursively.
     * 
     * @return a list containing all fills from all child orders
     */
    @JsonIgnore
    public List<Fill> getChildFills() {
        List<Fill> childFills = new ArrayList<>();
        for (OrderNode childOrder : childOrders.values()) {
            childFills.addAll(childOrder.getFills());
        }
        return childFills;
    }

    /**
     * Retrieves all fills associated with this order, including both direct fills
     * and fills from all child orders.
     * 
     * @return a list containing all fills from this order and all its child orders
     */
    @JsonIgnore
    public List<Fill> getAllFills() {
        List<Fill> allFills = new ArrayList<>(getFills());
        allFills.addAll(getChildFills());
        return allFills;
    }

}
