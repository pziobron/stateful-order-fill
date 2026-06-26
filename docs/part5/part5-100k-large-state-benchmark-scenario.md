# Part 5 — 100K Large-State Benchmark Scenario

This document defines the repeatable 100,000-iteration benchmark procedure used for the Apache Flink and Kafka Streams large-state comparison.

The final results are stored in:

```text
part5-100k-large-state-flink-vs-kafka-streams-results.md
```

## Workload

Each hierarchy contains five input records:

1. parent order
2. first child order
3. second child order
4. fill for the first child order
5. fill for the second child order

Each measured run therefore contains:

```text
100,000 hierarchies
500,000 input messages
100,000 final verification records
```

Every measured run is preceded by:

```text
1,000 warm-up hierarchies
5,000 warm-up messages
```

## Shared topology

| Parameter                     |   Value |
|-------------------------------|--------:|
| Input topic partitions        |       6 |
| Verification topic partitions |       6 |
| Processing units              |       4 |
| Warm-up iterations            |   1,000 |
| Measured iterations           | 100,000 |
| Independent measured cycles   |       3 |

## Fresh-cycle requirement

Every measured cycle must start with:

```text
fresh Kafka broker storage
fresh input and verification topics
fresh consumer offsets
fresh processor state
one 1K warm-up
one 100K measured run
```

Do not run an additional 10K measurement on the same processor state before the 100K run.

## Common cleanup and Kafka reset

```bash
kubectl delete job integration-test --ignore-not-found=true
kubectl delete job flink-job-submitter --ignore-not-found=true

kubectl delete -f flink/k8s/flink-taskmanager.yaml --ignore-not-found=true
kubectl delete -f flink/k8s/flink-jobmanager.yaml --ignore-not-found=true

helm uninstall order-processor --ignore-not-found || true

kubectl delete -f kafka-streams/k8s/kafka/ --ignore-not-found=true

kubectl wait   --for=delete   pod/kafka-broker-0   --timeout=120s || true

kubectl delete pvc kafka-data-kafka-broker-0 --ignore-not-found=true

kubectl apply -f kafka-streams/k8s/kafka/

kubectl wait   --for=condition=Ready   pod/kafka-broker-0   --timeout=180s

kubectl wait   --for=condition=Complete   job/kafka-topic-creator   --timeout=180s
```

Verify both benchmark topics have six partitions and zero offsets before every warm-up.

## Apache Flink 100K cycle

### Configuration

```text
4 TaskManagers
1 slot per TaskManager
parallelism = 4
state.backend.type = hashmap
execution.checkpointing.storage = filesystem
execution.checkpointing.dir = file:///flink-checkpoints
checkpoint interval = 10,000 ms
maximum concurrent checkpoints = 1
```

The checkpoint directory must be shared and writable by the JobManager and all TaskManagers.

### Deploy and submit

```bash
kubectl apply -f flink/k8s/flink-configmap.yaml
kubectl apply -f flink/k8s/flink-jobmanager.yaml
kubectl apply -f flink/k8s/flink-taskmanager.yaml

kubectl rollout status deployment/flink-jobmanager
kubectl rollout status deployment/flink-taskmanager

kubectl delete job flink-job-submitter --ignore-not-found=true
kubectl apply -f flink/k8s/flink-job-submitter.yaml
kubectl logs -f job/flink-job-submitter
```

### Warm up

```bash
kubectl delete job integration-test --ignore-not-found=true
kubectl apply -f kafka-streams/k8s/tests/integration-test-job-1000.yaml
kubectl logs -f job/integration-test
```

### Measure

```bash
kubectl delete job integration-test --ignore-not-found=true
kubectl apply -f kafka-streams/k8s/tests/integration-test-job-100000.yaml
kubectl logs -f job/integration-test
```

Expected:

```text
messages=500000
verificationRecords=100000
hierarchyKeys=100000
processors=4
```

Save the complete `SCENARIO_RESULT` line.

### Flink acceptance checks

After processing completes, wait for at least one full checkpoint interval and confirm:

- a checkpoint completed after the measured workload,
- no checkpoint failed,
- no `OutOfMemoryError` occurred,
- no TaskManager restarted,
- no task recovery occurred,
- durable checkpoint files were created.

Reject the run if processing succeeds but the following checkpoint fails.

## Kafka Streams 100K cycle

### Configuration

```text
4 application pods
num.stream.threads = 1
commit.interval.ms = 100
processing.guarantee = at_least_once
local RocksDB state store
Kafka changelog topics
```

### Deploy

```bash
helm install order-processor kafka-streams/k8s/helm-chart   --set kafka.bootstrapServers=kafka-broker:29092   --set replicas=4
```

Wait for all four pods to become ready and verify the effective configuration before starting the warm-up.

### Warm up

```bash
kubectl delete job integration-test --ignore-not-found=true
kubectl apply -f kafka-streams/k8s/tests/integration-test-job-1000.yaml
kubectl logs -f job/integration-test
```

### Measure

```bash
kubectl delete job integration-test --ignore-not-found=true
kubectl apply -f kafka-streams/k8s/tests/integration-test-job-100000.yaml
kubectl logs -f job/integration-test
```

Save the complete `SCENARIO_RESULT` line.

### Kafka Streams acceptance checks

Reject a run if any of the following occurs:

- pod restart,
- application rebalance during measurement,
- uncaught processing exception,
- RocksDB failure,
- out-of-memory error,
- result-count mismatch.

With six tasks and four stream threads, the processor distribution is expected to be approximately:

```text
33,000
33,000
17,000
17,000
```

## Shared acceptance checklist

Accept a measured run only when:

- `messages == 500000`
- `verificationRecords == hierarchyKeys == 100000`
- exactly four processors participated
- each hierarchy was attributed to one processor only
- one 1K warm-up preceded the measured run
- processor state was fresh before warm-up
- Kafka topics and offsets were fresh
- no competing implementation was running
- no restart, OOM, or uncaught exception occurred
- framework-specific durability checks succeeded

## Reporting

For every implementation:

1. preserve all three complete `SCENARIO_RESULT` lines,
2. calculate the median independently for each metric,
3. retain processor distributions,
4. document runtime versions and resource limits,
5. describe configuration differences explicitly.

Primary metrics:

```text
endToEndMs
processingWindowMs
endToEndMsgPerSec
processingInputMsgPerSec
completedHierarchiesPerSec
```
