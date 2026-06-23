# Part 5 — Apache Flink 10K Baseline — Memory-Backed Checkpoint Storage

This document describes the repeatable Kubernetes procedure for the initial Apache Flink versus Kafka Streams baseline comparison.

> Historical Baseline A: 10,000 measured iterations with `HashMapStateBackend` and memory-backed checkpoint storage.  
> Keep this document unchanged as the non-durable baseline; durable 10K and 100K results are recorded separately.

## Scope of the current comparison

This is **Baseline A**:

```text
Apache Flink: heap-backed `HashMapStateBackend` with the current in-memory/checkpoint setup
Kafka Streams: local RocksDB state store with Kafka changelog topics
```

The configurations are intentionally recorded as implemented rather than presented as storage-equivalent. A later baseline will evaluate Flink with Embedded RocksDB and durable checkpoint storage.


Each independent measured run uses:

```text
fresh Flink job and empty managed state
warm-up: 1,000 iterations = 5,000 messages
measured run: 10,000 iterations = 50,000 messages
```

Run the complete sequence three times and report the median of the three measured results.

Do not perform one warm-up followed by three measured tests on the same long-running job. That would grow the keyed state between runs and create different starting conditions.

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

| Parameter                     |  Flink |
|-------------------------------|-------:|
| Input topic partitions        |      6 |
| Verification topic partitions |      6 |
| JobManager                    |      1 |
| TaskManagers                  |      4 |
| Slots per TaskManager         |      1 |
| Job parallelism               |      4 |
| Warm-up iterations            |  1,000 |
| Measured iterations           | 10,000 |
| Measured runs                 |      3 |

Confirm the Kubernetes manifests contain:

```text
flink-taskmanager replicas = 4
taskmanager.numberOfTaskSlots = 1
FLINK_PARALLELISM = 4
parallelism.default = 4
```

> Six Kafka source partitions feed four Flink processing subtasks. After `keyBy`, hierarchy keys are redistributed across the four subtasks, so the final hierarchy distribution is expected to be close to even.

## Prerequisites

- Docker Desktop with Kubernetes enabled
- Docker
- `kubectl`
- repository root as the current directory

## 1. Build the Flink application image

Build the fat JAR:

```bash
./gradlew :flink:clean :flink:shadowJar
```

Build the image. The Docker build context must be the `flink` directory because the Dockerfile copies `build/libs/...`.

```bash
docker build \
  --no-cache \
  -t order-lifecycle-flink:latest \
  -f flink/Dockerfile \
  flink
```

Verify:

```bash
docker image inspect order-lifecycle-flink:latest \
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

- positions the verification consumer at the end of the verification topic,
- waits for one final verification record per completed hierarchy,
- emits one final verification record per completed hierarchy,
- carries `firstProcessedAt` and `completedAt` timestamps captured inside the processor,
- reports submission, drain, end-to-end, processing-window, and hierarchy-completion metrics,
- accepts only the first final record for each hierarchy so at-least-once duplicates do not inflate the measurements.

## 3. Start Kafka once

Remove any application from an earlier experiment:

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

Wait for the broker and topic creator:

```bash
kubectl get pods -w
```

The in-cluster bootstrap address is:

```text
kafka-broker:29092
```

Verify that the input and verification topics both have six partitions:

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

## 4. Run one fresh Flink benchmark cycle

Repeat this complete section for Run 1, Run 2, and Run 3.

For direct comparability with Kafka Streams, every measured cycle starts with:

```text
fresh Kafka broker storage
fresh input and verification topics
fresh Flink cluster
fresh Flink job state
```

### 4.1 Remove the previous test, Flink job, and cluster

```bash
kubectl delete job integration-test --ignore-not-found=true
kubectl delete job flink-job-submitter --ignore-not-found=true

kubectl delete -f flink/k8s/flink-taskmanager.yaml --ignore-not-found=true
kubectl delete -f flink/k8s/flink-jobmanager.yaml --ignore-not-found=true

