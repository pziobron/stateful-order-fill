# Flink Order Lifecycle Processor

This module provides an Apache Flink implementation of the Order Lifecycle Processor and serves as a functional equivalent of the Kafka Streams implementation.

The goal of this module is to demonstrate how the same stateful order matching and lifecycle aggregation logic can be implemented using Flink's DataStream API and managed state.

## Features

* Consumes execution reports from Kafka
* Maintains order lifecycle state using Flink Keyed State
* Supports parent and child order hierarchies
* Handles out-of-order events using event-time processing and watermarks
* Publishes verification records for partitioning and processing validation
* Supports configurable parallelism and checkpointing

## Processing Flow

1. Consume execution reports from Kafka
2. Extract event timestamps from business transaction time (`TxnTime`)
3. Re-key child orders using parent order identifiers
4. Maintain lifecycle state using Flink `ValueState`
5. Update lifecycle state through shared domain services
6. Emit updated `OrderState`
7. Publish verification records to Kafka

## Configuration

The job is configured using JVM system properties.

| Property                     | Default                        |
|------------------------------|--------------------------------|
| kafka.bootstrap.servers       | localhost:9092                 |
| kafka.executions.topic       | org.example.order.executions   |
| kafka.verification.topic     | org.example.order.verification |
| flink.offset.reset           | latest                         |
| flink.group.id               | order-lifecycle-flink          |
| flink.parallelism            | 4                              |
| flink.checkpoint.interval.ms | 10000                          |
| flink.print.lifecycle        | false                          |

`POD_ID` is loaded from an environment variable and defaults to `unknown`.

## Running Locally

```bash
./gradlew :flink:run \
  -Dkafka.bootstrap.servers=localhost:9092 \
  -Dflink.offset.reset=latest
```

## Running on a Flink Cluster

Build the application:

```bash
./gradlew :flink:build
```

Submit the generated JAR:

```bash
flink run \
  -c org.example.order.lifecycle.flink.job.FillOrderFlinkJob \
  flink/build/libs/flink-<version>.jar
```

## Main Components

### FillOrderFlinkJob

Application entry point responsible for:

* Kafka source configuration
* Watermark strategy configuration
* State processing topology
* Kafka sink configuration
* Checkpointing configuration

### OrderStateProcessFunction

Stateful Flink operator that:

* Maintains lifecycle state using `ValueState`
* Delegates business logic to shared domain services
* Produces updated `OrderState` snapshots

### FillOrderService

Shared domain service responsible for:

* Processing execution reports
* Matching fills
* Updating lifecycle status
* Calculating filled quantities

### VerificationRecordSerializationSchema

Produces verification records used to validate key distribution and processing consistency across Flink tasks and Kubernetes pods.

## State Management

Lifecycle state is maintained using Flink managed state:

`ValueState<OrderState>`

This is the Flink equivalent of the Kafka Streams state store used by the Kafka Streams implementation.

The module currently uses Flink managed state through `ValueState`.
The ForSt state backend dependency is included intentionally for future experiments with larger state and checkpointing behaviour, where it can be compared conceptually with Kafka Streams' RocksDB-backed state stores.

## Checkpointing

Checkpointing is enabled to support state recovery:

`env.enableCheckpointing(checkpointIntervalMs);`

Checkpoint interval is configurable through:

```text
flink.checkpoint.interval.ms
```

## Kafka Streams Comparison

| Kafka Streams      | Flink                      |
| ------------------ | -------------------------- |
| KTable Aggregate   | KeyedProcessFunction       |
| State Store        | ValueState                 |
| StreamsBuilder     | StreamExecutionEnvironment |
| Topology           | DataStream Job             |
| Exactly Once v2    | Checkpoint-based Recovery  |
| Materialized Store | Managed State              |

Both implementations share the same domain model and business logic from the `common` module.
