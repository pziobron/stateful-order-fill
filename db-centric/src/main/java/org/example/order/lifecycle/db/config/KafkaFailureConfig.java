package org.example.order.lifecycle.db.config;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.kafka.listener.CommonContainerStoppingErrorHandler;

@Configuration
public class KafkaFailureConfig {
    @Bean
    CommonContainerStoppingErrorHandler kafkaErrorHandler() {
        // Fail closed: never skip a bad record or commit its offset. Operators
        // must resolve the problem and restart the consumer/container. The container
        // may commit the successful prefix of a poll before invoking this handler;
        // any DB-committed input replayed on restart is recognized by processed_input.
        return new CommonContainerStoppingErrorHandler();
    }
}
