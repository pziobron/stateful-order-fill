package org.example.order.lifecycle.processor.util;

import org.example.order.fix.model.ExecutionReport;

/**
 * Utility class for working with {@link ExecutionReport} objects in the order lifecycle processor.
 * <p>
 * This class provides static helper methods to determine the type and relationships
 * of execution reports in the order processing pipeline. It helps in identifying
 * whether an execution report represents a parent order, a child order, or a fill.
 * </p>
 * <p>
 * The class is designed to be used as a utility with no state, and all methods are
 * thread-safe static methods.
 * </p>
 *
 * @see org.example.order.fix.model.ExecutionReport
 */
public final class ExecutionReportUtils {

    /**
     * Private constructor to prevent instantiation of this utility class.
     */
    private ExecutionReportUtils() {
        // Prevent instantiation
    }

    /**
     * Determines if the execution report represents a child order.
     * <p>
     * A child order is one that has a parent order ID associated with it.
     * This is typically used in order splitting or algorithmic trading scenarios
     * where a large order is broken down into smaller child orders.
     *
     * @param msg the execution report to check, must not be {@code null}
     * @return {@code true} if the execution report has a non-null parent ID,
     * indicating it's a child order; {@code false} otherwise
     * @throws NullPointerException if the input message is {@code null}
     */
    public static boolean isChild(ExecutionReport msg) {
        return msg.getParentId() != null;
    }

    /**
     * Determines if the execution report represents an original order.
     * <p>
     * An order is identified by having a type of 'O' (Order). This typically
     * represents the initial order before any executions or modifications.
     *
     * @param msg the execution report to check, must not be {@code null}
     * @return {@code true} if the execution report type is 'O',
     * indicating it's an order; {@code false} otherwise
     * @throws NullPointerException if the input message is {@code null}
     */
    public static boolean isOrder(ExecutionReport msg) {
        return msg.getType() == 'O';
    }

    /**
     * Determines if the execution report represents a fill.
     * <p>
     * A fill indicates that an order (or part of an order) has been executed.
     * It is identified by having a type of 'F' (Fill).
     *
     * @param msg the execution report to check, must not be {@code null}
     * @return {@code true} if the execution report type is 'F',
     * indicating it's a fill; {@code false} otherwise
     * @throws NullPointerException if the input message is {@code null}
     */
    public static boolean isFill(ExecutionReport msg) {
        return msg.getType() == 'F';
    }
}