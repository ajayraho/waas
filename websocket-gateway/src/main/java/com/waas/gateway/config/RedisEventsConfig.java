package com.waas.gateway.config;

import com.waas.gateway.fanout.FanoutService;
import java.nio.charset.StandardCharsets;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.data.redis.connection.RedisConnectionFactory;
import org.springframework.data.redis.listener.PatternTopic;
import org.springframework.data.redis.listener.RedisMessageListenerContainer;
import org.springframework.scheduling.annotation.EnableScheduling;

/**
 * Every gateway instance PSUBSCRIBEs to all waitlist event channels.
 *
 * <p>This is <b>broadcast</b> on purpose (§21.9): each instance must see every event, because any
 * of them may hold the socket that cares. A shared consumer group (Redis Streams / Kafka) would
 * hand each event to just one instance, usually the wrong one. The upgrade path keeps broadcast:
 * one consumer group <em>per instance</em>, or plain XREAD with a per-instance offset.
 *
 * <p>The pattern's braces are literal (Redis globs only treat {@code * ? [ ] \} specially), so it
 * matches exactly Core's hash-tagged channels {@code waitlist:{<id>}:events}.
 */
@Configuration
@EnableScheduling
public class RedisEventsConfig {

    @Bean
    public RedisMessageListenerContainer queueEventsListener(RedisConnectionFactory connectionFactory,
                                                             FanoutService fanout) {
        RedisMessageListenerContainer container = new RedisMessageListenerContainer();
        container.setConnectionFactory(connectionFactory);
        container.addMessageListener(
                (message, pattern) -> fanout.onRedisMessage(new String(message.getBody(), StandardCharsets.UTF_8)),
                new PatternTopic("waitlist:{*}:events"));
        return container;
    }
}