helm uninstall order-processor --ignore-not-found || true
```

Wait until all application pods disappear:

```bash
kubectl get pods
```

### 4.2 Remove Kafka and its benchmark PVC

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

Do not delete unrelated Kafka PVCs.

Verify:

```bash
kubectl get pvc
```

### 4.3 Start a fresh Kafka broker

```bash
kubectl apply -f kafka-streams/k8s/kafka/
```

Wait for Kafka and topic creation:

```bash
kubectl wait \
  --for=condition=Ready \
  pod/kafka-broker-0 \
  --timeout=180s

kubectl wait \
  --for=condition=Complete \
  job/kafka-topic-creator \
  --timeout=180s
```

Verify six partitions and zero offsets:

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

Every partition should end at offset zero.

### 4.4 Deploy a fresh Flink session cluster

```bash
kubectl apply -f flink/k8s/flink-configmap.yaml
kubectl apply -f flink/k8s/flink-jobmanager.yaml
kubectl apply -f flink/k8s/flink-taskmanager.yaml
```

Wait until ready:

```bash
kubectl rollout status deployment/flink-jobmanager
kubectl rollout status deployment/flink-taskmanager
```

Expected:

```text
1 JobManager
4 TaskManagers
1 slot per TaskManager
```

### 4.5 Submit a fresh Flink job

```bash
kubectl delete job flink-job-submitter --ignore-not-found=true

kubectl apply \
  -f flink/k8s/flink-job-submitter.yaml

kubectl logs -f job/flink-job-submitter
```

Verify that the job is running with parallelism four and no Kafka Streams processor is active.

Useful checks:

```bash
kubectl logs deployment/flink-jobmanager --tail=200
kubectl logs deployment/flink-taskmanager --tail=200
kubectl get pods
```

### 4.6 Warm up with 1,000 iterations

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

Verify that the input topic contains 5,000 records and the verification topic contains 1,000 final completion records:

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

### 4.7 Measure 10,000 iterations

```bash
kubectl delete job integration-test --ignore-not-found=true

kubectl apply \
  -f kafka-streams/k8s/tests/integration-test-job-10000.yaml

kubectl logs -f job/integration-test
```

Expected:

```text
messages=50000
verificationRecords=10000
hierarchyKeys=10000
processors=4
```

Save the complete `SCENARIO_RESULT` line.

After the measured test, the input topic should contain 55,000 records and the verification topic 11,000 final completion records:

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

### 4.8 Validate cluster health

```bash
kubectl logs deployment/flink-jobmanager --since=10m | \
  grep -iE 'OutOfMemoryError|checkpoint.*fail|failed checkpoint|exception' || true

kubectl logs deployment/flink-taskmanager --since=10m | \
  grep -iE 'OutOfMemoryError|checkpoint.*fail|failed checkpoint|exception' || true
```

Reject a run with an OOM, task recovery, or repeated checkpoint failure.

### 4.9 End the cycle

```bash
kubectl delete job integration-test --ignore-not-found=true
kubectl delete job flink-job-submitter --ignore-not-found=true

kubectl delete -f flink/k8s/flink-taskmanager.yaml --ignore-not-found=true
kubectl delete -f flink/k8s/flink-jobmanager.yaml --ignore-not-found=true
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
- `processorDistribution`: completed hierarchies grouped by Flink subtask identifier.

The submission and processing intervals overlap. Do not add `inputSubmissionMs` and `processingWindowMs`.

Duplicate final records from the same processor are ignored for counts and timing. A hierarchy observed from more than one processor fails the test.

## 6. Acceptance checklist

Accept a measured run only when:

- `messages == 50000`
- `verificationRecords == hierarchyKeys == 10000`
- four Flink subtasks participated
- every hierarchy was observed from one processor only
- the processor distribution is close to even
- no Kafka Streams processor was running
- no Flink task restarted or recovered during measurement
- no OOM or repeated checkpoint failure occurred
- the job started fresh and received exactly one 1,000-iteration warm-up before measurement

## 7. Baseline A results

Configuration:

```text
6 input partitions
6 verification partitions
4 TaskManagers × 1 slot
parallelism = 4
HashMapStateBackend / current in-memory checkpoint setup
1,000-iteration warm-up
10,000-iteration measured run
3 independent cycles
```

