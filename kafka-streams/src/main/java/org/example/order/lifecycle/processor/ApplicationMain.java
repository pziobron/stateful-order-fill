package org.example.order.lifecycle.processor;

import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;

/**
 * Main entry point for the Order Lifecycle Processor application.
 * <p>
 * This Spring Boot application is responsible for processing order lifecycle events using Kafka Streams.
 * It configures and starts the Spring application context, which includes the necessary
 * Kafka Streams topology for handling order processing workflows.
 * </p>
 */
@Slf4j
@SpringBootApplication
public class ApplicationMain {
    /**
     * Main method that serves as the entry point of the application.
     *
     * @param args command line arguments passed to the application
     */
    public static void main(String[] args) {
        SpringApplication.run(ApplicationMain.class, args);
    }
}