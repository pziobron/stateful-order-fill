# DB-centric 100K validation — 2026-10-30

One exploratory validation cycle: fresh state, 1K warm-up, then 100K measurement.
This is not a repeated benchmark or a three-implementation comparison. No 10K
measurement was executed in this session.

Implementation revision: `9dee62acb1ac9058139d03a243d2536ea951d705`. The only working-tree change at start was
a documentation correction removing the obsolete source identifier from the
startup checklist. No implementation changes were made during the cycle.

| Stage | Hierarchies | Input messages | End-to-end time | End-to-end input messages/s |
|---|---:|---:|---:|---:|
| Warm-up | 1,000 | 5,000 | 4.048 s | 1,235.18 |
| Measurement | 100,000 | 500,000 | 121.189 s | 4,125.79 |

Measurement input submission: 7.673 s; drain after submission: 113.515 s;
processing window: 120.843 s. Use end-to-end time as the primary timing.
Submission overlaps processing; separately rounded durations need not sum exactly.

## Environment and processing contract

- Docker Desktop Kubernetes v1.34.1, one arm64 node, default namespace.
- Docker VM: 14 CPUs, 8,216,887,296 bytes memory; host: 14 CPUs, 24 GiB memory.
- Kafka 4.1.2, six input and six output partitions; PostgreSQL 18.6, hostpath PVC.
- Four DB application pods: one consumer and pool size four per pod; Java 17.0.20.1.
- App limits: 500m CPU / 512Mi each; PostgreSQL: 2 CPU / 2Gi; total 4 CPU / 4Gi.
- BATCH offset acknowledgements, one READ COMMITTED DB transaction per event;
  fsync and synchronous_commit enabled. Scheduled publication delay: 100 ms.
- Only O/F facts; business-key fill deduplication; no processed-input ledger.
  Pending completions stored directly in hierarchies, no separate outbox table.
- Service logging WARN; startup INFO; Spring Kafka listener package INFO to
  observe assignments/revocations. No per-input or per-publication INFO logging.
- Verification topic remained unchanged; publisher locks hierarchy rows while sending.
- All application and generator images rebuilt before timing. Source Dockerfiles
  were used; no cached-runtime fallback was needed.
- Mac on AC power with caffeinate active; no sleep/resume observed during workloads.
- No Kubernetes metrics API; a Docker resource snapshot and DB wait samples were
  captured. These do not provide a continuous CPU/throttling profile.

## Acceptance

Both jobs completed successfully and emitted SCENARIO_RESULT with four processors.
Four stable consumers were monitored throughout each workload. No processing or
publication errors, workload-time rebalances, or pod restarts were observed.
Startup rebalances occurred before warm-up and are retained in the full logs.

After warm-up and measurement combined:

| Check | Observed |
|---|---:|
| Orders | 303,000 |
| Fills | 202,000 |
| Completed hierarchies | 101,000 |
| Published hierarchies | 101,000 |
| Filled roots | 101,000 |
| Pending publications | 0 |
| Input lag, every partition | 0 |

The measured completion distribution was 59,451 / 36,641 / 2,366 / 1,542.
These IDs identify who first recorded completion, not ownership of all work for
a hierarchy: events are partitioned by order ID and DB locking coordinates writers.

## Evidence and stopped state

- [100K raw test output](db-centric-100k-raw.txt)
- [1K warm-up raw test output](db-centric-1k-warmup-raw.txt)
- Full local evidence: `/Users/pziobron/benchmark-results/part6/db-centric-100k-validation-20261030`. Includes image IDs, startup logs, DB/broker logs,
  assignments, six-partition zero-lag checks, SQL counts, WAL statistics, power
  assertions, build logs and monitoring samples. No Secret resources were archived.

After acceptance the DB processor deployment was scaled to zero. PostgreSQL and
Kafka remain running for inspection; test jobs are completed. The competing local
Compose broker was stopped. Start each future independent benchmark cycle with
fresh pipeline state; do not treat this populated database as a new cycle.
