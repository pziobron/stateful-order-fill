# Part 5 — Kafka Streams 10K Baseline — One Stream Thread per Pod

This document describes the repeatable Kafka Streams baseline used for the controlled comparison with Apache Flink in Part 5.

> The original Part 4 measurements were collected with an earlier verification methodology.  
> For a direct comparison with Apache Flink, rerun Kafka Streams using the corrected shared integration test described here.

## Scope of the current comparison

This is **Baseline A**:

```text
Apache Flink: heap-backed `HashMapStateBackend` with the current in-memory/checkpoint setup
Kafka Streams: local RocksDB state store with Kafka changelog topics
```

The comparison therefore represents the current implementations, not storage-equivalent state backends. A later baseline will evaluate Flink with Embedded RocksDB and durable checkpoint storage.


The first comparison is intentionally limited to a moderate state size:

```text
warm-up: 1,000 iterations = 5,000 messages
measured run: 10,000 iterations = 50,000 messages
```

This document remains the historical 10K Kafka Streams baseline. The completed 100K comparison is recorded separately.

Each measured run starts with a fresh Kafka Streams application state. The warm-up and measured test are then executed against that fresh processor instance.

Run the complete sequence three times and report the median of the three measured 10,000-iteration results.

## Test model

Every hierarchy contains five execution reports:

1. parent order
2. first child order
3. second child order
4. fill for the first child order
5. fill for the second child order

Therefore:

```text
messages = iterations × 5
verificationRecords = iterations
completedHierarchies = iterations
```

A valid result must contain:

```text
verificationRecords == completedHierarchies == iterations
messages == iterations × 5
```

## Comparison topology

Use the same logical parallelism for both Kafka Streams and Flink. The current baseline uses six Kafka partitions and four processing units:

| Parameter                     | Kafka Streams |
|-------------------------------|--------------:|
| Input topic partitions        |             6 |
| Verification topic partitions |             6 |
| Application pods              |             4 |
| Stream threads per pod        |             1 |
| Commit interval               |        100 ms |
| Warm-up iterations            |         1,000 |
| Measured iterations           |        10,000 |
| Measured runs                 |             3 |

Confirm the Helm deployment uses:

```text
replicas = 4
num.stream.threads = 1
commit.interval.ms = 100
```

> Six Kafka partitions are intentionally processed by four stream threads. Two threads own two tasks each, while the remaining two own one task each, so an approximately 2:1 processor distribution is expected.

## Prerequisites

- Docker Desktop with Kubernetes enabled
- Docker
- `kubectl`
- Helm
- repository root as the current directory

## 1. Build the Kafka Streams image

```bash
docker build \
  --no-cache \
  -t order-state-processor:latest \
  -f kafka-streams/Dockerfile \
  .
```

Verify:

```bash
docker image inspect order-state-processor:latest \
  --format '{{.Id}} {{.Created}}'
```

## 2. Build the corrected integration-test image

```bash
docker build \
  --no-cache \
  -t order-state-processor-test:latest \
  -f kafka-streams/Dockerfile.test \
  .
```

Verify:

```bash
docker image inspect order-state-processor-test:latest \
  --format '{{.Id}} {{.Created}}'
```

The corrected test:

- positions the verification consumer at the end of the topic,
- waits for one final verification record per completed hierarchy,
- emits one final verification record per completed hierarchy,
- carries `firstProcessedAt` and `completedAt` timestamps captured inside the processor,
- reports submission, drain, end-to-end, processing-window, and hierarchy-completion metrics,
- accepts only the first final record for each hierarchy so at-least-once duplicates do not inflate the measurements.

## 3. Start Kafka once

Make sure no Flink or Kafka Streams processor is running:

```bash
kubectl delete job integration-test --ignore-not-found=true
kubectl delete job flink-job-submitter --ignore-not-found=true

kubectl delete -f flink/k8s/flink-taskmanager.yaml --ignore-not-found=true
kubectl delete -f flink/k8s/flink-jobmanager.yaml --ignore-not-found=true

helm uninstall order-processor --ignore-not-found || true
```

Start Kafka:

```bash
kubectl apply -f kafka-streams/k8s/kafka/
```

Wait until the broker and topic creator are ready/completed:

