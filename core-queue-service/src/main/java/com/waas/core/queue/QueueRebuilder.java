package com.waas.core.queue;

import com.waas.core.common.redis.RedisKeys;
import com.waas.core.tenant.Waitlist;
import com.waas.core.tenant.WaitlistService;
import java.time.Duration;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.SmartInitializingSingleton;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.ZSetOperations.TypedTuple;
import org.springframework.data.redis.core.script.RedisScript;
import org.springframework.stereotype.Component;

/**
 * Rebuilds live Redis state from Postgres (ARCHITECTURE.md §21.6).
 *
 * <p><b>When:</b> on startup, in {@link #afterSingletonsInstantiated()}. Spring calls that after
 * every bean exists but <em>before</em> the web server starts accepting requests, so no request
 * can observe a half-rebuilt queue on this instance.
 *
 * <p><b>What:</b> per waitlist, only if the key is missing. A key that exists survived
 * (AOF persistence) and is trusted. WAITING rows go to {@code queue} (score = queue_score).
 * RESERVED rows go to {@code reserved} (score = expiry ms). Ones that expired during the outage
 * are left for the expiry sweeper (Brick 3); they are not demoted to WAITING. Terminal states are
 * skipped.
 *
 * <p><b>How:</b> fill a staging key, then swap it in with {@code rebuild_swap.lua}: RENAME if the
 * live key is still missing, merge with AGGREGATE MIN if a join landed meanwhile. A per-waitlist
 * lock (SET NX EX) stops two Core instances starting together from both rebuilding.
 *
 * <p><b>Known gap:</b> an entry cancelled between our Postgres read and the swap can be re-added.
 * The periodic reconciliation job (Brick 3) removes it. It's a narrow window, bounded, and self-healing.
 */
@Component
public class QueueRebuilder implements SmartInitializingSingleton {

    private static final Logger log = LoggerFactory.getLogger(QueueRebuilder.class);
    private static final int BATCH = 1_000;
    private static final Duration LOCK_TTL = Duration.ofSeconds(60);

    private final WaitlistService waitlists;
    private final EntryRepository entries;
    private final StringRedisTemplate redis;
    private final RedisScript<String> swapScript;

    public QueueRebuilder(WaitlistService waitlists, EntryRepository entries, StringRedisTemplate redis,
                          @Qualifier("rebuildSwapScript") RedisScript<String> swapScript) {
        this.waitlists = waitlists;
        this.entries = entries;
        this.redis = redis;
        this.swapScript = swapScript;
    }

    @Override
    public void afterSingletonsInstantiated() {
        rebuildAll();
    }

    public void rebuildAll() {
        for (Waitlist w : waitlists.listActive()) {
            rebuild(w.id());
        }
    }

    public void rebuild(UUID waitlistId) {
        String queueKey = RedisKeys.queue(waitlistId);
        String reservedKey = RedisKeys.reserved(waitlistId);
        if (Boolean.TRUE.equals(redis.hasKey(queueKey)) || Boolean.TRUE.equals(redis.hasKey(reservedKey))) {
            return; // Redis still has this waitlist: nothing lost, nothing to do.
        }

        String lock = RedisKeys.rebuildLock(waitlistId);
        if (!Boolean.TRUE.equals(redis.opsForValue().setIfAbsent(lock, "1", LOCK_TTL))) {
            log.info("Waitlist {} is being rebuilt by another instance", waitlistId);
            return;
        }
        try {
            List<QueueEntry> live = entries.findLive(waitlistId);
            if (live.isEmpty()) {
                return;
            }
            Set<TypedTuple<String>> waiting = new HashSet<>();
            Set<TypedTuple<String>> reserved = new HashSet<>();
            for (QueueEntry e : live) {
                if (e.state() == EntryState.WAITING) {
                    waiting.add(TypedTuple.of(e.id().toString(), e.queueScore()));
                } else if (e.reservationExpiresAt() != null) {
                    reserved.add(TypedTuple.of(e.id().toString(), (double) e.reservationExpiresAt().toEpochMilli()));
                }
            }
            String q = stageAndSwap(queueKey, waiting);
            String r = stageAndSwap(reservedKey, reserved);
            log.info("Rebuilt waitlist {} from Postgres: {} waiting ({}), {} reserved ({})",
                    waitlistId, waiting.size(), q, reserved.size(), r);
        } finally {
            redis.delete(lock);
        }
    }

    private String stageAndSwap(String liveKey, Set<TypedTuple<String>> members) {
        if (members.isEmpty()) {
            return "EMPTY";
        }
        String staging = RedisKeys.staging(liveKey);
        redis.delete(staging); // leftovers from a crashed earlier attempt
        Set<TypedTuple<String>> batch = new HashSet<>();
        for (TypedTuple<String> m : members) {
            batch.add(m);
            if (batch.size() == BATCH) {
                redis.opsForZSet().add(staging, batch);
                batch.clear();
            }
        }
        if (!batch.isEmpty()) {
            redis.opsForZSet().add(staging, batch);
        }
        return redis.execute(swapScript, List.of(liveKey, staging));
    }
}
