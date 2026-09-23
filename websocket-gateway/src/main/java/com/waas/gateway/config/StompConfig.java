package com.waas.gateway.config;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Configuration;
import org.springframework.messaging.simp.config.MessageBrokerRegistry;
import org.springframework.web.socket.config.annotation.EnableWebSocketMessageBroker;
import org.springframework.web.socket.config.annotation.StompEndpointRegistry;
import org.springframework.web.socket.config.annotation.WebSocketMessageBrokerConfigurer;

/**
 * STOMP over plain WebSocket.
 *
 * <ul>
 *   <li>{@code /ws} — the handshake endpoint (no SockJS fallback: every browser we target
 *       speaks WebSocket, and SockJS would add a second transport to reason about).</li>
 *   <li>{@code /topic/**} — broadcast destinations, e.g. {@code /topic/waitlists/{id}} for the
 *       dashboard's whole-queue view.</li>
 *   <li>{@code /user/queue/**} — per-user destinations, e.g. "your position / your countdown".</li>
 * </ul>
 *
 * <p>The in-memory simple broker is correct here <em>because</em> fan-out input arrives from
 * Redis to every gateway instance (§21.9 broadcast semantics); each instance only needs to
 * deliver to its own sockets. A full STOMP broker relay (RabbitMQ) would be a second event
 * backbone doing the same job.
 */
@Configuration
@EnableWebSocketMessageBroker
public class StompConfig implements WebSocketMessageBrokerConfigurer {

    @Value("${gateway.allowed-origins:*}")
    private String[] allowedOrigins;

    @Override
    public void registerStompEndpoints(StompEndpointRegistry registry) {
        registry.addEndpoint("/ws").setAllowedOriginPatterns(allowedOrigins);
    }

    @Override
    public void configureMessageBroker(MessageBrokerRegistry registry) {
        registry.enableSimpleBroker("/topic", "/queue");
        // Messages to one session leave in the order they were sent: a client never sees
        // position 5 arrive after the position 3 that replaced it.
        registry.setPreservePublishOrder(true);
        registry.setApplicationDestinationPrefixes("/app");
        registry.setUserDestinationPrefix("/user");
    }
}
