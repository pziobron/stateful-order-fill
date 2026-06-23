package org.example.order.lifecycle.util;

import org.example.order.fix.model.ExecutionReport;

import java.time.ZoneId;

/**
 * Extracts the event timestamp from an ExecutionReport.
 */
public final class EventTimeUtils {

    private EventTimeUtils() {
    }

    /**
     * Extracts the event timestamp from an ExecutionReport.
     *
     * @param report the execution report
     * @return the event timestamp
     */
    public static long extractEventTimestamp(ExecutionReport report) {
        if (report == null || report.getTxnTime() == null) {
            throw new IllegalArgumentException("ExecutionReport TxnTime must not be null");
        }
        return report.getTxnTime()
                .atZone(ZoneId.systemDefault())
                .toInstant()
                .toEpochMilli();
    }
}