```bash
kubectl get pods -w
```

Verify the topics have six partitions. If the manifests already create six partitions, no change is required.

```bash
kubectl exec -it kafka-broker-0 -- \
  /opt/kafka/bin/kafka-topics.sh \
  --bootstrap-server localhost:9092 \
  --describe \
  --topic org.example.order.executions

kubectl exec -it kafka-broker-0 -- \
  /opt/kafka/bin/kafka-topics.sh \
  --bootstrap-server localhost:9092 \
  --describe \
  --topic org.example.order.verification
```

If needed, increase both topics to six partitions:

```bash
kubectl exec -it kafka-broker-0 -- \
  /opt/kafka/bin/kafka-topics.sh \
  --bootstrap-server localhost:9092 \
  --alter \
  --topic org.example.order.executions \
  --partitions 6

kubectl exec -it kafka-broker-0 -- \
  /opt/kafka/bin/kafka-topics.sh \
  --bootstrap-server localhost:9092 \
  --alter \
  --topic org.example.order.verification \
  --partitions 6
```

## 4. Run one fresh Kafka Streams benchmark cycle

Repeat this complete section for Run 1, Run 2, and Run 3.

For maximum reproducibility, every measured cycle starts with:

```text
fresh Kafka broker storage
fresh input and verification topics
fresh Kafka Streams internal topics
fresh consumer offsets
fresh Kafka Streams application state
```

This is intentionally heavier than deleting only the application pods, but it avoids stale topic data, changelog restoration, and old committed offsets affecting the result.

### 4.1 Remove the previous test and processor

```bash
kubectl delete job integration-test --ignore-not-found=true
helm uninstall order-processor --ignore-not-found || true
```

Wait until all Kafka Streams pods disappear:

```bash
kubectl get pods
```

### 4.2 Remove Kafka and its benchmark PVC

Delete the benchmark Kafka resources:

```bash
kubectl delete -f kafka-streams/k8s/kafka/ --ignore-not-found=true
```

Wait until the broker pod is gone:

```bash
kubectl wait \
  --for=delete \
  pod/kafka-broker-0 \
  --timeout=120s || true
```

Delete only the PVC used by this benchmark broker:

```bash
kubectl delete pvc kafka-data-kafka-broker-0 --ignore-not-found=true
```

Do not delete unrelated PVCs such as:

```text
data-dev-kafka-controller-*
data-my-release-kafka-controller-*
```

Verify that the benchmark PVC no longer exists:

```bash
kubectl get pvc
```

### 4.3 Start a fresh Kafka broker

```bash
kubectl apply -f kafka-streams/k8s/kafka/
```

Wait until the broker is ready:

```bash
kubectl wait \
  --for=condition=Ready \
  pod/kafka-broker-0 \
  --timeout=180s
```

Wait for the topic creator Job:

```bash
kubectl wait \
  --for=condition=Complete \
  job/kafka-topic-creator \
  --timeout=180s
```

Verify both benchmark topics have six partitions and zero offsets:

```bash
kubectl exec kafka-broker-0 -- \
  /opt/kafka/bin/kafka-topics.sh \
  --bootstrap-server localhost:9092 \
  --describe \
  --topic org.example.order.executions

kubectl exec kafka-broker-0 -- \
  /opt/kafka/bin/kafka-topics.sh \
  --bootstrap-server localhost:9092 \
  --describe \
  --topic org.example.order.verification
```

```bash
kubectl exec kafka-broker-0 -- \
  /opt/kafka/bin/kafka-get-offsets.sh \
  --bootstrap-server localhost:9092 \
  --topic org.example.order.executions

kubectl exec kafka-broker-0 -- \
  /opt/kafka/bin/kafka-get-offsets.sh \
  --bootstrap-server localhost:9092 \
  --topic org.example.order.verification
```

Expected for every partition:

```text
...:0:0
...:1:0
...
...:5:0
```

### 4.4 Deploy a fresh four-pod processor

```bash
helm install order-processor kafka-streams/k8s/helm-chart \
  --set kafka.bootstrapServers=kafka-broker:29092 \
  --set replicas=4
```

Wait for all four pods:

```bash
kubectl wait \
  --for=condition=Ready \
  pod \
  -l app=order-processor \
  --timeout=180s
```

