package org.example.order.lifecycle.db.service;

import org.example.order.fix.model.ExecutionReport;
import org.example.order.lifecycle.util.JsonUtils;

import java.util.Objects;

final class ExecutionReportParser {
    private ExecutionReportParser() {}

    static ExecutionReport parse(String payload) {
        ExecutionReport report;
        try {
            report = Objects.requireNonNull(
                    JsonUtils.getObjectMapper().readValue(payload, ExecutionReport.class),
                    "Execution report must not be null");
        } catch (Exception e) {
            throw new IllegalArgumentException("Invalid execution report", e);
        }
        if (report.getType() != 'O' && report.getType() != 'F') {
            throw new IllegalArgumentException("Unsupported report type: " + report.getType());
        }
        return report;
    }
}
