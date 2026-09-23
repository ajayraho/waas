package com.waas.core.reservation;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.waas.core.AbstractIntegrationTest;
import com.waas.core.common.error.ApiException;
import com.waas.core.common.redis.RedisKeys;
import com.waas.core.queue.EntryState;
import com.waas.core.queue.QueueService;
import com.waas.core.queue.QueueViews.JoinResponse;
import com.waas.core.queue.QueueViews.QueueSnapshot;
import com.waas.core.tenant.WaitlistService;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.Callable;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.redis.core.StringRedisTemplate;

class ReservationFlowTest extends AbstractIntegrationTest {

    @Autowired QueueService queue;
    @Autowired ReservationService reservations;
    @Autowired Reconciler reconciler;
    @Autowired WaitlistService waitlists;
    @Autowired StringRedisTemplate redis;

    /**
     * Negative grace = treat every row as settled. updated_at comes from the Postgres clock (in a
     * container), and on Docker Desktop that clock can drift seconds from the JVM's. Zero grace
     * would make these tests flaky; production uses 30 s.
     */
    private static final Duration ALL_SETTLED = Duration.ofMinutes(-5);

    private record Joined(UUID userId, UUID entryId, JoinResponse response) {}

    private Joined join(UUID waitlistId, String name) {
        UUID user = newUser(name);
        JoinResponse r = queue.join(waitlistId, user);
        return new Joined(user, r.entryId(), r);
    }

    private long reservedInPostgres(UUID waitlistId) {
        return jdbc.sql("SELECT COUNT(*) FROM waitlist_entry WHERE waitlist_id = :w AND state = 'RESERVED'")
                .param("w", waitlistId).query(Long.class).single();
    }

    @Test
    void joiningAWaitlistWithAFreeSlotIsReservedImmediately() {
        UUID w = newWaitlist(1, 600);
        Instant before = Instant.now();

        Joined a = join(w, "Asha");

        assertThat(a.response().position().state()).isEqualTo(EntryState.RESERVED);
        assertThat(a.response().position().expiresAt()).isBetween(before.plusSeconds(599), before.plusSeconds(602));
        assertThat(stateOf(a.entryId())).isEqualTo("RESERVED");
    }

    /**
     * The double-reservation race (§10 / §21.1): 60 people join an empty capacity-3 waitlist at
     * the same instant. Every join triggers a promotion. Exactly 3 must end up RESERVED, in both
     * stores.
     */
    @Test
    void capacityIsNeverExceededUnderConcurrentJoins() throws Exception {
        UUID w = newWaitlist(3, 600);
        List<UUID> users = new ArrayList<>();
        for (int i = 0; i < 60; i++) {
            users.add(newUser("Rush" + i));
        }

        List<Callable<JoinResponse>> joins = users.stream()
                .<Callable<JoinResponse>>map(u -> () -> queue.join(w, u)).toList();
        try (ExecutorService pool = Executors.newFixedThreadPool(20)) {
            pool.invokeAll(joins);
        }
        // Hammer promote as well: extra calls must be no-ops.
        List<Callable<List<UUID>>> promotes = new ArrayList<>();
        for (int i = 0; i < 50; i++) {
            promotes.add(() -> reservations.promote(w));
        }
        try (ExecutorService pool = Executors.newFixedThreadPool(20)) {
            pool.invokeAll(promotes);
        }

        QueueSnapshot s = queue.snapshot(w, 200);
        assertThat(s.reservedCount()).isEqualTo(3);
        assertThat(s.queueSize()).isEqualTo(57);
        assertThat(reservedInPostgres(w)).isEqualTo(3);
    }

    @Test
    void confirmingFreesTheSlotForTheNextInLine() {
        UUID w = newWaitlist(1, 600);
        Joined a = join(w, "Asha");
        Joined b = join(w, "Bilal");
        assertThat(b.response().position().state()).isEqualTo(EntryState.WAITING);

        reservations.confirm(w, a.entryId(), a.userId());
        reservations.confirm(w, a.entryId(), a.userId()); // idempotent

        assertThat(stateOf(a.entryId())).isEqualTo("CONFIRMED");
        assertThat(stateOf(b.entryId())).isEqualTo("RESERVED");
        assertThat(queue.snapshot(w, 10).confirmedCount()).isEqualTo(1);
    }