Allow Kafka Streams to complete assignment and reach a stable running state:

```bash
until kubectl exec kafka-broker-0 -- \
  /opt/kafka/bin/kafka-topics.sh \
  --bootstrap-server localhost:9092 \
  --describe \
  --topic order-state-processor-order-state-store-repartition \
  >/dev/null 2>&1; do
  echo "Waiting for Kafka Streams internal topic..."
  sleep 2
done

echo "Kafka Streams internal topic is available"
sleep 5
```

Verify four pods are running and no Flink pods are present:

```bash
kubectl get pods -l app=order-processor
kubectl get pods
```

### 4.5 Warm up with 1,000 iterations

```bash
kubectl delete job integration-test --ignore-not-found=true

kubectl apply \
  -f kafka-streams/k8s/tests/integration-test-job-1000.yaml

kubectl logs -f job/integration-test
```

Expected:

```text
messages=5000
verificationRecords=1000
hierarchyKeys=1000
processors=4
```

Do not include this result in the final comparison.

After the warm-up, verify that the input topic contains 5,000 records and the verification topic contains 1,000 final completion records:

```bash
kubectl exec kafka-broker-0 -- \
  /opt/kafka/bin/kafka-get-offsets.sh \
  --bootstrap-server localhost:9092 \
  --topic org.example.order.executions |
awk -F: '{sum += $3} END {print sum}'

kubectl exec kafka-broker-0 -- \
  /opt/kafka/bin/kafka-get-offsets.sh \
  --bootstrap-server localhost:9092 \
  --topic org.example.order.verification |
awk -F: '{sum += $3} END {print sum}'
```

Expected totals:

```text
executions:   5000
verification: 1000
```

### 4.6 Measure 10,000 iterations

Run the measured test on the same processor instance immediately after the warm-up:

```bash
kubectl delete job integration-test --ignore-not-found=true

kubectl apply \
  -f kafka-streams/k8s/tests/integration-test-job-10000.yaml

kubectl logs -f job/integration-test
```

Expected measured result:

```text
messages=50000
verificationRecords=10000
hierarchyKeys=10000
processors=4
```

Save the complete `SCENARIO_RESULT` line.

After the measured test, the total topic sizes should be:

```text
executions:   warm-up 5,000 + measured run 50,000 = 55,000
verification: warm-up 1,000 + measured run 10,000 = 11,000
```

Verify:

```bash
kubectl exec kafka-broker-0 -- \
  /opt/kafka/bin/kafka-get-offsets.sh \
  --bootstrap-server localhost:9092 \
  --topic org.example.order.executions |
awk -F: '{sum += $3} END {print sum}'

kubectl exec kafka-broker-0 -- \
  /opt/kafka/bin/kafka-get-offsets.sh \
  --bootstrap-server localhost:9092 \
  --topic org.example.order.verification |
awk -F: '{sum += $3} END {print sum}'
```

Expected totals:

```text
executions:   55000
verification: 11000
```

### 4.7 End the cycle

```bash
kubectl delete job integration-test --ignore-not-found=true
helm uninstall order-processor --ignore-not-found || true
```

Repeat Section 4 from the beginning for the next measured run.

## 5. Result fields

The test logs:

```text
SCENARIO_RESULT scenario=parent-order-fill \
messages=... \
verificationRecords=... \
hierarchyKeys=... \
processors=... \
inputSubmissionMs=... \
drainAfterSubmissionMs=... \
endToEndMs=... \
processingWindowMs=... \
endToEndMsgPerSec=... \
processingInputMsgPerSec=... \
completedHierarchiesPerSec=... \
processorDistribution={...}
```

Interpretation:

- `inputSubmissionMs`: test-data generation, serialization, Kafka sends, acknowledgements, and producer flush.
- `drainAfterSubmissionMs`: time from producer flush completion until the test receives one final record for every expected hierarchy.
- `endToEndMs`: total duration from test start until the final expected verification record is consumed. This is the primary end-to-end metric.
- `processingWindowMs`: `max(completedAt) - min(firstProcessedAt)` across the first accepted final record for every hierarchy.
- `endToEndMsgPerSec`: all 50,000 input messages divided by `endToEndMs`.
- `processingInputMsgPerSec`: all 50,000 input messages divided by `processingWindowMs`.
- `completedHierarchiesPerSec`: all 10,000 completed hierarchies divided by `processingWindowMs`.
- `processorDistribution`: completed hierarchies grouped by Kafka Streams pod identifier.

