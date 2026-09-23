package com.waas.core.reservation;

import com.waas.core.common.redis.RedisKeys;
import com.waas.core.queue.EntryState;
import com.waas.core.reservation.ReservationRepository.LiveRow;
import com.waas.core.tenant.Waitlist;
import com.waas.core.tenant.WaitlistService;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * Bidirectional reconciliation between Postgres and Redis (ARCHITECTURE.md §21.6).
 *
 * <p>Every write path is "one store, then the other", so a crash between the two leaves a gap.
 * Each path is written so that gap is <em>detectable</em>; this job detects and closes them.
 *
 * <p><b>Authority:</b> Postgres decides whether an entry exists and is active. Redis decides order
 * and slot allocation, because those decisions were made atomically in Lua. So a slot Redis
 * handed out is rolled <em>forward</em> into Postgres, never taken back.
 *
 * <p><b>Grace window:</b> Postgres rows touched in the last {@code grace} are skipped, so the job
 * never "repairs" a write that is simply still in flight (inserted, ZADD a millisecond away).
 *
 * <p><b>Scale:</b> a full pass is O(N) per waitlist. Fine at demo scale and every 30 s. At real
 * scale: reconcile incrementally (only rows with {@code updated_at} in the last window, via the
 * (waitlist_id, state) index), or replace polling with CDC/outbox (§12.3).
 */
@Component
public class Reconciler {

    private static final Logger log = LoggerFactory.getLogger(Reconciler.class);
    private static final Duration LOCK_TTL = Duration.ofSeconds(60);

    private final WaitlistService waitlists;
    private final ReservationRepository repo;
    private final ReservationRedis redis;
    private final ReservationService reservations;
    private final StringRedisTemplate locks;
    private final Clock clock;
    private final Duration grace;

    public Reconciler(WaitlistService waitlists, ReservationRepository repo, ReservationRedis redis,
                      ReservationService reservations, StringRedisTemplate locks, Clock clock,
                      @Value("${waas.reconcile.grace:30s}") Duration grace) {
        this.waitlists = waitlists;
        this.repo = repo;
        this.redis = redis;
        this.reservations = reservations;
        this.locks = locks;
        this.clock = clock;
        this.grace = grace;
    }

    public record Report(int queueRestored, int reservedRestored, int expiredInPostgres,
                         int rolledForward, int removedFromQueue, int removedFromReserved) {
        public int total() {
            return queueRestored + reservedRestored + expiredInPostgres + rolledForward + removedFromQueue + removedFromReserved;
        }
    }

    @Scheduled(fixedDelayString = "${waas.reconcile.interval-ms:30000}", initialDelayString = "${waas.reconcile.initial-delay-ms:15000}")
    void run() {
        for (Waitlist w : waitlists.listActive()) {
            String lock = RedisKeys.reconcileLock(w.id());
            if (!Boolean.TRUE.equals(locks.opsForValue().setIfAbsent(lock, "1", LOCK_TTL))) {
                continue; // another instance is on it
            }
            try {
                Report r = reconcile(w.id(), grace);
                if (r.total() > 0) {
                    log.warn("Reconciled waitlist {}: {}", w.id(), r);
                }
            } catch (RuntimeException e) {
                log.error("Reconciliation failed for waitlist {}", w.id(), e);
            } finally {
                locks.delete(lock);
            }
        }
    }

    public Report reconcile(UUID waitlistId, Duration grace) {
        Instant now = clock.instant();
        Instant settledBefore = now.minus(grace);

        List<LiveRow> live = repo.findLive(waitlistId);
        Map<UUID, Double> queued = redis.queueMembers(waitlistId);
        Map<UUID, Double> reserved = redis.reservedMembers(waitlistId);

        int queueRestored = 0, reservedRestored = 0, expiredInPg = 0, rolledForward = 0;
        int removedFromQueue = 0, removedFromReserved = 0;

        // ---- Postgres → Redis: active rows Redis doesn't know about ----
        Map<UUID, EntryState> pgState = new HashMap<>();
        for (LiveRow row : live) {
            pgState.put(row.id(), row.state());
            boolean inRedis = queued.containsKey(row.id()) || reserved.containsKey(row.id());
            if (inRedis || row.updatedAt().isAfter(settledBefore)) {
                continue;
            }
            if (row.state() == EntryState.WAITING) {
                redis.addToQueueIfAbsent(waitlistId, row.id(), row.queueScore()); // join's ZADD was lost
                queueRestored++;
            } else if (row.expiresAt() != null && row.expiresAt().isAfter(now)) {
                redis.addReserved(waitlistId, row.id(), row.expiresAt());         // holder keeps the slot
                reservedRestored++;
            } else {
                expiredInPg += repo.markExpired(List.of(row.id()));               // its window is over anyway
            }
        }

        // ---- Redis → Postgres: members whose row isn't (or is no longer) active ----
        Set<UUID> unknown = new HashSet<>();
        for (UUID id : queued.keySet()) if (!pgState.containsKey(id)) unknown.add(id);
        for (UUID id : reserved.keySet()) if (!pgState.containsKey(id)) unknown.add(id);
        pgState.putAll(repo.findStates(unknown)); // terminal / CONFIRMED rows (absent = no row at all)

        for (UUID id : queued.keySet()) {
            EntryState s = pgState.get(id);
            if (s != EntryState.WAITING && s != EntryState.RESERVED) {
                redis.removeFromQueue(waitlistId, id);  // e.g. cancelled, ZREM lost
                removedFromQueue++;
            }
        }
        for (Map.Entry<UUID, Double> m : reserved.entrySet()) {
            EntryState s = pgState.get(m.getKey());
            if (s == EntryState.WAITING) {
                // promote.lua gave it a slot but the Postgres write was lost: roll forward.
                rolledForward += repo.markReserved(List.of(m.getKey()), now,
                        Instant.ofEpochMilli(m.getValue().longValue()));
            } else if (s != EntryState.RESERVED) {
                redis.release(waitlistId, m.getKey());  // a leaked slot: free it
                removedFromReserved++;
            }
        }

        Report report = new Report(queueRestored, reservedRestored, expiredInPg, rolledForward,
                removedFromQueue, removedFromReserved);
        if (report.total() > 0) {
            reservations.promote(waitlistId); // repairs may have freed slots
        }
        return report;
    }
}