    @Test
    void onlyTheHolderCanConfirm() {
        UUID w = newWaitlist(1, 600);
        Joined a = join(w, "Asha");

        assertThatThrownBy(() -> reservations.confirm(w, a.entryId(), UUID.randomUUID()))
                .isInstanceOf(ApiException.class)
                .satisfies(e -> assertThat(((ApiException) e).code()).isEqualTo("ENTRY_NOT_FOUND"));
    }

    @Test
    void aLapsedWindowCannotBeConfirmedAndTheSweeperPromotesTheNext() throws Exception {
        UUID w = newWaitlist(1, 600);
        waitlists.updateConfig(w, null, 5);           // 5 s window (validation minimum)
        Joined a = join(w, "Asha");
        Joined b = join(w, "Bilal");
        // Fast-forward: pull Asha's expiry into the past in Redis (the clock the scripts use).
        redis.opsForZSet().add(RedisKeys.reserved(w), a.entryId().toString(), Instant.now().minusSeconds(1).toEpochMilli());

        assertThatThrownBy(() -> reservations.confirm(w, a.entryId(), a.userId()))
                .isInstanceOf(ApiException.class)
                .satisfies(e -> assertThat(((ApiException) e).code()).isEqualTo("RESERVATION_EXPIRED"));

        int expired = reservations.sweep(w);

        assertThat(expired).isEqualTo(1);
        assertThat(stateOf(a.entryId())).isEqualTo("EXPIRED");
        assertThat(stateOf(b.entryId())).isEqualTo("RESERVED");
        assertThat(queue.snapshot(w, 10).expiredCount()).isEqualTo(1);
        assertThat(reservations.sweep(w)).isZero(); // nothing left to expire
    }

    @Test
    void decliningHandsTheSlotOn() {
        UUID w = newWaitlist(1, 600);
        Joined a = join(w, "Asha");
        Joined b = join(w, "Bilal");

        reservations.decline(w, a.entryId(), a.userId());

        assertThat(stateOf(a.entryId())).isEqualTo("CANCELLED");
        assertThat(stateOf(b.entryId())).isEqualTo("RESERVED");
    }

    @Test
    void raisingCapacityPromotesImmediately() {
        UUID w = newWaitlist(1, 600);
        join(w, "A");
        join(w, "B");
        join(w, "C");
        assertThat(reservedInPostgres(w)).isEqualTo(1);

        waitlists.updateConfig(w, 3, null);

        assertThat(reservedInPostgres(w)).isEqualTo(3);
        assertThat(queue.snapshot(w, 10).reservedCount()).isEqualTo(3);
    }

    @Test
    void reconcilerClosesGapsInBothDirections() {
        UUID w = newWaitlist(1, 600);
        Joined a = join(w, "Asha");   // RESERVED
        Joined b = join(w, "Bilal");  // WAITING
        Joined c = join(w, "Chen");   // WAITING, will be cancelled
        queue.cancel(w, c.entryId());

        // Break things the way lost writes would:
        redis.opsForZSet().remove(RedisKeys.queue(w), b.entryId().toString());          // join's ZADD lost
        redis.opsForZSet().remove(RedisKeys.reserved(w), a.entryId().toString());       // slot vanished from Redis
        redis.opsForZSet().add(RedisKeys.queue(w), c.entryId().toString(), 1.0);         // cancel's ZREM lost

        Reconciler.Report report = reconciler.reconcile(w, ALL_SETTLED);

        assertThat(report.queueRestored()).isEqualTo(1);
        assertThat(report.reservedRestored()).isEqualTo(1);
        assertThat(report.removedFromQueue()).isEqualTo(1);
        QueueSnapshot s = queue.snapshot(w, 10);
        assertThat(s.reserved()).extracting(r -> r.entryId()).containsExactly(a.entryId());
        assertThat(s.waiting()).extracting(r -> r.entryId()).containsExactly(b.entryId());
    }

    @Test
    void reconcilerRollsALostPromotionForwardIntoPostgres() {
        UUID w = newWaitlist(1, 600);
        Joined a = join(w, "Asha");
        // Simulate promote.lua having succeeded but the Postgres write being lost.
        jdbc.sql("UPDATE waitlist_entry SET state='WAITING', reserved_at=NULL, reservation_expires_at=NULL WHERE id=:id")
                .param("id", a.entryId()).update();

        Reconciler.Report report = reconciler.reconcile(w, ALL_SETTLED);

        assertThat(report.rolledForward()).isEqualTo(1);
        assertThat(stateOf(a.entryId())).isEqualTo("RESERVED");
    }
}
