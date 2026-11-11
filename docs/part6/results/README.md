# Part 6 benchmark results — 2026-10-30

> Historical comparison: PostgreSQL results below use Java 17. The separate
> [Java 25 relational rerun (2026-11-11)](db-centric-relational-java25-20261111/README.md)
> preserves new results without replacing these original measurements.

Implementation revision: `9dee62acb1ac9058139d03a243d2536ea951d705`.
All variants use the simplified O/F contract, retain partial state continuously,
deduplicate fill identities, and emit one logical completion per hierarchy.
No implementation changes were made during these runs. Documentation and result
files were uncommitted; per-cycle working-tree patches are retained as evidence.

Each cycle started with fresh Kafka storage and processor state, ran one 1K
warm-up, then one measured workload. Warm-up state was retained for measurement.
10K measurements use three independent cycles per implementation, alternating
order: Streams/Flink/DB, Flink/DB/Streams, DB/Streams/Flink. 100K results are one
exploratory cycle per implementation, not medians or variability estimates.

## 10K: 50,000 input messages per measurement

| Implementation | Run 1 | Run 2 | Run 3 | Median end-to-end | Median input messages/s |
|---|---:|---:|---:|---:|---:|
| Kafka Streams | 3.828 s | 3.801 s | 3.859 s | 3.828 s | 13,061.65 |
| Apache Flink | 2.184 s | 2.013 s | 1.894 s | 2.013 s | 24,838.55 |
| PostgreSQL | 15.969 s | 15.391 s | 14.891 s | 15.391 s | 3,248.65 |

Each metric's median is calculated independently. See the CSV for submission,
drain and processing-window timings; elapsed end-to-end time is primary.

## 100K: 500,000 input messages per measurement

| Implementation | End-to-end | Processing window | Input submission | End-to-end input messages/s |
|---|---:|---:|---:|---:|
| Kafka Streams | 17.867 s | 17.464 s | 7.147 s | 27,984.55 |
| Apache Flink | 8.778 s | 6.862 s | 6.856 s | 56,960.58 |
| PostgreSQL | 121.189 s | 120.843 s | 7.673 s | 4,125.79 |

The DB 100K cycle was accepted earlier in the same session/day; its original
[validation report](db-centric-relational-java17-20261030/db-centric-100k-validation.md) is preserved. Streams and Flink
100K were subsequently run using the same revision, images, broker patch and
resource configuration. Submission overlaps processing. Output receipt excludes
later durability/lag acceptance checks from the measured time.

## Deployment and limits of comparison

- Single-node arm64 Docker Desktop Kubernetes v1.34.1; default namespace.
- Docker VM: 14 CPUs and 8,216,887,296 bytes RAM; host: 14 CPUs, 24 GiB RAM.
- Kafka 4.1.2, six source and six verification partitions; same generator image.
- Four Streams pods, one thread each; at_least_once, 100 ms commit interval;
  Java 25. Four DB consumer pods, one consumer each; Java 17. Four Flink
  TaskManagers, parallelism four, plus JobManager; Java 17.
- CPU/memory limits: Streams 4 CPU/4 GiB; DB applications plus PostgreSQL
  4 CPU/4 GiB; Flink including JobManager 5 CPU/6.25 GiB. Shared Kafka and
  generator costs are additional. This is a comparison of these deployments,
  not equal-resource or JVM-isolated benchmarking.
- Flink HashMap state backend, filesystem checkpoints every 10 seconds, no
  previous checkpoint restored. Offset commits on checkpoint enabled. Checkpoint
  files use the single-node hostPath; all evidence is associated with the new Job ID.
- PostgreSQL 18.6, durable settings enabled, per-event READ COMMITTED transactions,
  BATCH Kafka offset acknowledgements, business-key idempotency. Pending
  completion fields live in hierarchies; publication polls every 100 ms and holds
  the hierarchy lock while sending. Each publisher handles at most 100 sends per
  pass, with a 100 ms delay after the pass. These timings include that publication
  cadence and do not isolate PostgreSQL processing capacity. No input ledger or
  separate outbox table.
- Per-event lifecycle logging and optional lifecycle printing disabled. Streams
  processor-internals and DB listener package INFO retained for assignment evidence;
  effective configuration is archived. No implementation was run concurrently
  with another. All images were built before the accepted DB 100K cycle and their
  IDs matched before this continuation.
- Mac on AC power with sleep-prevention assertions active. Kubernetes metrics API
  unavailable; Docker snapshots, pod statuses and DB samples were saved. These
  are not continuous resource or CPU-throttling measurements.

## Acceptance and exclusions

Accepted workloads produced exactly the expected unique completion keys across
four processor identities, with successful test jobs and zero final lag. Streams
checks include internal topic lag and RUNNING threads. DB checks include SQL fact
counts, filled-root counts and zero pending publications. Flink checks include a
successful checkpoint triggered after each workload, no checkpoint failures after
readiness, an empty exception history, zero recovery and no restored checkpoints.
No workload-time rebalances, listener failures, OOM or pod restarts were accepted.

An initial Flink 100K preflight attempt reported one failed-checkpoint count with
no failed checkpoint record or task exception, before any input was sent. It was
stopped and preserved separately, and contributes no timing result. The clean
100K retry reached readiness and finished with zero failed checkpoints.
Readiness counters and any pre-workload values are retained for every Flink cycle;
failures after readiness invalidate a cycle.

Processor distribution identifies which worker first observed completion. In DB,
that does not mean exclusive ownership of all events for that hierarchy.

## Artifacts

PostgreSQL Java 17 artifacts are grouped in [their own directory](db-centric-relational-java17-20261030/README.md). The CSV files below retain the original combined comparison.

- [Measurement CSV](benchmark-results.csv), [warm-up CSV](warmup-results.csv).
- 10K raw logs: [Streams](kafka-streams-10k-raw.txt), [Flink](flink-10k-raw.txt),
  [DB](db-centric-relational-java17-20261030/db-centric-10k-raw.txt). Each file contains all three measured cycles.
- 100K raw logs: [Streams](kafka-streams-100k-raw.txt), [Flink](flink-100k-raw.txt),
  [DB](db-centric-relational-java17-20261030/db-centric-100k-raw.txt).
- Warm-up raw files are retained alongside each implementation's measurements.
- Full local evidence: `/Users/pziobron/benchmark-results/part6/comparison-20261030/`.
  Earlier DB 100K evidence: `/Users/pziobron/benchmark-results/part6/db-centric-100k-validation-20261030/`.
  Includes image IDs, JVMs, configs, assignments, application/broker logs,
  checkpoint REST snapshots, lag, power assertions and per-cycle acceptance markers.

These results supersede earlier Part 6 timing observations for the changed
implementation. Part 5 remains a historical reference, not a controlled
before/after measurement of one isolated optimization.

All scheduled measurements are complete. Processor deployments were scaled to
zero and the temporary port-forward and caffeinate processes were stopped. Kafka
remains running with the final Flink cycle's topics; final Flink checkpoint files
remain on the hostPath. DB data was reset between implementations as prescribed;
its acceptance counts and logs are archived. A final evidence audit found no
broker errors in accepted cycles and no host sleep/wake events during this
continuation. Accepted Flink measurement snapshots all have zero failed and
restored checkpoints. Results remain uncommitted for a separate results commit.