The submission and processing intervals overlap. Do not add `inputSubmissionMs` and `processingWindowMs`.

Duplicate final records from the same processor are ignored for counts and timing. A hierarchy observed from more than one processor fails the test.

## 6. Acceptance checklist

Accept a measured run only when:

- `messages == 50000`
- `verificationRecords == hierarchyKeys == 10000`
- four Kafka Streams pods participated
- each hierarchy was observed from one processor only
- the distribution follows the expected six-task-to-four-thread allocation
- no Flink job was running
- no processor restart, rebalance, OOM, or uncaught exception occurred during measurement
- effective configuration confirms `num.stream.threads=1` and `commit.interval.ms=100`
- the run followed a fresh processor deployment and one 1,000-iteration warm-up

## 7. Baseline A results

Configuration:

```text
6 input partitions
6 verification partitions
4 pods × 1 stream thread
commit.interval.ms = 100
at_least_once
RocksDB state store
1,000-iteration warm-up
10,000-iteration measured run
3 independent cycles
```

|        Run |   Messages | Input submission (ms) | Drain (ms) | End-to-end (ms) | Processing window (ms) | End-to-end msg/s | Processing input msg/s | Completed hierarchies/s |
|-----------:|-----------:|----------------------:|-----------:|----------------:|-----------------------:|-----------------:|-----------------------:|------------------------:|
|          1 |     50,000 |                 1,667 |      3,169 |           4,836 |                  4,492 |        10,339.12 |              11,130.90 |                2,226.18 |
|          2 |     50,000 |                 1,714 |      2,962 |           4,677 |                  4,317 |        10,690.61 |              11,582.12 |                2,316.42 |
|          3 |     50,000 |                 1,573 |      2,339 |           3,913 |                  3,626 |        12,777.92 |              13,789.30 |                2,757.86 |
| **Median** | **50,000** |             **1,667** |  **2,962** |       **4,677** |              **4,317** |    **10,690.61** |          **11,582.12** |            **2,316.42** |

The processor distribution consistently reflected six Kafka tasks assigned to four stream threads: two pods processed roughly twice as many hierarchies as the other two.

## 8. Baseline A comparison with Flink

Flink baseline configuration:

```text
6 input partitions
6 verification partitions
4 TaskManagers × 1 slot
parallelism = 4
HashMapStateBackend / current in-memory checkpoint setup
```

Median comparison:

| Metric                  | Kafka Streams |     Flink |                                     Relative result |
|-------------------------|--------------:|----------:|----------------------------------------------------:|
| Input submission (ms)   |         1,667 |     1,757 |                          similar producer-side cost |
| Drain (ms)              |         2,962 |       370 |                          Kafka Streams 8.01× longer |
| End-to-end (ms)         |         4,677 |     2,118 |               Flink 2.21× faster by completion time |
| Processing window (ms)  |         4,317 |     1,830 | Flink 2.36× faster in the observed processor window |
| End-to-end msg/s        |     10,690.61 | 23,607.18 |                                  Flink 2.21× higher |
| Processing input msg/s  |     11,582.12 | 27,322.40 |                                  Flink 2.36× higher |
| Completed hierarchies/s |      2,316.42 |  5,464.48 |                                  Flink 2.36× higher |

Interpretation:

- Input submission times were similar, so the result was not driven by the test producer.
- The dominant difference was the drain phase after producer flush.
- Kafka Streams used four stream threads for six partition tasks, creating an expected approximately 2:1 load split.
- Kafka Streams used its RocksDB-backed state store, while Flink used heap-backed state. The result is therefore implementation-specific rather than a universal framework ranking.

## 9. Follow-up status

Completed after this baseline:

- Kafka Streams `num.stream.threads=2` tuning experiment.
- Flink filesystem checkpoint-storage validation.
- Three durable Flink 10K runs.
- Three durable Flink 100K runs.
- Three Kafka Streams 100K runs.

