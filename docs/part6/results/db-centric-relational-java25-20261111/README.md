# PostgreSQL relational benchmark — Java 25 — 2026-11-11

Implementation revision: `7a0a5f5` (full SHA in [revision.txt](revision.txt)).
The tracked working tree was clean; the untracked article draft was not part of
any build. No application changes were made during this session. This report
covers **relational storage only**, not JSONB. The article was not modified.

## Measurements

Each cycle starts with fresh Kafka and PostgreSQL PVCs, then a 1,000-hierarchy
warm-up whose state is retained for the measurement. A hierarchy is five input
messages and one logical completion. Three independent 10K cycles and one
exploratory 100K cycle were executed sequentially; Streams and Flink were not
rerun or interleaved in this session. The generator/verifier and end-to-end
measurement boundary are unchanged.

| Workload | Cycle | Input messages | Submission | Drain | End-to-end | Processing window | Input messages/s |
|---|---:|---:|---:|---:|---:|---:|---:|
| 10K | 1 | 50,000 | 1.618 s | 12.863 s | 14.482 s | 14.228 s | 3,452.56 |
| 10K | 2 | 50,000 | 1.716 s | 13.383 s | 15.100 s | 14.831 s | 3,311.26 |
| 10K | 3 | 50,000 | 1.658 s | 13.210 s | 14.868 s | 14.601 s | 3,362.93 |
| 100K | 1 | 500,000 | 6.959 s | 108.905 s | 115.864 s | 115.556 s | 4,315.40 |

**10K median end-to-end: 14.868 s; median throughput: 3,362.93 input messages/s.**
Each metric's median is calculated independently. Millisecond fields come directly
from the verifier; rounding can make submission plus drain differ from total by
1 ms. The 100K observation does not establish variability.

End-to-end begins before generation/submission and ends when the verifier has
accepted every expected hierarchy completion. Final lag, publication-marker and
SQL checks occur afterward and are not added to that time. PostgreSQL's internal
processing window excludes the final input commit and output publication.

## Environment

- Single-node arm64 Docker Desktop Kubernetes v1.34.1, `docker-desktop`, default namespace.
- Docker VM: 14 CPUs, 8,216,887,296 bytes RAM.
- PostgreSQL 18.6; `fsync=on`, `synchronous_commit=on` verified each cycle.
- Four consumer pods, one consumer and a pool of four connections each.
- Every processor pod verified as Temurin Java **25.0.5**; image IDs and JVM outputs
  are in the per-cycle evidence. Image: `eclipse-temurin:25-jre`.
- Application limits: 500m CPU / 512Mi per pod. Database: 2 CPU / 2Gi.
  Combined limits: 4 CPU / 4Gi. Shared Kafka and generator are additional.
- Kafka 4.1.2; six input and six verification partitions, all empty before warm-up.
- Relational state, per-event READ COMMITTED transactions; Kafka offsets committed
  per poll (BATCH), max poll records 100. Publication delay 100 ms.
- Per-event service logging disabled (WARN); startup and listener INFO retained.
- Same shared generator image for all four accepted cycles; image IDs archived.
- Mac on AC power, `caffeinate -dims` active, no host sleep/wake during the session.
- Kubernetes Metrics API unavailable. No continuous resource or throttling profile
  was collected; the 100K cycle includes one in-flight DB wait snapshot.

## Acceptance

All eight jobs (four warm-ups and four measured workloads) succeeded with the
expected unique completion keys and four completion-producing processor IDs.
SQL checks and zero lag were required after each stage.

| Stage | Orders | Fills | Completed | Published | Filled roots | Pending |
|---|---:|---:|---:|---:|---:|---:|
| Each warm-up | 3,000 | 2,000 | 1,000 | 1,000 | 1,000 | 0 |
| Each warm-up + 10K | 33,000 | 22,000 | 11,000 | 11,000 | 11,000 | 0 |
| Warm-up + 100K | 303,000 | 202,000 | 101,000 | 101,000 | 101,000 | 0 |

Every partition had zero final input lag. No processor restarts, listener or
publication failures, broker ERROR entries, or measurement-time partition
assignment changes were observed. Initial group formation and assignments before
warm-up are expected. Processor distribution identifies first completion writers,
not exclusive ownership of each hierarchy's events.

Flyway emits a startup warning that PostgreSQL 18.6 is newer than its tested
support range (17). Migrations completed successfully. This warning was retained
and was not treated as a processing failure.

The first setup attempt was stopped when the orchestration script incorrectly
classified that known startup warning as a runtime failure. Its warm-up had not
been accepted; it contributes no timing result. Kafka and PostgreSQL were reset
before the accepted first cycle. Its evidence is preserved locally in the source
evidence directory noted below.

The 100K cycle's warm-up had an unusually long end-to-end time: **26.664 s**,
versus a 3.573 s internal processing window. Counts, publication, lag and job
checks passed, with no observed application failure or host sleep. The cause of
the extra delay was not isolated. This observation is retained explicitly, not
substituted with another warm-up. See the complete warm-up log.

## Java 17 history

The [original report](../README.md), [measurement CSV](../benchmark-results.csv),
and [raw Java 17 DB logs](../db-centric-relational-java17-20261030/README.md) are preserved. The new data
is deliberately kept in this separate Java 25 directory.

| Workload | Historical Java 17 end-to-end | Java 25 end-to-end |
|---|---:|---:|
| 10K, median of three | 15.391 s | 14.868 s |
| 100K, one run each | 121.189 s | 115.864 s |

These are observations from different dates and revisions, not a controlled
measurement of the isolated effect of the JDK. The Java 25 revision also contains
the optional JSONB refactoring, though only relational processing was selected.
Do not attribute the timing difference solely to Java 25.

## Artifacts and final state

- [Measured metrics](benchmark-results.csv), [warm-up metrics](warmup-results.csv).
- `evidence/<size>-cycle-<n>/`: complete generator/verifier logs, application logs,
  Java versions, pod image IDs, workloads, PVCs, SQL counts/statistics, group
  membership/lag, broker and database logs, and acceptance markers.
- [Benchmark runner](benchmark-runner.py): orchestration used for these runs;
  it deletes only the benchmark resources and named PVCs specified in the procedure.
- Full local evidence, including setup-attempt and host power logs:
  `/Users/pziobron/benchmark-results/part6/db-centric-java25-20261111/`.

After archiving, the relational processor deployment was scaled to zero. Kafka
and PostgreSQL remain running with the final 100K cycle's data for inspection.
The temporary sleep-prevention process was stopped. No JSONB benchmark was run,
no article changes were made, and no results were committed or pushed.
