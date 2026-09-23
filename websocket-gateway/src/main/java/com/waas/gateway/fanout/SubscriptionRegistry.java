package com.waas.gateway.fanout;

import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.stream.Collectors;
import org.springframework.context.event.EventListener;
import org.springframework.messaging.simp.stomp.StompHeaderAccessor;
import org.springframework.stereotype.Component;
import org.springframework.web.socket.messaging.SessionDisconnectEvent;
import org.springframework.web.socket.messaging.SessionSubscribeEvent;
import org.springframework.web.socket.messaging.SessionUnsubscribeEvent;

/**
 * Which waitlists and entries have subscribers <em>on this gateway instance</em>.
 *
 * <p>That's the whole point of the per-instance fan-out: every gateway hears every event
 * (broadcast), but each only does work for its own sockets. A waitlist nobody here is watching
 * costs this instance nothing.
 *
 * <p>STOMP UNSUBSCRIBE frames carry only (session, subscription id), not the destination,
 * so subscriptions are keyed by that pair. A dropped socket arrives as a disconnect, which clears
 * the whole session.
 */
@Component
public class SubscriptionRegistry {

    private record Key(String sessionId, String subscriptionId) {}

    private final Map<Key, Destinations.Target> subscriptions = new ConcurrentHashMap<>();
    private final Set<String> sessions = ConcurrentHashMap.newKeySet();

    @EventListener
    void onSubscribe(SessionSubscribeEvent event) {
        StompHeaderAccessor h = StompHeaderAccessor.wrap(event.getMessage());
        sessions.add(h.getSessionId());
        Destinations.parse(h.getDestination())
                .ifPresent(t -> subscriptions.put(new Key(h.getSessionId(), h.getSubscriptionId()), t));
    }

    @EventListener
    void onUnsubscribe(SessionUnsubscribeEvent event) {
        StompHeaderAccessor h = StompHeaderAccessor.wrap(event.getMessage());
        subscriptions.remove(new Key(h.getSessionId(), h.getSubscriptionId()));
    }

    @EventListener
    void onDisconnect(SessionDisconnectEvent event) {
        String session = event.getSessionId();
        sessions.remove(session);
        subscriptions.keySet().removeIf(k -> k.sessionId().equals(session));
    }

    /** Waitlists with at least one dashboard (whole-queue) subscriber here. */
    public Set<UUID> watchedQueues() {
        return subscriptions.values().stream().filter(Destinations.Target::isQueue)
                .map(Destinations.Target::waitlistId).collect(Collectors.toSet());
    }

    /** Entries of {@code waitlistId} with at least one subscriber here. */
    public Set<UUID> watchedEntries(UUID waitlistId) {
        return subscriptions.values().stream()
                .filter(t -> !t.isQueue() && t.waitlistId().equals(waitlistId))
                .map(Destinations.Target::entryId).collect(Collectors.toSet());
    }

    /** Every waitlist anyone here is watching, in either view. */
    public Set<UUID> watchedWaitlists() {
        return subscriptions.values().stream().map(Destinations.Target::waitlistId).collect(Collectors.toSet());
    }

    public boolean isWatched(UUID waitlistId, UUID entryId) {
        return subscriptions.values().stream()
                .anyMatch(t -> !t.isQueue() && t.waitlistId().equals(waitlistId) && t.entryId().equals(entryId));
    }

    public int sessionCount() {
        return sessions.size();
    }

    public int subscriptionCount() {
        return subscriptions.size();
    }
}