The 100K comparison is stored separately so this 10K baseline remains reproducible.

## 10. Deferred large-state scenario

Do not use the old 100,000-iteration results in the direct comparison. They were produced by the previous verification methodology and the current Flink in-memory checkpoint setup was not suitable for that state size.

Before rerunning:

- configure durable Flink checkpoint storage,
- evaluate Embedded RocksDB,
- inspect serialization,
- document equal CPU and memory limits,
- use fresh state for every measured cycle.

## 11. Cleanup

```bash
kubectl delete job integration-test --ignore-not-found=true
helm uninstall order-processor --ignore-not-found || true
kubectl delete -f kafka-streams/k8s/kafka/ --ignore-not-found=true
```

## Benchmark environment

Benchmark date: 2026-06-24
All benchmark runs were executed locally on Docker Desktop Kubernetes using the following host and runtime environment.

### Host machine

```text
Model: MacBook Pro (Mac16,8)
Processor: Apple M4 Pro
CPU cores: 14 total
  - 10 performance cores
  - 4 efficiency cores
Memory: 24 GB
Architecture: arm64
Operating system: macOS 15.5
Build: 24F74
```

### Docker and Kubernetes

```text
Docker Desktop: 4.55.0
Docker Engine: 29.1.3
Container architecture: linux/arm64

Kubernetes client: 1.34.1
Kubernetes server: 1.34.1
Kubernetes node CPU capacity: 14
Kubernetes node allocatable CPU: 14
Kubernetes node memory capacity: 8,024,304 KiB
Kubernetes node allocatable memory: 7,921,904 KiB
```

Docker Desktop resource allocation:

```text
CPU limit: 14
Memory limit: 7.9 GB
```

Although the host contained 24 GB of physical memory, the Kubernetes workloads were constrained by the Docker Desktop virtual-machine memory allocation.

### Build environment

```text
Host JDK: OpenJDK 25.0.1
Gradle: 9.2.1
Gradle launcher JVM: OpenJDK 25.0.1
```

### Apache Flink environment

```text
Apache Flink: 2.2.1
Flink runtime JDK: Eclipse Temurin 17.0.19
State backend: HashMapStateBackend
Checkpoint storage: filesystem
Checkpoint directory: file:///flink-checkpoints
Parallelism: 4
TaskManagers: 4
Slots per TaskManager: 1
```

Resource allocation:

| Component         | Replicas | CPU request |  CPU limit | Memory request |   Memory limit |
|-------------------|---------:|------------:|-----------:|---------------:|---------------:|
| Flink JobManager  |        1 |        500m |      1 CPU |          1 GiB |      1,280 MiB |
| Flink TaskManager |        4 |   500m each | 1 CPU each |     1 GiB each | 1,280 MiB each |

The four TaskManagers therefore had a combined application-processing limit of four CPU cores. The JobManager had a separate one-core limit.

### Kafka Streams environment

```text
Kafka Streams library: 3.9.0
Kafka Streams runtime JDK: Eclipse Temurin 25.0.3
Application pods: 4
Stream threads per pod: 1
Processing guarantee: at_least_once
Commit interval: 100 ms
State store: local RocksDB with Kafka changelog topics
```

Resource allocation:

| Component               | Replicas | CPU request |  CPU limit | Memory request | Memory limit |
|-------------------------|---------:|------------:|-----------:|---------------:|-------------:|
| Kafka Streams processor |        4 |   500m each | 1 CPU each |   512 MiB each |   1 GiB each |

The four Kafka Streams processor pods therefore also had a combined application-processing limit of four CPU cores.

### Kafka broker

```text
Apache Kafka broker: 4.1.1
Input topic partitions: 6
Verification topic partitions: 6
```

The Kafka broker and test producer were shared infrastructure for both implementations.

### JVM-version caveat

Apache Flink ran on Java 17, while Kafka Streams ran on Java 25. The benchmark therefore compares the tested deployments rather than isolating the frameworks from their JVM runtimes.

Differences in JIT compilation, garbage collection, allocation behavior, and runtime optimizations may have affected absolute performance. The JVM-version difference should be treated as a benchmark limitation, although the newer Java runtime was used by Kafka Streams rather than Flink.
