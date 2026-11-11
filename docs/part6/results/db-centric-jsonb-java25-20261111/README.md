# PostgreSQL JSONB benchmark — Java 25 — 2026-11-11

Implementation revision: `0c802b2` (full SHA in [revision.txt](revision.txt)).
The tracked working tree was clean. An untracked article draft was not included
in any build. No application changes were made during this session.
`STATE_STORAGE=jsonb` and `KAFKA_GROUP_ID=order-lifecycle-db-centric-jsonb` were
set on every processor pod; startup logs confirm the selected mode.

## Measurements

Three independent 10K cycles and one exploratory 100K cycle were executed
sequentially. Each cycle used fresh Kafka and PostgreSQL PVCs, then a
1,000-hierarchy warm-up retained for the measured workload. Each hierarchy has
one parent, two children and two fills: five inputs and one logical completion.
The shared generator/verifier and measurement boundary are unchanged.

| Workload | Cycle | Input messages | Submission | Drain | End-to-end | Processing window | Input messages/s |
|---|---:|---:|---:|---:|---:|---:|---:|
| 10K | 1 | 50,000 | 1.680 s | 12.981 s | 14.661 s | 14.368 s | 3,410.41 |
| 10K | 2 | 50,000 | 1.615 s | 13.095 s | 14.711 s | 14.443 s | 3,398.82 |
| 10K | 3 | 50,000 | 1.767 s | 12.682 s | 14.449 s | 14.245 s | 3,460.45 |
| 100K | 1 | 500,000 | 6.714 s | 102.249 s | 108.963 s | 108.650 s | 4,588.71 |

**10K median end-to-end: 14.661 s; median throughput: 3,410.41 input messages/s.**
Medians are calculated independently per metric. Millisecond fields are copied
from verifier output; rounding can make submission plus drain differ from total
by 1 ms. 100K is a single observation, not a variability estimate.

End-to-end includes generation/submission, transport, processing and receipt of
all expected unique completion keys by the verifier. Subsequent SQL, lag and
publication checks are acceptance gates outside the timed interval. JSONB's
internal timestamps are captured by FillOrderService after loading the document;
they exclude the initial database read/lock interval and final commit/publication.
Use end-to-end as the primary comparison metric.

## Environment

- Single-node arm64 Docker Desktop Kubernetes v1.34.1, default namespace.
- Docker VM: 14 CPUs, 8,216,887,296 bytes RAM.
- Four processor pods, Java **25.0.5** verified in every pod, one consumer and
  connection pool size four each; READ COMMITTED, one DB transaction per input.
- Application limits 500m CPU / 512Mi per pod; PostgreSQL limits 2 CPU / 2Gi.
  Combined limits 4 CPU / 4Gi; shared Kafka and generator are additional.
- PostgreSQL 18.6; `fsync=on` and `synchronous_commit=on` checked each cycle.
- Kafka 4.1.2; six input and six verification partitions, empty before warm-up.
- Processor image ID: `sha256:b014397dfa19de28e74f6b9d0b0c783f495e39adfefd07c191d9445b8bf28549`.
- Generator image ID: `sha256:dd5914dd0b411efe6883ac3cb1ccfb38e77bb37917470c29a4f0bb726a821a51`.
  Both images match those used for the relational Java 25 runs.
- Kafka BATCH offset acknowledgement, max poll records 100; publication delay
  100 ms and up to 100 sends per pass, one transaction per publication.
- DB service and shared FillOrderService logging suppressed at WARN; startup and
  Kafka listener INFO retained. All effective settings and pod image IDs archived.
- AC power, `caffeinate -dims`, no host sleep/wake during the session.
- Metrics API unavailable; DB wait samples collected during each workload.
  These samples are not a continuous CPU, memory or throttling profile.

## Acceptance

All four warm-ups and all four measurements succeeded, with the expected unique
completion keys and four completion-producing processor identities. After every
stage, all six input partitions had zero lag and four active consumer members.

| Stage | Documents | Completed | Published | Pending | Children | Fills | Roots with filled quantity 2 |
|---|---:|---:|---:|---:|---:|---:|---:|
| Each warm-up | 1,000 | 1,000 | 1,000 | 0 | 2,000 | 2,000 | 1,000 |
| Each warm-up + 10K | 11,000 | 11,000 | 11,000 | 0 | 22,000 | 22,000 | 11,000 |
| Warm-up + 100K | 101,000 | 101,000 | 101,000 | 0 | 202,000 | 202,000 | 101,000 |

Relational `orders`, `fills` and `hierarchies` remained empty in every cycle.
No application errors, publication failures, processor restarts, consumer stops,
workload-time partition assignment changes or broker ERROR entries were observed.
Assignments were compared against a pre-warm-up baseline for each pod.
The startup Flyway warning about PostgreSQL 18.6 being newer than its tested
support range remains in the logs; migrations succeeded. No cycles were rejected
or retried in this session.

Counts are collected after timing. They validate retained facts and completion
markers, not a crash/recovery guarantee. Publication remains at least once;
processor distribution records who first observed completion, not exclusive
hierarchy ownership.

## Comparison with relational Java 25

| Workload | Relational end-to-end | JSONB end-to-end | Relational input messages/s | JSONB input messages/s |
|---|---:|---:|---:|---:|
| 10K, median of three | 14.868 s | 14.661 s | 3,362.93 | 3,410.41 |
| 100K, single run | 115.864 s | 108.963 s | 4,315.40 | 4,588.71 |

JSONB's observed end-to-end time was about **1.4% shorter at 10K** and **6.0%
shorter at 100K**. The 10K ranges overlap: relational 14.482–15.100 s versus
JSONB 14.449–14.711 s. These measurements do not establish a general performance
advantage. 100K needs repeated runs, and the workload uses many small hierarchies;
it does not test growth of one large document.

[Relational Java 25 results](../db-centric-relational-java25-20261111/README.md)
were collected in a separate session on the same day with the same processor and
generator images, resource limits and measurement definition. Storage-mode runs
were not interleaved. SQL views versus Java aggregation is not the only differing
cost: JSONB reads, deserializes, serializes and rewrites the whole hierarchy on
every event. No profiling isolated those costs.

## Artifacts and final state

- [Measurements](benchmark-results.csv), [warm-ups](warmup-results.csv).
- `evidence/<size>-cycle-<n>/`: complete verifier logs, startup and application
  logs, JVMs, pod image IDs, workload/PVC manifests, empty-topic offsets, consumer
  membership and lag, SQL counts/statistics/wait samples, DB/broker logs and
  acceptance markers.
- [Benchmark runner](benchmark-runner.py), [environment](environment.txt).
- Full local evidence, including host power logs:
  `/Users/pziobron/benchmark-results/part6/db-centric-jsonb-java25-20261111/`.

The processor deployment was scaled to zero after archiving. Kafka and PostgreSQL
remain running with the final JSONB 100K cycle's data. Temporary sleep prevention
was stopped. Existing relational results and article drafts were not modified.
No commits or pushes were performed.
