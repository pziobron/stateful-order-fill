| Implementation | Processing deployment | Total CPU limit | Total memory limit | JVM |
|---|---|---:|---:|---|
| Kafka Streams | Four pods, one stream thread per pod | 4 CPU | 4 GiB | Java 25 |
| Apache Flink | Four TaskManagers plus one JobManager | 5 CPU | 6.25 GiB | Java 17 |
| PostgreSQL | Four consumer pods plus one PostgreSQL primary | 4 CPU | 4 GiB | Java 25 |
