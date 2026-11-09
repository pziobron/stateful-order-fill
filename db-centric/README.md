# PostgreSQL Order Lifecycle Processor

`db-centric` consumes the same ExecutionReport JSON as Kafka Streams and Flink,
maintains lifecycle state in relational PostgreSQL tables, and publishes the same
VerificationRecord JSON to Kafka. PostgreSQL is the processing state store, not
just a sink for another processor. In the default relational mode, quantities
and statuses are calculated by SQL views; that processor does not reconstruct
OrderState or call FillOrderService.

The default `STATE_STORAGE=relational` implementation is described below.
An optional [JSONB variant](../docs/part6/db-centric-jsonb-scenario.md) stores one
`OrderState` document per hierarchy and reuses common's `FillOrderService`.
Select it with `STATE_STORAGE=jsonb`; the Kafka listener, transaction settings,
publication loop, container image and resource limits are shared. Only the
selected implementation consumes and publishes. Unknown storage values fail startup.

## Processing and correctness

For every input record the application runs one READ COMMITTED JDBC transaction:

1. Upsert the hierarchy row, updating its last-processing timestamp and holding
   its row lock until commit. Read whether completion was already recorded from
   the upsert's `RETURNING` result.
2. Upsert order definitions directly, or ensure root/child placeholders in one
   statement for fills. Definitions may arrive after fills.
3. Apply fills. Duplicate fill IDs are scoped to the hierarchy
   and order. Identical fill content is accepted; conflicts roll back.
4. If no completion was previously recorded, use `order_lifecycle` to conditionally
   set completed_at and completed_by in the hierarchy row. This durably marks
   the completion as pending while published_at is NULL. Continue storing later facts
   even after first completion; only completion emission stops.
5. Commit PostgreSQL for this record. The record listener then returns. With
   BATCH acknowledgement the container synchronously commits Kafka offsets after
   processing the records returned by `poll()`. Auto-commit is disabled; the
   listener is still invoked separately for each input record.

Connection initialization sets UTC, a 10-second lock timeout and a 15-second
statement timeout once per pooled connection rather than on every input
transaction. The POC schema is created by V1; recreate existing development
databases after changing that initial migration.

Every writer follows the hierarchy lock protocol, so different consumers and
application instances serialize updates to the same hierarchy. Different
hierarchies can proceed concurrently. Cross-partition business order is not
assumed. PostgreSQL constraints protect identities; quantities and statuses are
views of received facts rather than independently maintained counters.

Schema migrations are in `src/main/resources/db/migration/`.
Primary keys index hierarchy/order/fill identities. A partial hierarchy index
covers completed rows awaiting publication. There is no separate outbox table,
GIN index or JSONB lifecycle snapshot. Completion JSON is reconstructed from
immutable first_processed_at, completed_at and completed_by fields.

### Domain contract

The rules match [shared lifecycle semantics](../docs/domain/lifecycle-semantics.md):

- The root's positive expected quantity defines completeness; a separate check
  that every child definition has arrived is not required.
- Only O/F reports are supported; cancellation and other report types are rejected.
- Every unique fill remains stored and contributes to its order and root quantities.
- The first observed completion time and processor identity survive subsequent facts.
  Published results are not emitted anew after additional fills or late definitions.
- One immutable definition per order, direct children only, positive fill
  quantities, and finite double prices form the supported input contract.
  Repeated O reports overwrite metadata as in common; amendments are unsupported.

All implementations assume input satisfying the shared domain contract. No
comprehensive input-validation layer is included in the comparison. The DB
processor parses JSON and dispatches O/F reports, but does not separately
validate their fields before processing. Invalid input is outside the benchmark
scope; database constraints, duplicate conflict checks, and processing operations
may still reject it. These checks are not replaced by silent acceptance or skipping.

PostgreSQL timestamps alone have microsecond precision. Business timestamps are
stored as whole seconds plus an integer nanosecond field,
so duplicate checks preserve Java LocalDateTime
precision. Processing timestamps use timestamptz; their microsecond storage
precision is a small difference from Java Instant. Prices remain double precision
to match the existing domain; this is not a production monetary representation.

### Failure and publication

A separate scheduled publisher, inside each application instance, selects one
hierarchy with completed_at IS NOT NULL and published_at IS NULL, using
FOR UPDATE SKIP LOCKED. It keeps that row locked while sending,
waits for Kafka acknowledgement, marks it published, and commits. There is one
DB transaction per send, at most 100 sends per scheduler pass, and no Kafka call
inside the input-processing transaction.

- Failure before DB commit rolls back facts and pending completion together.
- Failure after DB commit but before input offset commit can replay the input,
  which reapplies the same immutable definition or deduplicates the fill by its
  business identity. With BATCH acknowledgement, several
  independently committed DB transactions may await one Kafka offset commit.
  On a listener error, Spring may commit the successfully acknowledged prefix
  before stopping; the failing invocation remains unacknowledged. Any committed
  facts redelivered after restart cannot increase quantities or create another
  completion. Replays update last_processed_at and execute SQL again; conflicting
  fill content is still rejected.
- Failure or uncertainty during completion publication leaves it pending. If Kafka
  accepted a send before a rollback, retry can duplicate the exact output payload.
  Delivery is at-least-once, not an atomic PostgreSQL/Kafka transaction.
- The completion's processor ID is captured in completed_by when completion is first recorded;
  another pod publishing or retrying it does not change that identity. All pods
  must use the same verification topic, kept stable while publications are pending.
- A processing exception stops the listener container. It does not skip the
  record, commit its offset, or silently recover to a dead-letter topic. Resolve
  the problem and restart the application. Publisher retries are independent.

