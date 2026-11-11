# Part 6 — Optional PostgreSQL JSONB implementation

This variant runs alongside the relational implementation in the same module
and image. `STATE_STORAGE=relational` remains the default;
`STATE_STORAGE=jsonb` selects JsonbLifecycleProcessor and JsonbCompletionStore.
Exactly one processor and one completion store are active in a process. Invalid
storage values fail startup. Existing relational benchmark results are unchanged;
[JSONB Java 25 results](results/db-centric-jsonb-java25-20261111/README.md) are recorded separately.

## State and transaction

V2 adds `lifecycle_documents` without modifying V1 or migrating existing
relational state. Flyway can apply V2 to an existing V1 database. Each row holds
one wrapped OrderState JSONB document and completed_at, completed_by, published_at.
The completion columns index pending publication; the document holds the exact
Java Instant values and all partial order/fill state. There is no GIN index,
Kafka input-position ledger, separate outbox table or application state cache.

Each O/F event performs three SQL statements inside a READ COMMITTED transaction:

1. INSERT an empty document ON CONFLICT DO NOTHING to handle concurrent first events.
2. SELECT the latest document FOR UPDATE, then deserialize it with common's JsonUtils.
3. Apply common's FillOrderService and UPDATE the entire JSONB document, atomically
   recording the first completion time and processor identity when applicable.

The row lock is acquired before reading and held through commit. Concurrent
writers from different Kafka partitions cannot overwrite each other's fills.
State is read from PostgreSQL and persisted on every event, including partial
states, replay and facts after completion. The shared Java service handles late
order definitions, fill identity deduplication, conflicting fill rejection and
one logical completion. C and unknown report types are rejected before mutation.
The same supported input assumptions as the relational variant apply; order
amendments and arbitrary hierarchy depth remain outside the contract.

CompletionPublisher uses the selected CompletionStore to lock one pending row
with FOR UPDATE SKIP LOCKED, send the verification record, then commit published_at.
The shared loop, delay, transaction timeout, at-least-once retry behavior and
hierarchy-lock contention are the same for both variants. JSONB publication reads
firstProcessedAt and completedAt from the stored document, preserving nanoseconds;
completed_by remains the instance that first observed completion. Later facts or
publication by another instance cannot change the completion payload.

The shared JsonUtils mapper wraps the JSON under `OrderState`; JsonbCompletionStore
uses that format explicitly. A future incompatible model/serialization change
would require a document migration or replay from fresh state.

## Run locally

Build and run with the existing PostgreSQL/Kafka environment:

```bash
STATE_STORAGE=jsonb ./gradlew :db-centric:bootRun
```

For Compose, build the JAR first:

```bash
./gradlew :db-centric:bootJar
STATE_STORAGE=jsonb docker compose -f db-centric/docker-compose.yml up -d --build processor
```

One storage mode must be used by every pod in a consumer group. Stop and drain
pending publications before switching modes. Changing STATE_STORAGE does not
copy state, replay Kafka or reset offsets; each variant has its own state table.
For an independent benchmark, reset Kafka and database state together. Do not
mix storage variants in one group or run them simultaneously against the shared
benchmark input/output topics.

## Kubernetes benchmark procedure

Use the [shared fresh-cycle procedure](benchmark-common.md) with
`BENCH_IMPL=db-centric-jsonb` and the existing DB resource budget. Rebuild the
image from this revision before deploying. The following commands select JSONB
before any workload is published:

