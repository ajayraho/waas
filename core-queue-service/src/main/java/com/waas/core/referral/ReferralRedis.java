package com.waas.core.referral;

import static com.waas.core.common.redis.LuaResults.asDouble;
import static com.waas.core.common.redis.LuaResults.asLong;
import static com.waas.core.common.redis.LuaResults.asString;

import com.waas.core.common.redis.RedisKeys;
import java.time.Duration;
import java.util.List;
import java.util.UUID;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.RedisScript;
import org.springframework.stereotype.Component;

@Component
class ReferralRedis {

    private final StringRedisTemplate redis;
    @SuppressWarnings("rawtypes") private final RedisScript<List> bumpScript;

    @SuppressWarnings("rawtypes")
    ReferralRedis(StringRedisTemplate redis, @Qualifier("bumpScript") RedisScript<List> bumpScript) {
        this.redis = redis;
        this.bumpScript = bumpScript;
    }

    /**
     * Outcome of bump.lua.
     *
     * @param applied false = entry isn't WAITING (or the score gap is exhausted): bank everything
     * @param moved   spots actually gained (0..N). The caller banks {@code N - moved}.
     */
    record Bump(boolean applied, long moved, double score, long rank) {
        static Bump banked() {
            return new Bump(false, 0, 0, -1);
        }
    }

    Bump bump(UUID waitlistId, UUID entryId, int spots) {
        List<?> r = redis.execute(bumpScript, List.of(RedisKeys.queue(waitlistId)),
                entryId.toString(), Integer.toString(spots));
        if (!"APPLIED".equals(asString(r.get(0)))) {
            return Bump.banked(); // BANK or PRECISION
        }
        return new Bump(true, asLong(r.get(3)), asDouble(r.get(1)), asLong(r.get(2)));
    }

    /**
     * Fixed-window velocity counter: returns this referrer's count in the current window,
     * including this attempt.
     *
     * <p>{@code SET key 0 EX window NX} runs first, so the key always has a TTL before it is
     * incremented. The classic INCR-then-EXPIRE pattern can crash between the two and leave a
     * counter that never expires, permanently blocking the user.
     */
    long countInWindow(UUID waitlistId, UUID referrerId, Duration window) {
        String key = RedisKeys.referralVelocity(waitlistId, referrerId);
        redis.opsForValue().setIfAbsent(key, "0", window);
        Long n = redis.opsForValue().increment(key);
        return n == null ? 0 : n;
    }
}