This baseline favors clarity over throughput. Network sends hold the hierarchy row
lock and a connection, so later inputs for that hierarchy may wait; there is no claim of bounded storage or automatic retention.
The Kubernetes manifest has no application readiness endpoint: verify startup,
partition assignment, and absence of listener errors before measuring; a running
pod alone does not prove that its consumer is running.

## Build and test

JDK 25 and a working Docker daemon are required for the integration tests.
Both relational and JSONB processors run on Java 25; the Docker image uses
`eclipse-temurin:25-jre`. Historical Part 6 results used Java 17 and must be
distinguished from new benchmark runs.

```bash
./gradlew :db-centric:test :db-centric:bootJar
```

Tests use temporary PostgreSQL 18.6 and Kafka 4.1.2 Testcontainers. They do not
connect to your existing broker/database and do not run benchmarks. They cover
shared scenario vectors, per-event comparison with Java, nanosecond fill identity checks,
concurrent writes, rollback, input replay, publication retry and lock skipping, Kafka
output compatibility, and real group offsets after successful/failed processing.
A missing Docker daemon fails the tests rather than silently skipping SQL checks.

The reusable scenario vectors live in `common/src/testFixtures`. The existing
benchmark generator and verifier remain in `kafka-streams`; no second generator
is introduced.

The runtime keeps the Spring-managed Kafka client (3.9.1). Snappy is constrained
to 1.1.10.12, the legacy org.lz4 dependency is substituted with the compatible
maintained at.yawk.lz4 1.12.1 implementation, and Logback uses 1.6.6. These match
the codec/logging maintenance versions selected for Flink. Both codecs are
checked through the actual Kafka client batch format. Dependency resolution is
not a full security scan of the application or its container image.

## Run locally

Start the existing Kafka environment, create the database, and run the processor:

```bash
docker compose -f kafka-streams/docker-compose.yml up -d
docker compose -f db-centric/docker-compose.yml up -d postgres
./gradlew :db-centric:bootRun
```

Alternatively, after building the JAR, run the processor in Compose:

```bash
docker compose -f db-centric/docker-compose.yml up -d --build
```

The Compose Kafka network must exist first. Local credentials are for this POC.
Flyway migrates the database at startup. No SQL needs to be applied manually.

At INFO level, startup logs show the broker, input/output topics, processor ID,
and consumer configuration. Each committed input logs its hierarchy, order,
execution ID, and report type. First completion logs that verification has
been queued; successful completion publication is logged after the database commit.
Replayed inputs execute the same processing path and are logged at INFO level.
For benchmarks, disable per-record INFO logging, for example with
`LOGGING_LEVEL_ORG_EXAMPLE_ORDER_LIFECYCLE_DB=WARN`, and record that setting
alongside the other processors' logging levels.

Run the existing verifier with only this processor consuming the scenario:

```bash
./gradlew :kafka-streams:test -PincludeIntegrationTests \
  -Dkafka.bootstrapServers=localhost:9092 \
  --tests org.example.order.lifecycle.processor.integration.OrderLifecycleKafkaScenarioTest.testParentOrderFill \
  --info --rerun-tasks
```

See [Part 6 benchmark procedure](../docs/part6/README.md) for warm-up, resource
budgets, clean rounds, and the limitations of the verifier.

## Configuration

| Environment variable | Default | Meaning |
|---|---|---|
| STATE_STORAGE | relational | State model: relational or jsonb |
| DB_URL | jdbc:postgresql://localhost:5432/order_lifecycle | JDBC database |
| DB_USER / DB_PASSWORD | orders / orders-local | POC database credentials |
| DB_POOL_SIZE | 4 | Total connections per application instance, including publisher |
| KAFKA_BOOTSTRAP_SERVERS | localhost:9092 | Kafka bootstrap servers |
| KAFKA_GROUP_ID | order-lifecycle-db-centric | Shared consumer group |
| KAFKA_EXECUTIONS_TOPIC | org.example.order.executions | Input topic |
| KAFKA_VERIFICATION_TOPIC | org.example.order.verification | Output topic |
| DB_CONSUMERS | 1 | Synchronous consumer threads per pod |
| POD_ID | local-db-centric | Completion processor identity |
| PUBLICATION_DELAY_MS | 100 | Delay between publisher passes |
| PUBLICATION_ENABLED | true | Enable scheduled completion publication |
| DB_CONSUMER_ENABLED | true | Start Kafka listeners |

Replay protection uses immutable order definitions, unique fill identities, and
first-completion state rather than a table of Kafka input positions. Changing a
group ID alone does not reset DB state. For a full replay that recomputes results,
start with a fresh database and output topic as well as fresh input offsets.

The consumer reads at most 100 records per poll, with a 5-minute poll interval
budget. `ack-mode: batch` batches Kafka offset commits, not DB transactions or
completion sends. It does not wait to accumulate 100 records: each available poll is
processed immediately, committing each fact to PostgreSQL before moving on. DB lock/statement timeouts fail a transaction rather than wait forever.
Large slow batches can still exceed the poll budget; measure before increasing
concurrency or batch size. Explicitly tune these values in any benchmark run.

## Kubernetes artifacts

```bash
./gradlew :db-centric:bootJar
docker build -t order-lifecycle-db-centric:latest -f db-centric/Dockerfile .
kubectl apply -f db-centric/k8s/postgres.yaml
kubectl apply -f db-centric/k8s/processor.yaml
```

These commands are manual instructions, not part of tests. The manifest defines
four application replicas and one PostgreSQL instance with persistent storage.
It is a single-primary POC, without replication/HA. Replace sample credentials
before use outside the local benchmark cluster. Declare the StorageClass and
node/storage conditions when recording measurements.
