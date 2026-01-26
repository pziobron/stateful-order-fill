package org.example.order.fix.model;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonInclude;
import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.io.Serializable;
import java.time.LocalDateTime;
import java.util.Date;

/**
 * Represents an execution report for an order in a trading system.
 * This class is used to track the status and details of order executions,
 * including trade information, quantities, and pricing.
 *
 * <p>The class is designed to be serializable for network transmission and storage,
 * and includes JSON serialization/deserialization support through Jackson annotations.</p>
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
@JsonInclude(JsonInclude.Include.NON_EMPTY)
@JsonIgnoreProperties(ignoreUnknown = true)
public class ExecutionReport implements Serializable {
    /**
     * Unique identifier for the order
     */
    private String orderId;

    /**
     * Identifier of the parent order, if this is part of a larger order
     */
    private String parentId;

    /**
     * Unique identifier for this execution report
     */
    private String execId;

    /**
     * Type of the execution report (e.g., '0' = New, '1' = Partial Fill, '2' = Fill, etc.)
     */
    private Character type;

    /**
     * Currency of the order (e.g., "USD", "EUR")
     */
    private String currency;

    /**
     * Date when the trade occurred
     */
    private Date tradeDate;

    /**
     * Date and time when the transaction occurred, with timezone information
     */
    private LocalDateTime txnTime;

    /**
     * Total quantity of the order
     */
    private int orderQuantity;

    /**
     * Quantity of shares bought/sold in this execution
     */
    private int lastQty;

    /**
     * Price at which the execution occurred
     */
    private double lastPx;
}
