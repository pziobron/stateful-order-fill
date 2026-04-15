package org.example.order.lifecycle.processor.stream;

import org.apache.kafka.common.serialization.Serdes;
import org.apache.kafka.streams.TestInputTopic;
import org.apache.kafka.streams.TopologyTestDriver;
import org.apache.kafka.streams.kstream.Consumed;
import org.apache.kafka.streams.state.WindowStore;
import org.apache.kafka.streams.state.SessionStore;
import org.example.order.fix.model.ExecutionReport;
import org.example.order.lifecycle.model.ExecutionVolumeMetrics;
import org.example.order.lifecycle.processor.util.BusinessTimestampExtractor;
import org.example.order.lifecycle.processor.util.JsonUtils;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.time.LocalDateTime;
import java.time.Instant;
import java.util.Date;
import java.util.List;
import java.util.ArrayList;

import static org.junit.jupiter.api.Assertions.*;
import static org.example.order.lifecycle.processor.stream.ExecutionVolumeAnalytics.*;

/**
 * Test class demonstrating TimeWindow functionality for order analytics.
 * This test shows how different window types work in a fintech context.
 */
class TimeWindowAnalyticsTest {

    private TopologyTestDriver testDriver;
    private TestInputTopic<String, ExecutionReport> inputTopic;

    @BeforeEach
    void setUp() {
        // Setup test topology with TimeWindow analytics
        var streamsBuilder = new org.apache.kafka.streams.StreamsBuilder();
        ExecutionVolumeAnalytics executionVolumeAnalytics = new ExecutionVolumeAnalytics(
                JsonUtils.createJsonSerde(ExecutionReport.class),
                JsonUtils.createJsonSerde(ExecutionVolumeMetrics.class)
        );
        
        // Create the input topic first
        var executionReportSerde = JsonUtils.createJsonSerde(ExecutionReport.class);
        
        // Add the analytics to the stream BEFORE building the topology
        // Use the same timestamp extractor as production to use business timestamp (TxnTime) for windowing
        try (var stringSerde = Serdes.String()) {
            var executionReportStream = streamsBuilder.stream("test-executions",
                    Consumed.with(stringSerde, executionReportSerde)
                            .withTimestampExtractor(new BusinessTimestampExtractor()));
            executionVolumeAnalytics.addWindowedAnalytics(executionReportStream);
        }
        
        // Build topology with analytics included
        var topology = streamsBuilder.build();
        testDriver = new TopologyTestDriver(topology);
        
        // Create input topic
        try (var stringSerde = Serdes.String()) {
            inputTopic = testDriver.createInputTopic("test-executions", 
                    stringSerde.serializer(), 
                    executionReportSerde.serializer());
        }
    }

    @AfterEach
    void tearDown() {
        if (testDriver != null) {
            testDriver.close();
        }
    }

