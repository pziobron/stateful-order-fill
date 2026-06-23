# Flink Order Lifecycle Processor

This module provides an Apache Flink implementation of the Order Lifecycle Processor and serves as a functional equivalent of the Kafka Streams implementation.

The goal of this module is to demonstrate how the same stateful order matching and lifecycle aggregation logic can be implemented using Flink's DataStream API and managed state.

## Features

* Consumes execution reports from Kafka
* Maintains order lifecycle state using Flink keyed state
* Supports parent and child order hierarchies 
* Assigns business event timestamps and generates watermarks for future event-time and late-event experiments 
* Processes out-of-order lifecycle events using order-independent shared domain logic
* Publishes verification records for partitioning and processing validation
* Supports configurable parallelism and checkpointing
* Can be executed locally through Gradle or submitted to a Docker Compose Flink cluster
* Shares domain models and business logic with the Kafka Streams implementation

## Processing Flow

1. Consume execution reports from Kafka
2. Extract event timestamps from business transaction time (`TxnTime`)
3. Re-key child orders using parent order identifiers
4. Maintain lifecycle state using Flink `ValueState`
5. Update lifecycle state through shared domain services
6. Emit updated `OrderState`
7. Publish verification records to Kafka

## Prerequisites

The examples assume that the commands are executed from the project root.

Required tools:

* Java 17 or newer for building the project
* Docker and Docker Compose
* A running Kafka broker
* Gradle Wrapper included in the project

The Flink Docker cluster uses the official Java 17 Flink image.

## Configuration

The job can be configured using JVM system properties or environment variables.

JVM system properties take precedence over environment variables.

| JVM property                         | Environment variable                 | Default                          |
|--------------------------------------|--------------------------------------|----------------------------------|
| `kafka.bootstrap.servers`            | `KAFKA_BOOTSTRAP_SERVERS`            | `localhost:9092`                 |
| `kafka.executions.topic`             | `KAFKA_EXECUTIONS_TOPIC`             | `org.example.order.executions`   |
| `kafka.verification.topic`           | `KAFKA_VERIFICATION_TOPIC`           | `org.example.order.verification` |
| `flink.offset.reset`                 | `FLINK_OFFSET_RESET`                 | `latest`                         |
| `flink.group.id`                     | `FLINK_GROUP_ID`                     | `order-lifecycle-flink`          |
| `flink.parallelism`                  | `FLINK_PARALLELISM`                  | `4`                              |
| `flink.checkpoint.interval.ms`       | `FLINK_CHECKPOINT_INTERVAL_MS`       | `10000`                          |
| `flink.print.lifecycle`              | `FLINK_PRINT_LIFECYCLE`              | `false`                          |
| `kafka.commit.offsets.on.checkpoint` | `KAFKA_COMMIT_OFFSETS_ON_CHECKPOINT` | `true`                           |
| —                                    | `POD_ID`                             | `unknown`                        |

## Running Locally with Gradle

In this mode, Kafka runs in Docker, while the Flink job runs directly from Gradle on the host machine.

### 1. Start Kafka

```bash
docker compose -f ./kafka-streams/docker-compose.yml up -d
```

The local job connects to Kafka through:

```text
localhost:9092
```

### 2. Start the Flink job

```bash
./gradlew :flink:run \
  -Dkafka.bootstrap.servers=localhost:9092 \
  -Dflink.offset.reset=latest
```

The job remains attached to the current terminal.

### 3. Run the integration test

Run the test in a separate terminal:

```bash
./gradlew :kafka-streams:test \
  -PincludeIntegrationTests \
  -Dkafka.bootstrapServers=localhost:9092 \
  --tests OrderLifecycleKafkaScenarioTest \
  --info \
  --rerun-tasks
```

The integration test publishes execution reports to Kafka and verifies the records produced by the lifecycle processor.

### 4. Stop the local job

Stop the Gradle process with:

```text
Ctrl+C
```

## Running on a Docker Compose Flink Cluster

In this mode, Kafka, Flink JobManager, and Flink TaskManager run in Docker containers.

### 1. Start Kafka

```bash
docker compose -f ./kafka-streams/docker-compose.yml up -d
```

Inside the Docker network, the Flink cluster connects to Kafka through:

```text
broker:29092
```

### 2. Build the Flink application JAR

```bash
./gradlew :flink:clean :flink:shadowJar
```

The generated fat JAR is mounted into the JobManager container as:

```text
/opt/flink/usrlib/order-lifecycle-flink.jar
```

### 3. Start the Flink cluster

```bash
docker compose -f ./flink/docker-compose.yml up -d
```

The Flink dashboard is available at:

```text
http://localhost:8081
```

### 4. Submit the job

```bash
docker exec -it flink-jobmanager flink run -d \
  -c org.example.order.lifecycle.flink.job.FillOrderFlinkJob \
  /opt/flink/usrlib/order-lifecycle-flink.jar
```

The `-d` option submits the job in detached mode.

The command returns a Flink Job ID:

```text
Job has been submitted with JobID ...
```

### 5. Observe TaskManager logs

```bash
docker logs -f flink-taskmanager
```

To inspect JobManager logs:

