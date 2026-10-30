# Part 6 — PostgreSQL DB-centric 10K scenario

Follow [shared steps 1–4](benchmark-common.md) with `BENCH_IMPL=db-centric`.
Build before deploying; repeat the fresh cycle three times.

## Build and deploy

```bash
./gradlew :db-centric:bootJar
docker build -t order-lifecycle-db-centric:latest -f db-centric/Dockerfile .
kubectl apply -f db-centric/k8s/postgres.yaml
kubectl rollout status statefulset/db-centric-postgres --timeout=180s
kubectl apply -f db-centric/k8s/processor.yaml
kubectl set env deployment/db-centric \
  LOGGING_LEVEL_ORG_EXAMPLE_ORDER_LIFECYCLE_DB_SERVICE=WARN \
  LOGGING_LEVEL_ORG_SPRINGFRAMEWORK_KAFKA_LISTENER_KAFKAMESSAGELISTENERCONTAINER=INFO
kubectl rollout status deployment/db-centric --timeout=180s
kubectl logs -l app=db-centric --prefix --tail=-1
kubectl exec kafka-broker-0 -- /opt/kafka/bin/kafka-consumer-groups.sh --bootstrap-server localhost:29092 --describe --group order-lifecycle-db-centric --members
```

Require four active consumers with assignments and no listener errors. Flyway
must have migrated the database; the startup config should show the expected
topics, group, source, pool size 4, BATCH acknowledgement and one consumer per
pod. Each event still uses its own DB transaction; only Kafka offsets are
committed per poll. Service WARN disables
per-input and per-publication logs while retaining StartupConfig INFO. Listener
INFO records partition assignments/revocations. Record these effective logging
levels alongside the other benchmark configuration.

```bash
kubectl exec db-centric-postgres-0 -- psql -U orders -d order_lifecycle \
  -c 'SELECT (SELECT count(*) FROM orders) AS orders, (SELECT count(*) FROM processed_input) AS inputs, (SELECT count(*) FROM outbox) AS outbox;' \
  -c 'SHOW fsync;' -c 'SHOW synchronous_commit;'
```

All counts must be zero before warm-up; durability settings must be on. SOURCE_ID
remains `k8s-executions-v1` across this cycle's pods. Reset DB and Kafka together.

## Warm-up, measurement, acceptance

Execute shared step 5, the checks below, shared step 6, then the checks again.

```bash
kubectl exec kafka-broker-0 -- /opt/kafka/bin/kafka-consumer-groups.sh --bootstrap-server localhost:29092 --describe --group order-lifecycle-db-centric
kubectl exec db-centric-postgres-0 -- psql -U orders -d order_lifecycle \
  -c "SELECT (SELECT count(*) FROM processed_input) AS inputs, (SELECT count(*) FROM orders) AS orders, (SELECT count(*) FROM fills) AS fills, (SELECT count(*) FROM outbox) AS outbox, (SELECT count(*) FROM outbox WHERE published_at IS NULL) AS pending, (SELECT count(*) FROM order_lifecycle WHERE hierarchy_key=order_id AND status='FILLED') AS filled_roots;"
```

| Check | After 1K warm-up | After warm-up + 10K |
|---|---:|---:|
| Inputs | 5000 | 55000 |
| Orders | 3000 | 33000 |
| Fills | 2000 | 22000 |
| Outbox rows | 1000 | 11000 |
| Filled roots | 1000 | 11000 |
| Pending outbox | 0 | 0 |
| Input lag on every partition | 0 | 0 |

Save logs for each pod and DB statistics after both stages (use separate names):

```bash
for bench_pod in $(kubectl get pods -l app=db-centric -o name); do
  kubectl logs "$bench_pod" -c processor > "$BENCH_DIR/${bench_pod#pod/}.log"
  kubectl exec "$bench_pod" -c processor -- java -version 2> "$BENCH_DIR/${bench_pod#pod/}-java.txt"
done
kubectl logs db-centric-postgres-0 > "$BENCH_DIR/postgres.log"
kubectl exec db-centric-postgres-0 -- psql -U orders -d order_lifecycle \
  -c 'SELECT pg_database_size(current_database());' \
  -c 'SELECT wal_records, wal_fpi, wal_bytes FROM pg_stat_wal;' \
  -c 'SELECT wait_event_type, wait_event, count(*) FROM pg_stat_activity GROUP BY 1,2;' \
  > "$BENCH_DIR/db-after-measurement.txt"
```

For WAL growth, also collect the same statistics before warm-up and after warm-up
under different filenames. Sample waits during measurement in another terminal;
a final idle snapshot cannot diagnose contention. Require no restart, listener
stop, publication failures, or measurement-time rebalance. Four completion IDs
identify who first observed completion, not exclusive hierarchy ownership.
Finish with shared step 7.

Limits: four app pods (500m / 512Mi each) plus PostgreSQL (2 CPU / 2Gi), total
4 CPU / 4Gi. PostgreSQL uses a 10Gi PVC; record its StorageClass. DB state is
retained after the run for inspection; the next independent cycle resets it.
