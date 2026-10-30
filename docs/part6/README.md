# Part 6 — PostgreSQL baseline and comparable benchmarks

Part 6 implements the PostgreSQL baseline and defines repeatable benchmark
scenarios for all three processors. Benchmark results are documented
separately. Do not use historical Part 5 results as measurements of the current
processors: Part 6 changes domain reconciliation, completion emission,
configuration wiring, and dependencies.

## Manual scenarios

Run commands from the repository root. Start with the shared procedure, then
follow the selected implementation's scenario. These documents are also the
execution checklist for a later assisted run; creating them does not start tests.

- [Shared preparation, scoped reset, 1K warm-up, 10K measurement and reporting](benchmark-common.md)
- [Kafka Streams 10K scenario](kafka-streams-10k-scenario.md)
- [Apache Flink 10K scenario with filesystem checkpoints](flink-10k-scenario.md)
- [PostgreSQL DB-centric 10K scenario](db-centric-10k-scenario.md)

Each implementation needs three independent fresh cycles. Preserve the 1K
warm-up state for its following 10K measurement. Never run two implementations
against the same verification topic during a cycle. The shared procedure also
explains how to prepare a separate 100K cycle and its timeout limitation.

## Shared processing contract

See [shared processing contract](processing-contract.md)
for continuous fact storage, delivery/recovery guarantees and durability
differences. Replacement 10K and 100K results on Kafka 4.1.2 are pending.

## Workload and verifier

Reuse the existing OrderLifecycleKafkaScenarioTest.testParentOrderFill and its
JSON templates without changing Kafka input keys. Input and verification topics
have six partitions. A hierarchy has one parent of quantity 2, two children of
quantity 1, and one fill of quantity 1 per child. Input records may arrive across
partitions in a different order than publication. No cancellations, amendments,
overfills, or intentional duplicates are generated for throughput measurements.

Input satisfying the shared domain contract is assumed for all processors. There
is no additional field-validation pass specific to the DB variant. Parsing,
database constraints, and duplicate conflict detection remain part of processing;
the comparison does not claim identical handling of arbitrary invalid inputs.

For each implementation and each measured workload size:

- start fresh, run one 1K hierarchy warm-up (5K messages);
- run either 10K (50K messages) or 100K (500K messages), not both in one cycle;
- repeat three independent cycles and report medians per metric;
- run only one implementation at a time.

Existing Kubernetes jobs in kafka-streams/k8s/tests and Dockerfile.test are reused.
The verifier must start and position itself at the output topic end before
publishing this run's input, as the existing test already does.

Acceptance requires the exact expected set of hierarchy keys, one accepted
completion per key, valid timestamps and processor identities, and no processing
errors or restarts. Expect four completion-producing identities with four active
processors for these workload sizes. SQL tests additionally validate quantities,
statuses, replay, rollback, and concurrent writers: the output verifier alone
does not validate full lifecycle state or prove that one pod processed all events
of a hierarchy. In PostgreSQL, multiple pods can update the same hierarchy under
its row lock. The outbox retains the identity of the instance that first observed
completion, even if another instance publishes the result.

## Measurement boundaries

Use the existing SCENARIO_RESULT fields unchanged:

- endToEndMs starts before generation/submission and ends when the verifier has
  received one accepted completion for every measured hierarchy. It includes
  generation, Kafka, DB commits, outbox publication, and result consumption.
- inputSubmissionMs ends at producer flush; drainAfterSubmissionMs measures the
  remaining interval. Submission and processing overlap.
- processingWindowMs is max(completedAt) minus min(firstProcessedAt) across
  accepted completions. PostgreSQL captures completion inside the DB transaction,
  so this metric excludes its final commit and outbox transport. It is not a
  durable end-to-end completion metric. Keep clocks synchronized across pods.

Treat end-to-end throughput as the primary comparison. After timing finishes,
check zero input lag, no pending outbox entries, committed DB state, and the
framework-specific durability checks. These postconditions are acceptance gates,
not changes to the historical timing definition. Do not count raw output topic
records as distinct completions: at-least-once publication can duplicate them.

## Resource budget

The provided DB manifests request/limit:

| Component | CPU request / limit | Memory request / limit |
|---|---|---|
| Each of 4 app pods, publisher included | 250m / 500m | 384Mi / 512Mi |
| PostgreSQL | 1 / 2 | 2Gi / 2Gi |
| Total | 2 / 4 | 3.5Gi / 4Gi |

PostgreSQL has a 10Gi PVC. Record StorageClass, disk latency, placement, image
versions, JVM versions, pool sizes, and CPU throttling. Keep fsync and
synchronous_commit enabled. Kafka and generator resources are additional shared
costs and must be identical across runs and reported separately. The existing
100K generator job permits 2 CPU and 3Gi memory.

This DB budget is a proposed baseline, not proof of resource equivalence with
Part 5. Existing Kafka Streams limits total 4 CPU/4Gi; Flink's four TaskManagers
plus JobManager total 5 CPU/6.25Gi. For an equal-budget experiment, prepare
explicit alternative allocations that include JobManager and rerun all variants;
do not silently reuse unequal historical limits or overcommit the host VM.

## Fresh round procedure

Before each independent cycle, stop processors and test jobs. Recreate only the
benchmark's Kafka storage/topics and clear consumer offsets and internal state,
following the scoped Part 5 cleanup procedure. Recreate PostgreSQL's benchmark
PVC/database for a new DB cycle, including orders, fills, hierarchies,
processed_input, outbox, and Flyway metadata. Allow Flyway to migrate the empty
database. Do not reset offsets alone against populated database state.

Check that topics have six partitions and zero offsets, the database is empty,
and only the implementation under test is active. Wait for database readiness,
successful migration, and all consumer assignments before warm-up. A running DB
application pod is insufficient: its consumer deliberately stops on input error.
Confirm four participating application consumers in Kafka's group description.

Run exactly one warm-up, wait for its outputs, zero lag and an empty pending
outbox, then execute the measured job. Preserve warm-up state during that cycle.
After the run, preserve full SCENARIO_RESULT logs, effective configuration,
resource samples, DB sizes, WAL growth, lock waits and outbox backlog. Reject runs
with errors, rebalances during measurement, restarts, missing results, or failed
durability checks. Reset everything before the next independent round.

A PostgreSQL restart resets its shared buffers, but does not guarantee a cold OS
or storage cache. The warm-up is intentional. Record host conditions and
alternate execution order across implementations to reduce systematic drift.

## Architectural limits

PostgreSQL uses remote synchronous transactions and a shared primary; Streams
uses local RocksDB with Kafka changelogs; Flink uses keyed heap state and periodic
filesystem checkpoints. Their persistence boundaries and recovery costs differ.
The DB outbox adds polling and at-least-once transport. A hot hierarchy is
serialized in all variants; DB replicas scale independent hierarchies only until
the shared database saturates. Four consumers with six source partitions do not
imply the same work distribution as Flink keyBy or Streams repartitioning.

Run failures/recovery as separate correctness experiments, not inside the clean
throughput runs. The existing Flink hostPath checkpoint setup is a local-cluster
configuration, not a demonstration of multi-node HA. The DB single-primary PVC
setup likewise does not claim HA. SQL is mostly relational, but upsert, locking,
outbox polling and migration details remain PostgreSQL-specific.
