# kafka-streams

Kafka Streams application responsible for **stateful order lifecycle processing**.

This module consumes execution reports (orders, fills, cancels) from Kafka
and builds a stateful `OrderState` aggregate per order hierarchy using **Kafka Streams**.

The implementation is intentionally simple and educational – it focuses on:
- correctness
- event ordering
- state handling
- testability

rather than production optimizations.

---

## What this module does

- Consumes `ExecutionReport` events from Kafka
- Groups events by hierarchy key (parent `orderId` when present, otherwise `orderId`)
- Builds and updates `OrderState` using a state store
- Handles:
    - order creation
    - partial and full fills
    - order completion

The logic is implemented using **Kafka Streams DSL**.

---

## Key concepts demonstrated

- Stateful processing with Kafka Streams
- `groupByKey()` and aggregation
- Custom state objects (`OrderState`)
- Deterministic testing using `TopologyTestDriver`
- Integration testing using **real Kafka** (Docker)

---

## Running the Application

The application can be launched in two environments:
1. **Locally** using Docker Compose for Kafka + local Spring Boot application
2. **Kubernetes** using Helm charts

---

## 1. Running Locally

### Prerequisites
- Java 25+
- Docker & Docker Compose

### 1.1 Start Kafka

Kafka is provided by the root `docker-compose.yml`.

From the project root:

```bash
docker compose -f ./kafka-streams/docker-compose.yml up -d
```

This will start:
- A single Kafka broker (KRaft mode)
- Topic: `org.example.order.executions`

### 1.2 Run the Kafka Streams Application

From this module:

```bash
./gradlew bootRun --args='--spring.profiles.active=local'
```

The application will start consuming events from Kafka and building order state.


### 1.3 Run Integration Test

To execute the integration test from gradle:

```bash
./gradlew test -PincludeIntegrationTests -Dkafka.bootstrapServers=localhost:9092 --tests SimpleKafkaIntegrationTest --info --rerun-tasks
```

This requires Kafka to be running (see 1.1). The test connects to real Kafka, produces sample ExecutionReport events, and verifies successful message production.

### 1.4 Stop Kafka

```bash
docker compose -f ./kafka-streams/docker-compose.yml down -v
```

---

## 2. Running in Kubernetes

This setup is optimized for Docker Desktop Kubernetes.
Docker Desktop Kubernetes is the primary development environment used for the examples.
If you use kind or minikube, load the locally built images into the cluster before applying the manifests.

Tested on Docker Desktop Kubernetes on macOS. 
The setup should also work on Docker Desktop Kubernetes on Windows/WSL2, but users of kind/minikube may need to load locally built images into the cluster manually.

### Prerequisites
- Kubernetes cluster (local or remote)
- Helm 3.x
- kubectl

### 2.1 Deploy Kafka

```bash
kubectl apply -f kafka-streams/k8s/kafka/
```

### 2.2 Build the Docker Image

From the project root:

```bash
docker build -t order-state-processor:latest -f kafka-streams/Dockerfile .
```

### 2.3 Deploy the Application

```bash
helm install order-processor kafka-streams/k8s/helm-chart --set kafka.bootstrapServers=kafka-broker:9092
```

To deploy with multiple replicas (e.g., 4 replicas for testing partitioning):

```bash
helm install order-processor kafka-streams/k8s/helm-chart \
  --set kafka.bootstrapServers=kafka-broker:9092 \
  --set replicas=4
```

### 2.4 Run Integration Tests in Kubernetes

Integration tests run in a Docker container with all code baked in — no hostPath required.

#### Build the Test Image

From the project root:

```bash
docker build -t order-state-processor-test:latest -f kafka-streams/Dockerfile.test .
```

#### Run the Integration Test Job (1000 Iterations)

```bash
kubectl delete job integration-test --ignore-not-found=true
kubectl apply -f kafka-streams/k8s/tests/integration-test-job-1000.yaml
kubectl logs -f job/integration-test
```


#### Run the Integration Test Job (10000 Iterations)

```bash
kubectl delete job integration-test --ignore-not-found=true
kubectl apply -f kafka-streams/k8s/tests/integration-test-job-10000.yaml
kubectl logs -f job/integration-test
```

The repository provides two predefined workloads:

| File | Iterations | Purpose |
|------|-----------:|----------|
| `integration-test-job-1000.yaml` | 1,000 | Quick verification and smoke testing |
| `integration-test-job-10000.yaml` | 10,000 | Performance and scaling experiments |

Both jobs execute the same integration test scenario and differ only in the number of generated order hierarchies.


### 2.5 Cleanup / Remove All Resources

To remove everything deployed in Kubernetes:

```bash
# Remove the application
helm uninstall order-processor --ignore-not-found

# Remove Kafka
kubectl delete -f kafka-streams/k8s/kafka/ --ignore-not-found

# Remove integration test job
kubectl delete job integration-test --ignore-not-found

# Verify cleanup
kubectl get all
```

---

## Configuration

Local configuration is provided via:

```
src/main/resources/application-local.yaml
```

Key properties:
•	spring.kafka.bootstrap-servers
•	kafka.executions.topic

---

## Testing

### 1. Kafka Streams unit tests

The core processing logic is tested using TopologyTestDriver:
•	no Kafka broker required
•	fully deterministic
•	validates state store contents

These tests cover:
•	simple order + fills
•	fully filled orders
•	order hierarchy scenarios (in later chapters)

Run:
```bash
./gradlew test
```

### 2. Kafka integration test

An integration test is included that:
•	connects to real Kafka (Docker)
•	produces sample ExecutionReport events
•	verifies successful message production
•	**verifies partitioning guarantee** — parent and child orders are processed by the same pod
•	verifies horizontal scaling behavior across multiple Kafka Streams instances
•	measures hierarchy ownership and throughput under load

Note
This test does not validate full state.
State correctness is verified using TopologyTestDriver tests.
The goal here is to validate wiring, serialization and local setup.

**Partitioning verification:** The application writes verification records to a separate topic (`org.example.order.verification`) containing the POD_ID that processed each order. The test consumes this topic and asserts that all records with the same key (parent order ID) were processed by the **same pod**. This validates Kafka Streams' `groupByKey()` partitioning guarantee, ensuring order hierarchies are never mixed between pod instances.

---

## Notes on state design

For clarity and educational purposes, OrderState keeps a collection of all Fill objects.

In a real production system:
•	keeping thousands of fills in a state store would be inefficient
•	state would be minimized to the data required for decision making
•	detailed fill data could be handled by downstream consumers or separate storage

This trade-off is discussed in the accompanying articles.

---

## Partitioning and Scaling

Kafka Streams parallelism is bounded by the number of Kafka partitions.
Adding more application instances than partitions does not increase active processing parallelism.

The integration tests included in this repository demonstrate this behavior using multiple replicas and varying partition counts.

For a detailed description of the experimental setup, test scenarios, and results, see:

- [Part 4 Horizontal Scaling Experiments](docs/part4-horizontal-scaling-experiments.md)

---

## Related articles

This module is part of a larger example described in a series of articles:
- Part 1: Stateful order fill processing with Kafka Streams
- Part 2: Order hierarchy (parent / child orders)
- Part 3: Late events and windowing
- Part 4: Horizontal scaling and partitions
  - Experimental setup and benchmark results: [part4-horizontal-scaling-experiments.md](docs/part4-horizontal-scaling-experiments.md)
- Part 5: Kafka Streams vs Apache Flink: Solving the Same Stateful Problem [Not ready yet]
- Part 6: Stateful Streaming vs Database-Centric Processing [Planned]
- Part 7: CQRS and Read Models [Planned]