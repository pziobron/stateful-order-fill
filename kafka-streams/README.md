# kafka-streams

Kafka Streams application responsible for **stateful order lifecycle processing**.

This module consumes execution reports (orders, fills, cancels) from Kafka
and builds a stateful `OrderState` aggregate per order using **Kafka Streams**.

The implementation is intentionally simple and educational – it focuses on:
- correctness
- event ordering
- state handling
- testability

rather than production optimizations.

---

## What this module does

- Consumes `ExecutionReport` events from Kafka
- Groups events by `orderId`
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

## Running locally

### Prerequisites
- Java 25+
- Docker & Docker Compose

---

### 1. Start Kafka

Kafka is provided by the root `docker-compose.yml`.

From the project root:

```bash
docker compose up -d
```
This will start:
•	a single Kafka broker (KRaft mode)
•	topic: org.example.order.executions

---

### 2. Run the Kafka Streams application

From this module:
```bash
../gradlew bootRun --args='--spring.profiles.active=local'
```
The application will start consuming events from Kafka and building order state.

### 3. Stop Kafka
```bash
docker compose down -v
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
../gradlew test
```

### 2. Kafka integration test

A simple integration test is included that:
•	connects to real Kafka (Docker)
•	produces sample ExecutionReport events
•	verifies successful message production

Note
This test does not validate state.
State correctness is verified using TopologyTestDriver tests.
The goal here is to validate wiring, serialization and local setup.

---

## Notes on state design

For clarity and educational purposes, OrderState keeps a collection of all Fill objects.

In a real production system:
•	keeping thousands of fills in a state store would be inefficient
•	state would be minimized to the data required for decision making
•	detailed fill data could be handled by downstream consumers or separate storage

This trade-off is discussed in the accompanying articles.

---

## Related articles

This module is part of a larger example described in a series of articles:
- Part 1: Stateful order fill processing with Kafka Streams
- Part 2: Order hierarchy (parent / child orders) [Not ready yet]
- Part 3: Late events and windowing [Not ready yet]
- Part 4: Testing Kafka Streams applications [Not ready yet]
- Part 5: Horizontal scaling and partitions [Not ready yet]
- Part 6: Kafka Streams vs Apache Flink [Not ready yet]
- Part 7: Kafka Streams vs DB-centric designs [Not ready yet]
