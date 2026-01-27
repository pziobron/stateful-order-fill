package org.example.order.lifecycle.model;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonInclude;
import lombok.Data;
import lombok.EqualsAndHashCode;
import lombok.NoArgsConstructor;

import java.time.ZonedDateTime;


/**
 * Represents the state of an order in the trading system.
 * <p>
 * This class extends {@link OrderNode} to include additional state information
 * such as the timestamp of the last action performed on the order. It maintains
 * all the order information including fills, child orders, and execution details.
 * </p>
 * <p>
 * The OrderState is used as the value object in the Kafka Streams state store
 * to maintain the current state of each order throughout its lifecycle.
 * </p>
 *
 * @see OrderNode
 * @see OrderStatus
 */
@Data
@NoArgsConstructor
@EqualsAndHashCode(callSuper = true)
@JsonInclude(JsonInclude.Include.NON_EMPTY)
@JsonIgnoreProperties(ignoreUnknown = true)
public class OrderState extends OrderNode {

    /**
     * Timestamp of the last action performed on this order.
     * This includes order creation, modifications, fills, or any other state changes.
     * The timestamp is in UTC timezone and includes the full date and time information.
     */
    private ZonedDateTime lastActionTimestamp;

}