    @Test
    void testTumblingWindowAggregation() {
        // Create test orders with timestamps aligned to 5-minute windows
        LocalDateTime baseTime = LocalDateTime.now()
                .withMinute(0)
                .withSecond(0)
                .withNano(0);
        
        ExecutionReport order1 = createTestOrder("order1", "USD", 100, baseTime); // 14:00:00
        ExecutionReport order2 = createTestOrder("order2", "USD", 200, baseTime.plusMinutes(2)); // 14:02:00 - same window
        ExecutionReport order3 = createTestOrder("order3", "EUR", 150, baseTime.plusMinutes(6)); // 14:06:00 - next window, different currency
        
        // Send orders to input topic
        inputTopic.pipeInput("order1", order1);
        inputTopic.pipeInput("order2", order2);
        inputTopic.pipeInput("order3", order3);
        
        // Advance time to trigger window computation
        testDriver.advanceWallClockTime(Duration.ofMinutes(10));
        
        // Verify tumbling window results by querying the state store
        WindowStore<String, ExecutionVolumeMetrics> tumblingStore = testDriver.getWindowStore(EXECUTION_VOLUME_STORE);
        
        // Query all windows for USD key - expand the time range to ensure we capture the window
        Instant startTime = baseTime.minusMinutes(10).atZone(java.time.ZoneId.systemDefault()).toInstant();
        Instant endTime = baseTime.plusMinutes(30).atZone(java.time.ZoneId.systemDefault()).toInstant();
        
        List<ExecutionVolumeMetrics> usdMetrics = new ArrayList<>();
        try (var iterator = tumblingStore.fetch("USD", startTime, endTime)) {
            while (iterator.hasNext()) {
                usdMetrics.add(iterator.next().value);
            }
        }
        
        // Should have at least one window with USD orders
        assertFalse(usdMetrics.isEmpty(), "Should have tumbling windows for USD");

        // Find the first window (14:00-14:05) - should contain order1 and order2
        ExecutionVolumeMetrics firstWindow = usdMetrics.stream()
                .filter(m -> m.getTotalQuantity() >= 300 && m.getTotalQuantity() < 450) // 100 + 200 = 300
                .findFirst()
                .orElse(null);

        // Query EUR windows for order3
        List<ExecutionVolumeMetrics> eurMetrics = new ArrayList<>();
        try (var iterator = tumblingStore.fetch("EUR", startTime, endTime)) {
            while (iterator.hasNext()) {
                eurMetrics.add(iterator.next().value);
            }
        }
        
        // Should have at least one window with EUR orders
        assertFalse(eurMetrics.isEmpty(), "Should have tumbling windows for EUR");
        
        // Find the second window (14:05-14:10) - should contain order3 in EUR
        ExecutionVolumeMetrics secondWindow = eurMetrics.stream()
                .filter(m -> m.getTotalQuantity() == 150) // Just order3
                .findFirst()
                .orElse(null);

        // Verify first window contains order1 and order2
        assertNotNull(firstWindow, "Should find first tumbling window with order1 and order2");
        assertEquals(300, firstWindow.getTotalQuantity(), "First window should contain exactly 300 units (100 + 200)");
        assertEquals(2, firstWindow.getExecutionCount(), "First window should contain exactly 2 orders");
        assertTrue(firstWindow.getAverageExecutionSize() > 0,
                "Average order size should be positive");

        // Verify second window contains order3 (EUR)
        assertNotNull(secondWindow, "Should find second tumbling window with EUR order3");
        assertEquals(150, secondWindow.getTotalQuantity(), "Second window should contain exactly 150 units (order3)");
        assertEquals(1, secondWindow.getExecutionCount(), "Second window should contain exactly 1 order");
        assertTrue(secondWindow.getAverageExecutionSize() > 0,
                "Average order size should be positive");
    }

    @Test
    void testHoppingWindowOverlap() {
        // Create fills with timestamps aligned to hopping windows
        LocalDateTime baseTime = LocalDateTime.now()
                .withMinute(0)
                .withSecond(0)
                .withNano(0);

        // Create fills that should appear in multiple hopping windows
        ExecutionReport fill1 = createTestFill("fill1", 50, baseTime); // 14:00:00
        ExecutionReport fill2 = createTestFill("fill2", 75, baseTime.plusMinutes(1)); // 14:01:00

        inputTopic.pipeInput("fill1", fill1);
        inputTopic.pipeInput("fill2", fill2);

        // Advance time to trigger multiple hopping windows
        testDriver.advanceWallClockTime(Duration.ofMinutes(3));

        // Verify hopping window results by querying the state store
        WindowStore<String, ExecutionVolumeMetrics> hoppingStore = testDriver.getWindowStore(EXECUTION_RATE_STORE);

        // Query all windows for EUR key
        Instant startTime = baseTime.minusMinutes(1).atZone(java.time.ZoneId.systemDefault()).toInstant();
        Instant endTime = baseTime.plusMinutes(15).atZone(java.time.ZoneId.systemDefault()).toInstant();

        List<ExecutionVolumeMetrics> eurMetrics = new ArrayList<>();
        try (var iterator = hoppingStore.fetch("EUR", startTime, endTime)) {
            while (iterator.hasNext()) {
                eurMetrics.add(iterator.next().value);
            }
        }

        // Should have at least one window with EUR fills
        assertFalse(eurMetrics.isEmpty(), "Should have hopping windows for EUR");

        // Find a window with our aggregated fill data
        ExecutionVolumeMetrics aggregatedWindow = eurMetrics.stream()
            .filter(m -> m.getTotalQuantity() >= 125) // Should contain both fills (50 + 75)
            .findFirst()
            .orElse(null);

        assertNotNull(aggregatedWindow, "Should find a hopping window with EUR fill data");
        assertTrue(aggregatedWindow.getTotalQuantity() >= 125,
                "Hopping window should contain at least 125 units (50 + 75)");
        assertTrue(aggregatedWindow.getExecutionCount() >= 2,
                "Hopping window should contain at least 2 fills");
        assertTrue(aggregatedWindow.getAverageExecutionSize() > 0,
                "Average fill size should be positive");
    }

