package org.example.order.lifecycle.processor.config;

import org.apache.kafka.streams.StreamsConfig;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;

import java.util.Properties;

import static org.junit.jupiter.api.Assertions.*;

class KafkaStreamsConfigTest {
    // Load only configuration beans: no broker connection or stream startup.
    private final ApplicationContextRunner contextRunner = new ApplicationContextRunner()
            .withUserConfiguration(KafkaStreamsConfig.class)
            .withPropertyValues(
                    "spring.application.name=config-test",
                    "spring.kafka.bootstrap-servers=localhost:9092");

    @Test
    void defaultsToOneThreadAndAtLeastOnce() {
        contextRunner.run(context -> {
            assertNull(context.getStartupFailure());
            var config = new StreamsConfig(context.getBean("kafkaStreamsProperties", Properties.class));
            assertEquals("config-test", config.getString(StreamsConfig.APPLICATION_ID_CONFIG));
            assertEquals(1, config.getInt(StreamsConfig.NUM_STREAM_THREADS_CONFIG));
            assertEquals(StreamsConfig.AT_LEAST_ONCE,
                    config.getString(StreamsConfig.PROCESSING_GUARANTEE_CONFIG));
            assertEquals(30000L, config.getLong(StreamsConfig.COMMIT_INTERVAL_MS_CONFIG));
        });
    }

    @Test
    void forwardsHelmPropertyNamesToTheActualStreamsConfiguration() {
        contextRunner.withPropertyValues(
                "spring.kafka.streams.num-stream-threads=2",
                "spring.kafka.streams.processing-guarantee=exactly_once_v2",
                "spring.kafka.streams.properties.commit.interval.ms=100")
                .run(context -> {
                    assertNull(context.getStartupFailure());
                    var config = new StreamsConfig(context.getBean("kafkaStreamsProperties", Properties.class));
                    assertEquals(2, config.getInt(StreamsConfig.NUM_STREAM_THREADS_CONFIG));
                    assertEquals(StreamsConfig.EXACTLY_ONCE_V2,
                            config.getString(StreamsConfig.PROCESSING_GUARANTEE_CONFIG));
                    assertEquals(100L, config.getLong(StreamsConfig.COMMIT_INTERVAL_MS_CONFIG));
                });
    }
}
