package com.waas.core;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.jdbc.core.simple.JdbcClient;

/** Brick 1 smoke test: schema + demo seed applied, Redis reachable. */
class InfrastructureTest extends AbstractIntegrationTest {

    @Autowired JdbcClient jdbc;
    @Autowired StringRedisTemplate redisTemplate;

    @Test
    void schemaAndSeedAreApplied() {
        List<String> waitingOrder = jdbc.sql("""
                SELECT u.name
                FROM waitlist_entry e JOIN app_user u ON u.id = e.user_id
                WHERE e.waitlist_id = '10000000-0000-0000-0000-000000000001'
                  AND e.state = 'WAITING'
                ORDER BY e.queue_score
                """).query(String.class).list();

        // Alice joined 6th but her referral bumps put her at the head (§21.3 story).
        assertThat(waitingOrder).startsWith("Alice", "Bob", "Carol");
    }

    @Test
    void bumpedIsNotAPersistableState() {
        // §21.11: BUMPED is an event; the state CHECK must not allow it.
        int rows = jdbc.sql("""
                SELECT COUNT(*) FROM information_schema.check_constraints
                WHERE check_clause LIKE '%WAITING%' AND check_clause LIKE '%BUMPED%'
                """).query(Integer.class).single();
        assertThat(rows).isZero();
    }

    @Test
    void redisIsReachable() {
        redisTemplate.opsForValue().set("waas:smoke", "ok");
        assertThat(redisTemplate.opsForValue().get("waas:smoke")).isEqualTo("ok");
    }
}
