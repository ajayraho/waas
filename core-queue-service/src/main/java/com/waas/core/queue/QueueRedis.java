package com.waas.core.queue;

import static com.waas.core.common.redis.LuaResults.asDouble;
import static com.waas.core.common.redis.LuaResults.asLong;
import static com.waas.core.common.redis.LuaResults.asString;

import com.waas.core.common.redis.RedisKeys;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.RedisScript;
import org.springframework.stereotype.Component;

/**
 * The queue module's only door into Redis. Every read or write that needs more than one
 * command goes through a Lua script, so it runs atomically on the Redis server.
 */
@Component
class QueueRedis {

    private final StringRedisTemplate redis;
    @SuppressWarnings("rawtypes") private final RedisScript<List> joinScript;
    @SuppressWarnings("rawtypes") private final RedisScript<List> locateScript;
    @SuppressWarnings("rawtypes") private final RedisScript<List> snapshotScript;

    @SuppressWarnings("rawtypes")
    QueueRedis(StringRedisTemplate redis,
               @Qualifier("joinScript") RedisScript<List> joinScript,
               @Qualifier("locateScript") RedisScript<List> locateScript,
               @Qualifier("snapshotScript") RedisScript<List> snapshotScript) {
        this.redis = redis;
        this.joinScript = joinScript;
        this.locateScript = locateScript;
        this.snapshotScript = snapshotScript;
    }

    record JoinResult(long rank, long queueSize, boolean added) {}

    /** ZADD NX + ZRANK + ZCARD in one step (join.lua). */
    JoinResult join(UUID waitlistId, UUID entryId, double score) {
        List<?> r = redis.execute(joinScript, List.of(RedisKeys.queue(waitlistId)),
                entryId.toString(), Double.toString(score));
        return new JoinResult(asLong(r.get(0)), asLong(r.get(1)), asLong(r.get(2)) == 1);
    }

    sealed interface Location permits Waiting, Reserved, Absent {}
    record Waiting(long rank, long queueSize, double score) implements Location {}
    record Reserved(Instant expiresAt) implements Location {}
    record Absent() implements Location {}

    /** Where is this entry right now (locate.lua)? */
    Location locate(UUID waitlistId, UUID entryId) {
        List<?> r = redis.execute(locateScript,
                List.of(RedisKeys.queue(waitlistId), RedisKeys.reserved(waitlistId)), entryId.toString());
        return switch (asString(r.get(0))) {
            case "WAITING" -> new Waiting(asLong(r.get(1)), asLong(r.get(2)), asDouble(r.get(3)));
            case "RESERVED" -> new Reserved(Instant.ofEpochMilli((long) asDouble(r.get(1))));
            default -> new Absent();
        };
    }

    record Scored(UUID entryId, double score) {}
    record Snapshot(long queueSize, long reservedSize, List<Scored> waiting, List<Scored> reserved,
                    long confirmed, long expired) {}

    /** Head of the queue + every reserved slot, read atomically (snapshot.lua). */
    Snapshot snapshot(UUID waitlistId, int limit) {
        List<?> r = redis.execute(snapshotScript,
                List.of(RedisKeys.queue(waitlistId), RedisKeys.reserved(waitlistId), RedisKeys.stats(waitlistId)),
                Integer.toString(limit));
        List<?> stats = (List<?>) r.get(4);
        return new Snapshot(asLong(r.get(0)), asLong(r.get(1)), pairs((List<?>) r.get(2)), pairs((List<?>) r.get(3)),
                asLong(stats.get(0)), asLong(stats.get(1)));
    }

    boolean remove(UUID waitlistId, UUID entryId) {
        Long removed = redis.opsForZSet().remove(RedisKeys.queue(waitlistId), entryId.toString());
        return removed != null && removed > 0;
    }

    long queueSize(UUID waitlistId) {
        Long size = redis.opsForZSet().zCard(RedisKeys.queue(waitlistId));
        return size == null ? 0 : size;
    }

    /** WITHSCORES replies are flat: [member, score, member, score, ...]. */
    private static List<Scored> pairs(List<?> flat) {
        List<Scored> out = new ArrayList<>(flat.size() / 2);
        for (int i = 0; i + 1 < flat.size(); i += 2) {
            out.add(new Scored(UUID.fromString(asString(flat.get(i))), asDouble(flat.get(i + 1))));
        }
        return out;
    }
}
