package com.waas.gateway;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;

/**
 * WebSocket Gateway — the one real service split (ARCHITECTURE.md §13).
 *
 * <p>It scales on concurrent connections, not on join volume, and holds no queue state:
 * Redis is the source of truth, so any number of gateway instances can run behind a
 * load balancer with zero changes to Core.
 */
@SpringBootApplication
public class WebSocketGatewayApplication {

    public static void main(String[] args) {
        SpringApplication.run(WebSocketGatewayApplication.class, args);
    }
}
