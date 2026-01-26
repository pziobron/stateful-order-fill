
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
- Exactly-once processing semantics
- Deterministic, per-key serial processing
- Windowing and event-time handling for late events

➡ Module: `kafka-streams`

This implementation serves as the **reference implementation** for the article series.

---

### 2. Apache Flink

A stream processing implementation focusing on:

- Event-time processing
- Watermarks
- Stateful operators
- Explicit handling of late events
- More fine-grained control over time and state

➡ Module: `flink` *(work in progress)*

This module will be added incrementally to allow a **direct comparison**
with Kafka Streams using the same business requirements.

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

The `main` branch contains the implementation described in **Part 1** of the article series.

Support for parent orders, order hierarchies, late events, and more advanced scenarios
will be introduced incrementally in subsequent parts and is developed on separate branches
until each article is published.

---

## Repository Structure

```text
.
├── kafka-streams/     # Kafka Streams implementation
├── flink/             # Apache Flink implementation (WIP)
├── db-centric/        # Database-centric approach (planned)
├── docker-compose.yml # Local Kafka setup
└── README.md          # This file
```

---

## Article Series

This repository accompanies a series of articles explaining the design decisions,
trade-offs, and implementation details step by step:
- Part 1: Stateful Order Fill Matching with Kafka Streams – The Basics
- Part 2: Order Hierarchies (Parent / Child Orders) (planned)
- Part 3: Late Events, Ordering, and Windowing (planned)
- Part 4: Testing Stateful Stream Processing (TopologyTestDriver) (planned)
- Part 5: Horizontal Scaling, Partitions, and Parallelism (planned)
- Part 6: Kafka Streams vs Apache Flink (planned)
- Part 7: Stream Processing vs DB-centric Architectures (planned)

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
