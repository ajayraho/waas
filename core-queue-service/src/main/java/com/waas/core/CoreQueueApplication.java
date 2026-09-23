package com.waas.core;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;

/**
 * Core Queue Service — the modular monolith.
 *
 * <p>One deployable, several bounded contexts (ARCHITECTURE.md §13). Each context is a
 * top-level package under {@code com.waas.core}; see each {@code package-info.java} for
 * what it owns and which way its dependencies are allowed to point.
 */
@SpringBootApplication
public class CoreQueueApplication {

    public static void main(String[] args) {
        SpringApplication.run(CoreQueueApplication.class, args);
    }
}
