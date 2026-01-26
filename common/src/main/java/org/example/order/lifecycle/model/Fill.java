package org.example.order.lifecycle.model;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonInclude;
import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.io.Serializable;
import java.time.LocalDateTime;

/**
 * Represents a single execution or fill of an order in the trading system.
 * Each instance of this class records the details of a trade execution,
 * including the price and quantity filled at a specific point in time.
 *
 * <p>This class is designed to be immutable and thread-safe, making it suitable
 * for use in concurrent trading environments. It includes JSON serialization
 * support for easy integration with messaging systems and APIs.</p>
 *
 * @see OrderState
 * @see OrderStatus
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
@JsonInclude(JsonInclude.Include.NON_EMPTY)
@JsonIgnoreProperties(ignoreUnknown = true)
public class Fill implements Serializable {
    /**
     * Unique identifier for this fill.
     * This should be globally unique across all fills in the system.
     */
    private String fillId;

    /**
     * The exact date and time when this fill occurred.
     * This timestamp should be in the timezone of the exchange where the fill occurred.
     */
    private LocalDateTime transactionTime;

    /**
     * The quantity of the instrument that was filled in this execution.
     * Must be a positive number representing the number of units filled.
     */
    private int quantity;

    /**
     * The price at which the fill occurred.
     * This represents the price per unit of the instrument.
     */
    private double price;
}
