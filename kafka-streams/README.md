# Kafka Streams Order Lifecycle Processor

Kafka Streams application responsible for **stateful order lifecycle processing**.

This module consumes execution reports such as orders, fills, and cancellations from Kafka and builds a stateful `OrderState` aggregate for each order hierarchy using Kafka Streams.

The implementation is intentionally simple and educational. It focuses on:

* correctness
* event ordering
* state handling
* testability

rather than production-specific optimizations.

---

## What This Module Does

* Consumes `ExecutionReport` events from Kafka
* Groups events by hierarchy key:

  * parent `orderId`, when present
  * otherwise the original `orderId`
* Builds and updates `OrderState` using a materialized state store
* Handles:

  * order creation
  * partial fills
  * full fills
  * order completion
* Publishes verification records used to validate partition ownership and horizontal scaling

The processing topology is implemented using the Kafka Streams DSL.

---

## Key Concepts Demonstrated

* Stateful processing with Kafka Streams
* Re-keying and `groupByKey()`
* Stateful aggregation
* Custom state objects such as `OrderState`
* Materialized Kafka Streams state stores
* Deterministic testing using `TopologyTestDriver`
* Integration testing using a real Kafka broker
* Partition-based horizontal scaling
* Parent and child order hierarchy processing

---

## Running the Application

The application can be launched in two environments:

1. Locally using Docker Compose for Kafka and a local Spring Boot application
2. In Kubernetes using Helm charts

All commands below are executed from the project root unless stated otherwise.

---

## 1. Running Locally

### Prerequisites

* A compatible Java version configured for the Gradle build
* Docker and Docker Compose
* Gradle Wrapper included in the project

### 1.1 Start Kafka

Start the local Kafka broker:

```bash
docker compose -f ./kafka-streams/docker-compose.yml up -d
```

The Docker Compose environment starts:

* a single Kafka broker in KRaft mode
* `org.example.order.executions`
* `org.example.order.verification`

The locally running application connects to Kafka through:

```text
localhost:9092
```

### 1.2 Run the Kafka Streams Application

```bash
./gradlew :kafka-streams:bootRun \
  --args='--spring.profiles.active=local'
```

The application starts consuming execution reports from Kafka and maintaining order lifecycle state.

### 1.3 Run the Integration Test

Run the integration test in a separate terminal:

```bash
./gradlew :kafka-streams:test \
  -PincludeIntegrationTests \
  -Dkafka.bootstrapServers=localhost:9092 \
  --tests OrderLifecycleKafkaScenarioTest \
  --info \
  --rerun-tasks
```

The integration test:

* publishes execution reports to `org.example.order.executions`
* waits for the running processor to handle them
* consumes records from `org.example.order.verification`
* validates processing results
* validates partition and runtime-instance ownership

The same integration scenario can also be used to validate the Apache Flink implementation. The implementation under test depends on which processor is currently running.

Do not run the Kafka Streams and Flink processors against the same test topics at the same time unless that behaviour is intentional.

### 1.4 Stop the Application

Stop the local Spring Boot process with:

```text
Ctrl+C
```

### 1.5 Stop Kafka

Stop the containers while preserving broker data:

```bash
docker compose -f ./kafka-streams/docker-compose.yml down
```

To also remove Docker volumes and reset all Kafka data:

```bash
docker compose -f ./kafka-streams/docker-compose.yml down -v
```

Use `down -v` only when a completely clean Kafka environment is required.

---

## 2. Running in Kubernetes

This setup is optimized for Docker Desktop Kubernetes, which is the primary development environment used for the examples.

The setup has been tested on Docker Desktop Kubernetes on macOS. It should also work on Docker Desktop Kubernetes on Windows or WSL2.

When using kind or minikube, locally built images may need to be loaded into the cluster manually before applying the manifests.

### Prerequisites

* Kubernetes cluster
* Helm 3.x
* `kubectl`
* Docker

### 2.1 Deploy Kafka

```bash
kubectl apply -f kafka-streams/k8s/kafka/
```

### 2.2 Build the Application Docker Image

From the project root:

```bash
docker build \
  -t order-state-processor:latest \
  -f kafka-streams/Dockerfile \
  .
```

### 2.3 Deploy the Application

Deploy a single application replica:

```bash
helm install order-processor kafka-streams/k8s/helm-chart \
  --set kafka.bootstrapServers=kafka-broker:9092
```

Deploy multiple replicas, for example four replicas for partitioning and scaling experiments:

```bash
helm install order-processor kafka-streams/k8s/helm-chart \
  --set kafka.bootstrapServers=kafka-broker:9092 \
  --set replicas=4
```

### 2.4 Run Integration Tests in Kubernetes

Integration tests run in a Docker container with the application and test code included. No host path mount is required.

#### Build the Test Image

```bash
docker build \
  -t order-state-processor-test:latest \
  -f kafka-streams/Dockerfile.test \
  .
```

#### Run the Integration Test Job with 1,000 Iterations

```bash
kubectl delete job integration-test --ignore-not-found=true
kubectl apply -f kafka-streams/k8s/tests/integration-test-job-1000.yaml
kubectl logs -f job/integration-test
```

#### Run the Integration Test Job with 10,000 Iterations

```bash
kubectl delete job integration-test --ignore-not-found=true
kubectl apply -f kafka-streams/k8s/tests/integration-test-job-10000.yaml
kubectl logs -f job/integration-test
```

The repository provides two predefined workloads:

