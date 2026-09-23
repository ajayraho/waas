package com.waas.core.reservation;

import static com.waas.core.common.redis.LuaResults.asDouble;
import static com.waas.core.common.redis.LuaResults.asString;

import com.waas.core.common.redis.RedisKeys;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.ZSetOperations.TypedTuple;
import org.springframework.data.redis.core.script.RedisScript;
import org.springframework.stereotype.Component;

/** The reservation module's door into Redis: slot allocation decisions, all atomic. */
@Component
class ReservationRedis {

    private final StringRedisTemplate redis;
    @SuppressWarnings("rawtypes") private final RedisScript<List> promoteScript;
    private final RedisScript<Long> confirmScript;
    @SuppressWarnings("rawtypes") private final RedisScript<List> expireDueScript;

    @SuppressWarnings("rawtypes")
    ReservationRedis(StringRedisTemplate redis,
                     @Qualifier("promoteScript") RedisScript<List> promoteScript,
                     @Qualifier("confirmScript") RedisScript<Long> confirmScript,
                     @Qualifier("expireDueScript") RedisScript<List> expireDueScript) {
        this.redis = redis;
        this.promoteScript = promoteScript;
        this.confirmScript = confirmScript;
        this.expireDueScript = expireDueScript;
    }

    record Promotion(Instant expiresAt, List<UUID> entryIds) {}

    /** promote.lua: move queue-head entries into free slots. Never exceeds {@code capacity}. */
    Promotion promote(UUID waitlistId, int capacity, Instant now, long windowMs) {
        List<?> r = redis.execute(promoteScript,
                List.of(RedisKeys.queue(waitlistId), RedisKeys.reserved(waitlistId)),
                Integer.toString(capacity), Long.toString(now.toEpochMilli()), Long.toString(windowMs));
        Instant expiresAt = Instant.ofEpochMilli((long) asDouble(r.get(0)));
        List<UUID> ids = new ArrayList<>(r.size() - 1);
        for (int i = 1; i < r.size(); i++) {
            ids.add(UUID.fromString(asString(r.get(i))));
        }
        return new Promotion(expiresAt, ids);
    }

    enum ConfirmOutcome { CONFIRMED, LAPSED, ABSENT }

    /** confirm.lua: release the slot only if the window is still open. */
    ConfirmOutcome confirm(UUID waitlistId, UUID entryId, Instant now) {
        Long r = redis.execute(confirmScript, List.of(RedisKeys.reserved(waitlistId)),
                entryId.toString(), Long.toString(now.toEpochMilli()));
        long code = r == null ? 0 : r;
        return code == 1 ? ConfirmOutcome.CONFIRMED : code == -1 ? ConfirmOutcome.LAPSED : ConfirmOutcome.ABSENT;
    }

    /** expire_due.lua: atomically take up to {@code limit} lapsed members out of the reserved set. */
    List<UUID> expireDue(UUID waitlistId, Instant now, int limit) {
        List<?> r = redis.execute(expireDueScript, List.of(RedisKeys.reserved(waitlistId)),
                Long.toString(now.toEpochMilli()), Integer.toString(limit));
        List<UUID> ids = new ArrayList<>(r.size());
        for (Object o : r) {
            ids.add(UUID.fromString(asString(o)));
        }
        return ids;
    }

    boolean release(UUID waitlistId, UUID entryId) {
        Long removed = redis.opsForZSet().remove(RedisKeys.reserved(waitlistId), entryId.toString());
        return removed != null && removed > 0;
    }

    void incrementStat(UUID waitlistId, String field, long by) {
        if (by > 0) {
            redis.opsForHash().increment(RedisKeys.stats(waitlistId), field, by);
        }
    }

    // ---- reconciliation helpers (full reads: O(N), run every ~30 s, see Reconciler) ----

    Map<UUID, Double> queueMembers(UUID waitlistId) {
        return all(RedisKeys.queue(waitlistId));
    }

    Map<UUID, Double> reservedMembers(UUID waitlistId) {
        return all(RedisKeys.reserved(waitlistId));
    }

    void addToQueueIfAbsent(UUID waitlistId, UUID entryId, double score) {
        redis.opsForZSet().addIfAbsent(RedisKeys.queue(waitlistId), entryId.toString(), score);
    }

    void addReserved(UUID waitlistId, UUID entryId, Instant expiresAt) {
        redis.opsForZSet().addIfAbsent(RedisKeys.reserved(waitlistId), entryId.toString(), expiresAt.toEpochMilli());
    }

    void removeFromQueue(UUID waitlistId, UUID entryId) {
        redis.opsForZSet().remove(RedisKeys.queue(waitlistId), entryId.toString());
    }

    private Map<UUID, Double> all(String key) {
        Set<TypedTuple<String>> tuples = redis.opsForZSet().rangeWithScores(key, 0, -1);
        Map<UUID, Double> out = new HashMap<>();
        if (tuples != null) {
            for (TypedTuple<String> t : tuples) {
                out.put(UUID.fromString(t.getValue()), t.getScore());
            }
        }
        return out;
    }
}
