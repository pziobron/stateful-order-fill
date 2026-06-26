# Part 5 — 100K Large-State Results — Flink versus Kafka Streams

This document records the 100,000-iteration large-state comparison between Apache Flink and Kafka Streams using the corrected shared integration test.

## Test model

Each hierarchy produces five input records:

1. parent order
2. first child order
3. second child order
4. fill for the first child order
5. fill for the second child order

Therefore each measured run contains:

```text
100,000 hierarchies
500,000 input messages
100,000 final verification records
```

Every measured cycle started from fresh Kafka storage and fresh processor state, followed by one 1,000-iteration warm-up.

## Configurations

### Apache Flink

```text
6 input partitions
6 verification partitions
4 TaskManagers × 1 slot
parallelism = 4
state.backend.type = hashmap
execution.checkpointing.storage = filesystem
execution.checkpointing.dir = file:///flink-checkpoints
3 independent measured cycles
```

Filesystem checkpoint storage was mounted as a shared writable volume. Runs were accepted only when processing completed, no task restarted or recovered, no checkpoint failed, and durable checkpoint files were created.

### Kafka Streams

```text
6 input partitions
6 verification partitions
4 application pods
num.stream.threads = 1
commit.interval.ms = 100
processing.guarantee = at_least_once
local RocksDB state store
Kafka changelog topics
3 independent measured cycles
```

## Apache Flink results

|        Run |    Messages | Input submission (ms) | Drain (ms) | End-to-end (ms) | Processing window (ms) | End-to-end msg/s | Processing input msg/s | Completed hierarchies/s |
|-----------:|------------:|----------------------:|-----------:|----------------:|-----------------------:|-----------------:|-----------------------:|------------------------:|
|          1 |     500,000 |                 7,289 |      2,044 |           9,333 |                  8,835 |        53,573.34 |              56,593.10 |               11,318.62 |
|          2 |     500,000 |                 7,651 |      1,980 |           9,632 |                  9,320 |        51,910.30 |              53,648.07 |               10,729.61 |
|          3 |     500,000 |                 7,428 |      1,945 |           9,373 |                  8,881 |        53,344.71 |              56,299.97 |               11,259.99 |
| **Median** | **500,000** |             **7,428** |  **1,980** |       **9,373** |              **8,881** |    **53,344.71** |          **56,299.97** |           **11,259.99** |

### Flink processor distribution

```text
Run 1: 24985, 24744, 25276, 24995
Run 2: 24751, 25075, 25214, 24960
Run 3: 25116, 25116, 25161, 24607
```

The load remained close to evenly distributed across all four subtasks.

## Kafka Streams results

|        Run |    Messages | Input submission (ms) | Drain (ms) | End-to-end (ms) | Processing window (ms) | End-to-end msg/s | Processing input msg/s | Completed hierarchies/s |
|-----------:|------------:|----------------------:|-----------:|----------------:|-----------------------:|-----------------:|-----------------------:|------------------------:|
|          1 |     500,000 |                 8,276 |     14,374 |          22,650 |                 22,289 |        22,075.06 |              22,432.59 |                4,486.52 |
|          2 |     500,000 |                 7,158 |     14,054 |          21,212 |                 20,837 |        23,571.56 |              23,995.78 |                4,799.16 |
|          3 |     500,000 |                 7,241 |     14,109 |          21,350 |                 20,931 |        23,419.20 |              23,888.01 |                4,777.60 |
| **Median** | **500,000** |             **7,241** | **14,109** |      **21,350** |             **20,931** |    **23,419.20** |          **23,888.01** |            **4,777.60** |

### Kafka Streams processor distribution

```text
Run 1: 33336, 16554, 33328, 16782
Run 2: 33253, 16624, 16870, 33253
Run 3: 33239, 16734, 33470, 16557
```

The approximately 2:1 split is expected because six partition tasks are assigned to four stream threads: two threads own two tasks and two threads own one task.

## Median comparison

| Metric                  |     Flink | Kafka Streams |            Relative result |
|-------------------------|----------:|--------------:|---------------------------:|
| Input submission (ms)   |     7,428 |         7,241 | similar producer-side cost |
| Drain (ms)              |     1,980 |        14,109 | Kafka Streams 7.13× longer |
| End-to-end (ms)         |     9,373 |        21,350 |         Flink 2.28× faster |
| Processing window (ms)  |     8,881 |        20,931 |         Flink 2.36× faster |
| End-to-end msg/s        | 53,344.71 |     23,419.20 |         Flink 2.28× higher |
| Processing input msg/s  | 56,299.97 |     23,888.01 |         Flink 2.36× higher |
| Completed hierarchies/s | 11,259.99 |      4,777.60 |         Flink 2.36× higher |

## Initial rejected Flink 100K attempt

The first Flink 100K attempt used memory-backed checkpoint storage. Processing produced all expected verification records, but subsequent checkpoints failed with two related errors:

```text
Size of the state is larger than the maximum permitted memory-backed state.
maxSize = 5,242,880 bytes

java.lang.OutOfMemoryError: Java heap space
during HeapSnapshotStrategy / Kryo serialization
```

That run was rejected because successful processing without a successful checkpoint did not satisfy the fault-tolerance acceptance criteria.

After changing only checkpoint storage to filesystem storage, all three Flink 100K runs completed without checkpoint errors.

## Interpretation

- Input submission medians differed by only 187 ms, so the producer was not the main source of the performance gap.
- Most of the difference appeared in the drain phase after producer flush.
- Flink drained the remaining workload in a median of 1.980 seconds.
- Kafka Streams required a median of 14.109 seconds for the same phase.
- Flink retained close-to-even key distribution across four processing subtasks.
- Kafka Streams retained the expected 2:1 task imbalance caused by mapping six partition tasks to four stream threads.
- The comparison is implementation-specific. Flink used heap-backed working state with durable filesystem checkpoints, while Kafka Streams used local RocksDB with Kafka changelog topics.
- The checkpoint mechanisms are architecturally different. Flink checkpoint intervals and Kafka Streams commit intervals should not be treated as directly equivalent durability controls.

## Conclusion

For this workload and deployment configuration, Apache Flink processed 500,000 input messages in a median of 9.373 seconds, compared with 21.350 seconds for Kafka Streams.

Flink achieved:

```text
2.28× higher end-to-end throughput
2.36× higher processing-window throughput
```

These results do not establish a universal framework ranking. They apply to the tested topology, state model, partition count, resource limits, serializer choices, and durability configurations.


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
