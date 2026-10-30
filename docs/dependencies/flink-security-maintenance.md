# Flink dependency maintenance — Part 6

Reviewed on 2026-10-28 following IDE/Mend findings. This change keeps Flink 2.2.1,
Kafka connector 5.0.0-2.2, and the resolved Kafka client 4.2.0 unchanged.

## Dependency changes

| Component | Previous resolved version | Updated version | Scope |
|---|---|---|---|
| Snappy Java | 1.1.10.7 | 1.1.10.12 | Flink runtime and tests |
| yawkat LZ4 Java | 1.10.3 | 1.12.1 | Flink runtime and tests |
| Logback classic/core | 1.5.18 | 1.6.6 | Flink runtime and tests |
| AssertJ | 3.27.3 | 3.27.7 | Flink tests only |

Snappy and LZ4 minimum versions are expressed as dependency constraints in the
Flink module, with versions in the shared catalog. The constraints upgrade
existing dependencies rather than adding unused compression libraries. Logback's
catalog version is updated; Spring Boot dependency management in other modules
is a separate resolution mechanism, so this is not a repository-wide security
upgrade or an audit of Kafka Streams dependencies.

Upstream references:

- [Snappy 1.1.10.9](https://github.com/xerial/snappy-java/releases/tag/v1.1.10.9)
  fixes CVE-2026-90559; [1.1.10.12](https://github.com/xerial/snappy-java/releases/tag/v1.1.10.12)
  includes subsequent compatibility fixes.
- [LZ4 releases](https://github.com/yawkat/lz4-java/releases) document native
  loading and input validation fixes, including security release 1.12.1.
- [Logback release notes](https://logback.qos.ch/news.html) describe the MDC path
  fixes in 1.6.3 and 1.6.5; 1.6.6 is the selected maintenance release. The current
  console configuration does not use MDC-derived file paths or Janino conditions.
- [AssertJ advisory](https://github.com/assertj/assertj/security/advisories/GHSA-rqfh-9r24-8c9r)
  identifies 3.27.7 as the fix for CVE-2026-24400.

The broad flink-test-utils dependency is replaced with non-transitive runtime
and core test JARs plus flink-test-utils-junit. AssertJ and Mockito are declared
explicitly for those utilities. This removes curator-test and its unshaded
ZooKeeper 3.7.1, Guava 31.1-jre, and Netty 4.1.76.Final dependencies, as well as
unneeded Log4j 2.24.3 test dependencies. These were test dependencies, not additions
to the application JAR. Future harness upgrades may require revisiting this
explicit dependency set.

## Validation and boundaries

Validation commands:

```bash
./gradlew :common:test :kafka-streams:test :flink:test :flink:shadowJar
./gradlew :flink:dependencies --configuration runtimeClasspath
./gradlew :flink:dependencies --configuration testRuntimeClasspath
```

The operator tests exercise managed state snapshots and restoration. The codec
compatibility tests write and read Kafka batches using Snappy and LZ4, including
payloads crossing block boundaries and empty payloads. They are local compatibility
checks, not exploit tests or broker/cluster integration tests.

The resolved dependency trees were checked for the selected versions and removal
of the unwanted test dependency branches. The shadow JAR was inspected to confirm
that JUnit, AssertJ, Mockito, Curator, unshaded ZooKeeper/Guava/Netty, and Log4j
classes are absent. Flink still includes its own shaded dependencies.

This is not a complete vulnerability scan. Mend must be refreshed separately.
The existing flink:2.2.1-java17 container image and its preinstalled runtime JARs
are unchanged and were not scanned or deployed. Class loading in that runtime,
its shaded dependencies, and native binaries require separate image-level
validation; Gradle constraints cannot update embedded or preinstalled libraries.

Historical Part 5 benchmarks used older dependencies. Rebuild the application
artifact and image before new experiments, record the versions used, and do not
attribute any performance change solely to processing logic. No benchmark was
run as part of this maintenance change.
