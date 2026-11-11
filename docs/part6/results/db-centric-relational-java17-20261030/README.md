# PostgreSQL relational benchmark — Java 17 — 2026-10-30

Historical PostgreSQL results, preserved separately from the
[Java 25 rerun](../db-centric-relational-java25-20261111/README.md).
The [original combined report](../README.md) retains the deployment details,
acceptance criteria, and Kafka Streams/Flink comparison.

| Workload | End-to-end | Input messages/s |
|---|---:|---:|
| 10K — median of three runs | 15.391 s | 3,248.65 |
| 100K — single exploratory run | 121.189 s | 4,125.79 |

Implementation revision: `9dee62acb1ac9058139d03a243d2536ea951d705`.

- [Measurement CSV](benchmark-results.csv), [warm-up CSV](warmup-results.csv):
  PostgreSQL rows extracted from the original combined CSVs, which remain intact
  in the parent directory.
- [10K measured logs](db-centric-10k-raw.txt).
- [10K warm-up logs](db-centric-10k-warmups-raw.txt).
- [100K measured log](db-centric-100k-raw.txt).
- [100K warm-up log](db-centric-1k-warmup-raw.txt).
- [100K validation report](db-centric-100k-validation.md).

Raw logs and the validation report were moved without changing their contents.
