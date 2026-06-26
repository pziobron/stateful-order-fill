# Part 5 — Kafka Streams versus Apache Flink

This directory contains the benchmark methodology, results, and supporting notes for Part 5 of the Stateful Order Fill Matching series.

## Benchmark documents

### 10K baseline

- [Kafka Streams 10K baseline](part5-kafka-streams-10k-baseline.md)
- [Flink 10K memory-checkpoint baseline](part5-flink-10k-memory-checkpoint-baseline.md)
- [Flink 10K filesystem-checkpoint results](part5-flink-10k-filesystem-checkpoint-results.md)

### 100K large-state comparison

- [100K benchmark scenario](part5-100k-large-state-benchmark-scenario.md)
- [Flink versus Kafka Streams results](part5-100k-large-state-flink-vs-kafka-streams-results.md)

## Raw results

Kafka Streams:

- [10K raw results](../../kafka-streams/benchmark-results/kafka-streams-10k-raw.txt)
- [10K, two-thread raw results](../../kafka-streams/benchmark-results/kafka-streams-10k-2-threads-raw.txt)
- [100K Java 25 raw results](../../kafka-streams/benchmark-results/kafka-streams-100k-raw.txt)
- [100K Java 17 control results](../../kafka-streams/benchmark-results/kafka-streams-100k-jdk17-raw.txt)

Apache Flink:

- [10K memory-checkpoint raw results](../../flink/benchmark-results/flink-10k-memory-checkpoint-raw.txt)
- [10K filesystem-checkpoint raw results](../../flink/benchmark-results/flink-10k-filesystem-checkpoint-raw.txt)
- [100K filesystem-checkpoint raw results](../../flink/benchmark-results/flink-100k-filesystem-checkpoint-raw.txt)