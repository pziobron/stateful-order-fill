package org.example.order.lifecycle.db;

import org.example.order.lifecycle.db.kafka.ExecutionReportListener;
import org.example.order.lifecycle.db.service.*;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.PlatformTransactionManager;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;

class StorageSelectionTest {
    private final ApplicationContextRunner runner = new ApplicationContextRunner()
            .withUserConfiguration(Components.class)
            .withBean(JdbcTemplate.class, () -> mock(JdbcTemplate.class))
            .withBean(PlatformTransactionManager.class, () -> mock(PlatformTransactionManager.class))
            .withBean(CompletionSender.class, () -> (topic, key, payload) -> {})
            .withPropertyValues("app.processor-id=test", "app.verification-topic=verification");

    @Configuration(proxyBeanMethods = false)
    @Import({RelationalLifecycleProcessor.class, JsonbLifecycleProcessor.class,
            RelationalCompletionStore.class, JsonbCompletionStore.class,
            ExecutionReportListener.class, CompletionPublisher.class})
    static class Components {}

    @Test
    void defaultsToRelationalStorage() {
        runner.run(context -> {
            assertThat(context).hasSingleBean(LifecycleProcessor.class).hasSingleBean(CompletionStore.class);
            assertThat(context.getBean(LifecycleProcessor.class)).isInstanceOf(RelationalLifecycleProcessor.class);
            assertThat(context.getBean(CompletionStore.class)).isInstanceOf(RelationalCompletionStore.class);
        });
    }

    @Test
    void selectsJsonbForBothProcessingAndPublication() {
        runner.withPropertyValues("app.state-storage=jsonb").run(context -> {
            assertThat(context).hasSingleBean(LifecycleProcessor.class).hasSingleBean(CompletionStore.class);
            assertThat(context.getBean(LifecycleProcessor.class)).isInstanceOf(JsonbLifecycleProcessor.class);
            assertThat(context.getBean(CompletionStore.class)).isInstanceOf(JsonbCompletionStore.class);
        });
    }

    @Test
    void rejectsUnknownStorageInsteadOfSilentlyFallingBack() {
        runner.withPropertyValues("app.state-storage=unknown").run(context -> assertThat(context).hasFailed());
    }
}