| File                              | Iterations | Purpose                              |
|-----------------------------------|-----------:|--------------------------------------|
| `integration-test-job-1000.yaml`  |      1,000 | Quick verification and smoke testing |
| `integration-test-job-10000.yaml` |     10,000 | Performance and scaling experiments  |

Both jobs execute the same integration test scenario and differ only in the number of generated order hierarchies.

### 2.5 Cleanup

Remove the application:

```bash
helm uninstall order-processor --ignore-not-found
```

Remove Kafka:

```bash
kubectl delete -f kafka-streams/k8s/kafka/ --ignore-not-found
```

Remove the integration test job:

```bash
kubectl delete job integration-test --ignore-not-found
```

Verify that the resources were removed:

```bash
kubectl get all
```

---

## Configuration

Local configuration is provided through:

```text
kafka-streams/src/main/resources/application-local.yaml
```

Key configuration properties include:

* `spring.kafka.bootstrap-servers`
* `kafka.executions.topic`
* `kafka.verification.topic`

The runtime instance identifier is obtained from:

```text
POD_ID
```

For a locally running application, it can be set using an environment variable. In Kubernetes, it identifies the pod that processed a verification record.

---

## Testing

### 1. Unit and Topology Tests

The Kafka Streams topology is tested using `TopologyTestDriver`.

These tests:

* do not require a running Kafka broker
* are deterministic
* validate state-store contents
* validate emitted records
* verify order and fill processing
* verify parent and child order hierarchy processing

Run the regular module tests:

```bash
./gradlew :kafka-streams:test
```

Integration tests are excluded unless the `includeIntegrationTests` Gradle property is provided.

### 2. Kafka Integration Test

The integration test connects to a real Kafka broker and validates a running stream processor.

Start Kafka:

```bash
docker compose -f ./kafka-streams/docker-compose.yml up -d
```

Start the Kafka Streams application:

```bash
./gradlew :kafka-streams:bootRun \
  --args='--spring.profiles.active=local'
```

Run the test in another terminal:

```bash
./gradlew :kafka-streams:test \
  -PincludeIntegrationTests \
  -Dkafka.bootstrapServers=localhost:9092 \
  --tests OrderLifecycleKafkaScenarioTest \
  --info \
  --rerun-tasks
```

The integration test:

* connects to a real Kafka broker
* produces sample `ExecutionReport` events
* consumes verification records
* verifies the partitioning guarantee
* verifies that parent and child orders are processed by the same runtime instance
* validates horizontal scaling behaviour across multiple Kafka Streams instances
* measures hierarchy ownership and throughput under load

The integration test focuses on:

* Kafka connectivity
* serialization and deserialization
* topic wiring
* partition assignment
* processing-instance ownership
* end-to-end message processing

Detailed state correctness is additionally validated using `TopologyTestDriver`.

### Partitioning Verification

The application publishes verification records to:

```text
org.example.order.verification
```

Each verification record contains the `POD_ID` of the runtime instance that processed the corresponding order.

The integration test consumes these records and verifies that all events with the same hierarchy key are processed by the same runtime instance.

This validates the Kafka partitioning guarantee and ensures that parent and child orders belonging to the same hierarchy are not processed concurrently by different application instances.

---

## Notes on State Design

For clarity and educational purposes, `OrderState` keeps a collection of all `Fill` objects associated with an order hierarchy.

In a production system:

* keeping thousands of fills in a state store could be inefficient
* state would normally be minimized to the data required for decision-making
* detailed fill information could be persisted by downstream consumers
* historical fill data could be stored in a dedicated database or object store

This trade-off is discussed in the accompanying articles.

---

## Partitioning and Scaling

Kafka Streams processing parallelism is bounded by the number of Kafka partitions.

Adding more application instances or processing threads than available partitions does not increase active processing parallelism.

For example:

```text
3 Kafka partitions
4 Kafka Streams instances
```

results in at most three active processing tasks for the source topic. At least one instance remains without an assigned source partition.

The integration tests included in this repository demonstrate this behaviour using:

* multiple application replicas
* varying Kafka partition counts
* verification records containing runtime-instance identifiers
* different workload sizes

For a detailed description of the experimental setup, scenarios, and results, see:

* [Part 4 Horizontal Scaling Experiments](docs/part4-horizontal-scaling-experiments.md)

---

## Kafka Streams and Flink Implementations

The repository contains two implementations of the same order lifecycle processing problem:

* `kafka-streams` — Kafka Streams implementation
* `flink` — Apache Flink DataStream implementation
* `common` — shared domain model and business logic

Both processors:

* consume execution reports from Kafka
* group records by order hierarchy
* maintain stateful `OrderState` aggregates
* use the same shared domain services
* publish records to the verification topic
* can be validated using the same integration scenario

The implementations differ mainly in their state-management, scaling, deployment, and recovery models.

---

## Related Articles

This module is part of a larger example described in a series of articles:

* Part 1: Stateful Order Fill Processing with Kafka Streams
* Part 2: Order Hierarchies — Parent and Child Orders
* Part 3: Time, Event Ordering, and the Limits of Windows
* Part 4: Horizontal Scaling and Kafka Partitions
  - [Experimental setup and benchmark results](docs/part4-horizontal-scaling-experiments.md)
* Part 5: Kafka Streams vs Apache Flink — Solving the Same Stateful Problem
* Part 6: Stateful Streaming vs Database-Centric Processing `[Planned]`
* Part 7: CQRS and Read Models `[Planned]`
