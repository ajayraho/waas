package com.waas.core.tenant;

import static java.nio.charset.StandardCharsets.UTF_8;

import com.waas.core.common.redis.RedisKeys;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.springframework.data.redis.connection.RedisConnection;
import org.springframework.data.redis.core.RedisCallback;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;

/**
 * Live "waiting / being served" numbers for a page of waitlists.
 *
 * <p>Two ZCARDs per waitlist, all sent in one pipeline: a page of 20 waitlists costs one
 * network round trip to Redis, not 40. Read-only and approximate by nature (the numbers can
 * change a millisecond later), so no Lua script is needed here.
 */
@Component
class WaitlistCounts {

    record Counts(long waiting, long reserved) {}

    private final StringRedisTemplate redis;

    WaitlistCounts(StringRedisTemplate redis) {
        this.redis = redis;
    }

    Map<UUID, Counts> of(List<UUID> waitlistIds) {
        if (waitlistIds.isEmpty()) {
            return Map.of();
        }
        List<Object> replies = redis.executePipelined((RedisCallback<Object>) (RedisConnection c) -> {
            for (UUID id : waitlistIds) {
                c.zSetCommands().zCard(RedisKeys.queue(id).getBytes(UTF_8));
                c.zSetCommands().zCard(RedisKeys.reserved(id).getBytes(UTF_8));
            }
            return null; // results come back from executePipelined, in send order
        });
        Map<UUID, Counts> out = new HashMap<>();
        for (int i = 0; i < waitlistIds.size(); i++) {
            out.put(waitlistIds.get(i), new Counts(asLong(replies.get(2 * i)), asLong(replies.get(2 * i + 1))));
        }
        return out;
    }

    private static long asLong(Object o) {
        return o instanceof Number n ? n.longValue() : 0;
    }
}
