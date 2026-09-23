package com.waas.gateway.fanout;

import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.springframework.data.redis.connection.RedisConnection;
import org.springframework.data.redis.core.RedisCallback;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;

/**
 * Reads live positions straight from Redis: the same sorted sets Core writes.
 *
 * <p>All watched entries of a waitlist are read in <b>one pipelined round trip</b>
 * (ZCARD + a ZRANK and a ZSCORE per entry). Cost: O(k log N) Redis work for k local watchers,
 * one network round trip, regardless of how many events caused the refresh.
 *
 * <p>Not atomic across entries (a pipeline isn't a transaction), and that's fine for display.
 * The next tick corrects any momentary skew. Core's atomic Lua reads are for decisions, not for
 * pixels.
 */
@Component
public class PositionReader {

    private final StringRedisTemplate redis;

    public PositionReader(StringRedisTemplate redis) {
        this.redis = redis;
    }

    static String queueKey(UUID waitlistId) {
        return "waitlist:{" + waitlistId + "}:queue";
    }

    static String reservedKey(UUID waitlistId) {
        return "waitlist:{" + waitlistId + "}:reserved";
    }

    /** Entries not found in either set come back with state {@code ABSENT} (terminal: ask Core). */
    public Map<UUID, EntryView> read(UUID waitlistId, List<UUID> entryIds) {
        byte[] queue = queueKey(waitlistId).getBytes(StandardCharsets.UTF_8);
        byte[] reserved = reservedKey(waitlistId).getBytes(StandardCharsets.UTF_8);

        List<Object> r = redis.executePipelined((RedisCallback<Object>) (RedisConnection c) -> {
            c.zSetCommands().zCard(queue);
            for (UUID id : entryIds) {
                byte[] member = id.toString().getBytes(StandardCharsets.UTF_8);
                c.zSetCommands().zRank(queue, member);
                c.zSetCommands().zScore(reserved, member);
            }
            return null;
        });

        long size = r.get(0) == null ? 0 : ((Number) r.get(0)).longValue();
        Map<UUID, EntryView> out = new LinkedHashMap<>();
        for (int i = 0; i < entryIds.size(); i++) {
            UUID id = entryIds.get(i);
            Object rank = r.get(1 + 2 * i);
            Object expiry = r.get(2 + 2 * i);
            if (rank != null) {
                out.put(id, new EntryView(id, waitlistId, "WAITING", ((Number) rank).longValue() + 1, size, null));
            } else if (expiry != null) {
                out.put(id, new EntryView(id, waitlistId, "RESERVED", null, null,
                        Instant.ofEpochMilli(((Number) expiry).longValue())));
            } else {
                out.put(id, new EntryView(id, waitlistId, "ABSENT", null, null, null));
            }
        }
        return out;
    }

    public Map<UUID, EntryView> read(UUID waitlistId, UUID entryId) {
        return read(waitlistId, new ArrayList<>(List.of(entryId)));
    }
}