```bash
docker logs -f flink-jobmanager
```

To list submitted jobs:

```bash
docker exec flink-jobmanager flink list
```

### 6. Run the integration test

The integration test still connects to Kafka through the host listener:

```bash
./gradlew :kafka-streams:test \
  -PincludeIntegrationTests \
  -Dkafka.bootstrapServers=localhost:9092 \
  --tests OrderLifecycleKafkaScenarioTest \
  --info \
  --rerun-tasks
```

The processing path in this mode is:

```text
Integration test
    |
    | localhost:9092
    v
Kafka broker
    |
    | broker:29092
    v
Flink cluster
    |
    v
Verification topic
    |
    | localhost:9092
    v
Integration test
```

### 7. Stop the Flink cluster

```bash
docker compose -f ./flink/docker-compose.yml down
```

To stop Kafka:

```bash
docker compose -f ./kafka-streams/docker-compose.yml down
```

## Docker Compose Configuration

The Docker Compose setup contains:

* one Flink JobManager
* one Flink TaskManager
* four TaskManager slots
* default job parallelism of four
* access to the external Kafka Docker network
* mounted application JAR
* mounted Log4j configuration

The JobManager and TaskManager join the external Kafka network:

```yaml
networks:
  kafka-network:
    external: true
    name: kafka-streams_kafka-network
```

The Kafka Compose environment must therefore be started before the Flink Compose environment.

## Main Components

### FillOrderFlinkJob

Application entry point responsible for:

* Kafka source configuration
* Watermark strategy configuration
* State processing topology
* Kafka sink configuration
* Checkpointing configuration
* Job parallelism configuration

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

The service is located in the `common` module and is shared between the Flink and Kafka Streams implementations.

### VerificationRecordSerializationSchema

Produces verification records used to validate key distribution and processing consistency across Flink tasks and runtime instances.

## State Management

Lifecycle state is maintained using Flink managed state:

```text
ValueState<OrderState>
```

This is the Flink equivalent of the materialized state store used by the Kafka Streams implementation.

The current implementation uses Flink managed state through `ValueState`.

The ForSt state backend dependency is included intentionally for future experiments with larger state and checkpointing behaviour. It can later be compared conceptually with the RocksDB-backed state stores used by Kafka Streams.

## Checkpointing

Checkpointing is enabled using:

`env.enableCheckpointing(checkpointIntervalMs);`

The checkpoint interval is configurable through:

```text
flink.checkpoint.interval.ms
```

or:

```text
FLINK_CHECKPOINT_INTERVAL_MS
```

A Flink checkpoint stores both:

* managed operator state
* Kafka source offsets

This allows the source position and lifecycle state to be restored from the same consistent checkpoint.

### Verification Sink Delivery Guarantee

The verification sink uses `AT_LEAST_ONCE` delivery semantics.

During recovery, records emitted after the last completed checkpoint may be produced again. As a result, duplicate verification records are possible.

Integration-test assertions should therefore be idempotent or deduplicate verification records using a stable business identity, such as the order hierarchy identifier and execution report identifier.

## Kafka Offset Commits

Kafka source offsets are always tracked by the Flink source and included in Flink checkpoints.

The additional property:

```text
kafka.commit.offsets.on.checkpoint
```

controls whether completed checkpoint offsets are also committed to the Kafka consumer group.

When enabled:

```text
true
```

Flink reports the current offsets to Kafka. Kafka stores those consumer-group offsets internally in the `__consumer_offsets` topic.

When disabled:

```text
false
```

Flink still stores source offsets in its checkpoints, but Kafka consumer-group tools do not show the current processing position.

The Docker Compose environment currently disables Kafka offset commits to avoid unnecessary local consumer-group coordinator warnings:

```yaml
KAFKA_COMMIT_OFFSETS_ON_CHECKPOINT: "false"
```

This does not disable Flink checkpoint recovery. However, restarting the application as a completely new job without restoring a checkpoint or savepoint uses the configured offset initializer instead of the previous Flink state.

## Logging

The Docker Compose environment uses a mounted `log4j-console.properties` configuration.

The default runtime logging level is reduced while application logs remain available at `INFO`.

TaskManager logs can be followed using:

```bash
docker logs -f flink-taskmanager
```

The official Java 17 Flink image may print JVM warnings similar to:

```text
WARNING: Unknown module: jdk.compiler specified to --add-exports
```

These warnings originate from JVM startup options in the official image and are unrelated to the application job.

## Kafka Streams Comparison

| Kafka Streams              | Flink                                |
| -------------------------- | ------------------------------------ |
| `KTable` aggregation       | `KeyedProcessFunction`               |
| Materialized state store   | `ValueState`                         |
| `StreamsBuilder`           | `StreamExecutionEnvironment`         |
| Kafka-native topology      | DataStream job graph                 |
| Changelog topic recovery   | Checkpoint-based recovery            |
| Committed source offsets   | Source offsets stored in checkpoints |
| `exactly_once_v2`          | Checkpoint-coordinated processing    |
| RocksDB-backed local state | Flink managed state                  |

Both implementations share the same domain model and business logic from the `common` module.