```bash
./gradlew :db-centric:bootJar
docker build -t order-lifecycle-db-centric:latest -f db-centric/Dockerfile .
kubectl apply -f db-centric/k8s/postgres.yaml
kubectl rollout status statefulset/db-centric-postgres --timeout=180s
kubectl apply -f db-centric/k8s/processor.yaml
kubectl set env deployment/db-centric \
  STATE_STORAGE=jsonb \
  KAFKA_GROUP_ID=order-lifecycle-db-centric-jsonb \
  LOGGING_LEVEL_ORG_EXAMPLE_ORDER_LIFECYCLE_DB_SERVICE=WARN \
  LOGGING_LEVEL_ORG_EXAMPLE_ORDER_LIFECYCLE_SERVICE=WARN \
  LOGGING_LEVEL_ORG_SPRINGFRAMEWORK_KAFKA_LISTENER=INFO
kubectl rollout status deployment/db-centric --timeout=180s
```

Suppress both the DB service logs and the shared FillOrderService logs during
benchmarks. Check every pod's startup line for stateStorage=jsonb, one consumer,
pool size four, BATCH offsets and publication enabled. Require four stable
members of `order-lifecycle-db-centric-jsonb` before warm-up. All pods must use
the same verification topic, kept stable while any publications are pending.
Applying the base processor manifest again restores its explicit relational mode;
reapply the JSONB environment overrides before running input.

Run the existing 1K warm-up and verifier, then a measured workload, retaining
warm-up state. See the [recorded JSONB runs](results/db-centric-jsonb-java25-20261111/README.md) for results and evidence.
For 10K, require messages=50000 and verificationRecords=hierarchyKeys=10000,
processors=4. For 100K, use the corresponding job/timeout from the shared procedure.

Inspect the JSONB table rather than the relational views:

```sql
SELECT hierarchy_key,
       state #>> '{OrderState,expectedQuantity}' AS expected_quantity,
       state #>> '{OrderState,filledQuantity}' AS filled_quantity,
       state #>> '{OrderState,status}' AS status,
       completed_at, completed_by, published_at
FROM lifecycle_documents
LIMIT 20;

SELECT count(*) AS documents,
       count(*) FILTER (WHERE completed_at IS NOT NULL) AS completed,
       count(*) FILTER (WHERE completed_at IS NOT NULL AND published_at IS NULL) AS pending,
       count(*) FILTER (WHERE published_at IS NOT NULL) AS published
FROM lifecycle_documents;
```

For the parent-order benchmark, after 1K warm-up expect 1,000 documents; after
warm-up plus 10K expect 11,000 documents. All must be completed and published,
with zero pending publications and zero input lag. After warm-up plus 100K the
corresponding count is 101,000. The relational hierarchies/orders/fills tables
remain empty in a fresh JSONB cycle. Validate retained facts as well as completion:

```sql
SELECT
    sum((SELECT count(*) FROM jsonb_each(state #> '{OrderState,childOrders}'))) AS children,
    sum((SELECT count(*) FROM jsonb_each(state #> '{OrderState,childOrders}') c,
         LATERAL jsonb_array_elements(c.value -> 'fills') f)) AS fills,
    count(*) FILTER (WHERE (state #>> '{OrderState,filledQuantity}')::bigint = 2) AS filled_roots
FROM lifecycle_documents;
```

Expected children/fills are 2,000 after warm-up and 22,000 after warm-up plus 10K;
filled_roots must match the document count. Archive logs, selected mode, image IDs,
DB/WAL sizes, lock waits and publication backlog, and reject workload-time errors,
rebalances, restarts, missing outputs or pending publications as in the relational
procedure. Keep all existing benchmark results separate from future JSONB results.

## What this experiment measures

The business aggregation code matches Streams and Flink. PostgreSQL still loads,
locks and rewrites the full document for every event, including JSON conversion,
network transfer, update/WAL work and contention within a hierarchy. The current
benchmark uses many small documents: one parent, two children and two fills.
It does not measure growth of a single large hierarchy.

First/completion processing timestamps are captured by FillOrderService after
loading the document; they exclude the initial read/lock interval. End-to-end
timing remains the primary comparison. Durability boundaries, JVM versions and
resource differences from the other deployments still apply. The recorded results apply to this workload and configuration; they do not
establish a general performance advantage.
