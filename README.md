
# Stateful Order Processing in Event-Driven Architectures

This repository demonstrates multiple approaches to **stateful order lifecycle processing**
in event-driven systems, using a realistic trading-inspired domain with orders, fills,
hierarchies, late events, and allocation logic.

The goal of this project is to compare **stateful stream processing** and
**database-centric architectures** from both a **technical** and **architectural**
perspective — focusing on correctness, scalability, and reasoning about state over time.

---

## Problem Domain

The examples are based on a simplified but realistic order lifecycle:

- Orders are created and updated via execution reports
- Orders can receive multiple partial fills
- Orders may form hierarchies (parent / child)
- Events can arrive out of order (late fills, late cancels)
- The system must derive consistent state (e.g. fully filled orders)

This domain is intentionally chosen because it highlights challenges where
**state management, ordering guarantees, and correctness** matter more than
simple CRUD-style data access.

---

## Implementations

This repository contains multiple implementations of the same business problem,
each representing a different architectural style.

### 1. Kafka Streams

A stateful stream processing implementation using:

- Kafka Streams
- Local state stores backed by changelog topics
- Configurable processing guarantees, including at-least-once and exactly-once
- Deterministic, per-key serial processing
- Order-independent handling of late lifecycle events using durable state 
- Separate event-time windowing examples for execution analytics

➡ Module: `kafka-streams`

This implementation serves as the **reference implementation** for the article series.

---

### 2. Apache Flink

A functional implementation of the same order lifecycle processor using:

- Flink DataStream API
- Kafka Source and Sink connectors
- Keyed managed state
- Event timestamps and watermarks
- Checkpoint-based recovery
- Shared domain logic from the `common` module

➡ Module: `flink`

The implementation can be executed locally through Gradle or submitted to
a Docker Compose Flink cluster. 
The implementation can be executed locally, through Docker Compose,
or on Kubernetes. The repository also contains repeatable 10K and
100K benchmark scenarios comparing it with Kafka Streams.

---

### 3. DB-centric approach

A reference, database-oriented design based on:

- Event ingestion into a relational database
- SQL-based aggregation and joins
- Polling, triggers, or transactional updates
- External state coordination

➡ Module: `db-centric` *(planned)*

This implementation exists to contrast traditional designs with stream-based approaches
and to make architectural trade-offs explicit.

---

## Branching and Article Scope

The `main` branch contains the implementation described in the articles published so far.

Support for parent orders, order hierarchies, late events, and more advanced scenarios
will be introduced incrementally in subsequent parts and is developed on separate branches
until each article is published.

---

## Repository Structure

```text
.
├── common/          # Shared domain model and business logic
├── kafka-streams/   # Kafka Streams implementation
├── flink/           # Apache Flink implementation
└── README.md
```

---

## Article Series

This repository accompanies a series of articles explaining the design decisions,
trade-offs, and implementation details step by step:
- Part 1: Stateful Order Fill Processing with Kafka Streams 
- Part 2: Order Hierarchies — Parent and Child Orders 
- Part 3: Time, Event Ordering, and the Limits of Windows 
- Part 4: Horizontal Scaling and Kafka Partitions 
- Part 5: Kafka Streams vs Apache Flink — Solving the Same Stateful Problem 
- Part 6: Stateful Streaming vs Database-Centric Processing `[Planned]`
- Part 7: CQRS and Read Models `[Planned]`

---

## Target audience

This project is intended for:
- Backend engineers working with event-driven systems
- Architects evaluating streaming vs database-centric designs
- Developers interested in Kafka Streams or Apache Flink
- Engineers dealing with stateful, high-throughput workflows
- Anyone curious about **how to reason about state over time**

---

## Disclaimer

This project focuses on **architecture, correctness, and reasoning about state**.
Some simplifications are intentional to keep the examples readable and educational.

It is not intended to be a production-ready trading system.