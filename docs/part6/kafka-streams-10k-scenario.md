# Part 6 — Kafka Streams 10K scenario

Follow [shared steps 1–4](benchmark-common.md) with `BENCH_IMPL=kafka-streams`.
Build before resetting/deploying; repeat the fresh cycle three times.

## Build and deploy

```bash
docker build -t order-state-processor:latest -f kafka-streams/Dockerfile .
helm install order-processor kafka-streams/k8s/helm-chart \
  --set replicas=4 \
  --set image.pullPolicy=Never \
  --set kafka.bootstrapServers=kafka-broker:29092 \
  --set kafkaStreams.numStreamThreads=1 \
  --set kafkaStreams.commitIntervalMs=100 \
  --set kafkaStreams.processingGuarantee=at_least_once
kubectl set env deployment/order-processor-order-processor \
  LOGGING_LEVEL_ORG_EXAMPLE_ORDER_LIFECYCLE_SERVICE=WARN \
  LOGGING_LEVEL_ORG_APACHE_KAFKA_STREAMS_PROCESSOR_INTERNALS_STREAMTHREAD=INFO
kubectl rollout status deployment/order-processor-order-processor --timeout=180s
kubectl logs -l app=order-processor --prefix --tail=-1
```

Suppress the shared service's per-event state logs while retaining application
startup configuration. StreamThread INFO provides assignment/rebalance evidence.
Verify each pod reports one stream thread, at_least_once, and commit interval
100 ms. Confirm stable assignments across all four threads, not just Running pods:

```bash
kubectl exec kafka-broker-0 -- /opt/kafka/bin/kafka-consumer-groups.sh --bootstrap-server localhost:29092 --describe --group order-state-processor --members
```

Kafka Streams subscribes dynamically to internal topics, so member counts need
interpretation alongside the topology/thread logs. Record actual assignments.

## Warm-up, measurement, acceptance

Execute shared step 5, checks below, shared step 6, then the checks again.

```bash
kubectl exec kafka-broker-0 -- /opt/kafka/bin/kafka-consumer-groups.sh --bootstrap-server localhost:29092 --describe --group order-state-processor
kubectl get pods -l app=order-processor
for bench_pod in $(kubectl get pods -l app=order-processor -o name); do
  kubectl logs "$bench_pod" > "$BENCH_DIR/${bench_pod#pod/}.log"
  kubectl exec "$bench_pod" -- java -version 2> "$BENCH_DIR/${bench_pod#pod/}-java.txt"
done
helm get values order-processor --all > "$BENCH_DIR/helm-values.yaml"
```

Require zero lag for input and internal processing topics, no restarts, no
processing/RocksDB failures, and no measurement-time rebalances. Inspect changelog
and repartition topics and preserve their offsets as evidence:

```bash
kubectl exec kafka-broker-0 -- /opt/kafka/bin/kafka-topics.sh --bootstrap-server localhost:29092 --list > "$BENCH_DIR/topics.txt"
kubectl exec kafka-broker-0 -- /opt/kafka/bin/kafka-get-offsets.sh --bootstrap-server localhost:29092 --topic 'order-state-processor.*' > "$BENCH_DIR/internal-offsets.txt"
```

These checks confirm the observed baseline; they do not replace a separate
crash/recovery experiment. Finish with shared step 7. Limits: four app pods,
4 CPU / 4 GiB total, plus shared Kafka and generator. JVM is Java 25; DB and
Flink use Java 17, so this is a deployment comparison, not an isolated JVM test.