    @Test
    void testSessionWindowInactivity() {
        LocalDateTime baseTime = LocalDateTime.now();
        
        // Create fills for the same order to test session window behavior
        ExecutionReport fill1 = createTestFill("fill1", 50, baseTime);
        ExecutionReport fill2 = createTestFill("fill2", 75, baseTime.plusMinutes(2));
        
        inputTopic.pipeInput("fill1", fill1);
        inputTopic.pipeInput("fill2", fill2);
        
        // Advance time beyond inactivity gap to close session
        testDriver.advanceWallClockTime(Duration.ofMinutes(6));
        
        // Create new fill to start new session
        ExecutionReport fill3 = createTestFill("fill3", 100, baseTime.plusMinutes(8));
        inputTopic.pipeInput("fill3", fill3);
        
        // Advance time to process the new session
        testDriver.advanceWallClockTime(Duration.ofMinutes(2));
        
        // Verify session window results by querying the state store
        SessionStore<String, ExecutionVolumeMetrics> sessionStore = testDriver.getSessionStore(EXECUTION_SESSION_STORE);
        
        // Query sessions for our parent-order key
        Instant startTime = baseTime.minusMinutes(1).atZone(java.time.ZoneId.systemDefault()).toInstant();
        Instant endTime = baseTime.plusMinutes(15).atZone(java.time.ZoneId.systemDefault()).toInstant();
        
        List<ExecutionVolumeMetrics> sessionMetrics = new ArrayList<>();
        try (var iterator = sessionStore.findSessions("parent-order", startTime, endTime)) {
            while (iterator.hasNext()) {
                sessionMetrics.add(iterator.next().value);
            }
        }
        
        // Should have at least one session with aggregated metrics
        assertFalse(sessionMetrics.isEmpty(), "Should have at least one session window for parent-order");
        
        // Verify session window contains aggregated fill data
        // We expect to see two sessions: one with fill1+fill2, another with fill3
        
        // Find the first session (should contain fill1+fill2)
        ExecutionVolumeMetrics sessionAggregation = sessionMetrics.getFirst();
        assertTrue(sessionAggregation.getTotalQuantity() >= 125,
                "Session window should contain at least 125 units (50 + 75)");
        assertTrue(sessionAggregation.getExecutionCount() >= 2,
                "Session window should contain at least 2 fills");
        assertTrue(sessionAggregation.getAverageExecutionSize() > 0,
                "Average fill size should be positive");
        
        // Verify that we can create and query the session store
        assertNotNull(sessionStore, "Session store should be accessible");
    }

    private ExecutionReport createTestOrder(String orderId, String currency, long quantity, LocalDateTime timestamp) {
        ExecutionReport report = new ExecutionReport();
        report.setOrderId(orderId);
        report.setCurrency(currency);
        report.setOrderQuantity(quantity);
        report.setType('O'); // Order
        report.setTxnTime(timestamp);
        report.setTradeDate(Date.from(timestamp.atZone(java.time.ZoneId.systemDefault()).toInstant()));
        return report;
    }

    private ExecutionReport createTestFill(String execId, long quantity, LocalDateTime timestamp) {
        ExecutionReport report = new ExecutionReport();
        report.setExecId(execId);
        report.setOrderId("parent-order");
        report.setCurrency("EUR");
        report.setLastQty(quantity);
        report.setType('F'); // Fill
        report.setTxnTime(timestamp);
        report.setTradeDate(Date.from(timestamp.atZone(java.time.ZoneId.systemDefault()).toInstant()));
        return report;
    }
}
