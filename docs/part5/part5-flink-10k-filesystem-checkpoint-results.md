# Part 5 — Apache Flink 10K — Durable Filesystem Checkpoint Storage

This document records the repeatable 10,000-iteration Apache Flink benchmark after replacing memory-backed checkpoint storage with durable filesystem checkpoint storage.

## Purpose

The original 10K Flink baseline used:

```text
HashMapStateBackend
memory-backed checkpoint storage
```

That configuration was fast at 10K, but the 100K workload exposed two checkpoint failures:

```text
memory-backed checkpoint state exceeded the 5 MiB limit
Java heap exhaustion while materializing a checkpoint snapshot
```

The state backend was deliberately left unchanged. Only checkpoint storage was changed so the effect of durable checkpoint persistence could be measured independently.

## Effective configuration

```text
Input topic partitions: 6
Verification topic partitions: 6
TaskManagers: 4
Slots per TaskManager: 1
Parallelism: 4

state.backend.type: hashmap
execution.checkpointing.storage: filesystem
execution.checkpointing.dir: file:///flink-checkpoints
execution.checkpointing.num-retained: 2
execution.checkpointing.timeout: 120000
execution.checkpointing.min-pause: 1000
execution.checkpointing.max-concurrent-checkpoints: 1

Warm-up: 1,000 iterations = 5,000 messages
Measured run: 10,000 iterations = 50,000 messages
Independent measured cycles: 3
```

The checkpoint directory was mounted as a shared writable volume in the JobManager and all TaskManagers.

## Run procedure

Each measured cycle used:

```text
fresh Kafka broker storage
fresh input and verification topics
fresh Flink cluster
fresh Flink job and empty managed state
one 1,000-iteration warm-up
one 10,000-iteration measured run
```

A run was accepted only when:

- `messages == 50000`
- `verificationRecords == hierarchyKeys == 10000`
- four Flink subtasks participated
- no task restarted or recovered
- no checkpoint failed
- no `OutOfMemoryError` occurred
- durable checkpoint files were created successfully

## Results

|        Run |   Messages | Input submission (ms) | Drain (ms) | End-to-end (ms) | Processing window (ms) | End-to-end msg/s | Processing input msg/s | Completed hierarchies/s |
|-----------:|-----------:|----------------------:|-----------:|----------------:|-----------------------:|-----------------:|-----------------------:|------------------------:|
|          1 |     50,000 |                 1,843 |        446 |           2,289 |                  2,029 |        21,843.60 |              24,642.68 |                4,928.54 |
|          2 |     50,000 |                 1,685 |        452 |           2,138 |                  1,915 |        23,386.34 |              26,109.66 |                5,221.93 |
|          3 |     50,000 |                 1,718 |        663 |           2,382 |                  2,096 |        20,990.76 |              23,854.96 |                4,770.99 |
| **Median** | **50,000** |             **1,718** |    **452** |       **2,289** |              **2,029** |    **21,843.60** |          **24,642.68** |            **4,928.54** |

## Processor distribution

All runs were close to evenly distributed across four subtasks:

```text
Run 1: 2501, 2466, 2546, 2487
Run 2: 2539, 2486, 2497, 2478
Run 3: 2508, 2546, 2459, 2487
```

The even distribution is expected because hierarchy keys are redistributed after `keyBy`.

## Comparison with the original memory-backed 10K baseline

| Metric                  | Memory-backed checkpoint median | Filesystem checkpoint median | Change |
|-------------------------|--------------------------------:|-----------------------------:|-------:|
| Input submission (ms)   |                           1,757 |                        1,718 |  -2.2% |
| Drain (ms)              |                             370 |                          452 | +22.2% |
| End-to-end (ms)         |                           2,118 |                        2,289 |  +8.1% |
| Processing window (ms)  |                           1,830 |                        2,029 | +10.9% |
| End-to-end msg/s        |                       23,607.18 |                    21,843.60 |  -7.5% |
| Processing input msg/s  |                       27,322.40 |                    24,642.68 |  -9.8% |
| Completed hierarchies/s |                        5,464.48 |                     4,928.54 |  -9.8% |

The durable configuration was modestly slower at 10K, but it removed the memory-backed checkpoint-size limitation and supported the 100K workload without checkpoint failures.

## Interpretation

- Producer-side submission time remained effectively unchanged.
- Durable checkpoint storage increased median end-to-end time by about 8%.
- The processing-window throughput decreased by about 10%.
- The cost is small enough that the durable configuration remains the more meaningful Flink baseline for large-state testing.
- `HashMapStateBackend` still keeps working state on the TaskManager heap. Filesystem checkpoint storage changes where snapshots are persisted; it does not move working state out of heap memory.

## Conclusion

With durable filesystem checkpoint storage enabled, Flink completed the 50,000-message workload in a median of 2.289 seconds and sustained a median processing-window throughput of 24,642.68 input messages per second.

This configuration should be used as the durable 10K reference. The original memory-backed result should remain available as a separate historical baseline rather than being overwritten.


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
