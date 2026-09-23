package com.waas.core;

import java.util.UUID;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.test.context.ActiveProfiles;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.containers.PostgreSQLContainer;

/**
 * Base for integration tests: one real Postgres + one real Redis for the whole test run.
 *
 * <p>"Singleton container" pattern: started once in a static block and never stopped by us
 * (Testcontainers' reaper removes them when the JVM exits). If each test class started its own
 * containers with {@code @Container}, Spring's cached application context would keep pointing
 * at the previous class's (now stopped) container ports.
 *
 * <p>Background jobs are off ({@code waas.scheduling.enabled=false}); tests call the sweeper and
 * reconciler directly, so nothing moves under a test's feet.
 */
@SpringBootTest(properties = "waas.scheduling.enabled=false")
@ActiveProfiles("demo")
public abstract class AbstractIntegrationTest {

    @ServiceConnection
    static final PostgreSQLContainer<?> POSTGRES = new PostgreSQLContainer<>("postgres:16-alpine");

    @ServiceConnection(name = "redis")
    static final GenericContainer<?> REDIS = new GenericContainer<>("redis:7-alpine").withExposedPorts(6379);

    static {
        POSTGRES.start();
        REDIS.start();
    }

    protected static final UUID DEMO_TENANT = UUID.fromString("00000000-0000-0000-0000-000000000001");
    protected static final UUID AIRMAX = UUID.fromString("10000000-0000-0000-0000-000000000001");

    @Autowired protected JdbcClient jdbc;

    /** A fresh, isolated waitlist per test, so tests never share queue state. */
    protected UUID newWaitlist(int servingCapacity, int reservationWindowSeconds) {
        return newWaitlist(servingCapacity, reservationWindowSeconds, 1);
    }

    protected UUID newWaitlist(int servingCapacity, int reservationWindowSeconds, int bumpAmount) {
        return jdbc.sql("""
                        INSERT INTO waitlist (tenant_id, name, serving_capacity, reservation_window_seconds, bump_amount)
                        VALUES (:tenant, :name, :capacity, :window, :bump)
                        RETURNING id
                        """)
                .param("tenant", DEMO_TENANT)
                .param("name", "test-" + UUID.randomUUID())
                .param("capacity", servingCapacity)
                .param("window", reservationWindowSeconds)
                .param("bump", bumpAmount)
                .query(UUID.class)
                .single();
    }

    protected UUID newUser(String name) {
        return jdbc.sql("""
                        INSERT INTO app_user (email, name, password_hash)
                        VALUES (:email, :name, 'x') RETURNING id
                        """)
                .param("email", name.toLowerCase() + "+" + UUID.randomUUID() + "@test.local")
                .param("name", name)
                .query(UUID.class)
                .single();
    }

    protected String stateOf(UUID entryId) {
        return jdbc.sql("SELECT state FROM waitlist_entry WHERE id = :id").param("id", entryId).query(String.class).single();
    }
}
