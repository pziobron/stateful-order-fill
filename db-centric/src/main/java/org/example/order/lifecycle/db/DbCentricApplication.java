package org.example.order.lifecycle.db;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.scheduling.annotation.EnableScheduling;

@EnableScheduling
@SpringBootApplication
public class DbCentricApplication {
    public static void main(String[] args) {
        SpringApplication.run(DbCentricApplication.class, args);
    }
}
