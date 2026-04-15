package org.example.order.lifecycle.model;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonInclude;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.LocalDateTime;

/**
 * Represents execution volume metrics for time-windowed analytics.
 * Used to aggregate execution quantities and timestamps within time windows.
 */
@Data
@NoArgsConstructor
@JsonInclude(JsonInclude.Include.NON_EMPTY)
@JsonIgnoreProperties(ignoreUnknown = true)
public class ExecutionVolumeMetrics {

    /**
     * Total execution quantity within the time window
     */
    private long totalQuantity = 0L;

    /**
     * Number of executions in the time window
     */
    private int executionCount = 0;

    /**
     * Average execution size in the time window
     */
    private double averageExecutionSize = 0.0;

    /**
     * Timestamp of the last execution in this window
     */
    private LocalDateTime lastTimestamp;

    /**
     * Adds a quantity to the metrics and recalculates averages
     */
    public void addQuantity(long quantity) {
        this.totalQuantity += quantity;
        this.executionCount++;
        this.averageExecutionSize = executionCount > 0 ? (double) totalQuantity / executionCount : 0.0;
    }

    /**
     * Updates the last timestamp for this window
     */
    public void updateLastTimestamp(LocalDateTime timestamp) {
        if (this.lastTimestamp == null || timestamp.isAfter(this.lastTimestamp)) {
            this.lastTimestamp = timestamp;
        }
    }

    /**
     * Merges this metrics with another (used for session window merging)
     */
    public void merge(ExecutionVolumeMetrics other) {
        this.totalQuantity += other.totalQuantity;
        this.executionCount += other.executionCount;
        this.averageExecutionSize = executionCount > 0 ? (double) totalQuantity / executionCount : 0.0;
        
        if (other.lastTimestamp != null && 
            (this.lastTimestamp == null || other.lastTimestamp.isAfter(this.lastTimestamp))) {
            this.lastTimestamp = other.lastTimestamp;
        }
    }

}
