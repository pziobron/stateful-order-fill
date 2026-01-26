package org.example.order.lifecycle.processor.stream;

import lombok.extern.slf4j.Slf4j;
import org.apache.kafka.common.serialization.Serdes;
import org.apache.kafka.streams.StreamsBuilder;
import org.apache.kafka.streams.TestInputTopic;
import org.apache.kafka.streams.TopologyTestDriver;
import org.apache.kafka.streams.state.KeyValueStore;
import org.example.order.fix.model.ExecutionReport;
import org.example.order.lifecycle.model.OrderState;
import org.example.order.lifecycle.model.OrderStatus;
import org.example.order.lifecycle.processor.service.FillOrderService;
import org.example.order.lifecycle.processor.service.OrderStateUpdater;
import org.example.order.lifecycle.processor.util.JsonUtils;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;
import org.mockito.Spy;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.kafka.support.serializer.JsonSerializer;

import java.io.IOException;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.Properties;
import java.util.stream.Stream;

import static org.example.order.lifecycle.processor.stream.FillOrderStream.ORDER_STATE_STORE;
import static org.example.order.lifecycle.processor.util.JsonUtils.createJsonSerde;
import static org.example.order.lifecycle.processor.utils.TestUtils.generateExecutionReportMessage;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.mockito.Mockito.*;

@Slf4j
@ExtendWith(MockitoExtension.class)
public class FillOrderStreamTest {

    @Spy
    private StreamsBuilder streamsBuilder;

    private TestInputTopic<String, ExecutionReport> inputTopic;

    private TopologyTestDriver testDriver;

    private FillOrderStream fillOrderStream;

    @BeforeEach
    public void setUp() {
        Properties kafkaStreamProperties = new Properties();
        kafkaStreamProperties.put("application.id", "test-application-id=" + System.nanoTime());
        kafkaStreamProperties.put("bootstrap.servers", "localhost:9092");
        kafkaStreamProperties.put("state.dir",
                System.getProperty("java.io.tmpdir") + "/kafka-streams" + System.nanoTime());

        fillOrderStream = new FillOrderStream(
                streamsBuilder,
                createJsonSerde(ExecutionReport.class),
                createJsonSerde(OrderState.class),
                kafkaStreamProperties,
                new FillOrderService(new OrderStateUpdater()));
        fillOrderStream.executionReportsTopic = "test-topic";

        fillOrderStream.startKafkaStream();

        //Create test driver
        testDriver = new TopologyTestDriver(streamsBuilder.build(), kafkaStreamProperties);

        //Create input topic
        JsonSerializer<ExecutionReport> serializer = new JsonSerializer<>(
                JsonUtils.getObjectMapper().constructType(ExecutionReport.class),
                JsonUtils.getObjectMapper());

        try (var stringSerde = Serdes.String()) {
            inputTopic =
                    testDriver.createInputTopic(fillOrderStream.executionReportsTopic, stringSerde.serializer(),
                            serializer);
        }
    }

    @AfterEach
    public void tearDown() {
        if (testDriver != null) {
            testDriver.close();
        }
    }

    /**
     * Tests simple order fill scenarios with different message ordering.
     * Verifies that regardless of the order in which execution reports are received,
     * the order state is correctly updated to FILLED status with proper fill tracking.
     * <br/>
     * Tests three scenarios:
     * - Normal order: order -> fill1 -> fill2
     * - Out of order 1: fill1 -> order -> fill2
     * - Out of order 2: fill1 -> fill2 -> order
     *
     * @param messageOrder the order in which messages should be processed
     * @throws IOException if test data files cannot be read
     */
    @ParameterizedTest
    @MethodSource("simpleOrderFillScenarios")
    public void testSimpleOrderFill(List<String> messageOrder) throws IOException {
        // Arrange
        String prefix = "test1";
        String orderId = prefix + "-order1";
        String inputDir = "jsonData/fillOrder/fullyFilled/2_fills/";

        ExecutionReport order = generateExecutionReportMessage(prefix, inputDir + "order.json");
        ExecutionReport fill1 = generateExecutionReportMessage(prefix, inputDir + "fill01.json");
        ExecutionReport fill2 = generateExecutionReportMessage(prefix, inputDir + "fill02.json");

        // Create message map for easy lookup
        var messageMap = Map.of(
            "order", order,
            "fill1", fill1,
            "fill2", fill2
        );

        // Act
        for (String messageType : messageOrder) {
            pipeInput(messageMap.get(messageType));
        }

        // Assert
        verifySimpleOrderFill(orderId, order, fill1, fill2);
    }

    /**
     * Provides test data for different message ordering scenarios.
     * Each list represents the order in which execution reports should be processed.
     * 
     * @return stream of message order scenarios
     */
    static Stream<List<String>> simpleOrderFillScenarios() {
        return Stream.of(
            Arrays.asList("order", "fill1", "fill2"),  // Normal order
            Arrays.asList("fill1", "order", "fill2"),  // Out of order 1
            Arrays.asList("fill1", "fill2", "order")   // Out of order 2
        );
    }

    private void verifySimpleOrderFill(String orderId,
                                       ExecutionReport order, ExecutionReport fill1, ExecutionReport fill2) {
        verify(streamsBuilder, times(1)).stream(
                eq(fillOrderStream.executionReportsTopic), any());
        KeyValueStore<String, OrderState> store = testDriver.getKeyValueStore(ORDER_STATE_STORE);

        // Check if order state is stored in the store
        OrderState orderState = store.get(orderId);
        assertNotNull(orderState, "OrderState should not be null");

        // Check if order state is correct
        assertEquals(orderId, orderState.getOrderId());
        assertEquals(order.getExecId(), orderState.getMsgId());
        assertEquals(order.getTradeDate(), orderState.getTradeDate());
        assertEquals(order.getTxnTime(), orderState.getTransactionTime());
        assertEquals(order.getCurrency(), orderState.getCurrency());
        assertEquals(order.getOrderQuantity(), orderState.getExpectedQuantity());
        assertEquals(order.getOrderQuantity(), orderState.getFilledQuantity());
        assertEquals(OrderStatus.FILLED, orderState.getStatus()); // check if order is fully filled

        // Check if fills are correct
        assertEquals(2, orderState.getFills().size());
        assertEquals(fill1.getExecId(), orderState.getFills().get(0).getFillId());
        assertEquals(fill2.getExecId(), orderState.getFills().get(1).getFillId());
        assertEquals(2, orderState.getFilledQuantityMap().size());
        assertNotNull(orderState.getFilledQuantityMap().get(fill1.getExecId()));
        assertEquals(fill1.getLastQty(), orderState.getFilledQuantityMap().get(fill1.getExecId()));
        assertNotNull(orderState.getFilledQuantityMap().get(fill2.getExecId()));
        assertEquals(fill2.getLastQty(), orderState.getFilledQuantityMap().get(fill2.getExecId()));

        // Check if last action timestamp is not null
        assertNotNull(orderState.getLastActionTimestamp());
    }

    private void pipeInput(ExecutionReport message) {
        inputTopic.pipeInput(message.getOrderId(), message);
    }
}
