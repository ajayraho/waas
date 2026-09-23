package com.waas.core.reservation;

import static org.assertj.core.api.Assertions.assertThat;

import com.waas.core.AbstractIntegrationTest;
import com.waas.core.common.redis.RedisKeys;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.redis.core.StringRedisTemplate;

/**
 * The double-reservation race, shown both ways. 100 people waiting, 3 slots, 200 threads
 * all trying to promote at the same moment.
 *
 * Naive: check ZCARD, then ZPOPMIN + ZADD as separate commands. Every thread can see
 * "a slot is free" before any of them has taken it, so the slots are over-booked.
 *
 * promote.lua: the check and the take run as one atomic script, so exactly 3.
 */
class ConcurrencyProofTest extends AbstractIntegrationTest {

    static final int WAITING = 100;
    static final int CAPACITY = 3;
    static final int THREADS = 200;

    @Autowired StringRedisTemplate redis;
    @Autowired ReservationRedis reservationRedis;

    // entry ids in queue order (members are entry UUIDs, like in the real queue)
    private final List<String> members = new ArrayList<>();

    private UUID fillQueue() {
        UUID w = UUID.randomUUID(); // Redis-only test, no Postgres rows needed
        members.clear();
        for (int i = 0; i < WAITING; i++) {
            String member = UUID.randomUUID().toString();
            members.add(member);
            redis.opsForZSet().add(RedisKeys.queue(w), member, i);
        }
        return w;
    }

    /** Starts all threads at once (latch) so they really race. */
    private void race(Runnable task) throws Exception {
        CountDownLatch start = new CountDownLatch(1);
        List<Callable<Void>> jobs = new ArrayList<>();
        for (int i = 0; i < THREADS; i++) {
            jobs.add(() -> {
                start.await();
                task.run();
                return null;
            });
        }
        try (ExecutorService pool = Executors.newFixedThreadPool(THREADS)) {
            var futures = jobs.stream().map(pool::submit).toList();
            start.countDown();
            for (var f : futures) {
                f.get();
            }
        }
    }

    @Test
    void naiveCheckThenActOverbooks() throws Exception {
        UUID w = fillQueue();
        String queue = RedisKeys.queue(w), reserved = RedisKeys.reserved(w);

        race(() -> {
            Long taken = redis.opsForZSet().zCard(reserved);       // 1. check
            if (taken != null && taken < CAPACITY) {
                sleepQuietly();                                     // the normal gap between a read and a write in a real service
                var head = redis.opsForZSet().popMin(queue);        // 2. act
                if (head != null) {
                    redis.opsForZSet().add(reserved, head.getValue(), 1);
                }
            }
        });

        Long booked = redis.opsForZSet().zCard(reserved);
        System.out.printf("NAIVE: %d reservations for %d slots%n", booked, CAPACITY);
        assertThat(booked).isGreaterThan(CAPACITY);
    }

    @Test
    void promoteLuaNeverExceedsCapacity() throws Exception {
        UUID w = fillQueue();

        race(() -> reservationRedis.promote(w, CAPACITY, Instant.now(), 60_000));

        Long booked = redis.opsForZSet().zCard(RedisKeys.reserved(w));
        System.out.printf("LUA:   %d reservations for %d slots%n", booked, CAPACITY);
        assertThat(booked).isEqualTo(CAPACITY);
        // and they're the first three in line
        assertThat(redis.opsForZSet().range(RedisKeys.reserved(w), 0, -1))
                .containsExactlyInAnyOrderElementsOf(members.subList(0, CAPACITY));
    }

    private static void sleepQuietly() {
        try {
            Thread.sleep(2);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }
}
