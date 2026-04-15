package org.example.order.lifecycle.processor.stream;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.apache.kafka.common.serialization.Serde;
import org.apache.kafka.common.serialization.Serdes;
import org.apache.kafka.common.utils.Bytes;
import org.apache.kafka.streams.kstream.*;
import org.apache.kafka.streams.state.SessionStore;
import org.apache.kafka.streams.state.WindowStore;
import org.example.order.fix.model.ExecutionReport;
import org.example.order.lifecycle.model.ExecutionVolumeMetrics;
import org.springframework.stereotype.Service;

import java.time.Duration;

import static org.example.order.lifecycle.processor.util.ExecutionReportUtils.isFill;
import static org.example.order.lifecycle.processor.util.ExecutionReportUtils.isOrder;

/**
 * Demonstrates TimeWindow usage for execution volume analytics in fintech.
 * This class shows how to use tumbling and hopping windows for real-time
 * execution flow analysis.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class ExecutionVolumeAnalytics {

    public static final String EXECUTION_VOLUME_STORE = "execution-volume-store";
    public static final String EXECUTION_RATE_STORE = "execution-rate-store";
    public static final String EXECUTION_SESSION_STORE = "execution-session-store";

    private final Serde<ExecutionReport> executionReportSerde;
    private final Serde<ExecutionVolumeMetrics> executionVolumeMetricsSerde;

    /**
     * Adds windowed analytics to the existing stream topology.
     * Demonstrates different window types for fintech use cases.
     */
    public void addWindowedAnalytics(KStream<String, ExecutionReport> executionReportStream) {
        
        // 1. Tumbling Window - Fixed-size, non-overlapping windows
        // Perfect for end-of-day trading summaries
        KTable<Windowed<String>, ExecutionVolumeMetrics> tumblingWindowMetrics = executionReportStream
                .filter((_, report) -> isOrder(report)) // Only orders, not fills
                .groupBy((_, report) -> report.getCurrency(), 
                        Grouped.with(Serdes.String(), executionReportSerde))
                .windowedBy(TimeWindows.ofSizeWithNoGrace(Duration.ofMinutes(5)))
                .aggregate(
                        ExecutionVolumeMetrics::new,
                        (_, report, metrics) -> {
                            metrics.addQuantity(report.getOrderQuantity());
                            metrics.updateLastTimestamp(report.getTxnTime());
                            return metrics;
                        },
                        Materialized.<String, ExecutionVolumeMetrics, WindowStore<Bytes, byte[]>>as(EXECUTION_VOLUME_STORE)
                                .withKeySerde(Serdes.String())
                                .withValueSerde(executionVolumeMetricsSerde)
                );

        // Print tumbling window results for demonstration
        tumblingWindowMetrics.toStream()
                .print(Printed.<Windowed<String>, ExecutionVolumeMetrics>toSysOut()
                        .withLabel("TUMBLING_VOLUME_5MIN"));

        // 2. Hopping Window - Overlapping windows for real-time monitoring
        // Useful for continuous trading dashboards
        KTable<Windowed<String>, ExecutionVolumeMetrics> hoppingWindowMetrics = executionReportStream
                .filter((_, report) -> isFill(report)) // Only fills
                .groupBy((_, report) -> report.getCurrency(),
                        Grouped.with(Serdes.String(), executionReportSerde))
                .windowedBy(TimeWindows.ofSizeWithNoGrace(Duration.ofMinutes(2))
                        .advanceBy(Duration.ofMinutes(1)))
                .aggregate(
                        ExecutionVolumeMetrics::new,
                        (_, report, metrics) -> {
                            metrics.addQuantity(report.getLastQty());
                            metrics.updateLastTimestamp(report.getTxnTime());
                            return metrics;
                        },
                        Materialized.<String, ExecutionVolumeMetrics, WindowStore<Bytes, byte[]>>as(EXECUTION_RATE_STORE)
                                .withKeySerde(Serdes.String())
                                .withValueSerde(executionVolumeMetricsSerde)
                );

        // Print hopping window results for demonstration
        hoppingWindowMetrics.toStream()
                .print(Printed.<Windowed<String>, ExecutionVolumeMetrics>toSysOut()
                        .withLabel("HOPPING_RATE_2MIN"));

        // 3. Session Window - For analyzing trading sessions
        // Groups activity by periods of inactivity
        executionReportStream
                .filter((_, report) -> isFill(report))
                .groupBy((_, report) -> report.getOrderId(),
                        Grouped.with(Serdes.String(), executionReportSerde))
                .windowedBy(SessionWindows.ofInactivityGapWithNoGrace(Duration.ofMinutes(5)))
                .aggregate(
                        ExecutionVolumeMetrics::new,
                        (_, report, metrics) -> {
                            metrics.addQuantity(report.getLastQty());
                            metrics.updateLastTimestamp(report.getTxnTime());
                            return metrics;
                        },
                        (_, metrics1, metrics2) -> {
                            // Merge session windows
                            metrics1.merge(metrics2);
                            return metrics1;
                        },
                        Materialized.<String, ExecutionVolumeMetrics, SessionStore<Bytes, byte[]>>as(EXECUTION_SESSION_STORE)
                                .withKeySerde(Serdes.String())
                                .withValueSerde(executionVolumeMetricsSerde)
                )
                .toStream()
                .print(Printed.<Windowed<String>, ExecutionVolumeMetrics>toSysOut()
                        .withLabel("SESSION_WINDOWS"));
    }
}
