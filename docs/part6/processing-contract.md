# Part 6 — Shared processing contract

The three implementations retain intermediate lifecycle state as events arrive.
This document defines their shared behavior and the deployment differences that
must be considered when comparing benchmark results.

## Facts and lifecycle behavior

Every arriving valid order or fill updates processing state. Partial
orders and early fills are retained; state is not written only on completion.
Only O/F reports are supported; C and other types are rejected. The shared
domain contract supports late definitions, duplicate fills, and one logical
completion on the first observed fully filled state. Later facts continue
updating state without
retracting or re-emitting that completion. SQL views calculate DB quantities and
statuses; business calculation is not moved to a Java lifecycle cache.

See [domain semantics](../domain/lifecycle-semantics.md). The verifier checks
completion keys and timing; processor/domain tests cover intermediate states,
permutations, rollback, and duplicate handling.

## Delivery and recovery

Kafka input positions are immutable within one source/topic incarnation. When a
topic is recreated with offsets starting at zero, reset pipeline state together
or change its source incarnation identifier as appropriate. The DB retains a
transactional input identity to recognize redelivery after DB commit but before
Kafka offset commit. Arbitrary replacement content at the same Kafka position is
outside the source contract. Business fill conflict checks remain enabled.

DB input identity, facts, first completion, and outbox insertion commit atomically.
Hierarchy row locking serializes concurrent writers to one hierarchy. A failure
inside an input transaction rolls it back and stops the listener rather than
skipping a record. A failure after DB commit can leave facts durable before their
Kafka offset is committed; redelivery is handled by `processed_input`. Outbox
publication remains at-least-once: uncertain sends may duplicate an identical
output. Streams and Flink also allow output replay under their at-least-once
configurations. No implementation claims an atomic cross-system exactly-once
transaction here.

## Durability boundaries

Continuous state mutation does not imply the same durability boundary:

| Implementation | State update | Recovery mechanism |
|---|---|---|
| Streams | Aggregation updates RocksDB-backed state, with configured caching | Kafka changelogs and committed offsets |
| Flink | Each event updates managed heap state | Periodic filesystem checkpoints including source positions |
| DB | Every event applies SQL facts in a committed transaction | PostgreSQL WAL plus transactional delivery identity and outbox |

The DB keeps fsync and synchronous_commit enabled. Its record listener uses
BATCH acknowledgement with synchronous Kafka offset commits: the container
processes records individually and commits offsets after processing a poll.
Each fact still commits in its own DB transaction before the next record is
processed. At most 100 records are returned per poll; a poll need not be full.
There is no input-transaction batching or outbox-send batching.

Before invoking the stopping error handler, the container may commit the
successfully acknowledged prefix of a poll. The failing invocation is not
acknowledged. If a crash or failure leaves DB-committed inputs without committed
Kafka offsets, replay skips those input identities. Tests cover both processing
failure and failure after DB commit but before the listener returns for ACK.

Kafka offset commit frequency is not identical to Streams' time-based commit
interval or Flink's checkpoint interval. Flink completion output can be observed
before a checkpoint covering it; require a successful post-workload checkpoint
separately. End-to-end output timing therefore compares these deployed
architectures, not equal per-event durability latency. Resource limits and JVM
differences must be documented with the results.

## Processing overhead and diagnostics

DB statement and lock timeouts are configured once per pooled connection.
A hierarchy upsert creates or locks the coordination row, updates its processing
time and returns whether completion was recorded. Definitions use upserts;
placeholders share an insert. Conditional completion update returns timestamps
without a separate select. After first completion, later facts still update state
while completion-related work is skipped.

The normal DB path uses four SQL statements for a root definition and five for
a child definition or fill before completion, plus one outbox
insert if that event first completes the hierarchy. Duplicate business fills
need an additional conflict-check query. Transaction protocol messages, JDBC
transaction-timeout handling and outbox publication are additional costs.

Shared lifecycle JSON is formatted only when INFO logging is enabled. Streams
and Flink lifecycle stdout printing is optional and disabled for benchmarks.
Input batches, outbox batches, alternative isolation, relaxed durability,
schema/index/view redesign and business-state caching are separate experiments.
