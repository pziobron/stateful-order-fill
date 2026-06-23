package org.example.order.lifecycle.flink.job;

import lombok.extern.slf4j.Slf4j;
import org.apache.flink.api.common.eventtime.WatermarkStrategy;
import org.apache.flink.api.common.typeinfo.Types;
import org.apache.flink.connector.base.DeliveryGuarantee;
import org.apache.flink.connector.kafka.sink.KafkaSink;
import org.apache.flink.connector.kafka.source.KafkaSource;
import org.apache.flink.connector.kafka.source.enumerator.initializer.OffsetsInitializer;
import org.apache.flink.streaming.api.environment.StreamExecutionEnvironment;
import org.example.order.fix.model.ExecutionReport;
import org.example.order.lifecycle.flink.config.OrderLifecycleFlinkConfig;
import org.example.order.lifecycle.flink.function.OrderStateProcessFunction;
import org.example.order.lifecycle.flink.model.ProcessedOrderState;
import org.example.order.lifecycle.flink.serialization.JacksonDeserializationSchema;
import org.example.order.lifecycle.flink.serialization.VerificationRecordSerializationSchema;
import org.example.order.lifecycle.model.OrderState;
import org.example.order.lifecycle.util.EventTimeUtils;

import java.time.Duration;

import static org.example.order.lifecycle.util.ExecutionReportUtils.isChild;

/**
 * Apache Flink implementation of the Order Lifecycle Processor.
 *
 * <p>The job consumes execution reports from Kafka, maintains order lifecycle
 * state using Flink managed keyed state, and publishes verification records
 * used to validate partition distribution and processing ownership.</p>
 *
 * <p>The processing flow is:</p>
 *
 * <ol>
 *     <li>Consume execution reports from Kafka</li>
 *     <li>Assign business event timestamps and generate watermarks</li>
 *     <li>Group records by logical order hierarchy identifier</li>
 *     <li>Maintain lifecycle state using Flink {@code ValueState}</li>
 *     <li>Attach runtime TaskManager and subtask metadata</li>
 *     <li>Publish verification records to Kafka</li>
 * </ol>
 *
 * @see OrderStateProcessFunction
 */
@Slf4j
public class FillOrderFlinkJob {

    public static final String ORDER_STATE_STORE = "order-state-store";

    public static void main(String[] args) throws Exception {
        OrderLifecycleFlinkConfig config =
                OrderLifecycleFlinkConfig.fromSystemProperties();

        log.info(
                "Starting job: bootstrapServers={}, executionsTopic={}, verificationTopic={}, " +
                        "offsetReset={}, groupId={}, parallelism={}, checkpointIntervalMs={}, " +
                        "commitOffsetsOnCheckpoint={}",
                config.bootstrapServers(),
                config.executionReportsTopic(),
                config.verificationTopic(),
                config.offsetReset(),
                config.groupId(),
                config.parallelism(),
                config.checkpointIntervalMs(),
                config.commitOffsetsOnCheckpoint()
        );

        StreamExecutionEnvironment env =
                StreamExecutionEnvironment.getExecutionEnvironment();

        env.enableCheckpointing(config.checkpointIntervalMs());
        env.setParallelism(config.parallelism());

        KafkaSource<ExecutionReport> source =
                KafkaSource.<ExecutionReport>builder()
                        .setBootstrapServers(config.bootstrapServers())
                        .setTopics(config.executionReportsTopic())
                        .setGroupId(config.groupId())
                        .setStartingOffsets(
                                offsetsInitializer(config.offsetReset())
                        )
                        .setValueOnlyDeserializer(
                                new JacksonDeserializationSchema<>(
                                        ExecutionReport.class
                                )
                        )
                        .setProperty(
                                "commit.offsets.on.checkpoint",
                                Boolean.toString(
                                        config.commitOffsetsOnCheckpoint()
                                )
                        )
                        .build();

        var executionReports = env.fromSource(
                        source,
                        WatermarkStrategy
                                .<ExecutionReport>forBoundedOutOfOrderness(
                                        Duration.ofSeconds(5)
                                )
                                .withTimestampAssigner(
                                        (report, ignored) ->
                                                EventTimeUtils
                                                        .extractEventTimestamp(report)
                                )
                                .withIdleness(Duration.ofSeconds(30)),
                        "execution-reports-source"
                )
                .name("execution-reports-source");

        var processedOrderStates = executionReports
                .keyBy(
                        report -> isChild(report)
                                ? report.getParentId()
                                : report.getOrderId()
                )
                .process(
                        new OrderStateProcessFunction(),
                        Types.GENERIC(ProcessedOrderState.class)
                )
                .name("order-state-processor")
                .uid("order-state-processor");

        if (config.printLifecycle()) {
            processedOrderStates
                    .map(
                            ProcessedOrderState::orderState,
                            Types.GENERIC(OrderState.class)
                    )
                    .name("extract-order-state")
                    .print("LIFECYCLES");
        }

        KafkaSink<ProcessedOrderState> verificationSink =
                KafkaSink.<ProcessedOrderState>builder()
                        .setBootstrapServers(config.bootstrapServers())
                        .setDeliveryGuarantee(
                                DeliveryGuarantee.AT_LEAST_ONCE
                        )
                        .setRecordSerializer(
                                new VerificationRecordSerializationSchema(
                                        config.verificationTopic()
                                )
                        )
                        .build();

        processedOrderStates
                .sinkTo(verificationSink)
                .name("verification-sink")
                .uid("verification-sink");

        env.execute("Order Lifecycle Processor");
    }

    private static OffsetsInitializer offsetsInitializer(
            String offsetReset
    ) {
        return switch (offsetReset.toLowerCase()) {
            case "earliest" -> OffsetsInitializer.earliest();
            case "latest" -> OffsetsInitializer.latest();
            default -> throw new IllegalArgumentException(
                    "Unsupported flink.offset.reset: "
                            + offsetReset
                            + ". Supported values: earliest, latest"
            );
        };
    }
}
