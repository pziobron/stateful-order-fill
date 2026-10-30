package org.example.order.lifecycle.db.config;

import org.slf4j.LoggerFactory;
import org.springframework.boot.ApplicationRunner;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.env.Environment;

@Configuration
public class StartupConfig {
    @Bean
    ApplicationRunner describeProcessingConfiguration(Environment environment) {
        return arguments -> LoggerFactory.getLogger(StartupConfig.class).info(
                "DB-centric config: bootstrapServers={}, executionsTopic={}, verificationTopic={}, processorId={}, consumerEnabled={}, group={}, source={}, consumers={}, pool={}, ackMode={}, autoCommit={}, outboxEnabled={}, outboxDelayMs={}",
                environment.getProperty("spring.kafka.bootstrap-servers"),
                environment.getProperty("app.executions-topic"),
                environment.getProperty("app.verification-topic"),
                environment.getProperty("app.processor-id"),
                environment.getProperty("spring.kafka.listener.auto-startup"),
                environment.getProperty("spring.kafka.consumer.group-id"),
                environment.getProperty("app.source-id"),
                environment.getProperty("spring.kafka.listener.concurrency"),
                environment.getProperty("spring.datasource.hikari.maximum-pool-size"),
                environment.getProperty("spring.kafka.listener.ack-mode"),
                environment.getProperty("spring.kafka.consumer.enable-auto-commit"),
                environment.getProperty("app.outbox-enabled"),
                environment.getProperty("app.outbox-delay-ms"));
    }
}
