package org.example.order.lifecycle.processor.stream;

import lombok.extern.slf4j.Slf4j;
import org.apache.kafka.common.serialization.Serdes;
import org.apache.kafka.streams.StreamsBuilder;
import org.apache.kafka.streams.TestInputTopic;
import org.apache.kafka.streams.TopologyTestDriver;
import org.apache.kafka.streams.state.KeyValueStore;
import org.example.order.fix.model.ExecutionReport;
import org.example.order.lifecycle.model.OrderNode;
import org.example.order.lifecycle.model.OrderState;
import org.example.order.lifecycle.model.OrderStatus;
import org.example.order.lifecycle.processor.service.FillOrderService;
import org.example.order.lifecycle.processor.service.OrderStateUpdater;
import org.example.order.lifecycle.processor.util.JsonUtils;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
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
import static org.junit.jupiter.api.Assertions.*;
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
        verifyOrderNode(orderState, order);

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

    @ParameterizedTest
    @MethodSource("parentOrderFillScenarios")
    public void testParentOrderFill(List<String> messageOrder) throws IOException {
        // Arrange
        String prefix = "test1";
        String inputDir = "jsonData/fillOrder/fullyFilled/parent_order/";

        ExecutionReport parentOrder = generateExecutionReportMessage(prefix, inputDir + "parent_order.json");
        ExecutionReport childOrder1 = generateExecutionReportMessage(prefix, inputDir + "child_order1.json");
        ExecutionReport childOrder2 = generateExecutionReportMessage(prefix, inputDir + "child_order2.json");
        ExecutionReport fill1ChildOrder1 = generateExecutionReportMessage(prefix, inputDir + "fill01_child_order1.json");
        ExecutionReport fill2ChildOrder2 = generateExecutionReportMessage(prefix, inputDir + "fill02_child_order2.json");

        // Create message map for easy lookup
        var messageMap = Map.of(
                "parentOrder", parentOrder,
                "childOrder1", childOrder1,
                "childOrder2", childOrder2,
                "fill1ChildOrder1", fill1ChildOrder1,
                "fill2ChildOrder2", fill2ChildOrder2
        );

        // Act
        for (String messageType : messageOrder) {
            pipeInput(messageMap.get(messageType));
        }

        // Assert
        verifyParentOrderFill(prefix, messageMap);

    }

    /**
     * Provides test data for different message ordering scenarios.
     * Each list represents the order in which execution reports should be processed.
     *
     * @return stream of message order scenarios
     */
    static Stream<List<String>> parentOrderFillScenarios() {
        return Stream.of(
                Arrays.asList("parentOrder", "childOrder1", "childOrder2", "fill1ChildOrder1", "fill2ChildOrder2"),  // Normal order
                Arrays.asList("fill2ChildOrder2", "childOrder1", "fill1ChildOrder1", "parentOrder", "childOrder2" ),  // Out of order 1
                Arrays.asList("childOrder2", "fill1ChildOrder1", "childOrder1", "fill2ChildOrder2", "parentOrder" ),  // Out of order 2
                Arrays.asList("childOrder2", "childOrder1", "parentOrder", "fill2ChildOrder2", "fill1ChildOrder1")  // Out of order 3
        );
    }

    private void verifyParentOrderFill(String orderIdPrefix, Map<String, ExecutionReport> messageMap) {
        String parentOrderId = orderIdPrefix + "-parent-order1";
        String childOrder1Id = orderIdPrefix + "-child-order1";
        String childOrder2Id = orderIdPrefix + "-child-order2";

        verify(streamsBuilder, times(1)).stream(
                eq(fillOrderStream.executionReportsTopic), any());
        KeyValueStore<String, OrderState> store = testDriver.getKeyValueStore(ORDER_STATE_STORE);

        ExecutionReport parentOrder = messageMap.get("parentOrder");
        ExecutionReport childOrder1 = messageMap.get("childOrder1");
        ExecutionReport childOrder2 = messageMap.get("childOrder2");
        ExecutionReport fill1ChildOrder1 = messageMap.get("fill1ChildOrder1");
        ExecutionReport fill2ChildOrder2 = messageMap.get("fill2ChildOrder2");

        // Check if parent order state is stored in the store (stores order hierarchy)
        OrderState parentOrderState = store.get(parentOrderId);
        assertNotNull(parentOrderState, "OrderState should not be null");
        // Make sure there is no state stored for child orders
        assertNull(store.get(childOrder1Id), "There should not be any state stored for child order 1");
        assertNull(store.get(childOrder2Id), "There should not be any state stored for child order 2");

        // Check if parent order state is correct
        verifyOrderNode(parentOrderState, parentOrder);
        assertEquals(2, parentOrderState.getAllFills().size());
        assertTrue(parentOrderState.getAllFills().stream()
                .anyMatch(fill -> fill.getFillId().equals(fill1ChildOrder1.getExecId())));
        assertTrue(parentOrderState.getAllFills().stream()
                .anyMatch(fill -> fill.getFillId().equals(fill2ChildOrder2.getExecId())));

        // Check if child order 1 state is correct
        OrderNode childOrder1State = parentOrderState.getChildOrders().get(childOrder1Id);
        assertNotNull(childOrder1State);
        verifyOrderNode(childOrder1State, childOrder1);

        // Check if fills are correct for child order 1
        verifySingleFill(childOrder1State, fill1ChildOrder1);

        // Check if child order 2 state is correct
        OrderNode childOrder2State = parentOrderState.getChildOrders().get(childOrder2Id);
        assertNotNull(childOrder2State);
        verifyOrderNode(childOrder2State, childOrder2);

        // Check if fills are correct for child order 2
        verifySingleFill(childOrder2State, fill2ChildOrder2);

        // Check if last action timestamp is not null
        assertNotNull(parentOrderState.getLastActionTimestamp());
    }

    private void verifyOrderNode(OrderNode orderNode, ExecutionReport order) {
        assertEquals(order.getOrderId(), orderNode.getOrderId());
        assertEquals(order.getExecId(), orderNode.getMsgId());
        assertEquals(order.getTradeDate(), orderNode.getTradeDate());
        assertEquals(order.getTxnTime(), orderNode.getTransactionTime());
        assertEquals(order.getCurrency(), orderNode.getCurrency());
        assertEquals(order.getOrderQuantity(), orderNode.getExpectedQuantity());
        assertEquals(order.getOrderQuantity(), orderNode.getFilledQuantity());
        assertEquals(OrderStatus.FILLED, orderNode.getStatus()); // check if order is fully filled
    }

    private void verifySingleFill(OrderNode orderNode, ExecutionReport fill) {
        assertEquals(1, orderNode.getFills().size());
        assertEquals(fill.getExecId(), orderNode.getFills().getFirst().getFillId());
        assertNotNull(orderNode.getFilledQuantityMap().get(fill.getExecId()));
        assertEquals(fill.getLastQty(), orderNode.getFilledQuantityMap().get(fill.getExecId()));
    }

    private void pipeInput(ExecutionReport message) {
        inputTopic.pipeInput(message.getOrderId(), message);
    }
}
