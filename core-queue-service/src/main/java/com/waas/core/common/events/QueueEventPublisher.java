package com.waas.core.common.events;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.waas.core.common.redis.RedisKeys;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;

/**
 * Fire-and-forget publish to Redis Pub/Sub.
 *
 * <p>A failed publish is logged and swallowed, never propagated. The event is a hint that
 * "this waitlist changed"; the truth is already in Redis and Postgres. Failing the user's join
 * because a notification couldn't be sent would be the wrong trade. The gateway's periodic
 * refresh (§21.7) covers a lost event.
 */
@Component
public class QueueEventPublisher {

    private static final Logger log = LoggerFactory.getLogger(QueueEventPublisher.class);

    private final StringRedisTemplate redis;
    private final ObjectMapper json;

    public QueueEventPublisher(StringRedisTemplate redis, ObjectMapper json) {
        this.redis = redis;
        this.json = json;
    }

    public void publish(QueueEvent event) {
        try {
            redis.convertAndSend(RedisKeys.events(event.waitlistId()), json.writeValueAsString(event));
        } catch (JsonProcessingException | RuntimeException e) {
            log.warn("Could not publish {} for entry {}", event.type(), event.entryId(), e);
        }
    }
}
