package org.example.order.lifecycle.processor.config;

import org.example.order.lifecycle.service.FillOrderService;
import org.example.order.lifecycle.service.OrderStateUpdater;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * Configuration class for the order lifecycle processor domain.
 * <p>
 * This configuration class defines the beans required for the order lifecycle processor domain.
 * It includes the {@link OrderStateUpdater} and {@link FillOrderService} beans.
 * </p>
 */
@SuppressWarnings("unused")
@Configuration
public class OrderLifecycleDomainConfig {

    @Bean
    public OrderStateUpdater orderStateUpdater() {
        return new OrderStateUpdater();
    }

    @Bean
    public FillOrderService fillOrderService(OrderStateUpdater orderStateUpdater) {
        return new FillOrderService(orderStateUpdater);
    }
}