|        Run |   Messages | Input submission (ms) | Drain (ms) | End-to-end (ms) | Processing window (ms) | End-to-end msg/s | Processing input msg/s | Completed hierarchies/s |
|-----------:|-----------:|----------------------:|-----------:|----------------:|-----------------------:|-----------------:|-----------------------:|------------------------:|
|          1 |     50,000 |                 1,768 |        370 |           2,138 |                  1,885 |        23,386.34 |              26,525.20 |                5,305.04 |
|          2 |     50,000 |                 1,558 |        457 |           2,016 |                  1,765 |        24,801.59 |              28,328.61 |                5,665.72 |
|          3 |     50,000 |                 1,757 |        361 |           2,118 |                  1,830 |        23,607.18 |              27,322.40 |                5,464.48 |
| **Median** | **50,000** |             **1,757** |    **370** |       **2,118** |              **1,830** |    **23,607.18** |          **27,322.40** |            **5,464.48** |

Processor distribution was close to even in every run, with approximately 2,500 completed hierarchies per subtask.

## 8. Baseline A comparison with Kafka Streams

Kafka Streams baseline configuration:

```text
6 input partitions
6 verification partitions
4 pods × 1 stream thread
commit.interval.ms = 100
RocksDB state store
```

Median comparison:

| Metric                  |     Flink | Kafka Streams |                                     Relative result |
|-------------------------|----------:|--------------:|----------------------------------------------------:|
| Input submission (ms)   |     1,757 |         1,667 |                          similar producer-side cost |
| Drain (ms)              |       370 |         2,962 |                          Kafka Streams 8.01× longer |
| End-to-end (ms)         |     2,118 |         4,677 |               Flink 2.21× faster by completion time |
| Processing window (ms)  |     1,830 |         4,317 | Flink 2.36× faster in the observed processor window |
| End-to-end msg/s        | 23,607.18 |     10,690.61 |                                  Flink 2.21× higher |
| Processing input msg/s  | 27,322.40 |     11,582.12 |                                  Flink 2.36× higher |
| Completed hierarchies/s |  5,464.48 |      2,316.42 |                                  Flink 2.36× higher |

Interpretation:

- Input submission times were similar, so the result was not driven by the test producer.
- Most of the difference appeared after submission: Flink drained the remaining work in a median of 370 ms, compared with 2,962 ms for Kafka Streams.
- Flink redistributed hierarchy keys almost evenly across four subtasks.
- Kafka Streams assigned six partition tasks to four stream threads, producing an expected approximately 2:1 load split.
- The result must be described as a comparison of the current implementations: heap-backed Flink state versus RocksDB-backed Kafka Streams state. It is not evidence that Flink is universally 2.21× faster.

## 9. Follow-up status

Completed after this baseline:

- Kafka Streams `num.stream.threads=2` tuning experiment.
- Flink filesystem checkpoint-storage validation.
- Three durable Flink 10K runs.
- Three durable Flink 100K runs.
- Three Kafka Streams 100K runs.

The durable and large-state results are stored in separate documents so this historical baseline remains reproducible.

## 10. Deferred 100,000-iteration experiment

The initial attempt exposed:

```text
memory-backed checkpoint size limit exceeded
Java heap space during checkpoint materialization
```

Before rerunning 100,000 iterations:

1. configure durable filesystem/object-store checkpoint storage,
2. evaluate Embedded RocksDB as the state backend,
3. inspect and optionally optimize Flink type serialization,
4. document equivalent CPU and memory limits,
5. restart from a fresh processor state for every measured run.

Do not include the current 100,000-iteration result in the baseline comparison.

## 11. Cleanup

```bash
kubectl delete job integration-test --ignore-not-found=true
kubectl delete job flink-job-submitter --ignore-not-found=true

kubectl delete -f flink/k8s/flink-taskmanager.yaml --ignore-not-found=true
kubectl delete -f flink/k8s/flink-jobmanager.yaml --ignore-not-found=true
kubectl delete -f flink/k8s/flink-configmap.yaml --ignore-not-found=true

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
