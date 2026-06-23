package org.example.order.lifecycle.flink.config;

/**
 * Configuration properties for the Order Lifecycle Flink job.
 * <p>
 * This configuration defines Kafka connectivity, source and sink topics,
 * consumer offset behaviour, Flink execution settings, and runtime metadata
 * used by the application.
 * <p>
 * Values are loaded from JVM system properties and environment variables,
 * allowing the same job to run locally, in containers, or in Kubernetes
 * without code changes.
 *
 * @param bootstrapServers      Kafka bootstrap servers used by the source and sink connectors
 * @param executionReportsTopic Kafka topic containing incoming execution reports
 * @param verificationTopic     Kafka topic used for publishing verification records
 * @param offsetReset           Kafka consumer offset reset strategy ("latest" or "earliest")
 * @param groupId               Kafka consumer group identifier used by the Flink source
 * @param podId                 Identifier of the Kubernetes pod processing the records
 * @param parallelism           Flink job parallelism level
 * @param printLifecycle       Enables printing lifecycle updates to stdout
 * @param checkpointIntervalMs Flink checkpoint interval in milliseconds
 * @param commitOffsetsOnCheckpoint Enables committing offsets on checkpoint
 */
public record OrderLifecycleFlinkConfig(
        String bootstrapServers,
        String executionReportsTopic,
        String verificationTopic,
        String offsetReset,
        String groupId,
        String podId,
        int parallelism,
        boolean printLifecycle,
        long checkpointIntervalMs,
        boolean commitOffsetsOnCheckpoint
) {

    /**
     * Creates a configuration instance using JVM system properties
     * and environment variables.
     * <p>
     * Default values are provided for local development and testing.
     *
     * @return configured {@link OrderLifecycleFlinkConfig} instance
     */
    public static OrderLifecycleFlinkConfig fromSystemProperties() {
        return new OrderLifecycleFlinkConfig(
                propertyOrEnvironment(
                        "kafka.bootstrap.servers",
                        "KAFKA_BOOTSTRAP_SERVERS",
                        "localhost:9092"
                ),
                propertyOrEnvironment(
                        "kafka.executions.topic",
                        "KAFKA_EXECUTIONS_TOPIC",
                        "org.example.order.executions"
                ),
                propertyOrEnvironment(
                        "kafka.verification.topic",
                        "KAFKA_VERIFICATION_TOPIC",
                        "org.example.order.verification"
                ),
                propertyOrEnvironment(
                        "flink.offset.reset",
                        "FLINK_OFFSET_RESET",
                        "latest"
                ),
                propertyOrEnvironment(
                        "flink.group.id",
                        "FLINK_GROUP_ID",
                        "order-lifecycle-flink-local"
                ),
                System.getenv().getOrDefault("POD_ID", "unknown"),
                Integer.parseInt(
                        propertyOrEnvironment(
                                "flink.parallelism",
                                "FLINK_PARALLELISM",
                                "6"
                        )
                ),
                Boolean.parseBoolean(
                        propertyOrEnvironment(
                                "flink.print.lifecycle",
                                "FLINK_PRINT_LIFECYCLE",
                                "false"
                        )
                ),
                Long.parseLong(
                        propertyOrEnvironment(
                                "flink.checkpoint.interval.ms",
                                "FLINK_CHECKPOINT_INTERVAL_MS",
                                "10000"
                        )
                ),
                Boolean.parseBoolean(
                        propertyOrEnvironment(
                                "kafka.commit.offsets.on.checkpoint",
                                "KAFKA_COMMIT_OFFSETS_ON_CHECKPOINT",
                                "true"
                        )
                )
        );
    }

    private static String propertyOrEnvironment(
            String propertyName,
            String environmentName,
            String defaultValue
    ) {
        String propertyValue = System.getProperty(propertyName);

        if (propertyValue != null && !propertyValue.isBlank()) {
            return propertyValue;
        }

        String environmentValue = System.getenv(environmentName);

        if (environmentValue != null && !environmentValue.isBlank()) {
            return environmentValue;
        }

        return defaultValue;
    }
}