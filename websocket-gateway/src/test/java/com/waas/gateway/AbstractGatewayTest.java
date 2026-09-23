package com.waas.gateway;

import com.sun.net.httpserver.HttpServer;
import java.io.IOException;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.containers.GenericContainer;

/**
 * Real gateway on a random port, real Redis, and a <b>fake Core</b>: the JDK's built-in HTTP
 * server answering with canned JSON per path (tests set {@link #CORE_RESPONSES}). The gateway
 * only relays Core's JSON, so a canned body is enough to prove the plumbing.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
public abstract class AbstractGatewayTest {

    @ServiceConnection(name = "redis")
    static final GenericContainer<?> REDIS = new GenericContainer<>("redis:7-alpine").withExposedPorts(6379);

    protected static final Map<String, String> CORE_RESPONSES = new ConcurrentHashMap<>();
    static final HttpServer FAKE_CORE;

    static {
        REDIS.start();
        try {
            FAKE_CORE = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        } catch (IOException e) {
            throw new IllegalStateException(e);
        }
        FAKE_CORE.createContext("/", exchange -> {
            String body = CORE_RESPONSES.get(exchange.getRequestURI().getPath());
            byte[] bytes = (body == null ? "{}" : body).getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().add("Content-Type", "application/json");
            exchange.sendResponseHeaders(body == null ? 404 : 200, bytes.length);
            try (OutputStream out = exchange.getResponseBody()) {
                out.write(bytes);
            }
        });
        FAKE_CORE.start();
    }

    @DynamicPropertySource
    static void coreUrl(DynamicPropertyRegistry registry) {
        registry.add("gateway.core-url", () -> "http://127.0.0.1:" + FAKE_CORE.getAddress().getPort());
        registry.add("gateway.fanout.tick-ms", () -> "100");
        // No background full refresh during tests: every push must be caused by the test itself.
        registry.add("gateway.fanout.full-refresh-ms", () -> "600000");
    }
}
